#!/usr/bin/env python3
"""Compare one captured Android QuickSR video-model tensor with a PC CPU golden.

The Android capture is intentionally made immediately after model inference and
before NCHW-to-RGBA packing.  That makes this comparison about the same model
and the same input bytes, rather than a screenshot or a presentation-path
surrogate.  All generated artifacts default to Git-ignored output locations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image, __version__ as pillow_version


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PLAN = ROOT / "contracts" / "android-video-frame-golden-plan.json"
DEVICE_RESULTS_ROOT = ROOT / "device-results"
VIDEO_EVIDENCE_ROOT = DEVICE_RESULTS_ROOT / "android-video-evidence"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def resolve_within(path: Path, permitted_root: Path, label: str) -> Path:
    """Resolve a local artifact path and fail closed if it escapes an ignored root."""
    resolved_path = path.resolve()
    resolved_root = permitted_root.resolve()
    try:
        resolved_path.relative_to(resolved_root)
    except ValueError as error:
        raise ValueError(f"{label} must be beneath the Git-ignored {resolved_root.name} root") from error
    return resolved_path


def resolve_evidence_root(path: Path) -> Path:
    """Accept an explicitly selected evidence root only under device-results."""
    resolved = resolve_within(path, DEVICE_RESULTS_ROOT, "evidence root")
    require(resolved != DEVICE_RESULTS_ROOT.resolve(), "evidence root must be a child of device-results")
    return resolved


def shape_tuple(value: Any, label: str) -> tuple[int, int, int, int]:
    require(isinstance(value, list) and len(value) == 4, f"{label} must be a four-item shape")
    shape = tuple(int(item) for item in value)
    require(all(item > 0 for item in shape), f"{label} dimensions must be positive")
    require(shape[0] == 1 and shape[1] == 3, f"{label} must be 1x3xHxW NCHW")
    return shape  # type: ignore[return-value]


def expected_tensor_bytes(shape: tuple[int, int, int, int]) -> int:
    return math.prod(shape) * np.dtype("<f4").itemsize


def json_safe_float(value: Any) -> float | None:
    """Keep failure reports valid JSON even when tensor data is nonfinite."""
    converted = float(value)
    return converted if math.isfinite(converted) else None


def strict_qnn_failures(metadata: dict[str, Any], plan: dict[str, Any]) -> list[str]:
    observed = metadata.get("qnnStrict")
    if not isinstance(observed, dict):
        return ["missing qnnStrict attestation"]
    required = plan["required_qnn_strict"]
    failures = []
    for field, expected in required.items():
        if field == "minimumSelectedNpuDeviceCount":
            if not isinstance(observed.get("selectedNpuDeviceCount"), int) or observed["selectedNpuDeviceCount"] < expected:
                failures.append(f"qnnStrict.selectedNpuDeviceCount must be >= {expected}")
        elif observed.get(field) != expected:
            failures.append(f"qnnStrict.{field}: expected {expected!r}, got {observed.get(field)!r}")
    return failures


def load_tensor(
    root: Path,
    tensor_spec: dict[str, Any],
    label: str,
) -> tuple[np.ndarray, bytes, tuple[int, int, int, int]]:
    required = {"file", "dtype", "byteOrder", "shape", "elementCount", "bytes", "sha256LittleEndianFloat32"}
    missing = sorted(required.difference(tensor_spec))
    require(not missing, f"{label} tensor metadata missing: {', '.join(missing)}")
    require(tensor_spec["dtype"] == "float32", f"{label} tensor dtype must be float32")
    require(tensor_spec["byteOrder"] == "little-endian", f"{label} tensor byte order must be little-endian")
    shape = shape_tuple(tensor_spec["shape"], f"{label} tensor shape")
    element_count = math.prod(shape)
    require(tensor_spec["elementCount"] == element_count, f"{label} tensor element count mismatch")
    expected_bytes = expected_tensor_bytes(shape)
    require(tensor_spec["bytes"] == expected_bytes, f"{label} tensor byte count metadata mismatch")
    file_name = tensor_spec["file"]
    require(isinstance(file_name, str) and Path(file_name).name == file_name, f"unsafe {label} tensor file name")
    path = root / file_name
    raw = path.read_bytes()
    require(len(raw) == expected_bytes, f"{label} tensor file byte count mismatch")
    observed_hash = sha256_bytes(raw)
    require(observed_hash == tensor_spec["sha256LittleEndianFloat32"], f"{label} tensor hash mismatch")
    array = np.frombuffer(raw, dtype="<f4").reshape(shape).copy()
    return array, raw, shape


def validate_metadata(metadata: dict[str, Any], plan: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    failures: list[str] = []
    require(metadata.get("schemaVersion") == 1, "unsupported video evidence schema version")
    require(metadata.get("kind") == plan["evidence_kind"], "unexpected video evidence kind")
    require(metadata.get("storage") == "APP_PRIVATE_NO_UPLOAD", "video evidence storage contract mismatch")
    require(isinstance(metadata.get("runId"), str) and metadata["runId"], "missing video evidence runId")
    profile = metadata.get("profile")
    require(isinstance(profile, dict), "missing profile metadata")
    for field in ("name", "modelVariant", "modelAsset", "modelSha256", "modelBytes", "inputShape", "outputShape"):
        require(field in profile, f"profile missing {field}")
    require(isinstance(profile["modelSha256"], str) and len(profile["modelSha256"]) == 64, "invalid model SHA-256")
    require(isinstance(profile["modelBytes"], int) and profile["modelBytes"] > 0, "invalid model byte count")
    input_shape = shape_tuple(profile["inputShape"], "profile input shape")
    output_shape = shape_tuple(profile["outputShape"], "profile output shape")
    tensors = metadata.get("tensors")
    require(isinstance(tensors, dict) and isinstance(tensors.get("input"), dict) and isinstance(tensors.get("output"), dict), "missing input/output tensor metadata")
    if shape_tuple(tensors["input"]["shape"], "input tensor shape") != input_shape:
        failures.append("input tensor shape does not match profile")
    if shape_tuple(tensors["output"]["shape"], "output tensor shape") != output_shape:
        failures.append("output tensor shape does not match profile")
    capture = metadata.get("capture")
    require(isinstance(capture, dict) and isinstance(capture.get("selector"), dict), "missing capture selector")
    require(capture["selector"].get("kind") in {"frame", "ptsUs"}, "unsupported capture selector")
    require(isinstance(capture["selector"].get("value"), int), "capture selector value must be integer")
    failures.extend(strict_qnn_failures(metadata, plan))
    return profile, failures


def canonical_f32le(values: np.ndarray) -> bytes:
    return np.asarray(values, dtype="<f4", order="C").tobytes(order="C")


def compare_tensors(android: np.ndarray, pc: np.ndarray, atol: float, rtol: float) -> dict[str, Any]:
    require(android.shape == pc.shape, "Android and PC output shapes differ")
    finite = np.isfinite(android) & np.isfinite(pc)
    nonfinite = int(android.size - int(np.count_nonzero(finite)))
    absolute = np.abs(android - pc)
    relative = absolute / np.maximum(np.abs(pc), np.finfo(np.float32).tiny)
    allowed = atol + rtol * np.abs(pc)
    mismatches = int(np.count_nonzero((absolute > allowed) | ~finite))
    return {
        "elementCount": int(android.size),
        "mismatchCount": mismatches,
        "nonfiniteCount": nonfinite,
        "maxAbsoluteError": json_safe_float(np.max(absolute)) if absolute.size else 0.0,
        "maxRelativeError": json_safe_float(np.max(relative)) if relative.size else 0.0,
        "meanAbsoluteError": json_safe_float(np.mean(absolute)) if absolute.size else 0.0,
        "absoluteTolerance": atol,
        "relativeTolerance": rtol,
    }


def rgb8_from_nchw(values: np.ndarray) -> np.ndarray:
    require(values.ndim == 4 and values.shape[0] == 1 and values.shape[1] == 3, "NCHW input required")
    hwc = np.transpose(values[0], (1, 2, 0))
    return np.clip(np.rint(hwc * 255.0), 0, 255).astype(np.uint8)


def write_local_artifacts(output: Path, pc_output: bytes, lanczos: np.ndarray) -> dict[str, Any]:
    output.mkdir(parents=True, exist_ok=True)
    pc_path = output / "pc-cpu-output.f32le"
    lanczos_path = output / "lanczos-rgb8.png"
    pc_path.write_bytes(pc_output)
    Image.fromarray(lanczos, mode="RGB").save(lanczos_path, format="PNG", optimize=False)
    return {
        "pcCpuOutput": {
            "file": pc_path.name,
            "bytes": len(pc_output),
            "sha256LittleEndianFloat32": sha256_bytes(pc_output),
            "dtype": "float32",
            "byteOrder": "little-endian",
        },
        "lanczosRgb8": {
            "file": lanczos_path.name,
            "bytes": lanczos_path.stat().st_size,
            "sha256": sha256_bytes(lanczos_path.read_bytes()),
            "width": int(lanczos.shape[1]),
            "height": int(lanczos.shape[0]),
            "format": "PNG",
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, required=True, help="Pulled app-private video-evaluations/<runId> directory")
    parser.add_argument(
        "--evidence-root",
        type=Path,
        default=VIDEO_EVIDENCE_ROOT,
        help="Git-ignored evidence root under device-results (default: android-video-evidence)",
    )
    parser.add_argument("--model", type=Path, required=True, help="Local ONNX matching metadata.profile.modelSha256")
    parser.add_argument("--output", type=Path, required=True, help="Git-ignored local report directory")
    parser.add_argument("--plan", type=Path, default=DEFAULT_PLAN)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    evidence_root = resolve_evidence_root(args.evidence_root)
    evidence_dir = resolve_within(args.evidence_dir, evidence_root, "evidence directory")
    output_dir = resolve_within(args.output, DEVICE_RESULTS_ROOT, "output directory")
    plan = read_json(args.plan.resolve())
    metadata_path = evidence_dir / "metadata.json"
    metadata = read_json(metadata_path)
    profile, attestation_failures = validate_metadata(metadata, plan)
    input_tensor, input_raw, input_shape = load_tensor(evidence_dir, metadata["tensors"]["input"], "input")
    android_output, android_raw, output_shape = load_tensor(evidence_dir, metadata["tensors"]["output"], "output")

    model_path = args.model.resolve()
    model_raw = model_path.read_bytes()
    require(len(model_raw) == profile["modelBytes"], "model byte count does not match Android evidence")
    require(sha256_bytes(model_raw) == profile["modelSha256"], "model hash does not match Android evidence")
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(model_path), sess_options=options, providers=["CPUExecutionProvider"])
    require(session.get_providers() == ["CPUExecutionProvider"], "PC session did not use CPUExecutionProvider only")
    require(len(session.get_inputs()) == 1 and len(session.get_outputs()) == 1, "expected one ONNX input and output")
    observed_input_shape = tuple(int(item) for item in session.get_inputs()[0].shape)
    observed_output_shape = tuple(int(item) for item in session.get_outputs()[0].shape)
    require(observed_input_shape == input_shape, "ONNX input shape differs from Android evidence")
    require(observed_output_shape == output_shape, "ONNX output shape differs from Android evidence")
    pc_output = np.asarray(
        session.run([session.get_outputs()[0].name], {session.get_inputs()[0].name: input_tensor})[0],
        dtype=np.float32,
    )
    require(tuple(pc_output.shape) == output_shape, "PC output shape differs from Android evidence")
    pc_raw = canonical_f32le(pc_output)

    comparison_contract = plan["numerical_comparison"]
    metrics = compare_tensors(
        android_output,
        pc_output,
        float(comparison_contract["absoluteTolerance"]),
        float(comparison_contract["relativeTolerance"]),
    )
    numerical_pass = (
        not attestation_failures
        and metrics["mismatchCount"] <= int(comparison_contract["allowedMismatchCount"])
        and metrics["nonfiniteCount"] <= int(comparison_contract["allowedNonfiniteCount"])
    )
    lanczos = np.asarray(
        Image.fromarray(rgb8_from_nchw(input_tensor), mode="RGB").resize(
            (output_shape[3], output_shape[2]), Image.Resampling.LANCZOS
        ),
        dtype=np.uint8,
    )
    artifact_metadata = write_local_artifacts(output_dir, pc_raw, lanczos)
    report = {
        "schemaVersion": 1,
        "kind": "android-video-frame-pc-golden-comparison",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "numericalGate": "PASS" if numerical_pass else "FAIL",
        "qualificationStatus": "OBSERVED_NOT_P4_QUALIFIED",
        "qualificationBoundary": comparison_contract["qualificationBoundary"],
        "sourceEvidence": {
            "metadataFile": metadata_path.name,
            "metadataSha256": sha256_bytes(metadata_path.read_bytes()),
            "runId": metadata["runId"],
            "capture": metadata["capture"],
            "profile": profile,
            "inputSha256LittleEndianFloat32": sha256_bytes(input_raw),
            "androidOutputSha256LittleEndianFloat32": sha256_bytes(android_raw),
        },
        "qnnStrictFailures": attestation_failures,
        "pcCpu": {
            "modelFile": model_path.name,
            "modelSha256": sha256_bytes(model_raw),
            "providersActive": session.get_providers(),
            "onnxruntime": ort.__version__,
            "platform": platform.platform(),
            "python": platform.python_version(),
            "numpy": np.__version__,
        },
        "metrics": metrics,
        "artifacts": artifact_metadata,
        "lanczosBaseline": {
            "implementation": plan["lanczos_baseline"]["implementation"],
            "pillow": pillow_version,
            "boundary": plan["lanczos_baseline"]["not_equivalent_to"],
        },
    }
    report_path = output_dir / "comparison.json"
    serialized_report = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
    report_path.write_text(serialized_report + "\n", encoding="utf-8")
    print(serialized_report)
    return 0 if numerical_pass else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Android video frame comparison failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
