"""Run the offline VFI contract and invoke an upstream ncnn Vulkan executable when allowed."""

from __future__ import annotations

import argparse
import ctypes
import json
import math
import os
import platform
import subprocess
import threading
import time
from dataclasses import asdict
from hashlib import sha256
from pathlib import Path
from statistics import median
from typing import Any

import numpy as np
from PIL import Image

from vfi_prefilter import FrameRecord, VfiPrefilter, file_sha256


def tree_identity(root: Path) -> tuple[str, list[dict[str, Any]]]:
    digest = sha256()
    files = []
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        observed = file_sha256(path)
        size = path.stat().st_size
        digest.update(relative.encode("utf-8") + b"\0" + observed.encode("ascii") + b"\0")
        files.append({"path": relative, "bytes": size, "sha256": observed})
    return digest.hexdigest(), files


def image_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0


def quality_proxies(output: Path, ground_truth: Path) -> dict[str, Any]:
    actual = image_rgb(output)
    expected = image_rgb(ground_truth)
    mse = float(np.mean((actual - expected) ** 2))
    psnr = float("inf") if mse == 0 else -10.0 * math.log10(mse)
    x = actual.reshape(-1).astype(np.float64)
    y = expected.reshape(-1).astype(np.float64)
    c1, c2 = 0.01**2, 0.03**2
    ux, uy = float(x.mean()), float(y.mean())
    vx, vy = float(x.var()), float(y.var())
    covariance = float(np.mean((x - ux) * (y - uy)))
    ssim = ((2 * ux * uy + c1) * (2 * covariance + c2)) / ((ux * ux + uy * uy + c1) * (vx + vy + c2))

    def edge_map(image: np.ndarray) -> np.ndarray:
        luma = image[..., 0] * 0.2126 + image[..., 1] * 0.7152 + image[..., 2] * 0.0722
        gx = np.diff(luma, axis=1, append=luma[:, -1:])
        gy = np.diff(luma, axis=0, append=luma[-1:, :])
        return np.sqrt(gx * gx + gy * gy)

    edge_mae = float(np.mean(np.abs(edge_map(actual) - edge_map(expected))))
    return {
        "psnr_db": psnr,
        "ssim_global_proxy": float(ssim),
        "edge_gradient_mae": edge_mae,
        "lpips": {"available": False, "reason": "lpips dependency not required by the source-only probe"},
        "human_review": "pending",
    }


def _windows_private_bytes(pid: int) -> int | None:
    if os.name != "nt":
        return None
    PROCESS_QUERY_INFORMATION = 0x0400
    PROCESS_VM_READ = 0x0010
    handle = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, False, pid)
    if not handle:
        return None

    class Counters(ctypes.Structure):
        _fields_ = [
            ("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
            ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t),
            ("PrivateUsage", ctypes.c_size_t),
        ]

    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    ok = ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
    ctypes.windll.kernel32.CloseHandle(handle)
    return int(counters.PrivateUsage) if ok else None


def run_process(command: list[str]) -> dict[str, Any]:
    started_ns = time.perf_counter_ns()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    peak_private_bytes = 0
    stop = threading.Event()

    def sample() -> None:
        nonlocal peak_private_bytes
        while not stop.is_set():
            observed = _windows_private_bytes(process.pid)
            if observed is not None:
                peak_private_bytes = max(peak_private_bytes, observed)
            stop.wait(0.005)

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    stdout, stderr = process.communicate()
    stop.set()
    sampler.join(timeout=1.0)
    ended_ns = time.perf_counter_ns()
    return {
        "returncode": process.returncode,
        "wall_time_ns": ended_ns - started_ns,
        "peak_private_bytes": peak_private_bytes or None,
        "stdout_tail": stdout[-4000:],
        "stderr_tail": stderr[-4000:],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-manifest", required=True)
    parser.add_argument("--executable", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--candidate-id", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--gpu-id", default="0")
    args = parser.parse_args()

    manifest_path = Path(args.fixture_manifest).resolve()
    fixture_root = manifest_path.parent
    executable = Path(args.executable).resolve()
    model_dir = Path(args.model_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    fixture = json.loads(manifest_path.read_text(encoding="utf-8"))
    model_tree_sha, model_files = tree_identity(model_dir)
    prefilter = VfiPrefilter()
    records = []

    for item in fixture["events"]:
        event_started_ns = time.perf_counter_ns()
        frame_path = fixture_root / item["file"]
        observed_sha = file_sha256(frame_path)
        expected_sha = fixture["files"][item["file"]]["sha256"]
        if observed_sha != expected_sha:
            raise RuntimeError(f"fixture SHA-256 mismatch for {frame_path}")
        frame = FrameRecord(
            frame_id=item["frame_id"], path=str(frame_path), sha256=observed_sha,
            stream_id=item["stream_id"], generation=int(item["generation"]),
        )
        decision_started_ns = time.perf_counter_ns()
        decision = prefilter.observe(frame)
        decision_ns = time.perf_counter_ns() - decision_started_ns
        if decision.reason != item["expected_reason"]:
            raise RuntimeError(
                f"{item['frame_id']}: expected {item['expected_reason']}, observed {decision.reason}"
            )
        record: dict[str, Any] = {
            "decision": decision.to_dict(),
            "decision_wall_time_ns": decision_ns,
            "model_runs": [],
            "quality": None,
        }
        if decision.decision == "INTERPOLATE":
            previous_id = decision.previous_frame_id
            previous_item = next(event for event in fixture["events"] if event["frame_id"] == previous_id)
            previous_path = fixture_root / previous_item["file"]
            for iteration in range(args.iterations):
                output_path = output_dir / f"{decision.pair_identity}-{iteration}.png"
                command = [
                    str(executable), "-0", str(previous_path), "-1", str(frame_path),
                    "-o", str(output_path), "-m", str(model_dir), "-g", args.gpu_id,
                    "-j", "1:1:1",
                ]
                result = run_process(command)
                result["iteration"] = iteration
                if result["returncode"] != 0 or not output_path.is_file():
                    raise RuntimeError(json.dumps({"command": command, "result": result}, indent=2))
                result["output"] = {
                    "path": str(output_path), "bytes": output_path.stat().st_size,
                    "sha256": file_sha256(output_path),
                }
                record["model_runs"].append(result)
            if item.get("ground_truth_midpoint"):
                record["quality"] = quality_proxies(
                    Path(record["model_runs"][-1]["output"]["path"]),
                    fixture_root / item["ground_truth_midpoint"],
                )
        record["end_to_end_wall_time_ns"] = time.perf_counter_ns() - event_started_ns
        records.append(record)

    model_times = [run["wall_time_ns"] for record in records for run in record["model_runs"]]
    report = {
        "schema": "anime-vfi-offline-host-report.v1",
        "scope": "host",
        "candidate": {
            "id": args.candidate_id,
            "source_commit": args.source_commit,
            "executable": {"sha256": file_sha256(executable), "bytes": executable.stat().st_size},
            "model_tree_sha256": model_tree_sha,
            "model_files": model_files,
        },
        "fixture": {"manifest_sha256": file_sha256(manifest_path), "rights": fixture["rights"]},
        "runtime": {"platform": platform.platform(), "python": platform.python_version(), "gpu_id": args.gpu_id},
        "temperature_proxy": {"available": False, "reason": "no trustworthy host sensor exposed to this probe"},
        "events": records,
        "summary": {
            "event_count": len(records),
            "interpolated_pair_count": sum(r["decision"]["decision"] == "INTERPOLATE" for r in records),
            "bypassed_pair_or_boundary_count": sum(r["decision"]["decision"] == "BYPASS" for r in records),
            "model_run_count": len(model_times),
            "model_wall_time_ms_median": median(model_times) / 1_000_000 if model_times else None,
            "model_wall_time_ms_min": min(model_times) / 1_000_000 if model_times else None,
            "model_wall_time_ms_max": max(model_times) / 1_000_000 if model_times else None,
            "peak_private_bytes_max": max(
                (run["peak_private_bytes"] or 0 for record in records for run in record["model_runs"]),
                default=0,
            ) or None,
        },
    }
    report_path = output_dir / "host-report.json"
    report_path.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
