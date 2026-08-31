#!/usr/bin/env python3
"""Recompute the durable Android-vs-PC golden correctness case."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from compare_android_output import (
    compare_tensors,
    tensor_from_bytes,
    validate_derived_model_linkage,
)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def read_base64(path: Path) -> bytes:
    encoded = "".join(path.read_text(encoding="ascii").split())
    return base64.b64decode(encoded, validate=True)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case-dir", required=True, type=Path)
    parser.add_argument(
        "--no-write",
        action="store_true",
        help="recompute and print validation without replacing the frozen validation artifact",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    case_dir = args.case_dir.resolve()
    manifest = read_json(case_dir / "pc-golden-manifest.json")
    comparison = read_json(case_dir / "android-vs-pc-comparison.json")
    require(manifest.get("status") == "PASS", "PC golden manifest is not PASS")
    require(comparison.get("status") == "PASS", "comparison is not PASS")
    require(
        comparison.get("correctnessReferenceCompared") is True,
        "comparison does not claim an actual reference comparison",
    )

    input_raw = read_base64(case_dir / manifest["input"]["artifact"])
    pc_raw = read_base64(case_dir / manifest["pcGoldenOutput"]["artifact"])
    receipt_contract = comparison["androidReceipt"]
    receipt_raw = read_base64(case_dir / receipt_contract["preservedArtifact"])
    receipt = json.loads(receipt_raw.decode("utf-8"))
    android_contract = comparison["androidOutput"]
    android_raw = read_base64(case_dir / android_contract["preservedArtifact"])

    pc_evidence = comparison.get("pcGoldenEvidence")
    if pc_evidence is not None:
        require(
            sha256_bytes((case_dir / pc_evidence["manifest"]["file"]).read_bytes())
            == pc_evidence["manifest"]["sha256"],
            "preserved PC manifest file hash mismatch",
        )
        require(
            sha256_bytes((case_dir / pc_evidence["inputArtifact"]["file"]).read_bytes())
            == pc_evidence["inputArtifact"]["sha256"],
            "preserved PC input file hash mismatch",
        )
        require(
            sha256_bytes((case_dir / pc_evidence["outputArtifact"]["file"]).read_bytes())
            == pc_evidence["outputArtifact"]["sha256"],
            "preserved PC output file hash mismatch",
        )

    require(
        sha256_bytes(input_raw) == manifest["input"]["sha256LittleEndianFloat32"],
        "input artifact hash mismatch",
    )
    require(
        sha256_bytes(pc_raw)
        == manifest["pcGoldenOutput"]["sha256LittleEndianFloat32"],
        "PC golden artifact hash mismatch",
    )
    require(
        sha256_bytes(receipt_raw) == receipt_contract["sha256"],
        "preserved receipt hash mismatch",
    )
    require(
        sha256_bytes(android_raw)
        == android_contract["sha256LittleEndianFloat32"],
        "Android artifact hash mismatch",
    )
    require(
        receipt["outputArtifact"]["sha256"]
        == android_contract["sha256LittleEndianFloat32"],
        "receipt-to-Android-artifact hash link mismatch",
    )
    require(
        receipt["inputIdentity"]["sha256LittleEndianFloat32"]
        == manifest["input"]["sha256LittleEndianFloat32"],
        "receipt-to-input hash link mismatch",
    )
    canonical_model_sha256 = manifest["model"]["sha256"]
    if receipt["model"]["observedSha256"] == canonical_model_sha256:
        require(
            comparison.get("derivedModelLinkage") is None,
            "canonical case unexpectedly contains derived model linkage",
        )
        model_linkage_kind = "canonical"
    else:
        recorded_linkage = comparison.get("derivedModelLinkage")
        require(
            isinstance(recorded_linkage, dict),
            "derived receipt lacks recorded derivation linkage",
        )
        derivation_artifact = recorded_linkage.get("derivationManifestArtifact", {})
        derivation_raw = read_base64(case_dir / derivation_artifact["file"])
        require(
            sha256_bytes(derivation_raw) == derivation_artifact.get("sha256"),
            "preserved derivation manifest hash mismatch",
        )
        derivation_manifest = json.loads(derivation_raw.decode("utf-8"))
        execution_plan = None
        execution_plan_raw = None
        execution_plan_artifact = recorded_linkage.get("executionPlanArtifact")
        if execution_plan_artifact is not None:
            execution_plan_raw = read_base64(
                case_dir / execution_plan_artifact["file"]
            )
            require(
                sha256_bytes(execution_plan_raw)
                == execution_plan_artifact.get("sha256"),
                "preserved execution plan hash mismatch",
            )
            execution_plan = json.loads(execution_plan_raw.decode("utf-8"))
        recomputed_linkage = validate_derived_model_linkage(
            receipt,
            canonical_model_sha256,
            manifest["input"]["sha256LittleEndianFloat32"],
            list(manifest["pcGoldenOutput"]["shape"]),
            derivation_manifest,
            derivation_raw,
            execution_plan,
            execution_plan_raw,
        )
        recorded_without_artifact = dict(recorded_linkage)
        recorded_without_artifact.pop("derivationManifestArtifact", None)
        recorded_without_artifact.pop("executionPlanArtifact", None)
        require(
            recomputed_linkage == recorded_without_artifact,
            "recomputed derived model linkage differs from comparison result",
        )
        model_linkage_kind = "derived"

    shape = tuple(int(value) for value in manifest["pcGoldenOutput"]["shape"])
    pc = tensor_from_bytes(pc_raw, shape)
    android = tensor_from_bytes(android_raw, shape)
    contract = manifest["toleranceContract"]
    recomputed = compare_tensors(
        android,
        pc,
        float(contract["absoluteTolerance"]),
        float(contract["relativeTolerance"]),
    )
    recorded = comparison["metrics"]
    for key in ("status", "elementCount", "mismatchCount", "nonfiniteCount"):
        require(recomputed[key] == recorded[key], f"recomputed metric drift: {key}")
    for key in ("maxAbsoluteError", "maxRelativeError", "meanAbsoluteError"):
        require(
            math.isclose(recomputed[key], recorded[key], rel_tol=0.0, abs_tol=0.0),
            f"recomputed metric drift: {key}",
        )

    validation = {
        "schemaVersion": "1.0.0",
        "kind": "golden-correctness-case-validation",
        "status": "PASS",
        "validatedAt": datetime.now(timezone.utc).isoformat(),
        "checks": {
            "inputArtifactHash": "PASS",
            "pcGoldenArtifactHash": "PASS",
            "androidReceiptHash": "PASS",
            "androidOutputHash": "PASS",
            "modelInputOutputLinkage": "PASS",
            "modelLineageKind": model_linkage_kind,
            "derivedManifestAndPlanLinkage": (
                "PASS" if model_linkage_kind == "derived" else "NOT_APPLICABLE"
            ),
            "elementwiseToleranceRecomputation": "PASS",
        },
        "metrics": recomputed,
    }
    if not args.no_write:
        output_path = case_dir / "validation-result.json"
        output_path.write_text(
            json.dumps(validation, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    print(json.dumps(validation, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Golden case validation failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
