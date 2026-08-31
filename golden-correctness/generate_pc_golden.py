#!/usr/bin/env python3
"""Generate a byte-addressable PC golden tensor for the Android QuickSR probe.

The Android probe deliberately generates its input in Java float arithmetic.  That
is not byte-identical to the older qualification input whose blue channel was
computed as ``(red + green) * 0.5``.  This script mirrors the Java operations,
verifies the resulting input against a real Android receipt, and runs the locked
ONNX model on the PC CPU provider.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort


EXPECTED_MODEL_BYTES = 93_994
EXPECTED_MODEL_SHA256 = "3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce"
EXPECTED_INPUT_SHA256 = "cc13c100d394903d5c9ccde7a44aab63660e266099077063a0a0de326f5b9fc9"
INPUT_NAME = "image"
OUTPUT_NAME = "upscaled_image"
INPUT_SHAPE = (1, 3, 64, 64)
OUTPUT_SHAPE = (1, 3, 128, 128)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_f32le_bytes(values: np.ndarray) -> bytes:
    array = np.asarray(values, dtype=np.float32, order="C")
    return array.astype("<f4", copy=False).tobytes(order="C")


def android_rgb_gradient_nchw(width: int = 64, height: int = 64) -> np.ndarray:
    """Mirror DeterministicInputs.rgbGradientNchw-v1 at float32 precision."""
    if width < 2 or height < 2:
        raise ValueError("width and height must both be at least 2")
    result = np.empty((1, 3, height, width), dtype=np.float32)
    for y in range(height):
        for x in range(width):
            result[0, 0, y, x] = np.float32(x) / np.float32(width - 1)
            result[0, 1, y, x] = np.float32(y) / np.float32(height - 1)
            result[0, 2, y, x] = np.float32(x + y) / np.float32(
                width + height - 2
            )
    return result


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def write_base64(path: Path, raw: bytes) -> None:
    path.write_text(base64.b64encode(raw).decode("ascii") + "\n", encoding="ascii")


def require_receipt_contract(receipt: dict[str, Any], receipt_path: Path) -> None:
    checks = {
        "status": receipt.get("status") == "PASS",
        "backendRequested": receipt.get("backendRequested") == "CPU",
        "input generator": receipt.get("inputIdentity", {}).get("generator")
        == "DeterministicInputs.rgbGradientNchw-v1",
        "input shape": receipt.get("inputIdentity", {}).get("shape")
        == list(INPUT_SHAPE),
        "input hash": receipt.get("inputIdentity", {}).get(
            "sha256LittleEndianFloat32"
        )
        == EXPECTED_INPUT_SHA256,
        "model bytes": receipt.get("model", {}).get("observedBytes")
        == EXPECTED_MODEL_BYTES,
        "model hash": receipt.get("model", {}).get("observedSha256")
        == EXPECTED_MODEL_SHA256,
        "output shape": receipt.get("structuralSanityValidation", {}).get("shape")
        == list(OUTPUT_SHAPE),
        "output element count": receipt.get("structuralSanityValidation", {}).get(
            "elementCount"
        )
        == int(np.prod(OUTPUT_SHAPE)),
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise ValueError(
            f"Android receipt contract mismatch ({receipt_path.name}): "
            + ", ".join(failed)
        )


def session_options() -> ort.SessionOptions:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    return options


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--android-receipt", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--qualification-plan",
        type=Path,
        default=(
            Path(__file__).resolve().parent.parent
            / "contracts"
            / "p0-cpu-golden-plan.json"
        ),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    model_path = args.model.resolve()
    receipt_path = args.android_receipt.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    model_bytes = model_path.read_bytes()
    if len(model_bytes) != EXPECTED_MODEL_BYTES:
        raise ValueError(
            f"model byte mismatch: expected {EXPECTED_MODEL_BYTES}, got {len(model_bytes)}"
        )
    model_sha256 = sha256_bytes(model_bytes)
    if model_sha256 != EXPECTED_MODEL_SHA256:
        raise ValueError(
            f"model hash mismatch: expected {EXPECTED_MODEL_SHA256}, got {model_sha256}"
        )

    receipt_raw = receipt_path.read_bytes()
    receipt = read_json(receipt_path)
    require_receipt_contract(receipt, receipt_path)

    qualification_plan_path = args.qualification_plan.resolve()
    qualification_plan_raw = qualification_plan_path.read_bytes()
    qualification_plan = read_json(qualification_plan_path)
    correctness_gate = qualification_plan.get("correctness_gate", {})
    if (
        correctness_gate.get("allowed_mismatch_count") != 0
        or correctness_gate.get("allowed_nonfinite_count") != 0
        or "1e-4 + 1e-4" not in correctness_gate.get("comparison", "")
    ):
        raise ValueError("upstream qualification correctness contract has drifted")

    input_tensor = android_rgb_gradient_nchw()
    input_raw = canonical_f32le_bytes(input_tensor)
    input_sha256 = sha256_bytes(input_raw)
    if input_sha256 != EXPECTED_INPUT_SHA256:
        raise AssertionError(
            f"generator drift: expected {EXPECTED_INPUT_SHA256}, got {input_sha256}"
        )

    session = ort.InferenceSession(
        str(model_path),
        sess_options=session_options(),
        providers=["CPUExecutionProvider"],
    )
    first = np.asarray(
        session.run([OUTPUT_NAME], {INPUT_NAME: input_tensor})[0], dtype=np.float32
    )
    second = np.asarray(
        session.run([OUTPUT_NAME], {INPUT_NAME: input_tensor})[0], dtype=np.float32
    )
    if tuple(first.shape) != OUTPUT_SHAPE:
        raise ValueError(f"output shape mismatch: expected {OUTPUT_SHAPE}, got {first.shape}")
    first_raw = canonical_f32le_bytes(first)
    second_raw = canonical_f32le_bytes(second)
    if first_raw != second_raw:
        raise ValueError("PC CPU output is not byte-deterministic across two consecutive runs")

    input_artifact = output_dir / "android-input.f32le.raw.b64"
    output_artifact = output_dir / "pc-golden-output.f32le.raw.b64"
    manifest_path = output_dir / "pc-golden-manifest.json"
    write_base64(input_artifact, input_raw)
    write_base64(output_artifact, first_raw)

    android_output_sha256 = receipt["structuralSanityValidation"][
        "sha256LittleEndianFloat32"
    ]
    manifest = {
        "schemaVersion": "1.0.0",
        "kind": "pc-golden-tensor-for-android-quicksr-probe",
        "status": "PASS",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "model": {
            "file": model_path.name,
            "bytes": len(model_bytes),
            "sha256": model_sha256,
        },
        "androidReceipt": {
            "file": receipt_path.name,
            "sha256": sha256_bytes(receipt_raw),
            "runId": receipt.get("runId"),
            "ortRuntimeVersion": receipt.get("ortRuntimeVersion"),
            "outputSha256LittleEndianFloat32": android_output_sha256,
        },
        "input": {
            "generator": "DeterministicInputs.rgbGradientNchw-v1",
            "shape": list(INPUT_SHAPE),
            "elementCount": int(input_tensor.size),
            "sha256LittleEndianFloat32": input_sha256,
            "artifact": input_artifact.name,
            "artifactEncoding": "base64(raw contiguous little-endian float32 NCHW)",
            "decodedBytes": len(input_raw),
        },
        "pcGoldenOutput": {
            "name": OUTPUT_NAME,
            "shape": list(OUTPUT_SHAPE),
            "elementCount": int(first.size),
            "sha256LittleEndianFloat32": sha256_bytes(first_raw),
            "artifact": output_artifact.name,
            "artifactEncoding": "base64(raw contiguous little-endian float32 NCHW)",
            "decodedBytes": len(first_raw),
            "finiteCount": int(np.count_nonzero(np.isfinite(first))),
            "min": float(np.min(first)),
            "max": float(np.max(first)),
            "repeatCount": 2,
            "byteDeterministicAcrossRepeats": True,
        },
        "pcRuntime": {
            "python": platform.python_version(),
            "numpy": np.__version__,
            "onnxruntime": ort.__version__,
            "providersRequested": ["CPUExecutionProvider"],
            "providersActive": session.get_providers(),
            "executionMode": "sequential",
            "intraOpThreads": 1,
            "interOpThreads": 1,
            "graphOptimizationLevel": "all",
            "platform": platform.platform(),
        },
        "toleranceContract": {
            "formula": "abs(android - pc_golden) <= atol + rtol * abs(pc_golden)",
            "absoluteTolerance": 0.0001,
            "relativeTolerance": 0.0001,
            "allowedMismatchCount": 0,
            "allowedNonfiniteCount": 0,
            "source": {
                "path": "contracts/p0-cpu-golden-plan.json",
                "sha256": sha256_bytes(qualification_plan_raw),
            },
        },
        "comparisonReadiness": {
            "exactHashMatch": android_output_sha256 == sha256_bytes(first_raw),
            "toleranceComparisonPrerequisite": "ANDROID_RAW_TENSOR_REQUIRED",
            "reason": (
                "The existing receipt records only the Android output hash. Different "
                "hashes cannot be converted into element-wise error metrics."
            ),
        },
    }
    write_json(manifest_path, manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # fail closed with a concise terminal message
        print(f"PC golden generation failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
