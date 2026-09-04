#!/usr/bin/env python3
"""Probe whether aggregating two frames into one unchanged QuickSR graph improves throughput.

The generated ONNX artifact only changes the fixed batch dimension from one to two. Learned
weights and operators remain unchanged. Host CPU results are directional; QNN HTP scheduling and
memory behavior require a physical-device experiment.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch", type=int, default=2)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    return parser.parse_args()


def fixed_shape(value_info: onnx.ValueInfoProto) -> list[int]:
    result: list[int] = []
    for dimension in value_info.type.tensor_type.shape.dim:
        if not dimension.HasField("dim_value") or dimension.dim_value <= 0:
            raise ValueError(f"Expected fixed shape for {value_info.name}")
        result.append(dimension.dim_value)
    return result


def rewrite_batch(source: Path, output: Path, batch: int) -> tuple[str, list[int], list[int]]:
    if batch <= 1:
        raise ValueError("batch must be greater than one")
    model = onnx.load(source)
    if len(model.graph.input) != 1 or len(model.graph.output) != 1:
        raise ValueError("Experiment requires one input and one output")
    input_info = model.graph.input[0]
    output_info = model.graph.output[0]
    input_shape = fixed_shape(input_info)
    output_shape = fixed_shape(output_info)
    if input_shape[0] != 1 or output_shape[0] != 1:
        raise ValueError("Source graph must have fixed batch one")
    input_info.type.tensor_type.shape.dim[0].dim_value = batch
    output_info.type.tensor_type.shape.dim[0].dim_value = batch
    for value_info in model.graph.value_info:
        tensor_shape = value_info.type.tensor_type.shape
        if tensor_shape.dim and tensor_shape.dim[0].HasField("dim_value"):
            if tensor_shape.dim[0].dim_value == 1:
                tensor_shape.dim[0].dim_value = batch
    onnx.checker.check_model(model)
    output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, output)
    return input_info.name, [batch, *input_shape[1:]], [batch, *output_shape[1:]]


def median_ms(samples_ns: list[int]) -> float:
    ordered = sorted(samples_ns)
    return ordered[len(ordered) // 2] / 1_000_000.0


def timed_run(
    session: ort.InferenceSession,
    input_name: str,
    input_value: np.ndarray,
    count: int,
) -> tuple[np.ndarray, list[int]]:
    result: np.ndarray | None = None
    samples: list[int] = []
    for _ in range(count):
        started = time.perf_counter_ns()
        result = session.run(None, {input_name: input_value})[0]
        samples.append(time.perf_counter_ns() - started)
    assert result is not None
    return result, samples


def main() -> int:
    args = parse_args()
    if args.warmup < 0 or args.iterations <= 0:
        raise ValueError("warmup must be non-negative and iterations must be positive")
    input_name, input_shape, output_shape = rewrite_batch(
        args.source, args.output, args.batch
    )
    providers = ["CPUExecutionProvider"]
    source_session = ort.InferenceSession(str(args.source), providers=providers)
    batch_session = ort.InferenceSession(str(args.output), providers=providers)
    rng = np.random.default_rng(0x42415443)
    batch_input = rng.random(input_shape, dtype=np.float32)

    def run_separate() -> tuple[np.ndarray, int]:
        outputs = []
        started = time.perf_counter_ns()
        for frame in range(args.batch):
            outputs.append(
                source_session.run(
                    None, {input_name: batch_input[frame : frame + 1]}
                )[0]
            )
        return np.concatenate(outputs, axis=0), time.perf_counter_ns() - started

    for _ in range(args.warmup):
        run_separate()
        batch_session.run(None, {input_name: batch_input})

    separate_samples: list[int] = []
    separate_output: np.ndarray | None = None
    for _ in range(args.iterations):
        separate_output, elapsed = run_separate()
        separate_samples.append(elapsed)
    batch_output, batch_samples = timed_run(
        batch_session, input_name, batch_input, args.iterations
    )
    assert separate_output is not None
    max_abs_error = float(np.max(np.abs(separate_output - batch_output)))
    separate_ms = median_ms(separate_samples)
    batch_ms = median_ms(batch_samples)
    throughput_gain = separate_ms / batch_ms - 1.0
    result = {
        "status": "PASS" if max_abs_error <= 1e-6 else "FAIL",
        "scope": "host_cpu_batch_probe_not_qnn_device_performance",
        "source": str(args.source),
        "generated": str(args.output),
        "batch": args.batch,
        "input_shape": input_shape,
        "output_shape": output_shape,
        "max_abs_error": max_abs_error,
        "separate_frames_cpu_p50_ms": round(separate_ms, 3),
        "batch_cpu_p50_ms": round(batch_ms, 3),
        "batch_throughput_gain_percent": round(throughput_gain * 100.0, 3),
        "batch_output_bytes": int(batch_output.nbytes),
    }
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
