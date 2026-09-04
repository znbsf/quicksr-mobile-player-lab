#!/usr/bin/env python3
"""Probe exact two-way spatial tiling and batch aggregation for fixed QuickSR.

The four 3x3, stride-one convolutions give the model a four-input-pixel halo.
Two half-frame tiles can therefore be evaluated as one batch without adding a
whole-frame temporal wait.  This host CPU probe checks stitched equivalence and
reports direction-only timing; it does not predict QNN HTP scheduling.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    return parser.parse_args()


def fixed_shape(value_info: onnx.ValueInfoProto) -> list[int]:
    shape = []
    for dimension in value_info.type.tensor_type.shape.dim:
        if not dimension.HasField("dim_value"):
            raise ValueError(f"dynamic dimension is unsupported: {value_info.name}")
        shape.append(dimension.dim_value)
    return shape


def convolution_radius(model: onnx.ModelProto) -> int:
    radius = 0
    convolution_count = 0
    for node in model.graph.node:
        if node.op_type != "Conv":
            continue
        attributes = {attribute.name: attribute for attribute in node.attribute}
        kernel = list(attributes["kernel_shape"].ints)
        strides = list(attributes["strides"].ints)
        dilations = list(attributes["dilations"].ints)
        if kernel != [3, 3] or strides != [1, 1] or dilations != [1, 1]:
            raise ValueError("probe only supports 3x3 stride-one dilation-one convolutions")
        radius += 1
        convolution_count += 1
    if convolution_count == 0:
        raise ValueError("model has no convolution")
    return radius


def set_dimension(value_info: onnx.ValueInfoProto, axis: int, value: int) -> None:
    dimension = value_info.type.tensor_type.shape.dim[axis]
    dimension.ClearField("dim_param")
    dimension.dim_value = value


def make_tile_batch_model(
    source: Path, output: Path
) -> tuple[str, str, list[int], list[int], int, int]:
    model = onnx.load(source)
    if len(model.graph.input) != 1 or len(model.graph.output) != 1:
        raise ValueError("probe expects one public input and one public output")
    input_info = model.graph.input[0]
    output_info = model.graph.output[0]
    input_shape = fixed_shape(input_info)
    output_shape = fixed_shape(output_info)
    if input_shape[0] != 1 or input_shape[1] != 3 or output_shape[0] != 1:
        raise ValueError(f"unexpected model shapes: {input_shape} -> {output_shape}")
    scale_h = output_shape[2] // input_shape[2]
    scale_w = output_shape[3] // input_shape[3]
    if scale_h != scale_w or scale_w <= 0:
        raise ValueError(f"non-uniform scale is unsupported: {input_shape} -> {output_shape}")
    if input_shape[3] % 2:
        raise ValueError("input width must be even")

    halo = convolution_radius(model)
    core_width = input_shape[3] // 2
    tile_width = core_width + halo
    set_dimension(input_info, 0, 2)
    set_dimension(input_info, 3, tile_width)
    set_dimension(output_info, 0, 2)
    set_dimension(output_info, 3, tile_width * scale_w)
    output.parent.mkdir(parents=True, exist_ok=True)
    onnx.checker.check_model(model)
    onnx.save(model, output)
    return (
        input_info.name,
        output_info.name,
        input_shape,
        output_shape,
        halo,
        scale_w,
    )


def median_ms(action, warmup: int, iterations: int) -> float:
    for _ in range(warmup):
        action()
    elapsed = []
    for _ in range(iterations):
        started = time.perf_counter_ns()
        action()
        elapsed.append((time.perf_counter_ns() - started) / 1_000_000.0)
    return statistics.median(elapsed)


def main() -> None:
    args = parse_args()
    if args.warmup < 0 or args.iterations <= 0:
        raise ValueError("warmup must be non-negative and iterations must be positive")

    input_name, output_name, input_shape, output_shape, halo, scale = (
        make_tile_batch_model(args.source, args.output)
    )
    full_session = ort.InferenceSession(
        str(args.source), providers=["CPUExecutionProvider"]
    )
    tile_session = ort.InferenceSession(
        str(args.output), providers=["CPUExecutionProvider"]
    )
    rng = np.random.default_rng(20260904)
    frame = rng.random(input_shape, dtype=np.float32)
    core_width = input_shape[3] // 2
    left = frame[:, :, :, : core_width + halo]
    right = frame[:, :, :, core_width - halo :]
    tiles = np.concatenate((left, right), axis=0)

    full_output = full_session.run([output_name], {input_name: frame})[0]
    tile_output = tile_session.run([output_name], {input_name: tiles})[0]
    halo_output = halo * scale
    core_output = core_width * scale
    stitched = np.concatenate(
        (
            tile_output[0:1, :, :, :core_output],
            tile_output[1:2, :, :, halo_output : halo_output + core_output],
        ),
        axis=3,
    )
    max_abs_error = float(np.max(np.abs(full_output - stitched)))

    full_p50 = median_ms(
        lambda: full_session.run([output_name], {input_name: frame}),
        args.warmup,
        args.iterations,
    )
    tile_p50 = median_ms(
        lambda: tile_session.run([output_name], {input_name: tiles}),
        args.warmup,
        args.iterations,
    )
    status = "PASS" if max_abs_error <= 1e-6 else "FAIL"
    report = {
        "status": status,
        "scope": "host_cpu_spatial_batch_probe_not_qnn_device_performance",
        "source": str(args.source),
        "generated": str(args.output),
        "halo_input_pixels": halo,
        "tile_batch_shape": list(tiles.shape),
        "stitched_output_shape": list(stitched.shape),
        "max_abs_error": max_abs_error,
        "full_frame_cpu_p50_ms": round(full_p50, 3),
        "spatial_batch_cpu_p50_ms": round(tile_p50, 3),
        "throughput_gain_percent": round((full_p50 / tile_p50 - 1.0) * 100.0, 3),
        "extra_input_percent": round((tiles.size / frame.size - 1.0) * 100.0, 3),
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    if status != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
