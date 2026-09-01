from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn


ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
DEFAULT_SOURCES = HERE / "model-sources.json"
DEFAULT_REPORT = ROOT / "build" / "pc-benchmark" / "model-export-report.json"
CANONICAL_2X = ROOT / "models" / "quicksrnet-small-2x-opset17.onnx"
CANONICAL_2X_SHA256 = "3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce"
INPUT_WIDTH = 640
INPUT_HEIGHT = 360


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class QuickSRNetSmall(nn.Module):
    """Minimal upstream-compatible QuickSRNetSmall graph for local ONNX export."""

    def __init__(self, scale: float):
        super().__init__()
        if scale not in (1.5, 2.0, 3.0, 4.0):
            raise ValueError(f"unsupported scale: {scale}")
        self.scale = scale
        self.cnn = nn.Sequential(
            nn.Conv2d(3, 32, 3, padding=1),
            nn.Hardtanh(0.0, 1.0),
            nn.Conv2d(32, 32, 3, padding=1),
            nn.Hardtanh(0.0, 1.0),
            nn.Conv2d(32, 32, 3, padding=1),
            nn.Hardtanh(0.0, 1.0),
        )
        if scale == 1.5:
            self.space_to_depth: nn.Module = nn.PixelUnshuffle(2)
            self.conv_last = nn.Conv2d(32 * 4, 3 * 9, 1)
            self.depth_to_space = nn.PixelShuffle(3)
        else:
            integer_scale = int(scale)
            self.space_to_depth = nn.Identity()
            self.conv_last = nn.Conv2d(32, 3 * integer_scale * integer_scale, 3, padding=1)
            self.depth_to_space = nn.PixelShuffle(integer_scale)
        self.clip_output = nn.Hardtanh(0.0, 1.0)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        features = self.space_to_depth(self.cnn(image))
        return self.depth_to_space(self.clip_output(self.conv_last(features)))


def verified_checkpoint(spec: dict[str, Any], cache_root: Path) -> Path:
    path = cache_root / spec["file_name"]
    if not path.is_file() or path.stat().st_size != spec["bytes"] or sha256(path) != spec["sha256"]:
        raise ValueError(f"checkpoint is missing or unverified: {path.name}")
    return path


def output_size(scale: float) -> tuple[int, int]:
    return int(INPUT_WIDTH * scale), int(INPUT_HEIGHT * scale)


def load_state_dict(path: Path) -> dict[str, torch.Tensor]:
    # These legacy official archives also contain optimizer state, which current
    # torch cannot parse with weights_only=True. The caller verifies the exact
    # source URL, byte count and SHA-256 before this trusted-pickle boundary.
    checkpoint = torch.load(path, map_location="cpu", weights_only=False)
    state_dict = checkpoint.get("state_dict")
    if not isinstance(state_dict, dict) or set(state_dict) != {
        "cnn.0.weight", "cnn.0.bias", "cnn.2.weight", "cnn.2.bias",
        "cnn.4.weight", "cnn.4.bias", "conv_last.weight", "conv_last.bias",
    }:
        raise ValueError(f"unexpected checkpoint state_dict contract: {path.name}")
    return state_dict


def session(model_bytes: bytes) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    return ort.InferenceSession(model_bytes, options, providers=["CPUExecutionProvider"])


def write_export(model: nn.Module, example: torch.Tensor, output_path: Path, dynamic: bool) -> bytes:
    temporary = output_path.with_name(f".{output_path.name}.tmp-{os.getpid()}")
    try:
        dynamic_axes = None
        if dynamic:
            dynamic_axes = {
                "image": {2: "height", 3: "width"},
                "upscaled_image": {2: "output_height", 3: "output_width"},
            }
        torch.onnx.export(
            model,
            (example,),
            temporary,
            input_names=["image"],
            output_names=["upscaled_image"],
            dynamic_axes=dynamic_axes,
            opset_version=17,
            do_constant_folding=True,
            dynamo=False,
        )
        exported = onnx.load(temporary)
        onnx.checker.check_model(exported, full_check=True)
        model_bytes = exported.SerializeToString(deterministic=True)
        temporary.write_bytes(model_bytes)
        os.replace(temporary, output_path)
        return model_bytes
    finally:
        temporary.unlink(missing_ok=True)


def export_one(spec: dict[str, Any], cache_root: Path) -> dict[str, Any]:
    scale = float(spec["scale"])
    checkpoint_path = verified_checkpoint(spec, cache_root)
    model = QuickSRNetSmall(scale)
    model.load_state_dict(load_state_dict(checkpoint_path), strict=True)
    model.eval()
    tag = "1p5" if scale == 1.5 else str(int(scale))
    output_path = ROOT / "derived-models" / f"quicksrnet-small-{tag}x-fixed640x360.onnx"
    example = torch.zeros((1, 3, INPUT_HEIGHT, INPUT_WIDTH), dtype=torch.float32)
    write_export(model, example, output_path, dynamic=False)

    width, height = output_size(scale)
    runner = session(output_path.read_bytes())
    input_meta, output_meta = runner.get_inputs()[0], runner.get_outputs()[0]
    if input_meta.name != "image" or input_meta.shape != [1, 3, INPUT_HEIGHT, INPUT_WIDTH]:
        raise ValueError(f"exported input contract changed for {scale}x")
    if output_meta.name != "upscaled_image" or output_meta.shape != [1, 3, height, width]:
        raise ValueError(f"exported output contract changed for {scale}x: {output_meta.shape}")
    generator = np.random.default_rng(20260901)
    test_input = generator.random((1, 3, INPUT_HEIGHT, INPUT_WIDTH), dtype=np.float32)
    actual = runner.run(["upscaled_image"], {"image": test_input})[0]
    if actual.shape != (1, 3, height, width) or not np.isfinite(actual).all():
        raise ValueError(f"exported inference failed for {scale}x")

    canonical_comparison = None
    if scale == 2.0:
        if not CANONICAL_2X.is_file() or sha256(CANONICAL_2X) != CANONICAL_2X_SHA256:
            raise ValueError("frozen canonical 2x model is missing or unverified")
        expected = session(CANONICAL_2X.read_bytes()).run(["upscaled_image"], {"image": test_input})[0]
        difference = np.abs(actual.astype(np.float64) - expected.astype(np.float64))
        canonical_comparison = {
            "canonical_sha256": CANONICAL_2X_SHA256,
            "max_abs_error": float(difference.max()),
            "mean_abs_error": float(difference.mean()),
            "allclose_atol_1e-6_rtol_1e-6": bool(np.allclose(actual, expected, atol=1e-6, rtol=1e-6)),
        }
        if not canonical_comparison["allclose_atol_1e-6_rtol_1e-6"]:
            raise ValueError(f"2x export differs from frozen canonical: {canonical_comparison}")

    dynamic_path = CANONICAL_2X if scale == 2.0 else ROOT / "models" / f"quicksrnet-small-{tag}x-opset17.onnx"
    if scale != 2.0:
        dynamic_example = torch.zeros((1, 3, 48, 80), dtype=torch.float32)
        write_export(model, dynamic_example, dynamic_path, dynamic=True)
    dynamic_runner = session(dynamic_path.read_bytes())
    dynamic_height, dynamic_width = 48, 80
    dynamic_input = generator.random((1, 3, dynamic_height, dynamic_width), dtype=np.float32)
    dynamic_output = dynamic_runner.run(["upscaled_image"], {"image": dynamic_input})[0]
    expected_dynamic_shape = (1, 3, int(dynamic_height * scale), int(dynamic_width * scale))
    if dynamic_output.shape != expected_dynamic_shape or not np.isfinite(dynamic_output).all():
        raise ValueError(f"dynamic exported inference failed for {scale}x: {dynamic_output.shape}")

    return {
        "scale": scale,
        "source": {"file_name": spec["file_name"], "bytes": spec["bytes"], "sha256": spec["sha256"], "url": spec["url"]},
        "artifact": {
            "path": output_path.relative_to(ROOT).as_posix(), "bytes": output_path.stat().st_size,
            "sha256": sha256(output_path), "input_shape": [1, 3, INPUT_HEIGHT, INPUT_WIDTH],
            "output_shape": [1, 3, height, width],
        },
        "dynamic_artifact": {
            "path": dynamic_path.relative_to(ROOT).as_posix(), "bytes": dynamic_path.stat().st_size,
            "sha256": sha256(dynamic_path), "input_shape": [1, 3, "height", "width"],
            "output_scale": scale,
        },
        "validation": {"status": "pass", "finite": True, "canonical_2x_comparison": canonical_comparison},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Export pinned QuickSRNetSmall 1.5x/2x/3x/4x checkpoints to local fixed-shape ONNX")
    parser.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--scale", type=float, action="append")
    args = parser.parse_args()
    manifest = json.loads(args.sources.read_text(encoding="utf-8"))
    cache_root = ROOT / manifest["cache_root"]
    selected = set(args.scale or [float(item["scale"]) for item in manifest["checkpoints"]])
    known = {float(item["scale"]) for item in manifest["checkpoints"]}
    if selected - known:
        raise ValueError(f"unknown scale(s): {sorted(selected - known)}")
    exports = [export_one(item, cache_root) for item in manifest["checkpoints"] if float(item["scale"]) in selected]
    report = {
        "schema_version": 1, "status": "pass", "torch": torch.__version__, "onnx": onnx.__version__,
        "onnxruntime": ort.__version__, "license": manifest["license"],
        "repository_policy": manifest["repository_policy"], "exports": exports,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
