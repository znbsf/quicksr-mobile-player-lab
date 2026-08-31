#!/usr/bin/env python3
"""Derive and validate a fixed-256 DCR QuickSRNetSmall x2 model.

The historical fixed-64 artifacts and their manifest are immutable inputs to
this derivation.  The script writes only the fixed-256 model and its separate
manifest beside itself.
"""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper

import derive_quicksrnet_fixed64 as base


DERIVED_ROOT = Path(__file__).resolve().parent
LAB_ROOT = DERIVED_ROOT.parent
MODEL_PATH = DERIVED_ROOT / "quicksrnet-small-2x-fixed256-dcr.onnx"
MANIFEST_PATH = DERIVED_ROOT / "derivation-manifest-fixed256.json"

EXPECTED_FIXED64_CORE_SHA256 = (
    "9a35f235ac9dc36447764a58a2d1511720dc346360f76b77fee490b347f9e3b6"
)
EXPECTED_FIXED64_DCR_SHA256 = (
    "c902565d3ec55de1fbfa66aac8e283890c7b77eab0e39c60ba35022691148a5f"
)
EXPECTED_FIXED64_MANIFEST_SHA256 = (
    "8ed648d623a15acb0cf42dac00622a492a23b6ff223b04de24cd9c8f33d03430"
)

INPUT_SIZE = 256
SCALE = 2
INPUT_SHAPE = (1, 3, INPUT_SIZE, INPUT_SIZE)
OUTPUT_SHAPE = (1, 3, INPUT_SIZE * SCALE, INPUT_SIZE * SCALE)


def _lab_relative(path: Path) -> str:
    return path.resolve().relative_to(LAB_ROOT.resolve()).as_posix()


def _require_unchanged(path: Path, expected_sha256: str, label: str) -> bytes:
    return base._require_file(path, expected_sha256, label)


def derive_model(canonical: onnx.ModelProto) -> onnx.ModelProto:
    """Reuse the qualified DCR rewrite, changing only fixed tensor shapes."""

    _, fixed64_dcr, _ = base.derive_models(canonical)
    model = copy.deepcopy(fixed64_dcr)
    base._set_shape(model.graph.input[0], INPUT_SHAPE)
    base._set_shape(model.graph.output[0], OUTPUT_SHAPE)
    model.graph.name = "quicksrnet_small_2x_fixed256_dcr_full"
    model.producer_name = "quicksrnet-fixed256-deriver"
    model.producer_version = "1"
    model.model_version = 1
    model.doc_string = (
        "Deterministic fixed256 DCR derivative of the frozen "
        "QuickSRNetSmall x2 ONNX."
    )
    helper.set_model_props(
        model,
        {
            "derivation.variant": "fixed256-dcr-full",
            "derivation.source_sha256": base.EXPECTED_CANONICAL_SHA256,
            "derivation.base_fixed64_sha256": EXPECTED_FIXED64_DCR_SHA256,
        },
    )
    onnx.checker.check_model(model, full_check=True)
    return model


def gradient_nchw() -> np.ndarray:
    coordinates = np.arange(INPUT_SIZE, dtype=np.float32)
    x = np.broadcast_to(coordinates[None, :], (INPUT_SIZE, INPUT_SIZE))
    y = np.broadcast_to(coordinates[:, None], (INPUT_SIZE, INPUT_SIZE))
    denominator = np.float32(INPUT_SIZE - 1)
    result = np.empty(INPUT_SHAPE, dtype=np.float32)
    result[0, 0] = x / denominator
    result[0, 1] = y / denominator
    result[0, 2] = (x + y) / np.float32(2 * (INPUT_SIZE - 1))
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
        cases = (
            ("rgb-gradient-256", gradient_nchw()),
            ("seeded-random-256", seeded_random_nchw()),
        )
        results: list[dict[str, Any]] = []
        for case_id, input_tensor in cases:
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
                    f"fixed256 output is not byte-exact for {case_id}: {comparison}"
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
    fixed64_before = {
        "core": base.sha256_bytes(
            _require_unchanged(
                base.CORE_MODEL_PATH,
                EXPECTED_FIXED64_CORE_SHA256,
                "fixed64 core ONNX",
            )
        ),
        "dcr": base.sha256_bytes(
            _require_unchanged(
                base.DCR_MODEL_PATH,
                EXPECTED_FIXED64_DCR_SHA256,
                "fixed64 DCR ONNX",
            )
        ),
        "manifest": base.sha256_bytes(
            _require_unchanged(
                base.MANIFEST_PATH,
                EXPECTED_FIXED64_MANIFEST_SHA256,
                "fixed64 derivation manifest",
            )
        ),
    }

    canonical = onnx.load_model_from_string(canonical_bytes)
    onnx.checker.check_model(canonical, full_check=True)
    model = derive_model(canonical)
    model_bytes = base._serialize_model(model)
    onnx.checker.check_model(
        onnx.load_model_from_string(model_bytes), full_check=True
    )
    validation = validate_equivalence(canonical_bytes, model_bytes)

    fixed64_after = {
        "core": base.sha256_bytes(base.CORE_MODEL_PATH.read_bytes()),
        "dcr": base.sha256_bytes(base.DCR_MODEL_PATH.read_bytes()),
        "manifest": base.sha256_bytes(base.MANIFEST_PATH.read_bytes()),
    }
    if fixed64_after != fixed64_before:
        raise base.DerivationError(
            f"fixed64 artifacts changed during fixed256 derivation: "
            f"before={fixed64_before}, after={fixed64_after}"
        )

    script_bytes = Path(__file__).read_bytes()
    base_script_bytes = Path(base.__file__).read_bytes()
    manifest = {
        "schema_version": 1,
        "document_kind": "deterministic-onnx-fixed256-derivation-manifest",
        "status": "pass",
        "source": {
            "path": _lab_relative(base.CANONICAL_PATH),
            "bytes": len(canonical_bytes),
            "sha256": base.sha256_bytes(canonical_bytes),
        },
        "fixed64_inputs_unchanged": fixed64_after,
        "derivation_tool": {
            "path": _lab_relative(Path(__file__)),
            "sha256": base.sha256_bytes(script_bytes),
            "base_tool_path": _lab_relative(Path(base.__file__)),
            "base_tool_sha256": base.sha256_bytes(base_script_bytes),
            "versions": tool_versions,
            "deterministic_protobuf_serialization": True,
        },
        "artifact": base._model_record(
            MODEL_PATH,
            model_bytes,
            model,
            [
                "apply the qualified fixed64 DCR graph rewrite",
                "freeze input shape to [1,3,256,256]",
                "freeze output shape to [1,3,512,512]",
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
                "fixed64_inputs_unchanged": manifest[
                    "fixed64_inputs_unchanged"
                ],
            },
            indent=2,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
