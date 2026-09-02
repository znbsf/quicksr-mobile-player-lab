"""Push and run the instrumented RIFE ncnn Vulkan CLI on one connected Android device.

This is intentionally not an application integration.  It creates a unique directory below
/data/local/tmp, records remote hashes, performs one PSS-sampled run plus bounded latency runs,
and leaves the remote evidence directory in place for inspection.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from statistics import median
from typing import Any

from run_vfi_host_benchmark import quality_proxies
from vfi_prefilter import FrameRecord, VfiPrefilter


def run(command: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command, check=check, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )


def adb(adb_path: str, serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return run([adb_path, "-s", serial, *args], check=check)


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def thermal_snapshot(adb_path: str, serial: str) -> dict[str, Any]:
    command = (
        "for z in /sys/class/thermal/thermal_zone*; do "
        "n=$(basename $z); t=$(cat $z/type 2>/dev/null); v=$(cat $z/temp 2>/dev/null); "
        "echo $n'|'$t'|'$v; done"
    )
    result = adb(adb_path, serial, "shell", command, check=False)
    zones = []
    numeric_celsius = []
    for line in result.stdout.splitlines():
        parts = line.strip().split("|", 2)
        if len(parts) != 3:
            continue
        value = None
        try:
            raw = float(parts[2])
            value = raw / 1000.0 if abs(raw) > 200 else raw
            if -50 <= value <= 200:
                numeric_celsius.append(value)
        except ValueError:
            pass
        zones.append({"zone": parts[0], "type": parts[1], "raw": parts[2], "celsius_proxy": value})
    return {
        "available": bool(numeric_celsius),
        "zones": zones,
        "max_celsius_proxy": max(numeric_celsius) if numeric_celsius else None,
        "stderr": result.stderr[-2000:],
    }


def parse_pss(text: str) -> tuple[int | None, int | None]:
    pss = re.search(r"^Pss:\s+(\d+)\s+kB", text, re.MULTILINE)
    rss = re.search(r"^Rss:\s+(\d+)\s+kB", text, re.MULTILINE)
    return (int(pss.group(1)) if pss else None, int(rss.group(1)) if rss else None)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--frame0", required=True)
    parser.add_argument("--frame1", required=True)
    parser.add_argument("--ground-truth", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--iterations", type=int, default=4)
    args = parser.parse_args()

    binary = Path(args.binary).resolve()
    model_dir = Path(args.model_dir).resolve()
    frame0 = Path(args.frame0).resolve()
    frame1 = Path(args.frame1).resolve()
    ground_truth = Path(args.ground_truth).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    required = [binary, model_dir / "flownet.param", model_dir / "flownet.bin", frame0, frame1, ground_truth]
    for path in required:
        if not path.exists():
            raise FileNotFoundError(path)

    prefilter = VfiPrefilter()
    prefilter.observe(
        FrameRecord("device-frame-0", str(frame0), file_sha256(frame0), "device-probe", 0)
    )
    device_decision = prefilter.observe(
        FrameRecord("device-frame-1", str(frame1), file_sha256(frame1), "device-probe", 0)
    )
    if device_decision.decision != "INTERPOLATE" or device_decision.reason != "DISTINCT_DRAWING":
        raise RuntimeError(f"device pair is not eligible: {device_decision.to_dict()}")

    devices = run([args.adb, "devices"]).stdout.splitlines()[1:]
    online = [line.split()[0] for line in devices if line.strip().endswith("\tdevice")]
    if online != [args.serial]:
        raise RuntimeError(f"expected exactly serial {args.serial}, observed {online}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    remote_root = f"/data/local/tmp/quicksr-vfi-probe-{stamp}"
    remote_model = f"{remote_root}/rife-v4.6"
    adb(args.adb, args.serial, "shell", "mkdir", "-p", remote_model)
    push_map = [
        (binary, f"{remote_root}/rife-ncnn-vulkan"),
        (model_dir / "flownet.param", f"{remote_model}/flownet.param"),
        (model_dir / "flownet.bin", f"{remote_model}/flownet.bin"),
        (frame0, f"{remote_root}/frame0.png"),
        (frame1, f"{remote_root}/frame1.png"),
    ]
    for local, remote in push_map:
        adb(args.adb, args.serial, "push", str(local), remote)
    adb(args.adb, args.serial, "shell", "chmod", "0755", f"{remote_root}/rife-ncnn-vulkan")

    remote_hashes = {}
    for local, remote in push_map:
        observed = adb(args.adb, args.serial, "shell", "toybox", "sha256sum", remote).stdout.split()[0]
        expected = file_sha256(local)
        if observed != expected:
            raise RuntimeError(f"remote SHA-256 mismatch for {remote}")
        remote_hashes[remote] = {"sha256": observed, "bytes": local.stat().st_size}

    properties = {}
    for key in [
        "ro.product.manufacturer", "ro.product.model", "ro.product.device", "ro.soc.model",
        "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
    ]:
        properties[key] = adb(args.adb, args.serial, "shell", "getprop", key).stdout.strip()
    surface_flinger = adb(args.adb, args.serial, "shell", "dumpsys", "SurfaceFlinger", check=False).stdout
    gpu_lines = [line.strip() for line in surface_flinger.splitlines() if "GLES:" in line or "Vulkan" in line][:20]
    temperature_before = thermal_snapshot(args.adb, args.serial)
    existing_pid = adb(args.adb, args.serial, "shell", "pidof", "rife-ncnn-vulkan", check=False).stdout.strip()
    if existing_pid:
        raise RuntimeError(f"another rife-ncnn-vulkan process is already running: {existing_pid}")

    runs = []
    for iteration in range(args.iterations):
        remote_output = f"{remote_root}/out-{iteration}.png"
        command = (
            f"cd {remote_root} && ./rife-ncnn-vulkan -0 frame0.png -1 frame1.png "
            f"-o out-{iteration}.png -m rife-v4.6 -g 0 -j 1:1:1"
        )
        started_ns = time.perf_counter_ns()
        process = subprocess.Popen(
            [args.adb, "-s", args.serial, "shell", command],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        )
        peak_pss_kb = None
        peak_rss_kb = None
        samples = 0
        # Sample only the first run; later latency runs avoid adb polling interference.
        if iteration == 0:
            while process.poll() is None:
                pid_result = adb(args.adb, args.serial, "shell", "pidof", "rife-ncnn-vulkan", check=False)
                pid = pid_result.stdout.strip().split()
                if pid:
                    memory = adb(
                        args.adb, args.serial, "shell", "cat", f"/proc/{pid[0]}/smaps_rollup", check=False
                    ).stdout
                    pss_kb, rss_kb = parse_pss(memory)
                    if pss_kb is not None:
                        peak_pss_kb = max(peak_pss_kb or 0, pss_kb)
                    if rss_kb is not None:
                        peak_rss_kb = max(peak_rss_kb or 0, rss_kb)
                    samples += 1
                time.sleep(0.01)
        stdout, stderr = process.communicate()
        ended_ns = time.perf_counter_ns()
        if process.returncode != 0:
            raise RuntimeError(json.dumps({"returncode": process.returncode, "stdout": stdout, "stderr": stderr}, indent=2))
        local_output = output_dir / f"device-out-{iteration}.png"
        adb(args.adb, args.serial, "pull", remote_output, str(local_output))
        matches = re.findall(r"VFI_MODEL_WALL_NS=(\d+)", stderr)
        if len(matches) != 1:
            raise RuntimeError(f"missing instrumented model timing in stderr: {stderr[-4000:]}")
        runs.append({
            "iteration": iteration,
            "sampled_for_memory": iteration == 0,
            "model_wall_time_ns": int(matches[0]),
            "end_to_end_process_wall_time_ns": ended_ns - started_ns,
            "peak_pss_kb": peak_pss_kb,
            "peak_rss_kb": peak_rss_kb,
            "memory_sample_count": samples,
            "output": {"path": str(local_output), "bytes": local_output.stat().st_size, "sha256": file_sha256(local_output)},
            "stderr_tail": stderr[-4000:],
        })

    temperature_after = thermal_snapshot(args.adb, args.serial)
    lifecycle_pid_after = adb(args.adb, args.serial, "shell", "pidof", "rife-ncnn-vulkan", check=False).stdout.strip()
    latency_runs = [item for item in runs if not item["sampled_for_memory"]]
    report = {
        "schema": "anime-vfi-offline-android-report.v1",
        "scope": "android-device-native-cli",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "candidate": {
            "id": "rife-ncnn-vulkan-v4.6",
            "source_commit": args.source_commit,
            "timing_patch_sha256": file_sha256(Path(__file__).with_name("patches") / "rife-ncnn-vulkan-model-timing.txt"),
            "binary_sha256": file_sha256(binary),
            "remote_hashes": remote_hashes,
        },
        "device": {"serial": args.serial, "properties": properties, "gpu_lines": gpu_lines},
        "pair": {
            "prefilter": device_decision.to_dict(),
            "frame0_sha256": file_sha256(frame0), "frame1_sha256": file_sha256(frame1),
            "ground_truth_midpoint_sha256": file_sha256(ground_truth),
        },
        "quality": quality_proxies(Path(runs[-1]["output"]["path"]), ground_truth),
        "temperature_proxy": {"before": temperature_before, "after": temperature_after},
        "runs": runs,
        "summary": {
            "bounded_run_count": len(runs),
            "latency_run_count_excluding_pss_sampled_run": len(latency_runs),
            "model_wall_time_ms_median": median(item["model_wall_time_ns"] for item in latency_runs) / 1_000_000,
            "model_wall_time_ms_min": min(item["model_wall_time_ns"] for item in latency_runs) / 1_000_000,
            "model_wall_time_ms_max": max(item["model_wall_time_ns"] for item in latency_runs) / 1_000_000,
            "sampled_peak_pss_kb": runs[0]["peak_pss_kb"],
            "sampled_peak_rss_kb": runs[0]["peak_rss_kb"],
            "process_absent_after_runs": not lifecycle_pid_after,
        },
        "remote_evidence_directory": remote_root,
        "human_review": "pending",
    }
    report_path = output_dir / "android-report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
