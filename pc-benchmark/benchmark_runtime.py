from __future__ import annotations

import hashlib
import json
import math
import statistics
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort


ROOT = Path(__file__).resolve().parents[1]


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


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
    return float(
        (
            np.abs(np.diff(reference, axis=1) - np.diff(actual, axis=1)).mean()
            + np.abs(np.diff(reference, axis=0) - np.diff(actual, axis=0)).mean()
        )
        / 2.0
    )


def quality_metrics(reference: np.ndarray, actual: np.ndarray) -> dict[str, float]:
    return {"psnr_db": psnr(reference, actual), "global_ssim": global_ssim(reference, actual), "edge_mae": edge_mae(reference, actual)}


@dataclass(frozen=True)
class ModelSpec:
    id: str
    scale: float
    path: Path
    sha256: str
    input_name: str
    output_name: str
    input_shape: list[int]
    output_shape: list[int]


def load_model_spec(registry_path: Path, model_id: str) -> ModelSpec:
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    matches = [item for item in registry["models"] if item["id"] == model_id]
    if len(matches) != 1:
        raise ValueError(f"model registry must contain exactly one {model_id!r}")
    item = matches[0]
    if item["status"] != "integrated" or not item.get("path") or not item.get("sha256"):
        raise ValueError(f"model {model_id} is not integrated: {item['status']}")
    path = ROOT / item["path"]
    if not path.is_file():
        raise FileNotFoundError(path)
    observed = file_sha256(path)
    if observed != item["sha256"]:
        raise ValueError(f"model SHA-256 mismatch: expected {item['sha256']}, observed {observed}")
    return ModelSpec(
        id=item["id"], scale=float(item["scale"]), path=path, sha256=item["sha256"],
        input_name=item["input_name"], output_name=item["output_name"],
        input_shape=item["input_shape"], output_shape=item["output_shape"],
    )


class OrtModelRunner:
    def __init__(self, spec: ModelSpec):
        options = ort.SessionOptions()
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        self.spec = spec
        self.session = ort.InferenceSession(spec.path.read_bytes(), options, providers=["CPUExecutionProvider"])
        input_meta, output_meta = self.session.get_inputs()[0], self.session.get_outputs()[0]
        if input_meta.name != spec.input_name or input_meta.shape != spec.input_shape:
            raise ValueError(f"model input contract mismatch: {input_meta.name} {input_meta.shape}")
        if output_meta.name != spec.output_name or output_meta.shape != spec.output_shape:
            raise ValueError(f"model output contract mismatch: {output_meta.name} {output_meta.shape}")

    def infer(self, rgb: np.ndarray) -> tuple[np.ndarray, float]:
        expected_height, expected_width = self.spec.input_shape[2], self.spec.input_shape[3]
        if rgb.shape != (expected_height, expected_width, 3):
            raise ValueError(f"expected RGB {expected_width}x{expected_height}, observed {rgb.shape}")
        tensor = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype=np.float32)
        started = time.perf_counter_ns()
        output = self.session.run([self.spec.output_name], {self.spec.input_name: tensor})[0]
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        return np.clip(output[0].transpose(1, 2, 0), 0.0, 1.0), elapsed_ms

    def warmup(self, rgb: np.ndarray, count: int) -> None:
        for _ in range(count):
            self.infer(rgb)
