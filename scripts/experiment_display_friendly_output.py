#!/usr/bin/env python3
"""Build and validate an output-layout wrapper around a frozen QuickSR graph.

The experiment keeps every learned weight and convolution unchanged. It can expose uint8 RGB or
float32 NHWC output so runtime profiling can separate tensor byte volume from layout conversion.
Generated ONNX files remain ignored local artifacts. Host equality does not establish QNN node
placement or device speed; those are separate strict-QNN device gates.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--variant",
        choices=("uint8-nhwc", "uint8-nchw", "float32-nhwc"),
        default="uint8-nhwc",
    )
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    return parser.parse_args()


def concrete_shape(value_info: onnx.ValueInfoProto) -> list[int]:
    dimensions: list[int] = []
    for dimension in value_info.type.tensor_type.shape.dim:
        if not dimension.HasField("dim_value") or dimension.dim_value <= 0:
            raise ValueError(f"Expected a fixed tensor shape for {value_info.name}")
        dimensions.append(dimension.dim_value)
    return dimensions


def build_display_graph(
    source_path: Path, output_path: Path, variant: str
) -> tuple[str, str, list[int], list[int]]:
    model = onnx.load(source_path)
    if len(model.graph.input) != 1 or len(model.graph.output) != 1:
        raise ValueError("Experiment requires exactly one graph input and one graph output")
    input_info = model.graph.input[0]
    output_info = model.graph.output[0]
    input_shape = concrete_shape(input_info)
    output_shape = concrete_shape(output_info)
    if len(input_shape) != 4 or len(output_shape) != 4 or output_shape[1] != 3:
        raise ValueError("Experiment requires fixed NCHW RGB input and output")

    float_output_name = output_info.name
    clipped_name = f"{float_output_name}__display_clipped"
    scaled_name = f"{float_output_name}__display_scaled"
    rounded_name = f"{float_output_name}__display_rounded"
    bounded_name = f"{float_output_name}__display_bounded"
    uint8_nchw_name = f"{float_output_name}__display_u8_nchw"
    display_output_name = f"{float_output_name}__display_u8_nhwc"

    if variant.startswith("uint8"):
        initializer_names = {item.name for item in model.graph.initializer}
        constants = {
            "__display_min_0": np.asarray(0.0, dtype=np.float32),
            "__display_max_1": np.asarray(1.0, dtype=np.float32),
            "__display_scale_255": np.asarray(255.0, dtype=np.float32),
            "__display_round_half": np.asarray(0.5, dtype=np.float32),
            "__display_max_255": np.asarray(255.0, dtype=np.float32),
        }
        collision = initializer_names.intersection(constants)
        if collision:
            raise ValueError(f"Generated initializer name collision: {sorted(collision)}")
        model.graph.initializer.extend(
            numpy_helper.from_array(value, name=name) for name, value in constants.items()
        )
        model.graph.node.extend(
            [
            helper.make_node(
                "Clip",
                [float_output_name, "__display_min_0", "__display_max_1"],
                [clipped_name],
                name="DisplayClip01",
            ),
            helper.make_node(
                "Mul",
                [clipped_name, "__display_scale_255"],
                [scaled_name],
                name="DisplayScale255",
            ),
            helper.make_node(
                "Add",
                [scaled_name, "__display_round_half"],
                [rounded_name],
                name="DisplayRoundHalfUp",
            ),
            helper.make_node(
                "Clip",
                [rounded_name, "__display_min_0", "__display_max_255"],
                [bounded_name],
                name="DisplayClip255",
            ),
            helper.make_node(
                "Cast",
                [bounded_name],
                [uint8_nchw_name],
                name="DisplayCastUint8",
                to=TensorProto.UINT8,
            ),
            ]
        )
    if variant == "uint8-nhwc":
        model.graph.node.extend(
            [helper.make_node(
                "Transpose",
                [uint8_nchw_name],
                [display_output_name],
                name="DisplayNchwToNhwc",
                perm=[0, 2, 3, 1],
            )]
        )
        candidate_output_name = display_output_name
        candidate_type = TensorProto.UINT8
        candidate_shape = [output_shape[0], output_shape[2], output_shape[3], output_shape[1]]
    elif variant == "uint8-nchw":
        candidate_output_name = uint8_nchw_name
        candidate_type = TensorProto.UINT8
        candidate_shape = output_shape
    else:
        float_nhwc_name = f"{float_output_name}__display_f32_nhwc"
        model.graph.node.extend(
            [helper.make_node(
                "Transpose",
                [float_output_name],
                [float_nhwc_name],
                name="DisplayFloatNchwToNhwc",
                perm=[0, 2, 3, 1],
            )]
        )
        candidate_output_name = float_nhwc_name
        candidate_type = TensorProto.FLOAT
        candidate_shape = [output_shape[0], output_shape[2], output_shape[3], output_shape[1]]
    del model.graph.output[:]
    model.graph.output.extend(
        [
            helper.make_tensor_value_info(
                candidate_output_name,
                candidate_type,
                candidate_shape,
            )
        ]
    )
    onnx.checker.check_model(model)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, output_path)
    return input_info.name, float_output_name, input_shape, output_shape


def median_ms(samples_ns: list[int]) -> float:
    ordered = sorted(samples_ns)
    return ordered[len(ordered) // 2] / 1_000_000.0


def run_session(session: ort.InferenceSession, input_name: str, value: np.ndarray, count: int) -> tuple[np.ndarray, list[int]]:
    output: np.ndarray | None = None
    samples: list[int] = []
    for _ in range(count):
        started = time.perf_counter_ns()
        output = session.run(None, {input_name: value})[0]
        samples.append(time.perf_counter_ns() - started)
    assert output is not None
    return output, samples


def main() -> int:
    args = parse_args()
    if args.warmup < 0 or args.iterations <= 0:
        raise ValueError("warmup must be non-negative and iterations must be positive")
    input_name, _, input_shape, output_shape = build_display_graph(
        args.source, args.output, args.variant
    )

    providers = ["CPUExecutionProvider"]
    source_session = ort.InferenceSession(str(args.source), providers=providers)
    display_session = ort.InferenceSession(str(args.output), providers=providers)
    rng = np.random.default_rng(0x51525354)
    input_value = rng.random(input_shape, dtype=np.float32)

    if args.warmup:
        run_session(source_session, input_name, input_value, args.warmup)
        run_session(display_session, input_name, input_value, args.warmup)
    source_output, source_samples = run_session(
        source_session, input_name, input_value, args.iterations
    )
    display_output, display_samples = run_session(
        display_session, input_name, input_value, args.iterations
    )

    if args.variant.startswith("uint8"):
        expected = np.clip(source_output, 0.0, 1.0) * np.float32(255.0) + np.float32(0.5)
        expected = np.clip(expected, 0.0, 255.0).astype(np.uint8)
        if args.variant == "uint8-nhwc":
            expected = np.transpose(expected, (0, 2, 3, 1))
        mismatches = int(np.count_nonzero(display_output != expected))
    else:
        expected = np.transpose(source_output, (0, 2, 3, 1))
        mismatches = int(np.count_nonzero(display_output != expected))
    float_bytes = int(np.prod(output_shape, dtype=np.int64) * np.dtype(np.float32).itemsize)
    display_bytes = int(display_output.nbytes)
    result = {
        "status": "PASS" if mismatches == 0 else "FAIL",
        "scope": "host_cpu_graph_boundary_experiment_not_qnn_placement_or_device_speed",
        "source": str(args.source),
        "generated": str(args.output),
        "variant": args.variant,
        "input_shape": input_shape,
        "source_output_shape": output_shape,
        "display_output_shape": list(display_output.shape),
        "display_output_dtype": str(display_output.dtype),
        "mismatched_values": mismatches,
        "source_float_output_bytes": float_bytes,
        "candidate_output_bytes": display_bytes,
        "boundary_byte_reduction_percent": round((1.0 - display_bytes / float_bytes) * 100.0, 3),
        "source_cpu_p50_ms": round(median_ms(source_samples), 3),
        "display_graph_cpu_p50_ms": round(median_ms(display_samples), 3),
    }
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if mismatches == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
