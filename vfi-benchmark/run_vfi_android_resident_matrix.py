"""Measure a resident RIFE v4.6 Android process across a bounded resolution matrix."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean, median
from typing import Any

from run_vfi_android_probe import adb, file_sha256, parse_pss, thermal_snapshot
from run_vfi_host_benchmark import quality_proxies, tree_identity


MODEL_PATTERN = re.compile(
    r"VFI_MODEL_WALL_NS id=(\d+) ns=(\d+) timestep=([0-9.]+) width=(\d+) height=(\d+) padded_width=(\d+) padded_height=(\d+)"
)
STAGE_PATTERNS = {
    "decode": re.compile(r"VFI_DECODE_WALL_NS id=(\d+) ns=(\d+)"),
    "encode": re.compile(r"VFI_ENCODE_WALL_NS id=(\d+) ns=(\d+)"),
}


def parse_timing(stderr: str) -> dict[str, Any]:
    model = [
        {
            "id": int(match.group(1)), "wall_time_ns": int(match.group(2)),
            "timestep": float(match.group(3)), "width": int(match.group(4)),
            "height": int(match.group(5)), "padded_width": int(match.group(6)),
            "padded_height": int(match.group(7)),
        }
        for match in MODEL_PATTERN.finditer(stderr)
    ]
    stages = {
        name: [{"id": int(match.group(1)), "wall_time_ns": int(match.group(2))} for match in pattern.finditer(stderr)]
        for name, pattern in STAGE_PATTERNS.items()
    }
    vulkan = re.findall(r"VFI_VULKAN_INIT_WALL_NS=(\d+)", stderr)
    model_load = re.findall(r"VFI_MODEL_LOAD_WALL_NS gpu=(-?\d+) ns=(\d+)", stderr)
    if len(vulkan) != 1 or len(model_load) != 1:
        raise RuntimeError("missing Vulkan/model-load timing")
    if len(model) != 14 or len(stages["decode"]) != 14 or len(stages["encode"]) != 14:
        raise RuntimeError(
            f"expected 14 model/decode/encode events, observed {len(model)}/{len(stages['decode'])}/{len(stages['encode'])}"
        )
    midpoint = sorted((item for item in model if abs(item["timestep"] - 0.5) < 1e-6), key=lambda item: item["id"])
    if [item["id"] for item in midpoint] != [1, 3, 5, 7, 9, 11]:
        raise RuntimeError(f"unexpected midpoint ids: {[item['id'] for item in midpoint]}")
    return {
        "vulkan_init_wall_time_ns": int(vulkan[0]),
        "model_load": {"gpu": int(model_load[0][0]), "wall_time_ns": int(model_load[0][1])},
        "model_calls": sorted(model, key=lambda item: item["id"]),
        "midpoint_calls": midpoint,
        "decode_calls": sorted(stages["decode"], key=lambda item: item["id"]),
        "encode_calls": sorted(stages["encode"], key=lambda item: item["id"]),
    }


def run_remote(
    adb_path: str, serial: str, remote_root: str, level_id: str, sampled: bool
) -> dict[str, Any]:
    suffix = "sampled" if sampled else "latency"
    remote_output = f"{remote_root}/{level_id}/output-{suffix}"
    adb(adb_path, serial, "shell", "mkdir", "-p", remote_output)
    command = (
        f"cd {remote_root} && ./rife-ncnn-vulkan -i {level_id}/input "
        f"-o {level_id}/output-{suffix} -m rife-v4.6 -g 0 -j 1:1:1 -v"
    )
    started_ns = time.perf_counter_ns()
    peak_pss_kb = None
    peak_rss_kb = None
    memory_samples = 0
    # File-backed capture prevents a verbose adb pipe from filling while the parent samples PSS.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as stdout_file, tempfile.TemporaryFile(
        mode="w+", encoding="utf-8", errors="replace"
    ) as stderr_file:
        process = subprocess.Popen(
            [adb_path, "-s", serial, "shell", command], stdout=stdout_file, stderr=stderr_file,
            text=True, encoding="utf-8", errors="replace",
        )
        if sampled:
            while process.poll() is None:
                pid_text = adb(adb_path, serial, "shell", "pidof", "rife-ncnn-vulkan", check=False).stdout.strip()
                if pid_text:
                    pid = pid_text.split()[0]
                    memory = adb(adb_path, serial, "shell", "cat", f"/proc/{pid}/smaps_rollup", check=False).stdout
                    pss_kb, rss_kb = parse_pss(memory)
                    if pss_kb is not None:
                        peak_pss_kb = max(peak_pss_kb or 0, pss_kb)
                    if rss_kb is not None:
                        peak_rss_kb = max(peak_rss_kb or 0, rss_kb)
                    memory_samples += 1
                time.sleep(0.01)
        process.wait()
        stdout_file.seek(0)
        stderr_file.seek(0)
        stdout, stderr = stdout_file.read(), stderr_file.read()
    elapsed_ns = time.perf_counter_ns() - started_ns
    if process.returncode != 0:
        raise RuntimeError(json.dumps({"returncode": process.returncode, "stdout": stdout, "stderr": stderr[-12000:]}, indent=2))
    timing = parse_timing(stderr)
    timing.update(
        {
            "whole_process_wall_time_ns": elapsed_ns,
            "peak_pss_kb": peak_pss_kb,
            "peak_rss_kb": peak_rss_kb,
            "memory_sample_count": memory_samples,
            "stderr_tail": stderr[-12000:],
        }
    )
    return timing


def summarize_level(
    adb_path: str,
    serial: str,
    remote_root: str,
    fixture_root: Path,
    local_output_root: Path,
    level: dict[str, Any],
) -> dict[str, Any]:
    level_id = level["id"]
    remote_level = f"{remote_root}/{level_id}"
    remote_input = f"{remote_level}/input"
    adb(adb_path, serial, "shell", "mkdir", "-p", remote_input)
    for index in range(7):
        local = fixture_root / level_id / "input" / f"frame_{index:03d}.png"
        adb(adb_path, serial, "push", str(local), f"{remote_input}/{local.name}")

    temperature_before = thermal_snapshot(adb_path, serial)
    latency = run_remote(adb_path, serial, remote_root, level_id, sampled=False)
    sampled = run_remote(adb_path, serial, remote_root, level_id, sampled=True)
    temperature_after = thermal_snapshot(adb_path, serial)

    level_output = local_output_root / level_id
    level_output.mkdir(parents=True, exist_ok=True)
    outputs = []
    midpoint_quality = []
    for output_index in range(1, 15):
        name = f"{output_index:08d}.png"
        local = level_output / name
        adb(adb_path, serial, "pull", f"{remote_level}/output-latency/{name}", str(local))
        item = {"name": name, "bytes": local.stat().st_size, "sha256": file_sha256(local)}
        task_id = output_index - 1
        if task_id in [1, 3, 5, 7, 9, 11]:
            pair_index = task_id // 2
            quality = quality_proxies(
                local, fixture_root / level_id / "ground-truth" / f"mid_{pair_index:03d}.png"
            )
            item["pair_index"] = pair_index
            item["quality"] = quality
            midpoint_quality.append(quality)
        outputs.append(item)

    midpoint_calls = latency["midpoint_calls"]
    stable = midpoint_calls[1:]
    dimensions = {(item["width"], item["height"], item["padded_width"], item["padded_height"]) for item in latency["model_calls"]}
    if dimensions != {(level["width"], level["height"], level["padded_width"], level["padded_height"])}:
        raise RuntimeError(f"dimension mismatch: {dimensions}")
    output_tree_hash = tree_identity(level_output)[0]
    return {
        "id": level_id,
        "status": "PASS",
        "input": {
            "width": level["width"], "height": level["height"],
            "padded_width": level["padded_width"], "padded_height": level["padded_height"],
            "source_frames": 7, "all_adjacent_pairs": "INTERPOLATE/DISTINCT_DRAWING",
            "pair_identities": [item["pair_identity"] for item in level["decisions"][1:]],
        },
        "cold_process": {
            "whole_process_wall_time_ns": latency["whole_process_wall_time_ns"],
            "vulkan_init_wall_time_ns": latency["vulkan_init_wall_time_ns"],
            "model_load_wall_time_ns": latency["model_load"]["wall_time_ns"],
            "first_model_call_wall_time_ns": latency["model_calls"][0]["wall_time_ns"],
        },
        "midpoint_timing": {
            "warmup_wall_time_ns": midpoint_calls[0]["wall_time_ns"],
            "stable_wall_time_ns": [item["wall_time_ns"] for item in stable],
            "stable_median_ns": median(item["wall_time_ns"] for item in stable),
            "stable_min_ns": min(item["wall_time_ns"] for item in stable),
            "stable_max_ns": max(item["wall_time_ns"] for item in stable),
        },
        "png_io": {
            "decode_pair_wall_time_ns": [item["wall_time_ns"] for item in latency["decode_calls"]],
            "encode_wall_time_ns": [item["wall_time_ns"] for item in latency["encode_calls"]],
            "decode_pair_median_ns": median(item["wall_time_ns"] for item in latency["decode_calls"]),
            "encode_median_ns": median(item["wall_time_ns"] for item in latency["encode_calls"]),
            "note": "decode/model/encode use a pipeline and their sums are not additive to whole-process wall time",
        },
        "memory_sampled_run": {
            "peak_pss_kb": sampled["peak_pss_kb"], "peak_rss_kb": sampled["peak_rss_kb"],
            "memory_sample_count": sampled["memory_sample_count"],
            "whole_process_wall_time_ns": sampled["whole_process_wall_time_ns"],
            "excluded_from_latency_summary": True,
        },
        "temperature_proxy": {"before": temperature_before, "after": temperature_after},
        "outputs": {"count": len(outputs), "tree_sha256": output_tree_hash, "files": outputs},
        "quality_proxy_midpoints": {
            "count": len(midpoint_quality),
            "psnr_db_mean": mean(item["psnr_db"] for item in midpoint_quality),
            "ssim_global_mean": mean(item["ssim_global_proxy"] for item in midpoint_quality),
            "edge_gradient_mae_mean": mean(item["edge_gradient_mae"] for item in midpoint_quality),
            "human_review": "pending",
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--fixture-manifest", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--levels", nargs="+", required=True)
    args = parser.parse_args()

    manifest_path = Path(args.fixture_manifest).resolve()
    fixture_root = manifest_path.parent
    output_root = Path(args.output_dir).resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    binary = Path(args.binary).resolve()
    model_dir = Path(args.model_dir).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    levels_by_id = {item["id"]: item for item in manifest["levels"]}
    devices = subprocess.run([args.adb, "devices"], check=True, capture_output=True, text=True).stdout.splitlines()[1:]
    online = [line.split()[0] for line in devices if line.strip().endswith("\tdevice")]
    if online != [args.serial]:
        raise RuntimeError(f"expected exactly the requested device; observed {len(online)} online device(s)")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    remote_root = f"/data/local/tmp/quicksr-vfi-resident-{stamp}"
    adb(args.adb, args.serial, "shell", "mkdir", "-p", f"{remote_root}/rife-v4.6")
    push_files = [
        (binary, f"{remote_root}/rife-ncnn-vulkan"),
        (model_dir / "flownet.param", f"{remote_root}/rife-v4.6/flownet.param"),
        (model_dir / "flownet.bin", f"{remote_root}/rife-v4.6/flownet.bin"),
    ]
    remote_hashes = {}
    for local, remote in push_files:
        adb(args.adb, args.serial, "push", str(local), remote)
        observed = adb(args.adb, args.serial, "shell", "toybox", "sha256sum", remote).stdout.split()[0]
        if observed != file_sha256(local):
            raise RuntimeError(f"remote hash mismatch: {remote}")
        remote_hashes[remote] = {"sha256": observed, "bytes": local.stat().st_size}
    adb(args.adb, args.serial, "shell", "chmod", "0755", f"{remote_root}/rife-ncnn-vulkan")

    report: dict[str, Any] = {
        "schema": "anime-vfi-resident-android-matrix.v1",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "scope": "ignored raw physical-device evidence; standalone native CLI",
        "device_serial": args.serial,
        "candidate": {
            "source_commit": "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7",
            "ncnn_commit": "b4ba207c18d3103d6df890c0e3a97b469b196b26",
            "libwebp_commit": "5abb55823bb6196a918dd87202b2f32bbaff4c18",
            "binary_sha256": file_sha256(binary),
            "timing_patch_sha256": file_sha256(Path(__file__).with_name("patches") / "rife-ncnn-vulkan-model-timing.txt"),
            "remote_hashes": remote_hashes,
        },
        "fixture_manifest_sha256": file_sha256(manifest_path),
        "levels": [],
        "remote_evidence_directory": remote_root,
    }
    report_path = output_root / "resident-android-report.json"
    for level_id in args.levels:
        if level_id not in levels_by_id:
            raise RuntimeError(f"missing fixture level {level_id}")
        try:
            result = summarize_level(
                args.adb, args.serial, remote_root, fixture_root, output_root, levels_by_id[level_id]
            )
        except Exception as error:
            result = {"id": level_id, "status": "FAILED", "reason": str(error)}
            report["levels"].append(result)
            report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            raise
        report["levels"].append(result)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    report["process_absent_after_matrix"] = not adb(
        args.adb, args.serial, "shell", "pidof", "rife-ncnn-vulkan", check=False
    ).stdout.strip()
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
