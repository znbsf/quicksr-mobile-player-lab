#!/usr/bin/env python3
"""Validate structured QuickSR Android QNN benchmark Logcat output."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Any


RAW_TIMESTAMP_FIELDS = (
    "acceptedNs",
    "readbackReadyProxyNs",
    "inputCopyStartedNs",
    "inputCopiedNs",
    "inputHashStartedNs",
    "inputHashFinishedNs",
    "workerStartedNs",
    "outputTensorAcquireStartedNs",
    "outputTensorSlotAcquiredNs",
    "outputTensorReadyNs",
    "preprocessFinishedNs",
    "sessionReadyNs",
    "inferenceStartedNs",
    "inferenceFinishedNs",
    "outputPackStartedNs",
    "outputPackFinishedNs",
    "outputHashStartedNs",
    "outputHashFinishedNs",
    "directBufferCopyStartedNs",
    "directBufferCopyFinishedNs",
    "outputReadyNs",
    "glUploadStartedNs",
    "glUploadFinishedNs",
    "outputSubmittedProxyNs",
)

RAW_DURATION_FIELDS = (
    "tensorInputCopyNs",
    "ortRunNs",
    "tensorOutputCopyNs",
    "finiteScanNs",
)

STAGE_INTERVALS = {
    "acceptedToReadbackProxyNs": ("acceptedNs", "readbackReadyProxyNs"),
    "inputCopyNs": ("inputCopyStartedNs", "inputCopiedNs"),
    "inputHashNs": ("inputHashStartedNs", "inputHashFinishedNs"),
    "workerQueueWaitNs": ("inputHashFinishedNs", "workerStartedNs"),
    "outputTensorSlotWaitNs": ("outputTensorAcquireStartedNs", "outputTensorSlotAcquiredNs"),
    "outputTensorPrepareNs": ("outputTensorSlotAcquiredNs", "outputTensorReadyNs"),
    "preprocessNs": ("outputTensorReadyNs", "preprocessFinishedNs"),
    "sessionSetupNs": ("preprocessFinishedNs", "sessionReadyNs"),
    "sessionReadyToInferenceNs": ("sessionReadyNs", "inferenceStartedNs"),
    "inferenceCallerWallNs": ("inferenceStartedNs", "inferenceFinishedNs"),
    "postInferenceToOutputPackNs": ("inferenceFinishedNs", "outputPackStartedNs"),
    "outputPackNs": ("outputPackStartedNs", "outputPackFinishedNs"),
    "outputHashNs": ("outputHashStartedNs", "outputHashFinishedNs"),
    "directBufferCopyNs": ("directBufferCopyStartedNs", "directBufferCopyFinishedNs"),
    "outputReadyToGlSubmitQueueNs": ("outputReadyNs", "glUploadStartedNs"),
    "glUploadOutputSubmitProxyNs": ("glUploadStartedNs", "glUploadFinishedNs"),
    "effectTotalToOutputSubmitProxyNs": ("acceptedNs", "outputSubmittedProxyNs"),
}

COUNTER_FIELDS = (
    "acceptedCount",
    "processedCount",
    "lateCount",
    "droppedCount",
    "bypassedCount",
    "currentQueueDepth",
    "maxQueueDepth",
    "flushCount",
    "seekProxyCount",
)

MEASUREMENT_CONTRACT = {
    "queuePolicy": "bounded_blocking_backpressure",
    "workerQueueCapacity": 2,
    "workerCleanupReservedSlots": 1,
    "media3EffectQueueCapacity": 6,
    "media3PendingPboQueueCapacity": 1,
    "workerQueueDepthMeasurement": "measured_frame_admission_queue",
    "media3QueueDepthMeasurement": "unmeasured_fixed_library_internal_queue",
    "acceptedMeasurement": "measured_queue_input_callback",
    "readbackMeasurement": "proxy_process_image_callback_after_media3_readback",
    "preprocessMeasurement": "measured_cpu_elapsed_realtime_ns",
    "outputTensorSlotWaitMeasurement": "measured_bounded_semaphore_wait",
    "outputTensorPrepareMeasurement": "measured_pool_or_allocation_time",
    "ortMeasurement": "measured_caller_wall_ns_not_npu_kernel",
    "outputPackMeasurement": "measured_cpu_elapsed_realtime_ns",
    "directBufferCopyMeasurement": "measured_cpu_elapsed_realtime_ns",
    "outputSubmitMeasurement": "proxy_finish_processing_callback",
    "seekMeasurement": "proxy_media3_flush",
    "ptsWallClockDriftMeasurement": "proxy_generation_relative_to_first_accepted_frame",
    "surfaceFlingerLatchMeasurement": "unmeasured",
    "finalDisplayMeasurement": "unmeasured",
}

VALIDATOR_VERSION = "android-qnn-resolution-validator-v9"

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


def is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def summarize(values: list[float]) -> dict[str, float]:
    return {
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values),
        "mean": sum(values) / len(values),
    }


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
    cadence_mode = "OFF"
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
        cadence_mode = configuration.get("cadenceMode", "OFF")
        if cadence_mode not in {"OFF", "CONTENT_AWARE_V1"}:
            failures.append(f"configuration cadenceMode is invalid: {cadence_mode!r}")
        postprocess_mode = configuration.get("postprocessMode")
        if postprocess_mode not in {"SERIAL", "OVERLAP"}:
            failures.append(
                "configuration postprocessMode: expected SERIAL or OVERLAP, "
                f"got {postprocess_mode!r}"
            )
        overlap_enabled = postprocess_mode == "OVERLAP"
        output_packer = configuration.get("outputPacker", "JAVA")
        if output_packer not in {"JAVA", "NATIVE_NEON"}:
            failures.append(
                "configuration outputPacker: expected JAVA or NATIVE_NEON, "
                f"got {output_packer!r}"
            )
        expected_packer_self_test = "PASS" if output_packer == "NATIVE_NEON" else "NOT_RUN"
        if configuration.get("outputPackerSelfTest", "NOT_RUN") != expected_packer_self_test:
            failures.append(
                "configuration outputPackerSelfTest: expected "
                f"{expected_packer_self_test!r}, got {configuration.get('outputPackerSelfTest')!r}"
            )
        output_tensor_bytes = (
            case["model_output"][0] * case["model_output"][1] * 3 * 4
        )
        deferred_output_copy = configuration.get("deferredOutputCopy")
        if not isinstance(deferred_output_copy, bool):
            failures.append(
                "configuration deferredOutputCopy: expected a boolean, "
                f"got {deferred_output_copy!r}"
            )
            deferred_output_copy = False
        if deferred_output_copy:
            if not overlap_enabled:
                failures.append("deferred output copy requires OVERLAP postprocess mode")
            if output_packer != "JAVA":
                failures.append("deferred output copy requires the JAVA output packer")
            if configuration.get("outputTensorLayout") != "NHWC":
                failures.append("deferred output copy requires NHWC output layout")
            if "captureSelectorKind" in configuration:
                failures.append("deferred output copy is incompatible with tensor capture")
        expected_output_copy_measurement = (
            "measured_postprocess_thread_bulk_copy"
            if deferred_output_copy
            else "measured_inference_thread_bulk_copy"
        )
        expected_gl_upload_route = (
            "DIRECT_MEDIA3_OUTPUT_TEXTURE"
            if case["model_output"] == case["canvas"]
            else "INTERMEDIATE_NEURAL_TEXTURE_THEN_SCALE_BLIT"
        )
        pbo_upload = configuration.get("pboUpload")
        if not isinstance(pbo_upload, bool):
            failures.append(
                f"configuration pboUpload: expected a boolean, got {pbo_upload!r}"
            )
            pbo_upload = False
        if pbo_upload and expected_gl_upload_route != "DIRECT_MEDIA3_OUTPUT_TEXTURE":
            failures.append("PBO upload is restricted to the direct output texture route")
        expected_gl_upload_measurement = (
            "measured_cpu_pbo_stage_and_gl_upload_submission_not_gpu_completion"
            if pbo_upload
            else "measured_cpu_gl_upload_and_optional_scale_blit_submission_not_gpu_completion"
        )
        expected_configuration = {
            "schemaVersion": 2,
            "runId": run_id,
            "mode": "QUICKSR_QNN",
            "tuning": "SUSTAINED",
            "profile": case["profile"],
            "qnnRuntimeExpected": True,
            "postprocessQueueCapacity": 1 if overlap_enabled else 0,
            "outputTensorSlotCount": 2 if overlap_enabled else 1,
            "outputTensorBytesPerSlot": output_tensor_bytes,
            "additionalOverlapTensorBytes": output_tensor_bytes if overlap_enabled else 0,
            "deferredOutputCopy": deferred_output_copy,
            "pinnedOrtOutputTensorSlotCount": 2 if deferred_output_copy else 1,
            "additionalPinnedOrtOutputBytes": (
                output_tensor_bytes if deferred_output_copy else 0
            ),
            "tensorOutputCopyMeasurement": expected_output_copy_measurement,
            "glUploadRoute": expected_gl_upload_route,
            "pboUpload": pbo_upload,
            "glUploadPboSlotCount": 2 if pbo_upload else 0,
            "additionalGlUploadPboBytes": (
                case["model_output"][0] * case["model_output"][1] * 4 * 2
                if pbo_upload
                else 0
            ),
            "glUploadMeasurement": expected_gl_upload_measurement,
            **expected_dimensions,
            **MEASUREMENT_CONTRACT,
        }
        for field, expected in expected_configuration.items():
            if configuration.get(field) != expected:
                failures.append(f"configuration {field}: expected {expected!r}, got {configuration.get(field)!r}")
        for field in ("modelSha256", "sourceIdentitySha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", str(configuration.get(field, ""))):
                failures.append(f"configuration {field}: expected lowercase SHA-256")
        for field in ("modelVariant", "prototypeBuildId", "targetAbi"):
            if not isinstance(configuration.get(field), str) or not configuration[field].strip():
                failures.append(f"configuration {field}: expected non-empty string")
        if cadence_mode == "CONTENT_AWARE_V1":
            expected_cadence_configuration = {
                "cadenceAnalyzerVersion": "anime-cadence-analyzer-v1",
                "cadenceMaxReuseStreak": 2,
                "cadenceSubtitleDenseLumaDeltaThreshold": 48,
                "cadenceSubtitleDenseEdgeDeltaThreshold": 32,
                "cadenceSubtitleDenseLocalContrastThreshold": 24,
                "cadenceSubtitleDenseMinChangedPixels": 1,
                "cadenceSubtitleDenseMinHighContrastPixels": 1,
            }
            for field, expected in expected_cadence_configuration.items():
                if configuration.get(field) != expected:
                    failures.append(
                        f"configuration {field}: expected {expected!r}, "
                        f"got {configuration.get(field)!r}"
                    )

    strict_events = [event for event in events if event.get("event") == "qnn_strict"]
    if len(strict_events) != 1:
        failures.append(f"expected exactly one qnn_strict event, found {len(strict_events)}")
    elif strict_events:
        strict_event = strict_events[0]
        for field, expected in {
            "schemaVersion": 2,
            "runId": run_id,
            "mode": "QNN_HTP",
            "profile": case["profile"],
        }.items():
            if strict_event.get(field) != expected:
                failures.append(
                    f"qnn strict {field}: expected {expected!r}, got {strict_event.get(field)!r}"
                )
        if configurations and strict_event.get("modelVariant") != configurations[0].get("modelVariant"):
            failures.append(
                "qnn strict modelVariant does not match the configured modelVariant"
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
            if configurations:
                configured_deferred_copy = configurations[0].get("deferredOutputCopy", False)
                if strict_evidence.get("deferredOutputCopy") != configured_deferred_copy:
                    failures.append(
                        "qnn strict deferredOutputCopy does not match configuration"
                    )
                expected_pinned_slots = 2 if configured_deferred_copy else 1
                if strict_evidence.get("pinnedOrtOutputTensorSlotCount") != expected_pinned_slots:
                    failures.append(
                        "qnn strict pinnedOrtOutputTensorSlotCount does not match configuration"
                    )
                if strict_evidence.get("glUploadRoute") != configurations[0].get(
                    "glUploadRoute"
                ):
                    failures.append("qnn strict glUploadRoute does not match configuration")
                if strict_evidence.get("pboUpload") != configurations[0].get(
                    "pboUpload"
                ):
                    failures.append("qnn strict pboUpload does not match configuration")
                if strict_evidence.get("glUploadPboSlotCount") != configurations[0].get(
                    "glUploadPboSlotCount"
                ):
                    failures.append(
                        "qnn strict glUploadPboSlotCount does not match configuration"
                    )

    samples_by_frame: dict[int, dict[str, Any]] = {}
    ordered_samples: list[dict[str, Any]] = []
    for batch in (event for event in events if event.get("event") == "frame_batch"):
        configured_postprocess_mode = (
            configurations[0].get("postprocessMode") if configurations else None
        )
        configured_cadence_mode = (
            configurations[0].get("cadenceMode", "OFF") if configurations else "OFF"
        )
        configured_output_packer = (
            configurations[0].get("outputPacker", "JAVA") if configurations else "JAVA"
        )
        for field, expected in {
            "schemaVersion": 2,
            "mode": "QNN_HTP",
            "tuning": "SUSTAINED",
            "profile": case["profile"],
            "postprocessMode": configured_postprocess_mode,
            "outputPacker": configured_output_packer,
            "modelInputWidth": case["model_input"][0],
            "modelInputHeight": case["model_input"][1],
            "modelOutputWidth": case["model_output"][0],
            "modelOutputHeight": case["model_output"][1],
        }.items():
            observed = batch.get(field, "JAVA" if field == "outputPacker" else None)
            if observed != expected:
                failures.append(f"frame batch {field}: expected {expected!r}, got {observed!r}")
        if batch.get("cadenceMode", "OFF") != configured_cadence_mode:
            failures.append(
                "frame batch cadenceMode: expected "
                f"{configured_cadence_mode!r}, got {batch.get('cadenceMode')!r}"
            )
        samples = batch.get("samples")
        if not isinstance(samples, list):
            failures.append("frame batch samples are missing or not an array")
            continue
        for sample in samples:
            frame_id = sample.get("frameId")
            if not is_int(frame_id) or frame_id <= 0:
                failures.append(f"frame sample has invalid frameId: {frame_id!r}")
                continue
            if frame_id in samples_by_frame:
                failures.append(f"duplicate frameId: {frame_id}")
                continue
            samples_by_frame[frame_id] = sample
            ordered_samples.append(sample)

            for field in RAW_TIMESTAMP_FIELDS:
                value = sample.get(field)
                if not is_int(value) or value <= 0:
                    failures.append(f"frame {frame_id} missing positive raw timestamp: {field}")
            available_timestamps = [sample.get(field) for field in RAW_TIMESTAMP_FIELDS]
            if all(is_int(value) and value > 0 for value in available_timestamps):
                if available_timestamps != sorted(available_timestamps):
                    failures.append(f"frame {frame_id} raw stage timestamps are not monotonic")
                if sample.get("observedNs") != sample.get("outputSubmittedProxyNs"):
                    failures.append(
                        f"frame {frame_id} observedNs must equal outputSubmittedProxyNs"
                    )
            for field in RAW_DURATION_FIELDS:
                value = sample.get(field)
                if not is_int(value) or value < 0:
                    failures.append(f"frame {frame_id} missing non-negative raw duration: {field}")
            for field in COUNTER_FIELDS:
                value = sample.get(field)
                if not is_int(value) or value < 0:
                    failures.append(f"frame {frame_id} missing non-negative counter: {field}")
            for field in ("inputCrc32", "outputCrc32"):
                if not re.fullmatch(r"[0-9a-f]{8}", str(sample.get(field, ""))):
                    failures.append(f"frame {frame_id} has invalid {field}")
            if sample.get("frame") != frame_id:
                failures.append(f"frame {frame_id} legacy frame identity does not match frameId")
            if not is_int(sample.get("generation")) or sample["generation"] < 0:
                failures.append(f"frame {frame_id} has invalid generation")
            if not is_int(sample.get("generationFrameId")) \
                    or sample["generationFrameId"] <= 0:
                failures.append(f"frame {frame_id} has invalid generationFrameId")
            if not isinstance(sample.get("late"), bool):
                failures.append(f"frame {frame_id} has invalid late flag")
            if not is_int(sample.get("ptsWallClockDriftNs")):
                failures.append(f"frame {frame_id} has invalid ptsWallClockDriftNs")
            if is_int(sample.get("maxQueueDepth")) \
                    and sample["maxQueueDepth"] > MEASUREMENT_CONTRACT["workerQueueCapacity"]:
                failures.append(f"frame {frame_id} exceeds bounded worker queue capacity")
            if configured_cadence_mode == "CONTENT_AWARE_V1":
                decision = sample.get("cadenceDecision")
                if decision not in {"PROCESS", "REUSE"}:
                    failures.append(f"frame {frame_id} has invalid cadenceDecision")
                if not isinstance(sample.get("cadenceReason"), str):
                    failures.append(f"frame {frame_id} has invalid cadenceReason")
                streak = sample.get("reuseStreak")
                if not is_int(streak) or streak < 0 or streak > 2:
                    failures.append(f"frame {frame_id} exceeds cadence reuse streak")
                if not is_int(sample.get("cadenceAnalysisNs")) \
                        or sample["cadenceAnalysisNs"] < 0:
                    failures.append(f"frame {frame_id} has invalid cadenceAnalysisNs")
                for score_field in ("sceneScore", "subtitleScore", "motionScore"):
                    score = sample.get(score_field)
                    if not isinstance(score, (int, float)) or isinstance(score, bool) \
                            or not math.isfinite(score) or score < 0:
                        failures.append(f"frame {frame_id} has invalid {score_field}")
                if decision == "REUSE":
                    if sample.get("cadenceReferenceGeneration") != sample.get("generation"):
                        failures.append(f"frame {frame_id} cadence reuse crosses generation")
                    if sample.get("cadenceReferenceStreamEpoch") \
                            != sample.get("cadenceStreamEpoch"):
                        failures.append(f"frame {frame_id} cadence reuse crosses input stream")
                    reference_frame = sample.get("cadenceReferenceFrameId")
                    if not is_int(reference_frame) or reference_frame <= 0 \
                            or reference_frame >= frame_id:
                        failures.append(f"frame {frame_id} has invalid cadence reference frame")

    ordered = ordered_samples
    if ordered and ordered[0]["frameId"] != 1:
        failures.append(
            f"processed frame sequence must start at frameId 1, got {ordered[0]['frameId']}"
        )
    for previous, current in zip(ordered, ordered[1:]):
        if current["frameId"] != previous["frameId"] + 1:
            failures.append(
                "processed frame identities are missing or out of order: "
                f"{previous['frameId']} -> {current['frameId']}"
            )
        if current.get("generation") == previous.get("generation"):
            if current.get("generationFrameId", 0) <= previous.get("generationFrameId", 0):
                failures.append("generation-local frame identities are not strictly increasing")
            if current.get("ptsUs", -1) < previous.get("ptsUs", -1):
                failures.append("PTS decreases within one generation")
        elif current.get("generation", -1) <= previous.get("generation", -1):
            failures.append("generation does not increase across a flush boundary")
        for field in ("acceptedCount", "processedCount", "lateCount", "droppedCount",
                      "bypassedCount", "maxQueueDepth", "flushCount", "seekProxyCount"):
            if is_int(previous.get(field)) and is_int(current.get(field)) \
                    and current[field] < previous[field]:
                failures.append(f"pipeline counter decreases at frame {current['frameId']}: {field}")

    pipeline_snapshots = [event for event in events if event.get("event") == "pipeline_snapshot"]
    for snapshot in pipeline_snapshots:
        if snapshot.get("schemaVersion") != 2:
            failures.append("pipeline snapshot schemaVersion must be 2")
        for field in COUNTER_FIELDS:
            if not is_int(snapshot.get(field)) or snapshot[field] < 0:
                failures.append(f"pipeline snapshot has invalid counter: {field}")
        if is_int(snapshot.get("maxQueueDepth")) \
                and snapshot["maxQueueDepth"] > MEASUREMENT_CONTRACT["workerQueueCapacity"]:
            failures.append("pipeline snapshot exceeds bounded worker queue capacity")
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
        if cadence_mode == "CONTENT_AWARE_V1":
            cadence_decisions = [sample.get("cadenceDecision") for sample in measured]
            metrics["cadence_processed_count"] = cadence_decisions.count("PROCESS")
            metrics["cadence_reused_count"] = cadence_decisions.count("REUSE")
            metrics["cadence_inference_reduction_fraction"] = (
                cadence_decisions.count("REUSE") / len(cadence_decisions)
            )
        for metric_name, (start_field, end_field) in STAGE_INTERVALS.items():
            values = [float(sample[end_field] - sample[start_field]) for sample in measured
                      if is_int(sample.get(start_field)) and is_int(sample.get(end_field))]
            if len(values) != len(measured) or any(value < 0 for value in values):
                failures.append(f"missing, non-numeric or negative raw stage interval: {metric_name}")
            else:
                metrics[metric_name] = summarize(values)
        for field in RAW_DURATION_FIELDS:
            values = [float(sample[field]) for sample in measured if is_int(sample.get(field))]
            if len(values) != len(measured):
                failures.append(f"missing or non-numeric raw duration: {field}")
            else:
                metrics[field] = summarize(values)
        drift_values = [float(sample["ptsWallClockDriftNs"]) for sample in measured
                        if is_int(sample.get("ptsWallClockDriftNs"))]
        if len(drift_values) == len(measured):
            metrics["ptsWallClockDriftNs"] = summarize(drift_values)
        first, last = measured[0], measured[-1]
        observed_delta = last.get("outputSubmittedProxyNs", 0) - first.get("outputSubmittedProxyNs", 0)
        pts_delta = last.get("ptsUs", 0) - first.get("ptsUs", 0)
        if len(measured) > 1 and observed_delta > 0:
            metrics["observed_fps"] = (len(measured) - 1) * 1_000_000_000 / observed_delta
        else:
            failures.append("observed monotonic timestamps cannot produce throughput")
        if len(measured) > 1 and pts_delta > 0:
            metrics["sampled_pts_fps"] = (len(measured) - 1) * 1_000_000 / pts_delta

        observed_pts_intervals = []
        for previous, current in zip(measured, measured[1:]):
            previous_epoch = previous.get("cadenceStreamEpoch")
            current_epoch = current.get("cadenceStreamEpoch")
            same_stream = (
                previous_epoch == current_epoch
                if is_int(previous_epoch) and is_int(current_epoch)
                else previous.get("generation") == current.get("generation")
            )
            interval = current.get("ptsUs", 0) - previous.get("ptsUs", 0)
            if same_stream and interval > 0:
                observed_pts_intervals.append(float(interval))
        expected_source_fps = plan.get("expected_source_fps")
        if not isinstance(expected_source_fps, (int, float)) or isinstance(
                expected_source_fps, bool) or not math.isfinite(expected_source_fps) \
                or expected_source_fps <= 0:
            failures.append("plan expected_source_fps must be a positive finite number")
        elif observed_pts_intervals:
            nominal_interval_us = 1_000_000 / float(expected_source_fps)
            observed_median_interval_us = percentile(observed_pts_intervals, 0.50)
            metrics["nominal_pts_interval_us"] = nominal_interval_us
            metrics["nominal_pts_fps"] = float(expected_source_fps)
            metrics["observed_median_pts_interval_us"] = observed_median_interval_us
            metrics["source_pts_interval_ratio"] = (
                observed_median_interval_us / nominal_interval_us
            )
            if "sampled_pts_fps" in metrics:
                metrics["sampled_pts_coverage_ratio"] = (
                    metrics["sampled_pts_fps"] / metrics["nominal_pts_fps"]
                )
            if "observed_fps" in metrics:
                metrics["observed_source_cadence_ratio"] = (
                    metrics["observed_fps"] / metrics["nominal_pts_fps"]
                )

    performance_class = "unclassified"
    source_cadence_class = "unclassified"
    source_cadence_gate = "UNCLASSIFIED"
    source_pts_interval_gate = "UNCLASSIFIED"
    latency_class = "unclassified"
    if ("nominal_pts_fps" in metrics
            and "source_pts_interval_ratio" in metrics):
        maximum_interval_error = float(
            plan.get("maximum_source_pts_interval_error_ratio", 0.005)
        )
        source_pts_interval_gate = (
            "PASS"
            if abs(metrics["source_pts_interval_ratio"] - 1.0) <= maximum_interval_error
            else "FAIL"
        )
    if cadence_mode == "CONTENT_AWARE_V1":
        performance_class = "cadence_effect_proxy_unclassified"
    elif ("effectTotalToOutputSubmitProxyNs" in metrics
          and "observed_fps" in metrics
          and "nominal_pts_fps" in metrics
          and "sampled_pts_coverage_ratio" in metrics
          and "observed_source_cadence_ratio" in metrics):
        total_p95 = metrics["effectTotalToOutputSubmitProxyNs"]["p95"] / 1_000_000
        nominal_fps = metrics["nominal_pts_fps"]
        minimum_ratio = float(plan.get("minimum_source_cadence_ratio", 0.995))
        cadence_ratio = min(
            metrics["sampled_pts_coverage_ratio"],
            metrics["observed_source_cadence_ratio"],
        )
        cadence_matched = (
            cadence_ratio >= minimum_ratio and source_pts_interval_gate == "PASS"
        )
        if source_pts_interval_gate == "FAIL":
            source_cadence_class = "source_pts_interval_mismatch"
        else:
            source_cadence_class = (
                "source_cadence_matched" if cadence_matched else "below_source_cadence"
            )
        source_cadence_gate = "PASS" if cadence_matched else "FAIL"
        nominal_frame_ms = 1_000 / nominal_fps
        metrics["effect_p95_frame_budget_ratio"] = total_p95 / nominal_frame_ms
        latency_class = (
            "within_nominal_frame_interval"
            if total_p95 <= nominal_frame_ms
            else "pipelined_over_nominal_frame_interval"
        )
        realtime_30 = plan["performance_classes"]["realtime_30"]
        realtime_24 = plan["performance_classes"]["realtime_24"]
        if not cadence_matched:
            performance_class = "effect_proxy_below_source_cadence"
        elif nominal_fps >= realtime_30["minimum_observed_fps"]:
            performance_class = "effect_proxy_realtime_30_throughput"
        elif nominal_fps >= realtime_24["minimum_observed_fps"]:
            performance_class = "effect_proxy_realtime_24_throughput"
        else:
            performance_class = "effect_proxy_source_cadence_throughput"

    latest_counters: dict[str, int] = {}
    counter_sources = ordered + pipeline_snapshots
    if counter_sources:
        latest = max(
            counter_sources,
            key=lambda item: (
                item.get("acceptedCount") if is_int(item.get("acceptedCount")) else -1,
                sum(item.get(field) if is_int(item.get(field)) else -1
                    for field in ("processedCount", "droppedCount", "bypassedCount")),
                item.get("observedNs") if is_int(item.get("observedNs")) else -1,
            ),
        )
        for field in COUNTER_FIELDS:
            if is_int(latest.get(field)):
                latest_counters[field] = latest[field]
        if latest_counters.get("processedCount", -1) != len(ordered):
            failures.append("processed counter does not equal emitted processed frame samples")
        accepted_count = latest_counters.get("acceptedCount")
        terminal_count = sum(latest_counters.get(field, 0)
                             for field in ("processedCount", "droppedCount", "bypassedCount"))
        if accepted_count is not None and terminal_count > accepted_count:
            failures.append("terminal frame counters exceed acceptedCount")
        if latest_counters.get("lateCount", 0) > latest_counters.get("processedCount", 0):
            failures.append("lateCount exceeds processedCount")
        if latest_counters.get("droppedCount", 0) != 0:
            failures.append("resolution-matrix run contains dropped frames")
        if latest_counters.get("bypassedCount", 0) != 0:
            failures.append("resolution-matrix run contains bypassed frames")

    functional_gate = "PASS" if not failures else "FAIL"
    overall_gate = (
        "PASS"
        if functional_gate == "PASS"
        and (cadence_mode != "OFF" or source_cadence_gate == "PASS")
        else "FAIL"
    )
    return {
        "schema_version": 3,
        "plan_id": plan["plan_id"],
        "case_id": case["id"],
        "run_id": run_id,
        "postprocess_mode": configurations[0].get("postprocessMode") if configurations else None,
        "output_packer": configurations[0].get("outputPacker", "JAVA") if configurations else None,
        "deferred_output_copy": (
            configurations[0].get("deferredOutputCopy") if configurations else None
        ),
        "cadence_mode": cadence_mode,
        "overall_gate": overall_gate,
        "functional_gate": functional_gate,
        "performance_class": performance_class,
        "source_cadence_class": source_cadence_class,
        "source_cadence_gate": source_cadence_gate,
        "source_pts_interval_gate": source_pts_interval_gate,
        "latency_class": latency_class,
        "performance_scope": (
            "mixed_inference_and_reuse_effect_output_submit_proxy_not_realtime_classified"
            if cadence_mode == "CONTENT_AWARE_V1"
            else "effect_output_submit_throughput_proxy_not_gpu_completion_or_final_display"
        ),
        "final_display_status": "unmeasured",
        "metrics": metrics,
        "pipeline_counters": latest_counters,
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
    print(f"{report['overall_gate']} {case['id']}: {report['performance_class']} "
          f"({report['metrics']['measured_frame_count']} measured frames)")
    for failure in report["failures"]:
        print(f"  - {failure}")
    if report["functional_gate"] == "PASS" and report["source_cadence_gate"] == "FAIL":
        print("  - functional processing passed, but frozen source-cadence throughput failed")
    return 0 if report["overall_gate"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
