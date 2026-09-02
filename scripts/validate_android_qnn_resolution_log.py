#!/usr/bin/env python3
"""Validate structured QuickSR Android QNN benchmark Logcat output."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any


TIMING_FIELDS = (
    "sessionSetupMs",
    "copyMs",
    "queueMs",
    "inputConversionMs",
    "inferenceMs",
    "tensorInputCopyMs",
    "ortRunMs",
    "tensorOutputCopyMs",
    "finiteScanMs",
    "outputConversionMs",
    "totalProcessingMs",
)

VALIDATOR_VERSION = "android-qnn-resolution-validator-v2"

# These values are emitted only by the post-session ``qnn_strict`` event.
# Configuration and frame-mode labels are application intent, not evidence that
# QNN registration, NPU selection, and strict no-CPU-fallback setup succeeded.
STRICT_QNN_EVIDENCE_FIELDS: dict[str, Any] = {
    "registrationStatus": "PASS",
    "npuSelectionStatus": "PASS",
    "providerConfigurationStatus": "PASS",
    "cpuEpFallbackDisabled": True,
    "backendType": "htp",
    "diagnosticOnly": False,
    "strictReady": True,
    "providerAssignmentVerified": False,
    "providerFallbackTraceCaptured": False,
    "evidenceScope": "SESSION_CONFIGURATION_NOT_PER_NODE_PLACEMENT_PROOF",
}


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def load_events(path: Path, run_id: str) -> tuple[list[dict[str, Any]], list[str]]:
    events: list[dict[str, Any]] = []
    parse_errors: list[str] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        start = line.find("{")
        if start < 0:
            continue
        try:
            value = json.loads(line[start:])
        except json.JSONDecodeError as failure:
            parse_errors.append(f"line {line_number}: {failure.msg}")
            continue
        if value.get("runId") == run_id:
            events.append(value)
    return events, parse_errors


def find_case(plan: dict[str, Any], case_id: str) -> dict[str, Any]:
    for case in plan["cases"]:
        if case["id"] == case_id:
            return case
    raise ValueError(f"unknown case: {case_id}")


def validate(plan: dict[str, Any], case: dict[str, Any], events: list[dict[str, Any]],
             run_id: str, parse_errors: list[str]) -> dict[str, Any]:
    failures: list[str] = []
    configurations = [event for event in events if event.get("event") == "configuration"]
    errors = [event for event in events if event.get("event") == "error"]
    if len(configurations) != 1:
        failures.append(f"expected exactly one configuration event, found {len(configurations)}")
    if errors:
        failures.extend(f"device error at {event.get('stage')}: {event.get('message')}" for event in errors)
    if parse_errors:
        failures.extend(f"unparsed telemetry {error}" for error in parse_errors)

    expected_dimensions = {
        "modelInputWidth": case["model_input"][0],
        "modelInputHeight": case["model_input"][1],
        "modelOutputWidth": case["model_output"][0],
        "modelOutputHeight": case["model_output"][1],
        "canvasWidth": case["canvas"][0],
        "canvasHeight": case["canvas"][1],
    }
    if configurations:
        configuration = configurations[0]
        expected_configuration = {
            "schemaVersion": 1,
            "runId": run_id,
            "mode": "QUICKSR_QNN",
            "tuning": "SUSTAINED",
            "profile": case["profile"],
            "qnnRuntimeExpected": True,
            **expected_dimensions,
        }
        for field, expected in expected_configuration.items():
            if configuration.get(field) != expected:
                failures.append(f"configuration {field}: expected {expected!r}, got {configuration.get(field)!r}")

    strict_events = [event for event in events if event.get("event") == "qnn_strict"]
    if len(strict_events) != 1:
        failures.append(f"expected exactly one qnn_strict event, found {len(strict_events)}")
    elif strict_events:
        strict_event = strict_events[0]
        for field, expected in {
            "schemaVersion": 1,
            "runId": run_id,
            "mode": "QNN_HTP",
            "profile": case["profile"],
        }.items():
            if strict_event.get(field) != expected:
                failures.append(
                    f"qnn strict {field}: expected {expected!r}, got {strict_event.get(field)!r}"
                )
        strict_evidence = strict_event.get("qnnStrict")
        if not isinstance(strict_evidence, dict):
            failures.append("qnn strict evidence is missing or not an object")
        else:
            for field, expected in STRICT_QNN_EVIDENCE_FIELDS.items():
                if strict_evidence.get(field) != expected:
                    failures.append(
                        f"qnn strict {field}: expected {expected!r}, got {strict_evidence.get(field)!r}"
                    )
            selected_npu_count = strict_evidence.get("selectedNpuDeviceCount")
            if not isinstance(selected_npu_count, int) or isinstance(selected_npu_count, bool) \
                    or selected_npu_count < 1:
                failures.append(
                    "qnn strict selectedNpuDeviceCount: expected a positive integer, "
                    f"got {selected_npu_count!r}"
                )

    samples_by_frame: dict[int, dict[str, Any]] = {}
    for batch in (event for event in events if event.get("event") == "frame_batch"):
        for field, expected in {
            "schemaVersion": 1,
            "mode": "QNN_HTP",
            "tuning": "SUSTAINED",
            "profile": case["profile"],
            "modelInputWidth": case["model_input"][0],
            "modelInputHeight": case["model_input"][1],
            "modelOutputWidth": case["model_output"][0],
            "modelOutputHeight": case["model_output"][1],
        }.items():
            if batch.get(field) != expected:
                failures.append(f"frame batch {field}: expected {expected!r}, got {batch.get(field)!r}")
        for sample in batch.get("samples", []):
            if isinstance(sample.get("frame"), int):
                samples_by_frame[sample["frame"]] = sample

    ordered = [samples_by_frame[key] for key in sorted(samples_by_frame)]
    warmup = int(plan["warmup_frames"])
    measured = ordered[warmup:]
    minimum = int(case["minimum_measured_frames"])
    if len(measured) < minimum:
        failures.append(f"measured frames after {warmup} warmup: required {minimum}, found {len(measured)}")

    metrics: dict[str, Any] = {
        "raw_frame_count": len(ordered),
        "warmup_frames_discarded": min(warmup, len(ordered)),
        "measured_frame_count": len(measured),
    }
    if measured:
        for field in TIMING_FIELDS:
            values = [float(sample[field]) for sample in measured if isinstance(sample.get(field), (int, float))]
            if len(values) != len(measured):
                failures.append(f"missing or non-numeric timing field: {field}")
            else:
                metrics[field] = {
                    "p50": percentile(values, 0.50),
                    "p95": percentile(values, 0.95),
                    "mean": sum(values) / len(values),
                }
        first, last = measured[0], measured[-1]
        observed_delta = last.get("observedNs", 0) - first.get("observedNs", 0)
        pts_delta = last.get("ptsUs", 0) - first.get("ptsUs", 0)
        if len(measured) > 1 and observed_delta > 0:
            metrics["observed_fps"] = (len(measured) - 1) * 1_000_000_000 / observed_delta
        else:
            failures.append("observed monotonic timestamps cannot produce throughput")
        if len(measured) > 1 and pts_delta > 0:
            metrics["sampled_pts_fps"] = (len(measured) - 1) * 1_000_000 / pts_delta

    performance_class = "unclassified"
    if "totalProcessingMs" in metrics and "observed_fps" in metrics:
        total_p95 = metrics["totalProcessingMs"]["p95"]
        observed_fps = metrics["observed_fps"]
        realtime_30 = plan["performance_classes"]["realtime_30"]
        realtime_24 = plan["performance_classes"]["realtime_24"]
        if total_p95 <= realtime_30["maximum_p95_total_ms"] and observed_fps >= realtime_30["minimum_observed_fps"]:
            performance_class = "realtime_30"
        elif total_p95 <= realtime_24["maximum_p95_total_ms"] and observed_fps >= realtime_24["minimum_observed_fps"]:
            performance_class = "realtime_24"
        else:
            performance_class = "offline"

    return {
        "schema_version": 2,
        "plan_id": plan["plan_id"],
        "case_id": case["id"],
        "run_id": run_id,
        "functional_gate": "PASS" if not failures else "FAIL",
        "performance_class": performance_class,
        "metrics": metrics,
        "failures": failures,
        "device_errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--case", dest="case_id", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    plan_bytes = args.plan.read_bytes()
    raw_log_bytes = args.log.read_bytes()
    plan = json.loads(plan_bytes.decode("utf-8"))
    case = find_case(plan, args.case_id)
    events, parse_errors = load_events(args.log, args.run_id)
    report = validate(plan, case, events, args.run_id, parse_errors)
    report["validator_version"] = VALIDATOR_VERSION
    report["plan_sha256"] = hashlib.sha256(plan_bytes).hexdigest()
    report["raw_log_sha256"] = hashlib.sha256(raw_log_bytes).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{report['functional_gate']} {case['id']}: {report['performance_class']} "
          f"({report['metrics']['measured_frame_count']} measured frames)")
    for failure in report["failures"]:
        print(f"  - {failure}")
    return 0 if report["functional_gate"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
