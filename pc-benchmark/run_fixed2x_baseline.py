from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import statistics
import subprocess
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = ROOT / "derived-models" / "quicksrnet-small-2x-fixed640x360-dcr.onnx"
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "fixed2x-baseline"


def anime_like_reference(width: int = 1280, height: int = 720) -> np.ndarray:
    """Create a deterministic, rights-clear frame with flat colors and sharp line art."""
    y, x = np.mgrid[0:height, 0:width]
    image = np.empty((height, width, 3), dtype=np.float32)
    image[...] = np.array([0.72, 0.86, 0.98], dtype=np.float32)
    image += (y / max(height - 1, 1))[..., None] * np.array([0.08, -0.04, -0.08])

    face = ((x - width * 0.52) / (width * 0.19)) ** 2 + ((y - height * 0.50) / (height * 0.34)) ** 2 <= 1.0
    image[face] = np.array([0.98, 0.79, 0.68], dtype=np.float32)

    hair = face & (y < height * (0.31 + 0.10 * np.sin(x / width * 9.0)))
    image[hair] = np.array([0.10, 0.13, 0.24], dtype=np.float32)

    for eye_x in (0.46, 0.59):
        eye = ((x - width * eye_x) / (width * 0.024)) ** 2 + ((y - height * 0.50) / (height * 0.033)) ** 2 <= 1.0
        iris = ((x - width * eye_x) / (width * 0.010)) ** 2 + ((y - height * 0.50) / (height * 0.021)) ** 2 <= 1.0
        image[eye] = 0.98
        image[iris] = np.array([0.08, 0.12, 0.24], dtype=np.float32)

    outline_distance = ((x - width * 0.52) / (width * 0.19)) ** 2 + ((y - height * 0.50) / (height * 0.34)) ** 2
    outline = (outline_distance >= 0.985) & (outline_distance <= 1.025)
    image[outline] = np.array([0.07, 0.08, 0.12], dtype=np.float32)

    speed_lines = ((x + 2 * y) % 97 < 3) & (x < width * 0.28)
    image[speed_lines] = np.array([0.20, 0.46, 0.72], dtype=np.float32)
    return np.clip(image, 0.0, 1.0)


def downsample_area_2x(image: np.ndarray) -> np.ndarray:
    height, width, channels = image.shape
    if height % 2 or width % 2:
        raise ValueError("2x area downsample requires even dimensions")
    return image.reshape(height // 2, 2, width // 2, 2, channels).mean(axis=(1, 3), dtype=np.float32)


def resize_bilinear(image: np.ndarray, output_width: int, output_height: int) -> np.ndarray:
    input_height, input_width, _ = image.shape
    ys = (np.arange(output_height, dtype=np.float32) + 0.5) * input_height / output_height - 0.5
    xs = (np.arange(output_width, dtype=np.float32) + 0.5) * input_width / output_width - 0.5
    y0 = np.floor(ys).astype(np.int32)
    x0 = np.floor(xs).astype(np.int32)
    y1 = np.clip(y0 + 1, 0, input_height - 1)
    x1 = np.clip(x0 + 1, 0, input_width - 1)
    y0 = np.clip(y0, 0, input_height - 1)
    x0 = np.clip(x0, 0, input_width - 1)
    wy = (ys - y0).reshape(-1, 1, 1)
    wx = (xs - x0).reshape(1, -1, 1)
    rows = image[y0] * (1.0 - wy) + image[y1] * wy
    return (rows[:, x0] * (1.0 - wx) + rows[:, x1] * wx).astype(np.float32)


def psnr(reference: np.ndarray, actual: np.ndarray) -> float:
    mse = float(np.mean((reference - actual) ** 2, dtype=np.float64))
    return math.inf if mse == 0.0 else 10.0 * math.log10(1.0 / mse)


def global_ssim(reference: np.ndarray, actual: np.ndarray) -> float:
    scores = []
    for channel in range(reference.shape[2]):
        left = reference[..., channel].astype(np.float64)
        right = actual[..., channel].astype(np.float64)
        mean_left, mean_right = float(left.mean()), float(right.mean())
        var_left, var_right = float(left.var()), float(right.var())
        covariance = float(((left - mean_left) * (right - mean_right)).mean())
        c1, c2 = 0.01**2, 0.03**2
        scores.append(
            ((2 * mean_left * mean_right + c1) * (2 * covariance + c2))
            / ((mean_left**2 + mean_right**2 + c1) * (var_left + var_right + c2))
        )
    return float(statistics.fmean(scores))


def edge_mae(reference: np.ndarray, actual: np.ndarray) -> float:
    ref_dx = np.diff(reference, axis=1)
    ref_dy = np.diff(reference, axis=0)
    actual_dx = np.diff(actual, axis=1)
    actual_dy = np.diff(actual, axis=0)
    return float((np.abs(ref_dx - actual_dx).mean() + np.abs(ref_dy - actual_dy).mean()) / 2.0)


def metrics(reference: np.ndarray, actual: np.ndarray) -> dict[str, float]:
    return {
        "psnr_db": psnr(reference, actual),
        "global_ssim": global_ssim(reference, actual),
        "edge_mae": edge_mae(reference, actual),
    }


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[index]


def write_ppm(path: Path, image: np.ndarray) -> None:
    pixels = np.rint(np.clip(image, 0.0, 1.0) * 255.0).astype(np.uint8)
    path.write_bytes(f"P6\n{pixels.shape[1]} {pixels.shape[0]}\n255\n".encode("ascii") + pixels.tobytes())


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_revision() -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else None


def run(model_path: Path, output_dir: Path, warmups: int, iterations: int) -> dict[str, Any]:
    if warmups < 0 or iterations < 1:
        raise ValueError("warmups must be >= 0 and iterations must be >= 1")
    if not model_path.is_file():
        raise FileNotFoundError(model_path)

    options = ort.SessionOptions()
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(model_path.read_bytes(), options, providers=["CPUExecutionProvider"])
    input_meta, output_meta = session.get_inputs()[0], session.get_outputs()[0]
    if input_meta.shape != [1, 3, 360, 640] or output_meta.shape != [1, 3, 720, 1280]:
        raise ValueError(f"unexpected model contract: {input_meta.shape} -> {output_meta.shape}")

    reference = anime_like_reference()
    low_resolution = downsample_area_2x(reference)
    nchw = np.ascontiguousarray(low_resolution.transpose(2, 0, 1)[None], dtype=np.float32)
    for _ in range(warmups):
        session.run([output_meta.name], {input_meta.name: nchw})

    elapsed_ms: list[float] = []
    output = None
    for _ in range(iterations):
        started = time.perf_counter_ns()
        output = session.run([output_meta.name], {input_meta.name: nchw})[0]
        elapsed_ms.append((time.perf_counter_ns() - started) / 1_000_000.0)
    assert output is not None
    neural = np.clip(output[0].transpose(1, 2, 0), 0.0, 1.0)

    linear_started = time.perf_counter_ns()
    linear = resize_bilinear(low_resolution, 1280, 720)
    linear_ms = (time.perf_counter_ns() - linear_started) / 1_000_000.0

    output_dir.mkdir(parents=True, exist_ok=True)
    write_ppm(output_dir / "reference.ppm", reference)
    write_ppm(output_dir / "low-resolution.ppm", low_resolution)
    write_ppm(output_dir / "quicksr-2x.ppm", neural)
    write_ppm(output_dir / "bilinear-2x.ppm", linear)

    report: dict[str, Any] = {
        "schema_version": 1,
        "status": "observed-pc-baseline",
        "workload": "deterministic-rights-clear-anime-like-frame",
        "git_revision": git_revision(),
        "host": {"platform": platform.platform(), "python": platform.python_version()},
        "model": {
            "path": model_path.relative_to(ROOT).as_posix(),
            "sha256": sha256(model_path),
            "input_shape": input_meta.shape,
            "output_shape": output_meta.shape,
            "provider": session.get_providers()[0],
        },
        "timing_ms": {
            "warmups": warmups,
            "iterations": iterations,
            "quicksr_mean": statistics.fmean(elapsed_ms),
            "quicksr_p50": statistics.median(elapsed_ms),
            "quicksr_p95_nearest_rank": percentile(elapsed_ms, 0.95),
            "quicksr_samples": elapsed_ms,
            "bilinear_single_run": linear_ms,
        },
        "quality": {"quicksr_2x": metrics(reference, neural), "bilinear_2x": metrics(reference, linear)},
        "limits": [
            "Synthetic-frame scores are pipeline checks, not anime-dataset quality evidence.",
            "CPUExecutionProvider timing does not predict Android QNN HTP timing.",
            "global_ssim is a whole-frame diagnostic, not windowed SSIM.",
        ],
    }
    (output_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the fixed 640x360 QuickSR 2x PC baseline")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=10)
    args = parser.parse_args()
    report = run(args.model.resolve(), args.output.resolve(), args.warmups, args.iterations)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
