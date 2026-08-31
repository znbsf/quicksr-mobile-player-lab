#!/usr/bin/env python3
"""Derive and validate the two frozen QuickSRNetSmall x2 P2 ONNX models.

The canonical model and the frozen P2 plan are read-only trust anchors.  This
script accepts no path overrides and writes only beside itself.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper


DERIVED_ROOT = Path(__file__).resolve().parent
LAB_ROOT = DERIVED_ROOT.parent
CANONICAL_PATH = LAB_ROOT / "models" / "quicksrnet-small-2x-opset17.onnx"
P2_PLAN_PATH = DERIVED_ROOT.parent / "prototype-plan-p2.json"
CORE_MODEL_PATH = DERIVED_ROOT / "quicksrnet-small-2x-fixed64-core.onnx"
DCR_MODEL_PATH = DERIVED_ROOT / "quicksrnet-small-2x-fixed64-dcr.onnx"
MANIFEST_PATH = DERIVED_ROOT / "derivation-manifest.json"

EXPECTED_CANONICAL_SHA256 = (
    "3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce"
)
EXPECTED_P2_PLAN_SHA256 = (
    "44852e9245c46959af438b64dff75db3489f09ac94ac3913277af9d361a00859"
)
EXPECTED_ANDROID_INPUT_SHA256 = (
    "cc13c100d394903d5c9ccde7a44aab63660e266099077063a0a0de326f5b9fc9"
)
EXPECTED_TOOL_VERSIONS = {
    "numpy": "2.2.6",
    "onnx": "1.18.0",
    "onnxruntime": "1.22.1",
}

INPUT_NAME = "image"
CANONICAL_OUTPUT_NAME = "upscaled_image"
CORE_OUTPUT_NAME = "pre_shuffle_output"
INPUT_SHAPE = (1, 3, 64, 64)
CORE_OUTPUT_SHAPE = (1, 12, 64, 64)
FULL_OUTPUT_SHAPE = (1, 3, 128, 128)
BLOCK_SIZE = 2
ATOL = 1.0e-4
RTOL = 1.0e-4
DCR_GATHER_FROM_CRD = (0, 4, 8, 1, 5, 9, 2, 6, 10, 3, 7, 11)


class DerivationError(RuntimeError):
    """The frozen source, transformation, or validation contract failed."""


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def canonical_f32le_bytes(values: np.ndarray) -> bytes:
    array = np.asarray(values, dtype=np.float32, order="C")
    return array.astype("<f4", copy=False).tobytes(order="C")


def tensor_sha256(values: np.ndarray) -> str:
    return sha256_bytes(canonical_f32le_bytes(values))


def _require_file(path: Path, expected_sha256: str, label: str) -> bytes:
    if not path.is_file():
        raise DerivationError(f"{label} is missing: {path}")
    payload = path.read_bytes()
    actual_sha256 = sha256_bytes(payload)
    if actual_sha256 != expected_sha256:
        raise DerivationError(
            f"{label} SHA-256 changed: expected={expected_sha256}, "
            f"actual={actual_sha256}"
        )
    return payload


def _require_tool_versions() -> dict[str, str]:
    actual = {
        "numpy": np.__version__,
        "onnx": onnx.__version__,
        "onnxruntime": ort.__version__,
    }
    if actual != EXPECTED_TOOL_VERSIONS:
        raise DerivationError(
            f"pinned derivation environment changed: expected={EXPECTED_TOOL_VERSIONS}, "
            f"actual={actual}"
        )
    return actual


def _attribute(node: onnx.NodeProto, name: str) -> Any:
    matches = [item for item in node.attribute if item.name == name]
    if len(matches) != 1:
        raise DerivationError(
            f"node {node.name!r} must have exactly one {name!r} attribute"
        )
    return helper.get_attribute_value(matches[0])


def _producer_map(model: onnx.ModelProto) -> dict[str, onnx.NodeProto]:
    producers: dict[str, onnx.NodeProto] = {}
    for node in model.graph.node:
        for output in node.output:
            if output in producers:
                raise DerivationError(f"duplicate tensor producer: {output}")
            producers[output] = node
    return producers


def _consumer_map(model: onnx.ModelProto) -> dict[str, list[onnx.NodeProto]]:
    consumers: dict[str, list[onnx.NodeProto]] = defaultdict(list)
    for node in model.graph.node:
        for input_name in node.input:
            consumers[input_name].append(node)
    return consumers


def _initializer_map(model: onnx.ModelProto) -> dict[str, onnx.TensorProto]:
    values: dict[str, onnx.TensorProto] = {}
    for initializer in model.graph.initializer:
        if initializer.name in values:
            raise DerivationError(f"duplicate initializer: {initializer.name}")
        values[initializer.name] = initializer
    return values


def _op_inventory(model: onnx.ModelProto) -> dict[str, int]:
    return dict(sorted(Counter(node.op_type for node in model.graph.node).items()))


def _shape(value_info: onnx.ValueInfoProto) -> list[int | str | None]:
    result: list[int | str | None] = []
    for dim in value_info.type.tensor_type.shape.dim:
        if dim.HasField("dim_value"):
            result.append(int(dim.dim_value))
        elif dim.HasField("dim_param"):
            result.append(dim.dim_param)
        else:
            result.append(None)
    return result


def _set_shape(value_info: onnx.ValueInfoProto, shape: Iterable[int]) -> None:
    shape_tuple = tuple(int(value) for value in shape)
    dims = value_info.type.tensor_type.shape.dim
    if len(dims) != len(shape_tuple):
        raise DerivationError(
            f"rank changed for {value_info.name}: expected={len(shape_tuple)}, "
            f"actual={len(dims)}"
        )
    for dim, value in zip(dims, shape_tuple):
        dim.ClearField("dim_param")
        dim.ClearField("dim_value")
        dim.dim_value = value


def _canonical_contract(model: onnx.ModelProto) -> dict[str, Any]:
    if model.ir_version != 8:
        raise DerivationError(f"canonical IR version changed: {model.ir_version}")
    opsets = [(item.domain, int(item.version)) for item in model.opset_import]
    if opsets != [("", 17)]:
        raise DerivationError(f"canonical opset changed: {opsets}")
    if len(model.graph.input) != 1 or model.graph.input[0].name != INPUT_NAME:
        raise DerivationError("canonical input contract changed")
    if (
        len(model.graph.output) != 1
        or model.graph.output[0].name != CANONICAL_OUTPUT_NAME
    ):
        raise DerivationError("canonical output contract changed")
    expected_inventory = {"Clip": 4, "Constant": 8, "Conv": 4, "DepthToSpace": 1}
    actual_inventory = _op_inventory(model)
    if actual_inventory != expected_inventory:
        raise DerivationError(
            f"canonical operator inventory changed: {actual_inventory}"
        )

    depth_to_space = [node for node in model.graph.node if node.op_type == "DepthToSpace"]
    if len(depth_to_space) != 1:
        raise DerivationError("canonical model must contain one DepthToSpace")
    d2s = depth_to_space[0]
    if _attribute(d2s, "blocksize") != BLOCK_SIZE:
        raise DerivationError("canonical DepthToSpace blocksize changed")
    if _attribute(d2s, "mode") != b"CRD":
        raise DerivationError("canonical DepthToSpace mode is not CRD")

    producers = _producer_map(model)
    if len(d2s.input) != 1 or d2s.input[0] not in producers:
        raise DerivationError("canonical DepthToSpace input producer is missing")
    final_clip = producers[d2s.input[0]]
    if final_clip.op_type != "Clip" or len(final_clip.input) != 3:
        raise DerivationError("DepthToSpace is not fed by the final three-input Clip")
    final_conv = producers.get(final_clip.input[0])
    if final_conv is None or final_conv.op_type != "Conv" or len(final_conv.input) != 3:
        raise DerivationError("final Clip is not fed by a three-input Conv")

    initializers = _initializer_map(model)
    weight_name, bias_name = final_conv.input[1], final_conv.input[2]
    if weight_name not in initializers or bias_name not in initializers:
        raise DerivationError("final Conv weights are not initializers")
    weight = numpy_helper.to_array(initializers[weight_name])
    bias = numpy_helper.to_array(initializers[bias_name])
    if tuple(weight.shape) != (12, 32, 3, 3) or tuple(bias.shape) != (12,):
        raise DerivationError(
            f"final Conv shape changed: weight={weight.shape}, bias={bias.shape}"
        )
    return {
        "depth_to_space_name": d2s.name,
        "final_clip_name": final_clip.name,
        "final_conv_name": final_conv.name,
        "final_weight_name": weight_name,
        "final_bias_name": bias_name,
        "final_weight_sha256": tensor_sha256(weight),
        "final_bias_sha256": tensor_sha256(bias),
    }


def _materialize_clip_bounds(model: onnx.ModelProto) -> list[dict[str, Any]]:
    producers = _producer_map(model)
    consumers = _consumer_map(model)
    existing_initializers = set(_initializer_map(model))
    constants_to_remove: set[str] = set()
    new_initializers: list[onnx.TensorProto] = []
    rewrites: list[dict[str, Any]] = []

    clips = [node for node in model.graph.node if node.op_type == "Clip"]
    if len(clips) != 4:
        raise DerivationError(f"expected four Clip nodes, got {len(clips)}")
    for clip in clips:
        if len(clip.input) != 3:
            raise DerivationError(f"Clip {clip.name!r} does not have min/max inputs")
        for role, bound_name, expected_value in (
            ("min", clip.input[1], 0.0),
            ("max", clip.input[2], 1.0),
        ):
            constant = producers.get(bound_name)
            if constant is None or constant.op_type != "Constant":
                raise DerivationError(
                    f"Clip {clip.name!r} {role} is not produced by Constant"
                )
            if len(constant.input) != 0 or list(constant.output) != [bound_name]:
                raise DerivationError(f"unexpected Constant contract: {constant.name!r}")
            if consumers.get(bound_name) != [clip]:
                raise DerivationError(
                    f"Clip bound {bound_name!r} has unexpected consumers"
                )
            tensor = _attribute(constant, "value")
            if not isinstance(tensor, onnx.TensorProto):
                raise DerivationError(f"Constant {constant.name!r} is not tensor-valued")
            values = np.asarray(numpy_helper.to_array(tensor))
            if values.dtype != np.float32 or values.size != 1:
                raise DerivationError(
                    f"Clip bound {bound_name!r} is not scalar float32"
                )
            actual_value = float(values.reshape(-1)[0])
            if actual_value != expected_value:
                raise DerivationError(
                    f"Clip {clip.name!r} {role} changed: {actual_value}"
                )
            if bound_name in existing_initializers:
                raise DerivationError(f"Clip bound already exists as initializer: {bound_name}")
            initializer = copy.deepcopy(tensor)
            initializer.name = bound_name
            new_initializers.append(initializer)
            existing_initializers.add(bound_name)
            constants_to_remove.add(constant.name)
            rewrites.append(
                {
                    "clip_node": clip.name,
                    "role": role,
                    "constant_node": constant.name,
                    "initializer": bound_name,
                    "dtype": "float32",
                    "shape": [],
                    "value": actual_value,
                }
            )

    if len(constants_to_remove) != 8:
        raise DerivationError(
            f"expected eight distinct Clip Constant nodes, got {len(constants_to_remove)}"
        )
    retained_nodes = [
        node for node in model.graph.node if node.name not in constants_to_remove
    ]
    del model.graph.node[:]
    model.graph.node.extend(retained_nodes)
    model.graph.initializer.extend(new_initializers)

    if any(node.op_type == "Constant" for node in model.graph.node):
        raise DerivationError("a Constant node remained after Clip bound materialization")
    initializer_names = set(_initializer_map(model))
    for clip in (node for node in model.graph.node if node.op_type == "Clip"):
        if clip.input[1] not in initializer_names or clip.input[2] not in initializer_names:
            raise DerivationError(f"Clip {clip.name!r} bounds are not initializers")
    return rewrites


def _set_model_identity(model: onnx.ModelProto, variant: str) -> None:
    model.producer_name = "quicksrnet-fixed64-deriver"
    model.producer_version = "1"
    model.model_version = 1
    model.doc_string = (
        "Deterministic fixed64 derivative of the frozen QuickSRNetSmall x2 ONNX."
    )
    helper.set_model_props(
        model,
        {
            "derivation.variant": variant,
            "derivation.source_sha256": EXPECTED_CANONICAL_SHA256,
            "derivation.p2_plan_sha256": EXPECTED_P2_PLAN_SHA256,
        },
    )


def _replace_initializer(
    model: onnx.ModelProto, name: str, values: np.ndarray
) -> None:
    for initializer in model.graph.initializer:
        if initializer.name == name:
            replacement = numpy_helper.from_array(np.ascontiguousarray(values), name=name)
            initializer.CopyFrom(replacement)
            return
    raise DerivationError(f"initializer not found for replacement: {name}")


def derive_models(
    canonical: onnx.ModelProto,
) -> tuple[onnx.ModelProto, onnx.ModelProto, dict[str, Any]]:
    contract = _canonical_contract(canonical)
    common = copy.deepcopy(canonical)
    clip_rewrites = _materialize_clip_bounds(common)
    _set_shape(common.graph.input[0], INPUT_SHAPE)

    core = copy.deepcopy(common)
    core_d2s = [node for node in core.graph.node if node.op_type == "DepthToSpace"]
    if len(core_d2s) != 1:
        raise DerivationError("core derivation lost the unique DepthToSpace node")
    d2s = core_d2s[0]
    producers = _producer_map(core)
    final_clip = producers.get(d2s.input[0])
    if final_clip is None or final_clip.op_type != "Clip":
        raise DerivationError("core derivation cannot locate final Clip")
    old_pre_shuffle_name = d2s.input[0]
    final_clip.output[0] = CORE_OUTPUT_NAME
    retained_nodes = [node for node in core.graph.node if node.name != d2s.name]
    del core.graph.node[:]
    core.graph.node.extend(retained_nodes)
    del core.graph.output[:]
    core.graph.output.extend(
        [helper.make_tensor_value_info(CORE_OUTPUT_NAME, TensorProto.FLOAT, CORE_OUTPUT_SHAPE)]
    )
    core.graph.name = "quicksrnet_small_2x_fixed64_pre_shuffle_core"
    _set_model_identity(core, "fixed64-pre-shuffle-core")

    dcr = copy.deepcopy(common)
    dcr.graph.name = "quicksrnet_small_2x_fixed64_dcr_full"
    _set_shape(dcr.graph.output[0], FULL_OUTPUT_SHAPE)
    dcr_d2s = [node for node in dcr.graph.node if node.op_type == "DepthToSpace"]
    if len(dcr_d2s) != 1:
        raise DerivationError("DCR derivation lost the unique DepthToSpace node")
    dcr_node = dcr_d2s[0]
    if _attribute(dcr_node, "mode") != b"CRD":
        raise DerivationError("DCR derivation source mode changed before rewrite")
    mode_attributes = [item for item in dcr_node.attribute if item.name == "mode"]
    mode_attributes[0].s = b"DCR"

    initializers = _initializer_map(dcr)
    weight = numpy_helper.to_array(initializers[contract["final_weight_name"]])
    bias = numpy_helper.to_array(initializers[contract["final_bias_name"]])
    permutation = np.asarray(DCR_GATHER_FROM_CRD, dtype=np.int64)
    permuted_weight = np.ascontiguousarray(weight[permutation])
    permuted_bias = np.ascontiguousarray(bias[permutation])
    _replace_initializer(dcr, contract["final_weight_name"], permuted_weight)
    _replace_initializer(dcr, contract["final_bias_name"], permuted_bias)
    _set_model_identity(dcr, "fixed64-dcr-full")

    for label, model in (("core", core), ("dcr", dcr)):
        onnx.checker.check_model(model, full_check=True)
        if any(node.op_type == "Constant" for node in model.graph.node):
            raise DerivationError(f"{label} graph still contains Constant nodes")

    metadata = {
        "canonical_contract": contract,
        "clip_bound_rewrites": clip_rewrites,
        "core_removed": {
            "node": d2s.name,
            "op_type": "DepthToSpace",
            "mode": "CRD",
            "blocksize": BLOCK_SIZE,
            "old_input": old_pre_shuffle_name,
            "new_graph_output": CORE_OUTPUT_NAME,
        },
        "dcr_permutation": {
            "meaning": "new DCR input channel gathers this old CRD output channel",
            "gather_indices": list(DCR_GATHER_FROM_CRD),
            "weight_axis": 0,
            "bias_axis": 0,
            "weight_sha256_before": tensor_sha256(weight),
            "weight_sha256_after": tensor_sha256(permuted_weight),
            "bias_sha256_before": tensor_sha256(bias),
            "bias_sha256_after": tensor_sha256(permuted_bias),
            "depth_to_space_mode_before": "CRD",
            "depth_to_space_mode_after": "DCR",
        },
    }
    return core, dcr, metadata


def crd_pixel_shuffle(values: np.ndarray, block_size: int = BLOCK_SIZE) -> np.ndarray:
    """Exact NCHW CRD DepthToSpace/PixelShuffle implemented as data movement."""

    array = np.asarray(values)
    if array.ndim != 4:
        raise ValueError(f"expected rank-4 NCHW tensor, got shape {array.shape}")
    batch, channels, height, width = array.shape
    phases = block_size * block_size
    if channels % phases != 0:
        raise ValueError(
            f"channel count {channels} is not divisible by block_size^2={phases}"
        )
    output_channels = channels // phases
    shuffled = (
        array.reshape(batch, output_channels, block_size, block_size, height, width)
        .transpose(0, 1, 4, 2, 5, 3)
        .reshape(batch, output_channels, height * block_size, width * block_size)
    )
    return np.ascontiguousarray(shuffled)


def android_rgb_gradient_nchw() -> np.ndarray:
    """Mirror DeterministicInputs.rgbGradientNchw-v1 at float32 precision."""

    width = height = 64
    result = np.empty(INPUT_SHAPE, dtype=np.float32)
    for y in range(height):
        for x in range(width):
            result[0, 0, y, x] = np.float32(x) / np.float32(width - 1)
            result[0, 1, y, x] = np.float32(y) / np.float32(height - 1)
            result[0, 2, y, x] = np.float32(x + y) / np.float32(
                width + height - 2
            )
    return result


def seeded_random_nchw() -> np.ndarray:
    generator = np.random.default_rng(20260830)
    return generator.random(INPUT_SHAPE, dtype=np.float32)


def _session(model_bytes: bytes) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    return ort.InferenceSession(
        model_bytes,
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )


def _comparison(actual: np.ndarray, reference: np.ndarray) -> dict[str, Any]:
    actual = np.asarray(actual, dtype=np.float32)
    reference = np.asarray(reference, dtype=np.float32)
    if actual.shape != reference.shape:
        raise DerivationError(
            f"comparison shape mismatch: actual={actual.shape}, reference={reference.shape}"
        )
    actual64 = actual.astype(np.float64)
    reference64 = reference.astype(np.float64)
    finite_mask = np.isfinite(actual64) & np.isfinite(reference64)
    nonfinite_count = int(finite_mask.size - np.count_nonzero(finite_mask))
    absolute_error = np.abs(actual64 - reference64)
    threshold = ATOL + RTOL * np.abs(reference64)
    mismatch_mask = (~finite_mask) | (absolute_error > threshold)
    mismatch_count = int(np.count_nonzero(mismatch_mask))
    finite_errors = absolute_error[finite_mask]
    max_abs_error = float(np.max(finite_errors)) if finite_errors.size else None
    result = {
        "shape": list(actual.shape),
        "element_count": int(actual.size),
        "atol": ATOL,
        "rtol": RTOL,
        "allowed_mismatch_count": 0,
        "allowed_nonfinite_count": 0,
        "mismatch_count": mismatch_count,
        "nonfinite_count": nonfinite_count,
        "max_abs_error": max_abs_error,
        "exact_float32_bytes": canonical_f32le_bytes(actual)
        == canonical_f32le_bytes(reference),
        "actual_sha256_little_endian_float32": tensor_sha256(actual),
        "reference_sha256_little_endian_float32": tensor_sha256(reference),
        "status": "pass" if mismatch_count == 0 and nonfinite_count == 0 else "fail",
    }
    if result["status"] != "pass":
        raise DerivationError(f"frozen numerical gate failed: {result}")
    return result


def validate_equivalence(
    canonical_bytes: bytes, core_bytes: bytes, dcr_bytes: bytes
) -> dict[str, Any]:
    canonical_session = _session(canonical_bytes)
    core_session = _session(core_bytes)
    dcr_session = _session(dcr_bytes)
    cases = (
        ("android-rgb-gradient-64", android_rgb_gradient_nchw()),
        ("seeded-random-64", seeded_random_nchw()),
    )
    results: list[dict[str, Any]] = []
    for case_id, input_tensor in cases:
        input_hash = tensor_sha256(input_tensor)
        if case_id == "android-rgb-gradient-64" and input_hash != EXPECTED_ANDROID_INPUT_SHA256:
            raise DerivationError(
                f"Android input identity changed: expected={EXPECTED_ANDROID_INPUT_SHA256}, "
                f"actual={input_hash}"
            )
        canonical_output = canonical_session.run(
            [CANONICAL_OUTPUT_NAME], {INPUT_NAME: input_tensor}
        )[0]
        core_output = core_session.run([CORE_OUTPUT_NAME], {INPUT_NAME: input_tensor})[0]
        if tuple(core_output.shape) != CORE_OUTPUT_SHAPE:
            raise DerivationError(f"core ORT output shape changed: {core_output.shape}")
        core_full_output = crd_pixel_shuffle(core_output)
        dcr_output = dcr_session.run(
            [CANONICAL_OUTPUT_NAME], {INPUT_NAME: input_tensor}
        )[0]
        if tuple(canonical_output.shape) != FULL_OUTPUT_SHAPE:
            raise DerivationError(
                f"canonical ORT output shape changed: {canonical_output.shape}"
            )
        results.append(
            {
                "id": case_id,
                "input_shape": list(input_tensor.shape),
                "input_sha256_little_endian_float32": input_hash,
                "canonical_output_sha256_little_endian_float32": tensor_sha256(
                    canonical_output
                ),
                "core_raw_output_shape": list(core_output.shape),
                "core_raw_output_sha256_little_endian_float32": tensor_sha256(
                    core_output
                ),
                "core_plus_application_crd_pixel_shuffle_vs_canonical": _comparison(
                    core_full_output, canonical_output
                ),
                "dcr_full_vs_canonical": _comparison(dcr_output, canonical_output),
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


def _serialize_model(model: onnx.ModelProto) -> bytes:
    return model.SerializeToString(deterministic=True)


def _lab_relative(path: Path) -> str:
    return path.resolve().relative_to(LAB_ROOT.resolve()).as_posix()


def _model_record(
    path: Path,
    payload: bytes,
    model: onnx.ModelProto,
    transformations: list[str],
) -> dict[str, Any]:
    clip_initializers: list[dict[str, Any]] = []
    initializers = _initializer_map(model)
    for clip in (node for node in model.graph.node if node.op_type == "Clip"):
        for role, name in (("min", clip.input[1]), ("max", clip.input[2])):
            value = numpy_helper.to_array(initializers[name])
            clip_initializers.append(
                {
                    "clip_node": clip.name,
                    "role": role,
                    "initializer": name,
                    "shape": list(value.shape),
                    "dtype": str(value.dtype),
                    "value": float(np.asarray(value).reshape(-1)[0]),
                }
            )
    return {
        "path": _lab_relative(path),
        "bytes": len(payload),
        "sha256": sha256_bytes(payload),
        "input": {
            "name": model.graph.input[0].name,
            "dtype": "float32",
            "shape": _shape(model.graph.input[0]),
        },
        "output": {
            "name": model.graph.output[0].name,
            "dtype": "float32",
            "shape": _shape(model.graph.output[0]),
        },
        "operator_inventory": _op_inventory(model),
        "node_count": len(model.graph.node),
        "initializer_count": len(model.graph.initializer),
        "constant_node_count": sum(
            node.op_type == "Constant" for node in model.graph.node
        ),
        "clip_bound_initializers": clip_initializers,
        "transformations": transformations,
    }


def _write_atomic(path: Path, payload: bytes) -> None:
    if path.parent.resolve() != DERIVED_ROOT.resolve():
        raise DerivationError(f"refusing to write outside derived-models: {path}")
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
    tool_versions = _require_tool_versions()
    canonical_bytes = _require_file(
        CANONICAL_PATH, EXPECTED_CANONICAL_SHA256, "canonical ONNX"
    )
    plan_bytes = _require_file(P2_PLAN_PATH, EXPECTED_P2_PLAN_SHA256, "frozen P2 plan")
    canonical = onnx.load_model_from_string(canonical_bytes)
    onnx.checker.check_model(canonical, full_check=True)
    core, dcr, transform_metadata = derive_models(canonical)
    core_bytes = _serialize_model(core)
    dcr_bytes = _serialize_model(dcr)

    # Reparse the exact bytes that will be published before running ORT.
    onnx.checker.check_model(onnx.load_model_from_string(core_bytes), full_check=True)
    onnx.checker.check_model(onnx.load_model_from_string(dcr_bytes), full_check=True)
    validation = validate_equivalence(canonical_bytes, core_bytes, dcr_bytes)

    script_bytes = Path(__file__).read_bytes()
    manifest = {
        "schema_version": 1,
        "document_kind": "deterministic-onnx-derivation-manifest",
        "status": "pass",
        "p2_plan": {
            "path": _lab_relative(P2_PLAN_PATH),
            "bytes": len(plan_bytes),
            "sha256": sha256_bytes(plan_bytes),
            "expected_sha256": EXPECTED_P2_PLAN_SHA256,
        },
        "source": {
            "path": _lab_relative(CANONICAL_PATH),
            "bytes": len(canonical_bytes),
            "sha256": sha256_bytes(canonical_bytes),
            "expected_sha256": EXPECTED_CANONICAL_SHA256,
            "input": {
                "name": canonical.graph.input[0].name,
                "dtype": "float32",
                "shape": _shape(canonical.graph.input[0]),
            },
            "output": {
                "name": canonical.graph.output[0].name,
                "dtype": "float32",
                "shape": _shape(canonical.graph.output[0]),
            },
            "operator_inventory": _op_inventory(canonical),
            "node_count": len(canonical.graph.node),
            "initializer_count": len(canonical.graph.initializer),
        },
        "derivation_tool": {
            "path": _lab_relative(Path(__file__)),
            "sha256": sha256_bytes(script_bytes),
            "versions": tool_versions,
            "deterministic_protobuf_serialization": True,
            "path_overrides_allowed": False,
        },
        "frozen_correctness_gate": {
            "comparison": "abs(derived-canonical) <= 1e-4 + 1e-4 * abs(canonical)",
            "atol": ATOL,
            "rtol": RTOL,
            "allowed_mismatch_count": 0,
            "allowed_nonfinite_count": 0,
            "android_input_sha256_little_endian_float32": EXPECTED_ANDROID_INPUT_SHA256,
        },
        "transform_details": transform_metadata,
        "artifacts": {
            "fixed64_pre_shuffle_core": _model_record(
                CORE_MODEL_PATH,
                core_bytes,
                core,
                [
                    "freeze input shape to [1,3,64,64]",
                    "replace eight scalar Clip-bound Constant nodes with graph initializers",
                    "remove final DepthToSpace(mode=CRD, blocksize=2)",
                    "rename the exposed final Clip output to pre_shuffle_output",
                    "declare output shape [1,12,64,64]",
                ],
            ),
            "fixed64_dcr_full": _model_record(
                DCR_MODEL_PATH,
                dcr_bytes,
                dcr,
                [
                    "freeze input shape to [1,3,64,64] and output to [1,3,128,128]",
                    "replace eight scalar Clip-bound Constant nodes with graph initializers",
                    "gather final Conv weight output channels and bias with [0,4,8,1,5,9,2,6,10,3,7,11]",
                    "change DepthToSpace mode from CRD to DCR",
                ],
            ),
        },
        "pc_ort_validation": validation,
        "canonical_was_written": False,
    }
    manifest_bytes = (
        json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + "\n"
    ).encode("utf-8")
    _write_atomic(CORE_MODEL_PATH, core_bytes)
    _write_atomic(DCR_MODEL_PATH, dcr_bytes)
    _write_atomic(MANIFEST_PATH, manifest_bytes)
    return manifest


def main() -> int:
    manifest = generate()
    summary = {
        "status": manifest["status"],
        "source_sha256": manifest["source"]["sha256"],
        "p2_plan_sha256": manifest["p2_plan"]["sha256"],
        "core": {
            "path": manifest["artifacts"]["fixed64_pre_shuffle_core"]["path"],
            "sha256": manifest["artifacts"]["fixed64_pre_shuffle_core"]["sha256"],
        },
        "dcr": {
            "path": manifest["artifacts"]["fixed64_dcr_full"]["path"],
            "sha256": manifest["artifacts"]["fixed64_dcr_full"]["sha256"],
        },
        "validation_cases": len(manifest["pc_ort_validation"]["cases"]),
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
