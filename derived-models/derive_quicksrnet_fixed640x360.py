#!/usr/bin/env python3
"""Derive and validate a fixed 640x360 DCR QuickSRNetSmall x2 model."""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
from typing import Any

import numpy as np
import onnx
from onnx import helper

import derive_quicksrnet_fixed64 as base
import derive_quicksrnet_fixed256 as fixed256


DERIVED_ROOT = Path(__file__).resolve().parent
LAB_ROOT = DERIVED_ROOT.parent
MODEL_PATH = DERIVED_ROOT / "quicksrnet-small-2x-fixed640x360-dcr.onnx"
MANIFEST_PATH = DERIVED_ROOT / "derivation-manifest-fixed640x360.json"

INPUT_WIDTH = 640
INPUT_HEIGHT = 360
SCALE = 2
INPUT_SHAPE = (1, 3, INPUT_HEIGHT, INPUT_WIDTH)
OUTPUT_SHAPE = (1, 3, INPUT_HEIGHT * SCALE, INPUT_WIDTH * SCALE)


def _lab_relative(path: Path) -> str:
    return path.resolve().relative_to(LAB_ROOT.resolve()).as_posix()


def derive_model(canonical: onnx.ModelProto) -> onnx.ModelProto:
    """Reuse the qualified DCR rewrite, changing only the static tensor shapes."""

    _, fixed64_dcr, _ = base.derive_models(canonical)
    model = copy.deepcopy(fixed64_dcr)
    base._set_shape(model.graph.input[0], INPUT_SHAPE)
    base._set_shape(model.graph.output[0], OUTPUT_SHAPE)
    model.graph.name = "quicksrnet_small_2x_fixed640x360_dcr_full"
    model.producer_name = "quicksrnet-fixed640x360-deriver"
    model.producer_version = "1"
    model.model_version = 1
    model.doc_string = (
        "Deterministic fixed 640x360 DCR derivative of the frozen "
        "QuickSRNetSmall x2 ONNX."
    )
    helper.set_model_props(
        model,
        {
            "derivation.variant": "fixed640x360-dcr-full",
            "derivation.source_sha256": base.EXPECTED_CANONICAL_SHA256,
            "derivation.base_fixed64_sha256": fixed256.EXPECTED_FIXED64_DCR_SHA256,
        },
    )
    onnx.checker.check_model(model, full_check=True)
    return model


def gradient_nchw() -> np.ndarray:
    x = np.broadcast_to(
        np.linspace(0.0, 1.0, INPUT_WIDTH, dtype=np.float32)[None, :],
        (INPUT_HEIGHT, INPUT_WIDTH),
    )
    y = np.broadcast_to(
        np.linspace(0.0, 1.0, INPUT_HEIGHT, dtype=np.float32)[:, None],
        (INPUT_HEIGHT, INPUT_WIDTH),
    )
    result = np.empty(INPUT_SHAPE, dtype=np.float32)
    result[0, 0] = x
    result[0, 1] = y
    result[0, 2] = (x + y) * np.float32(0.5)
    return result


def seeded_random_nchw() -> np.ndarray:
    generator = np.random.default_rng(20260901)
    return generator.random(INPUT_SHAPE, dtype=np.float32)


def validate_equivalence(
    canonical_bytes: bytes, derived_bytes: bytes
) -> dict[str, Any]:
    canonical_session = base._session(canonical_bytes)
    derived_session = base._session(derived_bytes)
    try:
        results: list[dict[str, Any]] = []
        for case_id, input_tensor in (
            ("rgb-gradient-640x360", gradient_nchw()),
            ("seeded-random-640x360", seeded_random_nchw()),
        ):
            canonical_output = canonical_session.run(
                [base.CANONICAL_OUTPUT_NAME], {base.INPUT_NAME: input_tensor}
            )[0]
            derived_output = derived_session.run(
                [base.CANONICAL_OUTPUT_NAME], {base.INPUT_NAME: input_tensor}
            )[0]
            if tuple(canonical_output.shape) != OUTPUT_SHAPE:
                raise base.DerivationError(
                    f"canonical ORT output shape changed: {canonical_output.shape}"
                )
            comparison = base._comparison(derived_output, canonical_output)
            if not comparison["exact_float32_bytes"]:
                raise base.DerivationError(
                    f"fixed640x360 output is not byte-exact for {case_id}: {comparison}"
                )
            results.append(
                {
                    "id": case_id,
                    "input_shape": list(input_tensor.shape),
                    "input_sha256_little_endian_float32": base.tensor_sha256(
                        input_tensor
                    ),
                    "comparison": comparison,
                }
            )
        return {
            "status": "pass",
            "provider": "CPUExecutionProvider",
            "execution_mode": "sequential",
            "graph_optimization_level": "disabled",
            "intra_op_threads": 1,
            "inter_op_threads": 1,
            "cases": results,
        }
    finally:
        del canonical_session
        del derived_session


def _write_atomic(path: Path, payload: bytes) -> None:
    if path.parent.resolve() != DERIVED_ROOT.resolve():
        raise base.DerivationError(f"refusing to write outside derived-models: {path}")
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    try:
        with temporary.open("wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def generate() -> dict[str, Any]:
    tool_versions = base._require_tool_versions()
    canonical_bytes = base._require_file(
        base.CANONICAL_PATH,
        base.EXPECTED_CANONICAL_SHA256,
        "canonical ONNX",
    )
    fixed64_dcr = base._require_file(
        base.DCR_MODEL_PATH,
        fixed256.EXPECTED_FIXED64_DCR_SHA256,
        "fixed64 DCR ONNX",
    )
    canonical = onnx.load_model_from_string(canonical_bytes)
    onnx.checker.check_model(canonical, full_check=True)
    model = derive_model(canonical)
    model_bytes = base._serialize_model(model)
    validation = validate_equivalence(canonical_bytes, model_bytes)

    script_bytes = Path(__file__).read_bytes()
    manifest = {
        "schema_version": 1,
        "document_kind": "deterministic-onnx-fixed640x360-derivation-manifest",
        "status": "pass",
        "source": {
            "path": _lab_relative(base.CANONICAL_PATH),
            "bytes": len(canonical_bytes),
            "sha256": base.sha256_bytes(canonical_bytes),
        },
        "fixed64_dcr_input": {
            "path": _lab_relative(base.DCR_MODEL_PATH),
            "bytes": len(fixed64_dcr),
            "sha256": base.sha256_bytes(fixed64_dcr),
        },
        "derivation_tool": {
            "path": _lab_relative(Path(__file__)),
            "sha256": base.sha256_bytes(script_bytes),
            "base_tool_path": _lab_relative(Path(base.__file__)),
            "base_tool_sha256": base.sha256_bytes(Path(base.__file__).read_bytes()),
            "versions": tool_versions,
            "deterministic_protobuf_serialization": True,
        },
        "artifact": base._model_record(
            MODEL_PATH,
            model_bytes,
            model,
            [
                "apply the qualified fixed64 DCR graph rewrite",
                "freeze input shape to [1,3,360,640]",
                "freeze output shape to [1,3,720,1280]",
            ],
        ),
        "pc_ort_validation": validation,
        "canonical_was_written": False,
        "fixed64_was_written": False,
    }
    manifest_bytes = (
        json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + "\n"
    ).encode("utf-8")
    _write_atomic(MODEL_PATH, model_bytes)
    _write_atomic(MANIFEST_PATH, manifest_bytes)
    return manifest


def main() -> int:
    manifest = generate()
    print(
        json.dumps(
            {
                "status": manifest["status"],
                "path": manifest["artifact"]["path"],
                "bytes": manifest["artifact"]["bytes"],
                "sha256": manifest["artifact"]["sha256"],
                "validation_cases": len(manifest["pc_ort_validation"]["cases"]),
            },
            indent=2,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
