#!/usr/bin/env python3
"""Compare an Android QuickSR output tensor with the frozen PC golden tensor."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np


DERIVED_VARIANT_CONTRACTS: dict[str, dict[str, Any]] = {
    "fixed64-pre-shuffle-core": {
        "artifactKey": "fixed64_pre_shuffle_core",
        "allowedBackends": ["XNNPACK_CORE_STRICT", "XNNPACK_CORE_HYBRID"],
        "pcValidationKey": "core_plus_application_crd_pixel_shuffle_vs_canonical",
        "applicationPostprocess": "application-crd-pixel-shuffle",
    },
    "fixed64-dcr-full": {
        "artifactKey": "fixed64_dcr_full",
        "allowedBackends": [
            "NNAPI_DCR_STRICT",
            "NNAPI_DCR_HYBRID",
            "QNN_HTP_DCR_STRICT",
            "QNN_HTP_DCR_DIAGNOSTIC",
        ],
        "pcValidationKey": "dcr_full_vs_canonical",
        "applicationPostprocess": None,
    },
}

QNN_DCR_BACKENDS = {"QNN_HTP_DCR_STRICT", "QNN_HTP_DCR_DIAGNOSTIC"}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_sha256(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def read_tensor_bytes(path: Path) -> bytes:
    if path.name.endswith(".raw.b64"):
        try:
            encoded = "".join(path.read_text(encoding="ascii").split())
            return base64.b64decode(encoded, validate=True)
        except Exception as error:
            raise ValueError(f"invalid base64 tensor artifact: {path}") from error
    return path.read_bytes()


def tensor_from_bytes(raw: bytes, shape: tuple[int, ...]) -> np.ndarray:
    expected_bytes = int(np.prod(shape)) * np.dtype("<f4").itemsize
    if len(raw) != expected_bytes:
        raise ValueError(
            f"tensor byte count mismatch: expected {expected_bytes}, got {len(raw)}"
        )
    return np.frombuffer(raw, dtype="<f4").reshape(shape)


def compare_tensors(
    android: np.ndarray,
    golden: np.ndarray,
    absolute_tolerance: float,
    relative_tolerance: float,
) -> dict[str, Any]:
    if android.shape != golden.shape:
        raise ValueError(f"shape mismatch: android={android.shape}, golden={golden.shape}")
    finite = np.isfinite(android) & np.isfinite(golden)
    nonfinite_count = int(android.size - np.count_nonzero(finite))
    absolute_error = np.full(android.shape, np.inf, dtype=np.float64)
    relative_error = np.full(android.shape, np.inf, dtype=np.float64)
    if np.any(finite):
        absolute_error[finite] = np.abs(
            android[finite].astype(np.float64) - golden[finite].astype(np.float64)
        )
        denominator = np.maximum(np.abs(golden[finite].astype(np.float64)), 1.0e-12)
        relative_error[finite] = absolute_error[finite] / denominator
    allowed = absolute_tolerance + relative_tolerance * np.abs(
        golden.astype(np.float64)
    )
    mismatch = (~finite) | (absolute_error > allowed)
    mismatch_count = int(np.count_nonzero(mismatch))
    finite_absolute_error = absolute_error[finite]
    finite_relative_error = relative_error[finite]
    return {
        "status": "PASS" if mismatch_count == 0 and nonfinite_count == 0 else "FAIL",
        "elementCount": int(android.size),
        "mismatchCount": mismatch_count,
        "nonfiniteCount": nonfinite_count,
        "maxAbsoluteError": (
            float(np.max(finite_absolute_error))
            if finite_absolute_error.size
            else None
        ),
        "maxRelativeError": (
            float(np.max(finite_relative_error))
            if finite_relative_error.size
            else None
        ),
        "meanAbsoluteError": (
            float(np.mean(finite_absolute_error))
            if finite_absolute_error.size
            else None
        ),
        "absoluteTolerance": absolute_tolerance,
        "relativeTolerance": relative_tolerance,
        "allowedMismatchCount": 0,
        "allowedNonfiniteCount": 0,
    }


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def write_base64(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(base64.b64encode(raw).decode("ascii") + "\n", encoding="ascii")


def preserve_exact_bytes(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != raw:
        raise ValueError(f"refusing to overwrite different preserved evidence: {path}")
    path.write_bytes(raw)


def preserve_pc_golden_bundle(
    manifest_path: Path,
    manifest: dict[str, Any],
    result_directory: Path,
) -> dict[str, Any]:
    manifest_raw = manifest_path.read_bytes()
    input_path = manifest_path.parent / manifest["input"]["artifact"]
    output_path = manifest_path.parent / manifest["pcGoldenOutput"]["artifact"]
    input_raw_file = input_path.read_bytes()
    output_raw_file = output_path.read_bytes()
    destinations = {
        "manifest": result_directory / "pc-golden-manifest.json",
        "input": result_directory / input_path.name,
        "output": result_directory / output_path.name,
    }
    for source, destination, raw in (
        (manifest_path, destinations["manifest"], manifest_raw),
        (input_path, destinations["input"], input_raw_file),
        (output_path, destinations["output"], output_raw_file),
    ):
        if source.resolve() != destination.resolve():
            preserve_exact_bytes(destination, raw)
    return {
        "manifest": {
            "file": destinations["manifest"].name,
            "sha256": sha256_bytes(manifest_raw),
        },
        "inputArtifact": {
            "file": destinations["input"].name,
            "sha256": sha256_bytes(input_raw_file),
        },
        "outputArtifact": {
            "file": destinations["output"].name,
            "sha256": sha256_bytes(output_raw_file),
        },
    }


def validate_derived_model_linkage(
    receipt: dict[str, Any],
    canonical_model_sha256: str,
    pc_input_sha256: str,
    final_output_shape: list[int],
    derivation_manifest: dict[str, Any],
    derivation_manifest_raw: bytes,
    execution_plan: dict[str, Any] | None = None,
    execution_plan_raw: bytes | None = None,
) -> dict[str, Any]:
    receipt_model = receipt.get("model", {})
    variant = receipt_model.get("variant")
    contract = DERIVED_VARIANT_CONTRACTS.get(variant)
    if contract is None:
        raise ValueError(f"unsupported derived model variant: {variant}")
    if receipt.get("status") != "PASS":
        raise ValueError("derived correctness comparison requires a PASS device receipt")
    if receipt.get("backendRequested") not in contract["allowedBackends"]:
        raise ValueError("derived model variant and requested backend do not match")
    manifest_sha256 = sha256_bytes(derivation_manifest_raw)
    if receipt_model.get("derivationManifestSha256") != manifest_sha256:
        raise ValueError("device receipt derivation manifest hash does not match supplied bytes")
    if derivation_manifest.get("document_kind") != "deterministic-onnx-derivation-manifest":
        raise ValueError("unexpected derivation manifest document kind")
    if derivation_manifest.get("status") != "pass":
        raise ValueError("derivation manifest is not PASS")
    if derivation_manifest.get("canonical_was_written") is not False:
        raise ValueError("derivation manifest must prove the canonical model was not overwritten")
    source = derivation_manifest.get("source", {})
    if source.get("sha256") != canonical_model_sha256:
        raise ValueError("derivation manifest canonical source hash mismatch")
    if source.get("expected_sha256") != canonical_model_sha256:
        raise ValueError("derivation manifest expected canonical source hash mismatch")

    artifact = derivation_manifest.get("artifacts", {}).get(contract["artifactKey"], {})
    expected_model = {
        "derived": True,
        "observedBytes": artifact.get("bytes"),
        "expectedBytes": artifact.get("bytes"),
        "observedSha256": artifact.get("sha256"),
        "expectedSha256": artifact.get("sha256"),
        "canonicalSourceSha256": canonical_model_sha256,
    }
    mismatched_model_fields = [
        key for key, expected in expected_model.items() if receipt_model.get(key) != expected
    ]
    if mismatched_model_fields:
        raise ValueError(
            "derived receipt model identity mismatch: "
            + ", ".join(mismatched_model_fields)
        )
    if Path(str(artifact.get("path", ""))).name != receipt_model.get("asset"):
        raise ValueError("derived receipt asset name does not match derivation manifest")
    if not is_sha256(artifact.get("sha256")):
        raise ValueError("derived model artifact hash is malformed")

    p2_plan = derivation_manifest.get("p2_plan", {})
    if p2_plan.get("sha256") != p2_plan.get("expected_sha256"):
        raise ValueError("derivation manifest P2 plan self-linkage mismatch")

    backend_requested = receipt.get("backendRequested")
    qnn_execution_plan_sha256: str | None = None
    if backend_requested in QNN_DCR_BACKENDS:
        if execution_plan is None or execution_plan_raw is None:
            raise ValueError("QNN derived receipt requires supplied P3 execution plan bytes")
        qnn_execution_plan_sha256 = sha256_bytes(execution_plan_raw)
        if receipt.get("planSha256") != qnn_execution_plan_sha256:
            raise ValueError("QNN P3 execution plan hash linkage mismatch")
        expected_qnn_plan_fields = {
            "schemaVersion": "1.0.0",
            "experimentId": "QUICKSR-P3-QNN-HTP-INFRASTRUCTURE",
        }
        mismatched_qnn_plan_fields = [
            key
            for key, expected in expected_qnn_plan_fields.items()
            if execution_plan.get(key) != expected
        ]
        if mismatched_qnn_plan_fields:
            raise ValueError(
                "QNN P3 execution plan identity mismatch: "
                + ", ".join(mismatched_qnn_plan_fields)
            )
        qnn_plan_model = execution_plan.get("model", {})
        if (
            qnn_plan_model.get("variant") != variant
            or qnn_plan_model.get("sha256") != artifact.get("sha256")
            or qnn_plan_model.get("bytes") != artifact.get("bytes")
        ):
            raise ValueError("QNN P3 execution plan model linkage mismatch")
        qnn_provider_contract = execution_plan.get("providerContract", {})
        if (
            qnn_provider_contract.get("providerOptions", {}).get("backend_type")
            != "htp"
            or qnn_provider_contract.get("cpuFallback") != "disabled"
        ):
            raise ValueError("QNN P3 execution plan provider contract mismatch")
        qnn_workload = execution_plan.get("workload", {})
        if (
            qnn_workload.get("inputGenerator")
            != "DeterministicInputs.rgbGradientNchw-v1"
            or qnn_workload.get("warmupRuns") != 5
            or qnn_workload.get("measuredRuns") != 30
        ):
            raise ValueError("QNN P3 execution plan workload mismatch")
    elif (
        p2_plan.get("sha256") != receipt.get("planSha256")
        or p2_plan.get("expected_sha256") != receipt.get("planSha256")
    ):
        raise ValueError("P2 plan hash linkage mismatch")
    frozen_gate = derivation_manifest.get("frozen_correctness_gate", {})
    expected_gate = {
        "android_input_sha256_little_endian_float32": pc_input_sha256,
        "atol": 0.0001,
        "rtol": 0.0001,
        "allowed_mismatch_count": 0,
        "allowed_nonfinite_count": 0,
    }
    mismatched_gate_fields = [
        key for key, expected in expected_gate.items() if frozen_gate.get(key) != expected
    ]
    if mismatched_gate_fields:
        raise ValueError(
            "derivation correctness gate mismatch: " + ", ".join(mismatched_gate_fields)
        )

    artifact_output = artifact.get("output", {})
    session_contract = receipt.get("sessionContract", {})
    model_output_identity = receipt.get("modelOutputIdentity", {})
    if session_contract.get("outputName") != artifact_output.get("name"):
        raise ValueError("derived session output name mismatch")
    if session_contract.get("outputShape") != artifact_output.get("shape"):
        raise ValueError("derived session output shape mismatch")
    if model_output_identity.get("shape") != artifact_output.get("shape"):
        raise ValueError("derived model output identity shape mismatch")
    if model_output_identity.get("elementCount") != int(
        np.prod(artifact_output.get("shape", []))
    ):
        raise ValueError("derived model output identity element count mismatch")
    if not is_sha256(model_output_identity.get("sha256LittleEndianFloat32")):
        raise ValueError("derived model raw output hash is absent or malformed")

    postprocess_kind = contract["applicationPostprocess"]
    postprocess = receipt.get("applicationPostprocess")
    if postprocess_kind is not None:
        expected_postprocess = {
            "kind": postprocess_kind,
            "includedInOrtRunLatency": False,
            "inputShape": artifact_output.get("shape"),
            "outputShape": final_output_shape,
        }
        if not isinstance(postprocess, dict):
            raise ValueError("derived core receipt is missing application postprocess evidence")
        mismatched_postprocess = [
            key
            for key, expected in expected_postprocess.items()
            if postprocess.get(key) != expected
        ]
        if mismatched_postprocess:
            raise ValueError(
                "application postprocess contract mismatch: "
                + ", ".join(mismatched_postprocess)
            )
    elif postprocess is not None:
        raise ValueError("full derived model unexpectedly reports application postprocess")

    final_validation = receipt.get("structuralSanityValidation", {})
    if final_validation.get("shape") != final_output_shape:
        raise ValueError("derived final output shape does not match PC golden")
    if final_validation.get("elementCount") != int(np.prod(final_output_shape)):
        raise ValueError("derived final output element count mismatch")

    pc_validation = derivation_manifest.get("pc_ort_validation", {})
    if pc_validation.get("status") != "pass":
        raise ValueError("derived PC ORT validation is not PASS")
    validation_case = next(
        (
            item
            for item in pc_validation.get("cases", [])
            if item.get("id") == "android-rgb-gradient-64"
        ),
        None,
    )
    if validation_case is None:
        raise ValueError("derivation manifest lacks the Android input validation case")
    if validation_case.get("input_sha256_little_endian_float32") != pc_input_sha256:
        raise ValueError("derived PC validation input hash mismatch")
    equivalence = validation_case.get(contract["pcValidationKey"], {})
    expected_equivalence = {
        "status": "pass",
        "shape": final_output_shape,
        "mismatch_count": 0,
        "nonfinite_count": 0,
        "allowed_mismatch_count": 0,
        "allowed_nonfinite_count": 0,
        "atol": 0.0001,
        "rtol": 0.0001,
    }
    mismatched_equivalence = [
        key
        for key, expected in expected_equivalence.items()
        if equivalence.get(key) != expected
    ]
    if mismatched_equivalence:
        raise ValueError(
            "derived PC equivalence evidence mismatch: "
            + ", ".join(mismatched_equivalence)
        )
    if not is_sha256(equivalence.get("actual_sha256_little_endian_float32")):
        raise ValueError("derived PC equivalence output hash is malformed")
    if not is_sha256(equivalence.get("reference_sha256_little_endian_float32")):
        raise ValueError("canonical PC equivalence output hash is malformed")

    linkage = {
        "kind": "source-linked-derived-model",
        "variant": variant,
        "backendRequested": receipt.get("backendRequested"),
        "derivedModel": {
            "file": receipt_model.get("asset"),
            "bytes": artifact.get("bytes"),
            "sha256": artifact.get("sha256"),
        },
        "canonicalSourceSha256": canonical_model_sha256,
        "derivationManifestSha256": manifest_sha256,
        "p2PlanSha256": p2_plan.get("sha256"),
        "pcDerivationEquivalence": {
            "status": equivalence.get("status"),
            "mismatchCount": equivalence.get("mismatch_count"),
            "nonfiniteCount": equivalence.get("nonfinite_count"),
            "exactFloat32Bytes": equivalence.get("exact_float32_bytes"),
            "actualSha256LittleEndianFloat32": equivalence.get(
                "actual_sha256_little_endian_float32"
            ),
            "referenceSha256LittleEndianFloat32": equivalence.get(
                "reference_sha256_little_endian_float32"
            ),
        },
        "applicationPostprocess": postprocess_kind,
    }
    if qnn_execution_plan_sha256 is not None:
        linkage["executionPlanKind"] = "p3-qnn-htp-infrastructure"
        linkage["executionPlanSha256"] = qnn_execution_plan_sha256
    return linkage


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--android-receipt", required=True, type=Path)
    parser.add_argument("--android-output", type=Path)
    parser.add_argument("--derivation-manifest", type=Path)
    parser.add_argument("--execution-plan", type=Path)
    parser.add_argument("--result", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest_path = args.manifest.resolve()
    receipt_path = args.android_receipt.resolve()
    result_path = args.result.resolve()
    result_directory = result_path.parent
    manifest = read_json(manifest_path)
    receipt = read_json(receipt_path)
    receipt_raw = receipt_path.read_bytes()
    preserved_receipt_path = result_directory / "android-receipt.json.raw.b64"
    write_base64(preserved_receipt_path, receipt_raw)
    pc_golden_evidence = preserve_pc_golden_bundle(
        manifest_path, manifest, result_directory
    )

    output_contract = manifest["pcGoldenOutput"]
    tolerance = manifest["toleranceContract"]
    shape = tuple(int(value) for value in output_contract["shape"])
    golden_path = manifest_path.parent / output_contract["artifact"]
    golden_raw = read_tensor_bytes(golden_path)
    golden_sha256 = sha256_bytes(golden_raw)
    if golden_sha256 != output_contract["sha256LittleEndianFloat32"]:
        raise ValueError("PC golden artifact hash does not match its manifest")

    receipt_output_sha256 = receipt.get("structuralSanityValidation", {}).get(
        "sha256LittleEndianFloat32"
    )
    receipt_artifact = receipt.get("outputArtifact")
    if receipt_artifact is not None:
        if not isinstance(receipt_artifact, dict):
            raise ValueError("Android receipt outputArtifact must be an object")
        expected_artifact = {
            "bytes": int(np.prod(shape)) * 4,
            "sha256": receipt_output_sha256,
            "dtype": "float32",
            "byteOrder": "little-endian",
            "shape": list(shape),
        }
        mismatched = [
            key
            for key, expected in expected_artifact.items()
            if receipt_artifact.get(key) != expected
        ]
        if mismatched:
            raise ValueError(
                "Android receipt outputArtifact contract mismatch: "
                + ", ".join(mismatched)
            )
    result: dict[str, Any] = {
        "schemaVersion": "1.0.0",
        "kind": "android-vs-pc-golden-correctness",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "modelSha256": manifest["model"]["sha256"],
        "backendRequested": receipt.get("backendRequested"),
        "inputSha256LittleEndianFloat32": manifest["input"][
            "sha256LittleEndianFloat32"
        ],
        "pcGoldenOutputSha256LittleEndianFloat32": golden_sha256,
        "pcGoldenEvidence": pc_golden_evidence,
        "androidReceipt": {
            "file": receipt_path.name,
            "preservedArtifact": preserved_receipt_path.name,
            "preservedArtifactEncoding": "base64(original device receipt JSON bytes)",
            "sha256": sha256_bytes(receipt_raw),
            "runId": receipt.get("runId"),
            "reportedOutputSha256LittleEndianFloat32": receipt_output_sha256,
            "outputArtifact": receipt_artifact,
            "model": receipt.get("model"),
        },
    }

    receipt_model = receipt.get("model", {})
    if not isinstance(receipt_model, dict):
        raise ValueError("Android receipt model must be an object")
    if receipt.get("status") != "PASS":
        raise ValueError("golden correctness comparison requires a PASS device receipt")
    observed_model_sha256 = receipt_model.get("observedSha256")
    canonical_model_sha256 = manifest["model"]["sha256"]
    if observed_model_sha256 != canonical_model_sha256:
        if args.derivation_manifest is None:
            raise ValueError(
                "a derived Android receipt requires --derivation-manifest bytes"
            )
        derivation_manifest_path = args.derivation_manifest.resolve()
        derivation_manifest_raw = derivation_manifest_path.read_bytes()
        derivation_manifest = read_json(derivation_manifest_path)
        execution_plan = None
        execution_plan_raw = None
        if args.execution_plan is not None:
            execution_plan_path = args.execution_plan.resolve()
            execution_plan_raw = execution_plan_path.read_bytes()
            execution_plan = read_json(execution_plan_path)
        derived_linkage = validate_derived_model_linkage(
            receipt,
            canonical_model_sha256,
            manifest["input"]["sha256LittleEndianFloat32"],
            list(shape),
            derivation_manifest,
            derivation_manifest_raw,
            execution_plan,
            execution_plan_raw,
        )
        preserved_derivation_path = result_directory / "derivation-manifest.json.raw.b64"
        write_base64(preserved_derivation_path, derivation_manifest_raw)
        derived_linkage["derivationManifestArtifact"] = {
            "file": preserved_derivation_path.name,
            "encoding": "base64(original derivation manifest JSON bytes)",
            "sha256": sha256_bytes(derivation_manifest_raw),
        }
        if execution_plan_raw is not None:
            preserved_execution_plan_path = (
                result_directory / "execution-plan.json.raw.b64"
            )
            write_base64(preserved_execution_plan_path, execution_plan_raw)
            derived_linkage["executionPlanArtifact"] = {
                "file": preserved_execution_plan_path.name,
                "encoding": "base64(original execution plan JSON bytes)",
                "sha256": sha256_bytes(execution_plan_raw),
            }
        result["derivedModelLinkage"] = derived_linkage
    else:
        if receipt_model.get("derived") is True:
            raise ValueError("canonical model bytes cannot be labelled as derived")
        if args.derivation_manifest is not None:
            raise ValueError("--derivation-manifest was supplied for a canonical receipt")
        if args.execution_plan is not None:
            raise ValueError("--execution-plan was supplied for a canonical receipt")
    if (
        receipt.get("inputIdentity", {}).get("sha256LittleEndianFloat32")
        != manifest["input"]["sha256LittleEndianFloat32"]
    ):
        raise ValueError("Android receipt and PC golden use different input bytes")

    android_path: Path | None = None
    android_raw: bytes | None = None
    if args.android_output is not None:
        android_path = args.android_output.resolve()
        android_raw = read_tensor_bytes(android_path)
        android_sha256 = sha256_bytes(android_raw)
        if android_sha256 != receipt_output_sha256:
            raise ValueError(
                "Android tensor hash does not match the hash recorded in the device receipt"
            )
        if receipt_artifact is not None and len(android_raw) != receipt_artifact["bytes"]:
            raise ValueError(
                "Android tensor byte count does not match outputArtifact.bytes in the receipt"
            )
        preserved_android_path = result_directory / "android-output.f32le.raw.b64"
        write_base64(preserved_android_path, android_raw)
        result["androidOutput"] = {
            "file": android_path.name,
            "preservedArtifact": preserved_android_path.name,
            "preservedArtifactEncoding": (
                "base64(raw contiguous little-endian float32 NCHW)"
            ),
            "sha256LittleEndianFloat32": android_sha256,
            "decodedBytes": len(android_raw),
        }

    if receipt_output_sha256 == golden_sha256:
        if android_raw is not None:
            exact_metrics = compare_tensors(
                tensor_from_bytes(android_raw, shape),
                tensor_from_bytes(golden_raw, shape),
                float(tolerance["absoluteTolerance"]),
                float(tolerance["relativeTolerance"]),
            )
        else:
            exact_metrics = {
                "status": "PASS",
                "elementCount": int(np.prod(shape)),
                "mismatchCount": 0,
                "nonfiniteCount": 0,
                "maxAbsoluteError": 0.0,
                "maxRelativeError": 0.0,
                "meanAbsoluteError": 0.0,
                "absoluteTolerance": float(tolerance["absoluteTolerance"]),
                "relativeTolerance": float(tolerance["relativeTolerance"]),
                "allowedMismatchCount": 0,
                "allowedNonfiniteCount": 0,
            }
        result.update(
            {
                "status": "PASS",
                "comparisonKind": "exact-output-byte-hash",
                "correctnessReferenceCompared": True,
                "metrics": exact_metrics,
            }
        )
        write_json(result_path, result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0

    if android_raw is None:
        result.update(
            {
                "status": "INCOMPLETE",
                "comparisonKind": "hash-only-not-sufficient-for-tolerance",
                "correctnessReferenceCompared": False,
                "reason": (
                    "Android and PC output hashes differ, while the Android receipt does "
                    "not contain the output tensor. Export the raw 49152-element float32 "
                    "tensor and rerun with --android-output."
                ),
            }
        )
        write_json(result_path, result)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 2

    golden = tensor_from_bytes(golden_raw, shape)
    android = tensor_from_bytes(android_raw, shape)
    metrics = compare_tensors(
        android,
        golden,
        float(tolerance["absoluteTolerance"]),
        float(tolerance["relativeTolerance"]),
    )
    result.update(
        {
            "status": metrics["status"],
            "comparisonKind": "elementwise-tolerance",
            "correctnessReferenceCompared": True,
            "metrics": metrics,
        }
    )
    write_json(result_path, result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if metrics["status"] == "PASS" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # fail closed with a concise terminal message
        print(f"Android comparison failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
