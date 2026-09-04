#!/usr/bin/env python3
"""Summarize a 1080p JAVA/NATIVE_NEON/JAVA output-packer ABBA experiment."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
from statistics import mean
from typing import Any

import validate_android_qnn_resolution_log as validator


CASE = "1080p-primary"
EXPECTED_PACKERS = ("JAVA", "NATIVE_NEON", "NATIVE_NEON", "JAVA")
METRICS = (
    "inferenceCallerWallNs",
    "tensorOutputCopyNs",
    "outputPackNs",
    "directBufferCopyNs",
    "effectTotalToOutputSubmitProxyNs",
)
PINNED_CONFIGURATION_FIELDS = (
    "tuning",
    "profile",
    "modelInputWidth",
    "modelInputHeight",
    "modelOutputWidth",
    "modelOutputHeight",
    "canvasWidth",
    "canvasHeight",
    "modelVariant",
    "modelSha256",
    "sourceIdentitySha256",
    "targetAbi",
    "queuePolicy",
    "workerQueueCapacity",
    "workerCleanupReservedSlots",
    "media3EffectQueueCapacity",
    "media3PendingPboQueueCapacity",
    "postprocessMode",
    "cadenceMode",
)


def resolve_session(path: Path) -> Path:
    if (path / f"{CASE}.json").is_file():
        return path
    sessions = [
        candidate
        for candidate in path.iterdir()
        if candidate.is_dir() and (candidate / f"{CASE}.json").is_file()
    ]
    if len(sessions) != 1:
        raise ValueError(f"expected exactly one complete session beneath {path}, found {len(sessions)}")
    return sessions[0]


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be greater than zero")
    return parsed


def percent_change(baseline: float, candidate: float) -> float:
    return (candidate - baseline) * 100.0 / baseline


def load_run(
    session: Path,
    plan: dict[str, Any],
    plan_sha256: str,
) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    report_path = session / f"{CASE}.json"
    raw_log_path = session / f"{CASE}.log"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("case_id") != CASE:
        raise ValueError(f"{session.name} report case_id mismatch")
    if report.get("plan_sha256") != plan_sha256:
        raise ValueError(f"{session.name} plan SHA-256 mismatch")
    if report.get("validator_version") != validator.VALIDATOR_VERSION:
        raise ValueError(f"{session.name} validator version mismatch")
    raw_log_sha256 = hashlib.sha256(raw_log_path.read_bytes()).hexdigest()
    if report.get("raw_log_sha256") != raw_log_sha256:
        raise ValueError(f"{session.name} raw-log SHA-256 mismatch")
    events, parse_errors = validator.load_events(raw_log_path, report["run_id"])
    if parse_errors:
        raise ValueError(f"{session.name} has raw-log parse errors: {parse_errors}")
    configurations = [event for event in events if event.get("event") == "configuration"]
    if len(configurations) != 1:
        raise ValueError(f"{session.name} has {len(configurations)} configurations")
    revalidated = validator.validate(
        plan,
        validator.find_case(plan, CASE),
        events,
        report["run_id"],
        parse_errors,
    )
    if revalidated.get("functional_gate") != "PASS":
        raise ValueError(
            f"{session.name} does not pass current validator: {revalidated.get('failures')}"
        )
    samples = [
        sample
        for event in events
        if event.get("event") == "frame_batch"
        for sample in event.get("samples", [])
    ]
    return report, configurations[0], samples


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--run",
        action="append",
        required=True,
        metavar="LABEL=PATH",
        help="repeat in chronological order; requires JAVA,NATIVE_NEON,NATIVE_NEON,JAVA",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--plan",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "contracts"
        / "android-qnn-resolution-plan.json",
    )
    parser.add_argument("--minimum-matched-frame-occurrences", type=positive_int, default=180)
    args = parser.parse_args()

    if len(args.run) != 4:
        raise ValueError("exactly four chronological --run values are required")
    plan_bytes = args.plan.read_bytes()
    plan = json.loads(plan_bytes.decode("utf-8"))
    plan_sha256 = hashlib.sha256(plan_bytes).hexdigest()
    runs: list[tuple[str, Path]] = []
    for value in args.run:
        label, separator, raw_path = value.partition("=")
        if not separator or not label or not raw_path:
            raise ValueError(f"invalid --run value: {value!r}")
        runs.append((label, resolve_session(Path(raw_path).resolve())))

    rows: list[dict[str, Any]] = []
    configurations: list[dict[str, Any]] = []
    samples_by_packer: dict[str, list[tuple[int, str]]] = {
        "JAVA": [],
        "NATIVE_NEON": [],
    }
    outputs_by_input: dict[str, set[str]] = {}
    run_frame_counts: list[int] = []
    registered_frame_counts: set[int] = set()
    media_registration_reference: dict[str, Any] | None = None

    for index, (label, session) in enumerate(runs):
        report, configuration, samples = load_run(session, plan, plan_sha256)
        packer = report.get("output_packer")
        expected = EXPECTED_PACKERS[index]
        if packer != expected:
            raise ValueError(f"{label} expected {expected}, got {packer}")
        if report.get("postprocess_mode") != "SERIAL":
            raise ValueError(f"{label} did not use SERIAL postprocess")
        if report.get("cadence_mode") != "OFF":
            raise ValueError(f"{label} did not keep cadence OFF")
        if configuration.get("outputPacker") != packer:
            raise ValueError(f"{label} report/configuration packer mismatch")
        expected_self_test = "PASS" if packer == "NATIVE_NEON" else "NOT_RUN"
        if configuration.get("outputPackerSelfTest", "NOT_RUN") != expected_self_test:
            raise ValueError(f"{label} native packer self-test did not meet its gate")
        if report.get("functional_gate") != "PASS":
            raise ValueError(f"{label} is not a functional PASS")
        if media_registration_reference is None:
            media_registration_reference = report["media_registration"]
        elif report["media_registration"] != media_registration_reference:
            raise ValueError(f"{label} changed media registration")
        configurations.append(configuration)
        samples_by_packer[packer].extend(
            (sample["ptsUs"], sample["inputCrc32"]) for sample in samples
        )
        run_frame_counts.append(len(samples))
        registered_frame_counts.add(int(report["media_registration"]["clip_frame_count"]))
        for sample in samples:
            outputs_by_input.setdefault(sample["inputCrc32"], set()).add(sample["outputCrc32"])

        metrics = report["metrics"]
        row: dict[str, Any] = {
            "label": label,
            "packer": packer,
            "measured_frames": metrics["measured_frame_count"],
            "observed_fps": metrics["observed_fps"],
            "max_worker_queue_depth": report["pipeline_counters"]["maxQueueDepth"],
            "dropped_count": report["pipeline_counters"]["droppedCount"],
            "bypassed_count": report["pipeline_counters"]["bypassedCount"],
            "raw_log_sha256": report["raw_log_sha256"],
        }
        for metric in METRICS:
            row[metric] = {
                "p50_ms": metrics[metric]["p50"] / 1_000_000,
                "p95_ms": metrics[metric]["p95"] / 1_000_000,
            }
        rows.append(row)

    reference = configurations[0]
    for configuration in configurations[1:]:
        for field in PINNED_CONFIGURATION_FIELDS:
            if configuration.get(field) != reference.get(field):
                raise ValueError(f"changed pinned configuration field {field}")

    if len(registered_frame_counts) != 1:
        raise ValueError("inconsistent registered clip frame counts")
    registered_frame_count = next(iter(registered_frame_counts))
    all_samples = samples_by_packer["JAVA"] + samples_by_packer["NATIVE_NEON"]
    zero_crcs = {crc for pts_us, crc in all_samples if pts_us == 0}
    if len(zero_crcs) != 1:
        raise ValueError("cannot identify one PTS=0 cycle identity")
    zero_crc = next(iter(zero_crcs))
    cycle_starts = sorted({pts for pts, crc in all_samples if pts > 0 and crc == zero_crc})
    if not cycle_starts:
        raise ValueError("cannot infer repeated clip-cycle duration")
    cycle_duration_us = cycle_starts[0]
    normalized = {
        packer: Counter((pts % cycle_duration_us, crc) for pts, crc in samples)
        for packer, samples in samples_by_packer.items()
    }
    shared_identities = set(normalized["JAVA"]) & set(normalized["NATIVE_NEON"])
    matched_occurrences = sum((normalized["JAVA"] & normalized["NATIVE_NEON"]).values())
    mismatches = {
        input_crc: sorted(outputs)
        for input_crc, outputs in outputs_by_input.items()
        if len(outputs) != 1
    }
    consistency_pass = (
        matched_occurrences >= args.minimum_matched_frame_occurrences
        and min(run_frame_counts) >= args.minimum_matched_frame_occurrences
        and not mismatches
    )

    java_rows = [row for row in rows if row["packer"] == "JAVA"]
    native_rows = [row for row in rows if row["packer"] == "NATIVE_NEON"]

    def metric_mean(group: list[dict[str, Any]], metric: str, percentile: str) -> float:
        return mean(row[metric][percentile] for row in group)

    java_fps = mean(row["observed_fps"] for row in java_rows)
    native_fps = mean(row["observed_fps"] for row in native_rows)
    aggregate = {
        "java_mean_fps": java_fps,
        "native_neon_mean_fps": native_fps,
        "fps_change_percent": percent_change(java_fps, native_fps),
        "output_pack_p50_ms": {
            "java_mean": metric_mean(java_rows, "outputPackNs", "p50_ms"),
            "native_neon_mean": metric_mean(native_rows, "outputPackNs", "p50_ms"),
        },
        "direct_buffer_copy_p50_ms": {
            "java_mean": metric_mean(java_rows, "directBufferCopyNs", "p50_ms"),
            "native_neon_mean": metric_mean(native_rows, "directBufferCopyNs", "p50_ms"),
        },
        "total_p50_ms": {
            "java_mean": metric_mean(java_rows, "effectTotalToOutputSubmitProxyNs", "p50_ms"),
            "native_neon_mean": metric_mean(native_rows, "effectTotalToOutputSubmitProxyNs", "p50_ms"),
        },
    }
    aggregate["output_pack_p50_change_percent"] = percent_change(
        aggregate["output_pack_p50_ms"]["java_mean"],
        aggregate["output_pack_p50_ms"]["native_neon_mean"],
    )
    aggregate["total_p50_change_percent"] = percent_change(
        aggregate["total_p50_ms"]["java_mean"],
        aggregate["total_p50_ms"]["native_neon_mean"],
    )

    status = "PASS" if consistency_pass else "FAIL"
    summary = {
        "schema_version": 1,
        "experiment": "android-qnn-native-output-packer-abba",
        "sequence": list(EXPECTED_PACKERS),
        "status": status,
        "functional_gate": "PASS",
        "performance_disposition": "REJECT_DEFAULT_RETAIN_EXPERIMENTAL",
        "rows": rows,
        "aggregate": aggregate,
        "plan_sha256": plan_sha256,
        "media_registration": media_registration_reference,
        "output_crc_consistency": {
            "cycle_alignment": "PTS_MODULO_INFERRED_DURATION_FROM_PTS_ZERO_IDENTITY",
            "cycle_duration_us": cycle_duration_us,
            "registered_cycle_frame_count": registered_frame_count,
            "java_cycle_identity_count": len(normalized["JAVA"]),
            "native_neon_cycle_identity_count": len(normalized["NATIVE_NEON"]),
            "shared_cycle_identity_count": len(shared_identities),
            "full_cycle_coverage_status": "PASS"
            if len(shared_identities) >= registered_frame_count
            else "INCOMPLETE",
            "matched_cycle_identity_occurrence_count": matched_occurrences,
            "minimum_required_matched_occurrence_count": args.minimum_matched_frame_occurrences,
            "minimum_run_frame_count": min(run_frame_counts),
            "exact_output_crc_mismatch_count": len(mismatches),
            "scope": "cross_packer_output_consistency_not_independent_numeric_correctness",
            "status": status,
            "mismatches": mismatches,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"{status}: JAVA/NATIVE_NEON/NATIVE_NEON/JAVA")
    print(f"mean FPS {java_fps:.3f} -> {native_fps:.3f} ({aggregate['fps_change_percent']:+.1f}%)")
    print(
        "output-pack p50 "
        f"{aggregate['output_pack_p50_ms']['java_mean']:.3f} -> "
        f"{aggregate['output_pack_p50_ms']['native_neon_mean']:.3f} ms"
    )
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
