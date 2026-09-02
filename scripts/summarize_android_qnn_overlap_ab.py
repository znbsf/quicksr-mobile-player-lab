#!/usr/bin/env python3
"""Summarize an interleaved SERIAL/OVERLAP Android QNN A/B sequence."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
from statistics import mean
from typing import Any

import validate_android_qnn_resolution_log as validator


CASES = ("720p-baseline", "1080p-primary")
METRICS = (
    "workerQueueWaitNs",
    "outputTensorSlotWaitNs",
    "outputTensorPrepareNs",
    "inferenceCallerWallNs",
    "tensorOutputCopyNs",
    "outputPackNs",
    "directBufferCopyNs",
    "effectTotalToOutputSubmitProxyNs",
    "ptsWallClockDriftNs",
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
)


def resolve_session(path: Path) -> Path:
    if all((path / f"{case}.json").is_file() for case in CASES):
        return path
    sessions = [
        candidate for candidate in path.iterdir()
        if candidate.is_dir()
        and all((candidate / f"{case}.json").is_file() for case in CASES)
    ]
    if len(sessions) != 1:
        raise ValueError(f"expected exactly one complete session beneath {path}, found {len(sessions)}")
    return sessions[0]


def load_case(
    session: Path,
    case: str,
    plan: dict[str, Any],
    plan_sha256: str,
) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    report_path = session / f"{case}.json"
    raw_log_path = session / f"{case}.log"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("case_id") != case:
        raise ValueError(f"{session.name}/{case} report case_id mismatch")
    if report.get("plan_sha256") != plan_sha256:
        raise ValueError(f"{session.name}/{case} plan SHA-256 mismatch")
    if report.get("validator_version") != validator.VALIDATOR_VERSION:
        raise ValueError(f"{session.name}/{case} validator version mismatch")
    raw_log_sha256 = hashlib.sha256(raw_log_path.read_bytes()).hexdigest()
    if report.get("raw_log_sha256") != raw_log_sha256:
        raise ValueError(f"{session.name}/{case} raw-log SHA-256 mismatch")
    events, parse_errors = validator.load_events(raw_log_path, report["run_id"])
    if parse_errors:
        raise ValueError(f"{session.name}/{case} has raw-log parse errors: {parse_errors}")
    configurations = [event for event in events if event.get("event") == "configuration"]
    if len(configurations) != 1:
        raise ValueError(f"{session.name}/{case} has {len(configurations)} configurations")
    if configurations[0].get("runId") != report.get("run_id"):
        raise ValueError(f"{session.name}/{case} run-id mismatch")
    revalidated = validator.validate(
        plan,
        validator.find_case(plan, case),
        events,
        report["run_id"],
        parse_errors,
    )
    if revalidated.get("functional_gate") != "PASS":
        raise ValueError(
            f"{session.name}/{case} does not pass current validator: "
            f"{revalidated.get('failures')}"
        )
    samples = [
        sample
        for event in events if event.get("event") == "frame_batch"
        for sample in event.get("samples", [])
    ]
    return report, configurations[0], samples


def percent_change(baseline: float, candidate: float) -> float:
    return (candidate - baseline) * 100.0 / baseline


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be greater than zero")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--run",
        action="append",
        required=True,
        metavar="LABEL=PATH",
        help="repeat in chronological order; requires SERIAL,OVERLAP,SERIAL,OVERLAP",
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
    configurations_by_case: dict[str, list[dict[str, Any]]] = {case: [] for case in CASES}
    crc_outputs_by_case: dict[str, dict[str, set[str]]] = {case: {} for case in CASES}
    crc_inputs_by_case_mode: dict[str, dict[str, set[str]]] = {
        case: {"SERIAL": set(), "OVERLAP": set()} for case in CASES
    }
    samples_by_case_mode: dict[str, dict[str, list[tuple[int, str]]]] = {
        case: {"SERIAL": [], "OVERLAP": []} for case in CASES
    }
    run_frame_counts_by_case: dict[str, list[int]] = {case: [] for case in CASES}
    registered_frame_counts_by_case: dict[str, set[int]] = {case: set() for case in CASES}
    expected_modes = ("SERIAL", "OVERLAP", "SERIAL", "OVERLAP")

    for run_index, (label, session) in enumerate(runs):
        for case in CASES:
            report, configuration, samples = load_case(
                session,
                case,
                plan,
                plan_sha256,
            )
            mode = report.get("postprocess_mode")
            if report.get("functional_gate") != "PASS":
                raise ValueError(f"{label}/{case} is not a functional PASS")
            if mode != expected_modes[run_index]:
                raise ValueError(
                    f"{label}/{case} expected {expected_modes[run_index]}, got {mode}"
                )
            if configuration.get("postprocessMode") != mode:
                raise ValueError(f"{label}/{case} report/configuration mode mismatch")
            configurations_by_case[case].append(configuration)
            samples_by_case_mode[case][mode].extend(
                (sample["ptsUs"], sample["inputCrc32"]) for sample in samples
            )
            run_frame_counts_by_case[case].append(len(samples))
            registered_frame_counts_by_case[case].add(
                int(report["media_registration"]["clip_frame_count"])
            )
            for sample in samples:
                input_crc = sample["inputCrc32"]
                output_crc = sample["outputCrc32"]
                crc_inputs_by_case_mode[case][mode].add(input_crc)
                crc_outputs_by_case[case].setdefault(input_crc, set()).add(output_crc)

            metrics = report["metrics"]
            row: dict[str, Any] = {
                "label": label,
                "session": str(session),
                "case": case,
                "mode": mode,
                "measured_frames": metrics["measured_frame_count"],
                "observed_fps": metrics["observed_fps"],
                "max_worker_queue_depth": report["pipeline_counters"]["maxQueueDepth"],
                "dropped_count": report["pipeline_counters"]["droppedCount"],
                "bypassed_count": report["pipeline_counters"]["bypassedCount"],
                "output_tensor_slots": configuration["outputTensorSlotCount"],
                "additional_overlap_tensor_bytes": configuration["additionalOverlapTensorBytes"],
            }
            for metric in METRICS:
                row[metric] = {
                    "p50_ms": metrics[metric]["p50"] / 1_000_000,
                    "p95_ms": metrics[metric]["p95"] / 1_000_000,
                }
            rows.append(row)

    for case, configurations in configurations_by_case.items():
        reference = configurations[0]
        for configuration in configurations[1:]:
            for field in PINNED_CONFIGURATION_FIELDS:
                if configuration.get(field) != reference.get(field):
                    raise ValueError(f"{case} changed pinned configuration field {field}")

    output_crc_consistency: dict[str, Any] = {}
    for case in CASES:
        mismatches = {
            input_crc: sorted(outputs)
            for input_crc, outputs in crc_outputs_by_case[case].items()
            if len(outputs) != 1
        }
        shared_distinct = (
            crc_inputs_by_case_mode[case]["SERIAL"]
            & crc_inputs_by_case_mode[case]["OVERLAP"]
        )
        registered_counts = registered_frame_counts_by_case[case]
        if len(registered_counts) != 1:
            raise ValueError(f"{case} has inconsistent registered clip frame counts")
        expected_cycle_frames = next(iter(registered_counts))
        all_samples = (
            samples_by_case_mode[case]["SERIAL"]
            + samples_by_case_mode[case]["OVERLAP"]
        )
        zero_crcs = {crc for pts_us, crc in all_samples if pts_us == 0}
        if len(zero_crcs) != 1:
            raise ValueError(f"{case} cannot identify one PTS=0 cycle identity")
        zero_crc = next(iter(zero_crcs))
        cycle_starts = sorted({
            pts_us for pts_us, crc in all_samples if pts_us > 0 and crc == zero_crc
        })
        if not cycle_starts:
            raise ValueError(f"{case} cannot infer a repeated clip-cycle duration")
        cycle_duration_us = cycle_starts[0]
        normalized_by_mode = {
            mode: Counter(
                (pts_us % cycle_duration_us, crc)
                for pts_us, crc in samples_by_case_mode[case][mode]
            )
            for mode in ("SERIAL", "OVERLAP")
        }
        shared_cycle_identities = (
            set(normalized_by_mode["SERIAL"])
            & set(normalized_by_mode["OVERLAP"])
        )
        matched_occurrences = sum((
            normalized_by_mode["SERIAL"] & normalized_by_mode["OVERLAP"]
        ).values())
        required = args.minimum_matched_frame_occurrences
        output_crc_consistency[case] = {
            "shared_distinct_input_crc_count": len(shared_distinct),
            "cycle_alignment": "PTS_MODULO_INFERRED_DURATION_FROM_PTS_ZERO_IDENTITY",
            "cycle_duration_us": cycle_duration_us,
            "registered_cycle_frame_count": expected_cycle_frames,
            "serial_cycle_identity_count": len(normalized_by_mode["SERIAL"]),
            "overlap_cycle_identity_count": len(normalized_by_mode["OVERLAP"]),
            "shared_cycle_identity_count": len(shared_cycle_identities),
            "matched_cycle_identity_occurrence_count": matched_occurrences,
            "minimum_required_matched_occurrence_count": required,
            "minimum_run_frame_count": min(run_frame_counts_by_case[case]),
            "exact_output_crc_mismatch_count": len(mismatches),
            "scope": "stale_output_and_output_consistency_not_numeric_correctness",
            "status": "PASS"
            if matched_occurrences >= required
            and len(shared_cycle_identities) >= expected_cycle_frames
            and min(run_frame_counts_by_case[case]) >= required
            and not mismatches
            else "FAIL",
            "mismatches": mismatches,
        }

    aggregate: dict[str, Any] = {}
    for case in CASES:
        serial = [row for row in rows if row["case"] == case and row["mode"] == "SERIAL"]
        overlap = [row for row in rows if row["case"] == case and row["mode"] == "OVERLAP"]
        serial_fps = mean(row["observed_fps"] for row in serial)
        overlap_fps = mean(row["observed_fps"] for row in overlap)
        aggregate[case] = {
            "serial_mean_fps": serial_fps,
            "overlap_mean_fps": overlap_fps,
            "fps_change_percent": percent_change(serial_fps, overlap_fps),
        }

    status = "PASS" if all(
        item["status"] == "PASS" for item in output_crc_consistency.values()
    ) else "FAIL"
    summary = {
        "schema_version": 1,
        "experiment": "android-qnn-postprocess-overlap-ab",
        "sequence": list(expected_modes),
        "status": status,
        "rows": rows,
        "aggregate": aggregate,
        "plan_sha256": plan_sha256,
        "output_crc_consistency": output_crc_consistency,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"{status}: SERIAL/OVERLAP/SERIAL/OVERLAP")
    for case in CASES:
        item = aggregate[case]
        print(
            f"  {case}: {item['serial_mean_fps']:.3f} -> "
            f"{item['overlap_mean_fps']:.3f} FPS "
            f"({item['fps_change_percent']:+.1f}%), "
            f"CRC consistency {output_crc_consistency[case]['status']} "
            f"({output_crc_consistency[case]['shared_cycle_identity_count']} cycle identities, "
            f"{output_crc_consistency[case]['matched_cycle_identity_occurrence_count']} occurrences)"
        )
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
