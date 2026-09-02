#!/usr/bin/env python3
"""Summarize one bounded QuickSR cadence OFF/ON device A/B from raw Logcat files."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import statistics


def load_events(path: Path) -> list[dict]:
    events: list[dict] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        start = line.find("{")
        if start < 0:
            continue
        try:
            value = json.loads(line[start:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and value.get("schemaVersion") == 2:
            events.append(value)
    if not events:
        raise ValueError(f"no QuickSR schemaVersion=2 events in {path}")
    return events


def percentile(values: list[int], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * fraction)))
    return float(ordered[index])


def hold_alignment(samples: list[dict], source_fps: int, target_fps: int) -> dict:
    if source_fps <= 0 or target_fps <= 0 or source_fps > target_fps:
        raise ValueError("hold alignment requires 0 < source_fps <= target_fps")
    counts = Counter()
    previous_source_index: int | None = None
    previous_stream_epoch: int | None = None
    stream_origin_pts_us: int | None = None
    for sample in samples:
        stream_epoch = int(sample.get("cadenceStreamEpoch", 0))
        if previous_stream_epoch is not None and stream_epoch != previous_stream_epoch:
            previous_source_index = None
            stream_origin_pts_us = None
        if stream_origin_pts_us is None:
            stream_origin_pts_us = int(sample["ptsUs"])
        frame_index = round(
            (int(sample["ptsUs"]) - stream_origin_pts_us) * target_fps / 1_000_000
        )
        source_index = frame_index * source_fps // target_fps
        if previous_source_index is not None:
            expected_hold = source_index == previous_source_index
            reused = sample.get("cadenceDecision") == "REUSE"
            if expected_hold and reused:
                counts["expected_hold_reused"] += 1
            elif expected_hold:
                counts["expected_hold_processed"] += 1
            elif reused:
                counts["source_motion_reused"] += 1
            else:
                counts["source_motion_processed"] += 1
        previous_source_index = source_index
        previous_stream_epoch = stream_epoch
    comparable = sum(counts.values())
    expected_holds = counts["expected_hold_reused"] + counts["expected_hold_processed"]
    return {
        "expected_hold_reused": counts["expected_hold_reused"],
        "expected_hold_processed": counts["expected_hold_processed"],
        "source_motion_reused": counts["source_motion_reused"],
        "source_motion_processed": counts["source_motion_processed"],
        "comparable_frames": comparable,
        "expected_hold_fraction": expected_holds / comparable if comparable else 0.0,
        "hold_recall": (
            counts["expected_hold_reused"] / expected_holds if expected_holds else 0.0
        ),
        "source_motion_false_reuse_count": counts["source_motion_reused"],
    }


def summarize(
    path: Path,
    expected_cadence: str,
    run_id: str,
    source_fps: int | None = None,
    target_fps: int | None = None,
    validation_report_path: Path | None = None,
) -> dict:
    events = [event for event in load_events(path) if event.get("runId") == run_id]
    error_events = [event for event in events if event.get("event") == "error"]
    if error_events:
        raise ValueError(f"{run_id}: device error event is present")
    failing_terminals = [
        event for event in events
        if event.get("event") == "terminal" and event.get("status") != "PASS"
    ]
    if failing_terminals:
        raise ValueError(f"{run_id}: non-PASS terminal event is present")
    configurations = [event for event in events if event.get("event") == "configuration"]
    if len(configurations) != 1:
        raise ValueError(f"{run_id}: expected exactly one configuration event")
    configuration = configurations[0]
    if configuration.get("mode") != "QUICKSR_QNN":
        raise ValueError(f"{run_id}: A/B requires QUICKSR_QNN")
    if configuration.get("cadenceMode") != expected_cadence:
        raise ValueError(
            f"{run_id}: expected cadence {expected_cadence}, "
            f"observed {configuration.get('cadenceMode')}"
        )
    if configuration.get("tuning") != "SUSTAINED":
        raise ValueError(f"{run_id}: A/B requires SUSTAINED tuning")
    if configuration.get("postprocessMode") not in {"SERIAL", "OVERLAP"}:
        raise ValueError(f"{run_id}: A/B has invalid postprocess mode")
    samples = [
        sample
        for event in events
        if event.get("event") == "frame_batch"
        for sample in event.get("samples", [])
    ]
    if not samples:
        raise ValueError(f"{run_id}: no frame samples")
    frame_ids = [int(sample["frameId"]) for sample in samples]
    if frame_ids != list(range(1, len(frame_ids) + 1)):
        raise ValueError(f"{run_id}: frame IDs are not a complete one-output FIFO sequence")
    decisions = Counter(str(sample.get("cadenceDecision")) for sample in samples)
    reasons = Counter(str(sample.get("cadenceReason")) for sample in samples)
    processed = decisions.get("PROCESS", 0)
    reused = decisions.get("REUSE", 0)
    if processed + reused != len(samples):
        raise ValueError(f"{run_id}: missing cadence decisions")
    cumulative_processed = 0
    cumulative_reused = 0
    for sample in samples:
        if sample.get("cadenceDecision") == "PROCESS":
            cumulative_processed += 1
        elif sample.get("cadenceDecision") == "REUSE":
            cumulative_reused += 1
        if int(sample.get("cadenceProcessedCount", -1)) != cumulative_processed \
                or int(sample.get("cadenceReusedCount", -1)) != cumulative_reused:
            raise ValueError(f"{run_id}: cadence counters do not match emitted decisions")
        if sample.get("cadenceDecision") == "REUSE":
            if int(sample.get("cadenceReferenceGeneration", -1)) != int(sample["generation"]):
                raise ValueError(f"{run_id}: reused frame crosses generation")
            if int(sample.get("cadenceReferenceStreamEpoch", -1)) != int(
                sample.get("cadenceStreamEpoch", -2)
            ):
                raise ValueError(f"{run_id}: reused frame crosses input stream")
            if int(sample.get("cadenceReferenceFrameId", -1)) >= int(sample["frameId"]):
                raise ValueError(f"{run_id}: reused frame has invalid reference ordering")
            if int(sample.get("reuseStreak", 0)) > 2:
                raise ValueError(f"{run_id}: reuse streak exceeds hard limit")
    observed = [int(sample["observedNs"]) for sample in samples]
    pts = [int(sample["ptsUs"]) for sample in samples]
    submit_fps = 0.0
    if len(samples) > 1 and observed[-1] > observed[0]:
        submit_fps = (len(samples) - 1) * 1_000_000_000.0 / (observed[-1] - observed[0])
    pts_fps = 0.0
    if len(samples) > 1 and pts[-1] > pts[0]:
        pts_fps = (len(samples) - 1) * 1_000_000.0 / (pts[-1] - pts[0])
    analysis = [int(sample.get("cadenceAnalysisNs", 0)) for sample in samples]
    result = {
        "run_id": run_id,
        "raw_log": str(path),
        "raw_log_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "cadence_mode": expected_cadence,
        "profile": configuration.get("profile"),
        "tuning": configuration.get("tuning"),
        "postprocess_mode": configuration.get("postprocessMode"),
        "model_sha256": configuration.get("modelSha256"),
        "source_identity_sha256": configuration.get("sourceIdentitySha256"),
        "target_abi": configuration.get("targetAbi"),
        "sample_count": len(samples),
        "processed_count": processed,
        "reused_count": reused,
        "inference_reduction_fraction": reused / len(samples),
        "reuse_reason_distribution": dict(sorted(reasons.items())),
        "effect_output_submit_proxy_fps": submit_fps,
        "input_pts_fps": pts_fps,
        "max_worker_queue_depth": max(int(s.get("maxQueueDepth", 0)) for s in samples),
        "max_dropped_count": max(int(s.get("droppedCount", 0)) for s in samples),
        "analysis_ns_p50": statistics.median(analysis),
        "analysis_ns_p95": percentile(analysis, 0.95),
    }
    if source_fps is not None or target_fps is not None:
        if source_fps is None or target_fps is None:
            raise ValueError("source_fps and target_fps must be supplied together")
        result["known_hold_alignment"] = hold_alignment(samples, source_fps, target_fps)
    if validation_report_path is not None:
        result["validation_binding"] = load_validation_binding(
            validation_report_path, path, run_id, expected_cadence
        )
    return result


def load_validation_binding(
    report_path: Path,
    raw_log_path: Path,
    run_id: str,
    expected_cadence: str,
) -> dict:
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    if report.get("functional_gate") != "PASS" or report.get("failures"):
        raise ValueError(f"{run_id}: validation report is not a clean PASS")
    if report.get("run_id") != run_id or report.get("cadence_mode") != expected_cadence:
        raise ValueError(f"{run_id}: validation report identity does not match raw log")
    raw_hash = hashlib.sha256(raw_log_path.read_bytes()).hexdigest()
    if report.get("raw_log_sha256") != raw_hash:
        raise ValueError(f"{run_id}: validation report raw-log hash mismatch")
    media = report.get("media_registration")
    if not isinstance(media, dict) or media.get("status") != "BOUND":
        raise ValueError(f"{run_id}: validation report has no bound media registration")
    if not isinstance(media.get("clip_id"), str) or not media["clip_id"]:
        raise ValueError(f"{run_id}: media registration clip id is invalid")
    for field in ("clip_sha256", "receipt_sha256", "source_manifest_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(media.get(field, ""))):
            raise ValueError(f"{run_id}: media registration {field} is invalid")
    if not isinstance(report.get("plan_id"), str) or not report["plan_id"]:
        raise ValueError(f"{run_id}: validation report plan id is invalid")
    if not re.fullmatch(r"[0-9a-f]{64}", str(report.get("plan_sha256", ""))):
        raise ValueError(f"{run_id}: validation report plan hash is invalid")
    if not isinstance(report.get("case_id"), str) or not report["case_id"]:
        raise ValueError(f"{run_id}: validation report case id is invalid")
    return {
        "plan_id": report.get("plan_id"),
        "plan_sha256": report.get("plan_sha256"),
        "case_id": report.get("case_id"),
        "clip_id": media["clip_id"],
        "clip_sha256": media["clip_sha256"],
        "receipt_sha256": media["receipt_sha256"],
        "source_manifest_sha256": media["source_manifest_sha256"],
    }


def compare(off: dict, on: dict) -> dict:
    for field in (
        "profile", "tuning", "postprocess_mode", "model_sha256",
        "source_identity_sha256", "target_abi", "validation_binding",
    ):
        if off[field] != on[field]:
            raise ValueError(f"A/B configuration mismatch for {field}")
    if off["reused_count"] != 0:
        raise ValueError("cadence OFF run unexpectedly reused frames")
    return {
        "schema_version": 1,
        "scope": "effect_output_submit_proxy_not_final_display_or_quality",
        "off": off,
        "on": on,
        "inference_reduction_fraction": on["inference_reduction_fraction"],
        "submit_proxy_fps_delta": (
            on["effect_output_submit_proxy_fps"]
            - off["effect_output_submit_proxy_fps"]
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--off-log", type=Path, required=True)
    parser.add_argument("--off-run-id", required=True)
    parser.add_argument("--off-report", type=Path, required=True)
    parser.add_argument("--on-log", type=Path, required=True)
    parser.add_argument("--on-run-id", required=True)
    parser.add_argument("--on-report", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--source-fps", type=int)
    parser.add_argument("--target-fps", type=int)
    args = parser.parse_args()
    report = compare(
        summarize(
            args.off_log, "OFF", args.off_run_id, args.source_fps, args.target_fps,
            args.off_report,
        ),
        summarize(
            args.on_log,
            "CONTENT_AWARE_V1",
            args.on_run_id,
            args.source_fps,
            args.target_fps,
            args.on_report,
        ),
    )
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
