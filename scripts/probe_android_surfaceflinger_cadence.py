#!/usr/bin/env python3
"""Measure one player SurfaceView's SurfaceFlinger actual-present cadence.

This is a physical-device, layer-level presentation proxy. It binds the installed APK and the
registered media hash, starts the benchmark player, then samples SurfaceFlinger's 128-record
FrameTracker ring often enough to preserve a longer run. It does not measure photons or A/V sync.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shutil
import statistics
import subprocess
import time
from pathlib import Path
from typing import Any


APP_PACKAGE = "dev.aisystems.quicksrplayerlab"
ACTIVITY = f"{APP_PACKAGE}/.SuperResolutionActivity"
TELEMETRY_TAG = "QuickSRBenchmark"
LAYER_RE = re.compile(
    r"(?m)^\s*Layer \[\d+\] "
    r"(?P<name>[^\r\n]*SurfaceView\[dev\.aisystems\.quicksrplayerlab/"
    r"dev\.aisystems\.quicksrplayerlab\.SuperResolutionActivity\]\(BLAST\)#\d+)\r?\n"
    r"\s*visible reason= buffer=[^\r\n]* frame=(?P<frame>\d+)"
)
SAFE_LAYER_RE = re.compile(
    r"^[A-Fa-f0-9]+ SurfaceView\[dev\.aisystems\.quicksrplayerlab/"
    r"dev\.aisystems\.quicksrplayerlab\.SuperResolutionActivity\]\(BLAST\)#[0-9]+$"
)
SAFE_LEAF_RE = re.compile(r"^[A-Za-z0-9._-]+$")
SAFE_DEVICE_PATH_RE = re.compile(r"^/[A-Za-z0-9_./=+~-]+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
INT64_MAX = (1 << 63) - 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device-serial", required=True)
    parser.add_argument("--video-uri", required=True)
    parser.add_argument("--media-registration-receipt", type=Path, required=True)
    parser.add_argument("--expected-fps", type=float, required=True)
    parser.add_argument(
        "--video-mode",
        choices=("QUICKSR_QNN", "ORIGINAL", "GPU_LANCZOS"),
        default="QUICKSR_QNN",
        help="run one neural path or an apples-to-apples display baseline",
    )
    parser.add_argument("--run-seconds", type=int, default=30)
    parser.add_argument(
        "--sample-seconds",
        type=float,
        default=0.0,
        help="0 reads one non-intrusive tail window; 1..4 enables diagnostic ring polling",
    )
    parser.add_argument("--minimum-cadence-ratio", type=float, default=0.995)
    parser.add_argument("--maximum-cadence-ratio", type=float, default=1.005)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def nearest_rank(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def parse_latency_output(text: str) -> tuple[int, list[tuple[int, int, int]]]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or not lines[0].isdigit():
        raise ValueError("SurfaceFlinger latency output has no refresh-period header")
    refresh_period_ns = int(lines[0])
    records: list[tuple[int, int, int]] = []
    for line in lines[1:]:
        match = re.fullmatch(r"(\d+)\s+(\d+)\s+(\d+)", line)
        if not match:
            continue
        desired, actual, ready = (int(value) for value in match.groups())
        if desired <= 0 or actual <= 0 or actual >= INT64_MAX:
            continue
        records.append((desired, actual, ready))
    return refresh_period_ns, records


def summarize_present_records(
    records_by_desired: dict[int, tuple[int, int]], expected_fps: float
) -> dict[str, Any]:
    ordered_records = sorted(
        ((desired, actual, ready) for desired, (actual, ready) in records_by_desired.items()),
        key=lambda item: item[0],
    )
    actual_in_source_order = [record[1] for record in ordered_records]
    desired_in_source_order = [record[0] for record in ordered_records]
    non_monotonic = sum(
        current < previous
        for previous, current in zip(actual_in_source_order, actual_in_source_order[1:])
    )
    duplicate_actual = len(actual_in_source_order) - len(set(actual_in_source_order))
    unique_actual = sorted(set(actual_in_source_order))
    if len(unique_actual) < 2:
        raise ValueError("SurfaceFlinger returned fewer than two unique actual-present records")
    intervals_ns = [
        current - previous for previous, current in zip(unique_actual, unique_actual[1:])
    ]
    desired_intervals_ns = [
        current - previous
        for previous, current in zip(
            desired_in_source_order, desired_in_source_order[1:]
        )
    ]
    expected_interval_ns = 1_000_000_000.0 / expected_fps
    span_ns = unique_actual[-1] - unique_actual[0]
    observed_fps = (len(unique_actual) - 1) * 1_000_000_000.0 / span_ns
    interval_units = [interval / expected_interval_ns for interval in intervals_ns]
    long_intervals = [unit for unit in interval_units if unit > 1.5]
    short_intervals = [unit for unit in interval_units if unit < 0.5]
    implied_missing = sum(max(0, round(unit) - 1) for unit in long_intervals)
    intervals_ms = [interval / 1_000_000.0 for interval in intervals_ns]
    outliers = [
        {
            "after_present_index": index,
            "interval_ms": interval / 1_000_000.0,
            "source_frame_intervals": unit,
            "desired_interval_ms": (
                desired_intervals_ns[index - 1] / 1_000_000.0
                if index - 1 < len(desired_intervals_ns)
                else None
            ),
        }
        for index, (interval, unit) in enumerate(zip(intervals_ns, interval_units), start=1)
        if unit > 1.5 or unit < 0.5
    ]
    return {
        "frame_records": len(ordered_records),
        "unique_actual_present_times": len(unique_actual),
        "duplicate_actual_present_times": duplicate_actual,
        "non_monotonic_actual_present_pairs": non_monotonic,
        "actual_present_span_seconds": span_ns / 1_000_000_000.0,
        "actual_present_fps": observed_fps,
        "actual_present_cadence_ratio": observed_fps / expected_fps,
        "desired_present_fps": (
            (len(desired_in_source_order) - 1) * 1_000_000_000.0
            / (desired_in_source_order[-1] - desired_in_source_order[0])
        ),
        "desired_present_interval_ms": {
            "p50": nearest_rank(desired_intervals_ns, 0.50) / 1_000_000.0,
            "p95": nearest_rank(desired_intervals_ns, 0.95) / 1_000_000.0,
            "p99": nearest_rank(desired_intervals_ns, 0.99) / 1_000_000.0,
            "max": max(desired_intervals_ns) / 1_000_000.0,
        },
        "actual_present_interval_ms": {
            "p50": nearest_rank(intervals_ms, 0.50),
            "p95": nearest_rank(intervals_ms, 0.95),
            "p99": nearest_rank(intervals_ms, 0.99),
            "max": max(intervals_ms),
            "mean": statistics.fmean(intervals_ms),
        },
        "intervals_over_1_5_source_frames": len(long_intervals),
        "intervals_under_0_5_source_frames": len(short_intervals),
        "implied_missing_present_intervals": implied_missing,
        "interval_outliers": outliers[:20],
        "interval_outliers_truncated": len(outliers) > 20,
    }


def extract_telemetry_identity(
    events: list[dict[str, Any]], run_id: str, requested_mode: str
) -> dict[str, Any]:
    matching = [event for event in events if event.get("runId") == run_id]
    configuration = next(
        (event for event in matching if event.get("event") == "configuration"), None
    )
    if not isinstance(configuration, dict):
        raise RuntimeError("configuration telemetry was not captured")
    if configuration.get("mode") != requested_mode:
        raise RuntimeError("captured configuration mode does not match --video-mode")
    identity = {
        "video_mode": configuration.get("mode"),
        "benchmark_route": configuration.get("benchmarkRoute"),
        "neural_processing_enabled": configuration.get("neuralProcessingEnabled"),
        "prototype_build_id": configuration.get("prototypeBuildId"),
        "source_identity_sha256": configuration.get("sourceIdentitySha256"),
    }
    if requested_mode != "QUICKSR_QNN":
        if configuration.get("neuralProcessingEnabled") is not False:
            raise RuntimeError("display baseline telemetry claims neural processing")
        if configuration.get("qnnStrictRequired") is not False:
            raise RuntimeError("display baseline telemetry claims QNN strict is required")
        return identity

    strict = next((event for event in matching if event.get("event") == "qnn_strict"), None)
    if not isinstance(strict, dict):
        raise RuntimeError("qnn_strict telemetry was not captured")
    strict_evidence = strict.get("qnnStrict")
    if not isinstance(strict_evidence, dict) or not strict_evidence.get("strictReady"):
        raise RuntimeError("QNN strict-ready evidence is missing")
    if strict.get("modelVariant") != configuration.get("modelVariant"):
        raise RuntimeError("QNN strict model does not match the configured model")
    deferred_output_copy = configuration.get("deferredOutputCopy")
    if not isinstance(deferred_output_copy, bool):
        raise RuntimeError("QNN configuration has no deferred-output-copy identity")
    if strict_evidence.get("deferredOutputCopy") != deferred_output_copy:
        raise RuntimeError("QNN strict deferred-output-copy identity does not match configuration")
    pinned_output_slots = configuration.get("pinnedOrtOutputTensorSlotCount")
    if strict_evidence.get("pinnedOrtOutputTensorSlotCount") != pinned_output_slots:
        raise RuntimeError("QNN strict pinned-output-slot identity does not match configuration")
    if strict_evidence.get("glUploadRoute") != configuration.get("glUploadRoute"):
        raise RuntimeError("QNN strict GL-upload route does not match configuration")
    if strict_evidence.get("pboUpload") != configuration.get("pboUpload"):
        raise RuntimeError("QNN strict PBO-upload identity does not match configuration")
    if strict_evidence.get("glUploadPboSlotCount") != configuration.get(
        "glUploadPboSlotCount"
    ):
        raise RuntimeError("QNN strict PBO-slot identity does not match configuration")
    return {
        **identity,
        "model_variant": configuration.get("modelVariant"),
        "model_sha256": configuration.get("modelSha256"),
        "output_tensor_layout": configuration.get("outputTensorLayout"),
        "output_pack_stripe_count": configuration.get("outputPackStripeCount"),
        "postprocess_mode": configuration.get("postprocessMode"),
        "finite_validation_policy": configuration.get("finiteValidationPolicy"),
        "deferred_output_copy": deferred_output_copy,
        "pinned_ort_output_tensor_slot_count": pinned_output_slots,
        "additional_pinned_ort_output_bytes": configuration.get(
            "additionalPinnedOrtOutputBytes"
        ),
        "tensor_output_copy_measurement": configuration.get(
            "tensorOutputCopyMeasurement"
        ),
        "gl_upload_route": configuration.get("glUploadRoute"),
        "pbo_upload": configuration.get("pboUpload"),
        "gl_upload_pbo_slot_count": configuration.get("glUploadPboSlotCount"),
        "additional_gl_upload_pbo_bytes": configuration.get(
            "additionalGlUploadPboBytes"
        ),
        "qnn_strict_ready": True,
        "qnn_cpu_ep_fallback_disabled": strict_evidence.get("cpuEpFallbackDisabled"),
        "qnn_backend_type": strict_evidence.get("backendType"),
        "qnn_provider_assignment_verified": strict_evidence.get(
            "providerAssignmentVerified"
        ),
        "qnn_evidence_scope": strict_evidence.get("evidenceScope"),
    }


class Probe:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        adb = str(args.adb.resolve()) if args.adb else shutil.which("adb")
        if not adb:
            raise RuntimeError("adb was not found; pass --adb or add it to PATH")
        self.adb_path = adb

    def adb(self, *arguments: str, timeout: float = 30.0) -> str:
        completed = subprocess.run(
            [self.adb_path, "-s", self.args.device_serial, *arguments],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if completed.returncode != 0:
            raise RuntimeError(
                f"adb failed ({completed.returncode}): {' '.join(arguments)}\n{completed.stdout}"
            )
        return completed.stdout

    def preflight(self) -> dict[str, Any]:
        devices = subprocess.run(
            [self.adb_path, "devices"],
            check=True,
            stdout=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        ).stdout.splitlines()
        connected = [line.split("\t", 1)[0] for line in devices if line.endswith("\tdevice")]
        if connected.count(self.args.device_serial) != 1:
            raise RuntimeError("the requested physical device is not connected exactly once")
        if self.adb("shell", "getprop", "ro.kernel.qemu").strip() == "1":
            raise RuntimeError("SurfaceFlinger cadence evidence rejects emulators")
        abi = self.adb("shell", "getprop", "ro.product.cpu.abi").strip()
        if abi != "arm64-v8a":
            raise RuntimeError(f"expected arm64-v8a, got {abi!r}")
        manufacturer = self.adb("shell", "getprop", "ro.soc.manufacturer").strip()
        if not re.fullmatch(r"QTI|Qualcomm", manufacturer):
            raise RuntimeError("the target does not report a Qualcomm SoC")

        receipt_bytes = self.args.media_registration_receipt.read_bytes()
        receipt = json.loads(receipt_bytes.decode("utf-8-sig"))
        if (
            receipt.get("schema_version") != 1
            or receipt.get("kind") != "android-mobile-subset-media-registration"
            or receipt.get("status") != "PASS"
        ):
            raise RuntimeError("media registration receipt is not a schema-v1 PASS receipt")
        if receipt.get("mediaStoreUri") != self.args.video_uri:
            raise RuntimeError("receipt MediaStore URI does not match --video-uri")
        clip = receipt.get("clip")
        if not isinstance(clip, dict):
            raise RuntimeError("media registration receipt has no clip")
        clip_name = clip.get("file")
        clip_sha = str(clip.get("sha256", "")).lower()
        if not isinstance(clip_name, str) or not SAFE_LEAF_RE.fullmatch(clip_name):
            raise RuntimeError("receipt clip file name is unsafe")
        if not SHA256_RE.fullmatch(clip_sha):
            raise RuntimeError("receipt clip SHA-256 is invalid")
        remote_path = f"/sdcard/Movies/QuickSRBenchmark/{clip_sha}/{clip_name}"
        remote_hash_output = self.adb("shell", "sha256sum", remote_path)
        remote_hash = remote_hash_output.split(maxsplit=1)[0].lower()
        if remote_hash != clip_sha:
            raise RuntimeError("registered remote media hash does not match its receipt")

        package_paths = [
            line.removeprefix("package:").strip()
            for line in self.adb("shell", "pm", "path", APP_PACKAGE).splitlines()
            if line.startswith("package:")
        ]
        base_apks = [path for path in package_paths if path.endswith("/base.apk")]
        if len(base_apks) != 1 or not SAFE_DEVICE_PATH_RE.fullmatch(base_apks[0]):
            raise RuntimeError("could not resolve one safe installed base.apk path")
        apk_sha_output = self.adb("shell", "sha256sum", base_apks[0])
        apk_sha = apk_sha_output.split(maxsplit=1)[0].lower()
        if not SHA256_RE.fullmatch(apk_sha):
            raise RuntimeError("installed APK SHA-256 is invalid")

        return {
            "abi": abi,
            "soc_manufacturer": manufacturer,
            "receipt_sha256": hashlib.sha256(receipt_bytes).hexdigest(),
            "clip_id": clip.get("id"),
            "clip_sha256": clip_sha,
            "clip_frame_count": clip.get("frameCount"),
            "installed_apk_sha256": apk_sha,
        }

    def find_layer(self, expected_name: str | None = None) -> tuple[str, int, int]:
        dump = self.adb("shell", "dumpsys", "SurfaceFlinger", timeout=45.0)
        matches = list(LAYER_RE.finditer(dump))
        if expected_name is not None:
            matches = [match for match in matches if match.group("name").strip() == expected_name]
        names = {match.group("name").strip() for match in matches}
        if len(names) != 1:
            raise RuntimeError(f"expected one active QuickSR BLAST SurfaceView, found {len(names)}")
        name = next(iter(names))
        if not SAFE_LAYER_RE.fullmatch(name):
            raise RuntimeError("resolved SurfaceFlinger layer name failed the safety contract")
        matching_frames = [
            int(match.group("frame"))
            for match in matches
            if match.group("name").strip() == name
        ]
        return name, max(matching_frames), time.monotonic_ns()

    def layer_command(self, flag: str, layer: str) -> str:
        if flag not in {"--latency", "--latency-clear"} or not SAFE_LAYER_RE.fullmatch(layer):
            raise RuntimeError("unsafe SurfaceFlinger layer command")
        return self.adb("shell", f"dumpsys SurfaceFlinger {flag} '{layer}'")

    def telemetry_identity(self) -> dict[str, Any]:
        raw = self.adb(
            "logcat", "-d", "-v", "raw", "-s", f"{TELEMETRY_TAG}:I", "*:S",
            timeout=45.0,
        )
        events = []
        for line in raw.splitlines():
            if not line.startswith("{"):
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("runId") == self.run_id:
                events.append(event)
        return extract_telemetry_identity(events, self.run_id, self.args.video_mode)

    def run(self, preflight: dict[str, Any]) -> dict[str, Any]:
        self.run_id = f"sf-cadence-{time.strftime('%Y%m%d-%H%M%S')}"
        records: dict[int, tuple[int, int]] = {}
        refresh_periods: set[int] = set()
        failures: list[str] = []
        self.adb("shell", "am", "force-stop", APP_PACKAGE)
        self.adb("logcat", "-c")
        try:
            start = self.adb(
                "shell", "am", "start", "-W",
                "-a", "android.intent.action.VIEW",
                "-d", self.args.video_uri,
                "-t", "video/*",
                "-f", "0x1",
                "-n", ACTIVITY,
                "--es", f"{APP_PACKAGE}.extra.BENCHMARK_RUN_ID", self.run_id,
                "--es", f"{APP_PACKAGE}.extra.VIDEO_MODE", self.args.video_mode,
                "--es", f"{APP_PACKAGE}.extra.VIDEO_PROFILE", "FULL_1080P_3X",
                "--es", f"{APP_PACKAGE}.extra.VIDEO_TUNING", "SUSTAINED",
                "--es", f"{APP_PACKAGE}.extra.CADENCE_MODE", "OFF",
                "--es", f"{APP_PACKAGE}.extra.OUTPUT_PACKER", "JAVA",
            )
            if not re.search(r"(?m)^Status:\s*ok\s*$", start):
                raise RuntimeError("benchmark Activity did not report Status: ok")
            time.sleep(4.0)
            layer, _, _ = self.find_layer()
            self.layer_command("--latency-clear", layer)
            layer, start_frame, start_ns = self.find_layer(layer)
            identity = self.telemetry_identity()

            deadline = time.monotonic() + self.args.run_seconds
            if self.args.sample_seconds == 0:
                time.sleep(self.args.run_seconds)
            else:
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    time.sleep(min(self.args.sample_seconds, remaining))
                    refresh_ns, batch = parse_latency_output(
                        self.layer_command("--latency", layer)
                    )
                    refresh_periods.add(refresh_ns)
                    for desired, actual, ready in batch:
                        records[desired] = (actual, ready)
            refresh_ns, batch = parse_latency_output(self.layer_command("--latency", layer))
            refresh_periods.add(refresh_ns)
            for desired, actual, ready in batch:
                records[desired] = (actual, ready)
            _, end_frame, end_ns = self.find_layer(layer)
        finally:
            self.adb("shell", "am", "force-stop", APP_PACKAGE)

        if len(refresh_periods) != 1:
            failures.append("SurfaceFlinger refresh period changed or was missing")
        present = summarize_present_records(records, self.args.expected_fps)
        ratio = present["actual_present_cadence_ratio"]
        if not (self.args.minimum_cadence_ratio <= ratio <= self.args.maximum_cadence_ratio):
            failures.append("actual-present cadence ratio is outside the frozen tolerance")
        if present["duplicate_actual_present_times"]:
            failures.append("multiple source records share an actual-present timestamp")
        if present["non_monotonic_actual_present_pairs"]:
            failures.append("actual-present timestamps are non-monotonic in source order")
        if present["intervals_over_1_5_source_frames"]:
            failures.append("one or more actual-present intervals exceed 1.5 source frames")
        if present["intervals_under_0_5_source_frames"]:
            failures.append("one or more actual-present intervals are shorter than 0.5 source frames")

        elapsed_seconds = (end_ns - start_ns) / 1_000_000_000.0
        layer_delta = end_frame - start_frame
        layer_fps = layer_delta / elapsed_seconds
        layer_ratio = layer_fps / self.args.expected_fps
        if not (
            self.args.minimum_cadence_ratio
            <= layer_ratio
            <= self.args.maximum_cadence_ratio
        ):
            failures.append("SurfaceView buffer-counter cadence is outside the frozen tolerance")

        return {
            "schema_version": 1,
            "kind": "android-surfaceflinger-video-cadence",
            "status": "PASS" if not failures else "FAIL",
            "scope": (
                "surfaceflinger_layer_actual_present_and_buffer_counter_proxy; "
                "not_photon_timing_or_audio_video_sync"
            ),
            "expected_source_fps": self.args.expected_fps,
            "minimum_cadence_ratio": self.args.minimum_cadence_ratio,
            "maximum_cadence_ratio": self.args.maximum_cadence_ratio,
            "requested_measurement_seconds": self.args.run_seconds,
            "sample_period_seconds": self.args.sample_seconds,
            "actual_present_sampling_mode": (
                "single_non_intrusive_tail_window"
                if self.args.sample_seconds == 0
                else "diagnostic_repeated_ring_polling_may_perturb_surfaceflinger"
            ),
            "device_class": {
                "abi": preflight["abi"],
                "soc_manufacturer": preflight["soc_manufacturer"],
                "emulator_rejected": True,
                "serial_recorded": False,
            },
            "media": {
                "registration_receipt_sha256": preflight["receipt_sha256"],
                "clip_id": preflight["clip_id"],
                "clip_sha256": preflight["clip_sha256"],
                "clip_frame_count": preflight["clip_frame_count"],
                "audio_present": False,
            },
            "runtime": {
                "installed_apk_sha256": preflight["installed_apk_sha256"],
                **identity,
            },
            "surfaceflinger": {
                "layer_kind": "app_SurfaceView_BLAST",
                "refresh_period_ns": next(iter(refresh_periods), None),
                **present,
                "layer_buffer_counter_start": start_frame,
                "layer_buffer_counter_end": end_frame,
                "layer_buffer_counter_delta": layer_delta,
                "layer_buffer_counter_elapsed_seconds": elapsed_seconds,
                "layer_buffer_counter_fps": layer_fps,
                "layer_buffer_counter_cadence_ratio": layer_ratio,
            },
            "final_display_status": "surfaceflinger_actual_present_proxy",
            "audio_video_sync_status": "unmeasured_clip_has_no_audio",
            "failures": failures,
        }


def validate_args(args: argparse.Namespace) -> None:
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.device_serial):
        raise ValueError("--device-serial is unsafe")
    if not re.fullmatch(r"content://media/external/video/media/[0-9]+", args.video_uri):
        raise ValueError("--video-uri must be one exact MediaStore video URI")
    if not math.isfinite(args.expected_fps) or not 1.0 <= args.expected_fps <= 120.0:
        raise ValueError("--expected-fps must be finite and between 1 and 120")
    if not 10 <= args.run_seconds <= 600:
        raise ValueError("--run-seconds must be between 10 and 600")
    if args.sample_seconds != 0 and not 1.0 <= args.sample_seconds <= 4.0:
        raise ValueError("--sample-seconds must be 0 or between 1 and 4")
    if not 0.0 < args.minimum_cadence_ratio <= 1.0:
        raise ValueError("--minimum-cadence-ratio must be in (0, 1]")
    if not 1.0 <= args.maximum_cadence_ratio < 2.0:
        raise ValueError("--maximum-cadence-ratio must be in [1, 2)")


def ensure_output_is_local(args: argparse.Namespace) -> Path:
    repository_root = Path(__file__).resolve().parent.parent
    device_results = (repository_root / "device-results").resolve()
    output = args.output.resolve()
    try:
        output.relative_to(device_results)
    except ValueError as failure:
        raise ValueError("--output must stay beneath the ignored device-results directory") from failure
    output.parent.mkdir(parents=True, exist_ok=True)
    return output


def main() -> int:
    args = parse_args()
    validate_args(args)
    output = ensure_output_is_local(args)
    probe = Probe(args)
    preflight = probe.preflight()
    report = probe.run(preflight)
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    surface = report["surfaceflinger"]
    print(
        f"{report['status']} SurfaceFlinger actual-present proxy: "
        f"{surface['actual_present_fps']:.6f} fps from "
        f"{surface['unique_actual_present_times']} unique presents"
    )
    for failure in report["failures"]:
        print(f"  - {failure}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
