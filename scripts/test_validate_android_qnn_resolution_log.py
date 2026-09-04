"""Unit tests for the structured Android QNN Logcat validator."""

import json
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

import validate_android_qnn_resolution_log as validator


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.plan = {
            "plan_id": "test-plan",
            "warmup_frames": 1,
            "expected_source_fps": 24.0,
            "maximum_source_pts_interval_error_ratio": 0.005,
            "minimum_source_cadence_ratio": 0.995,
            "performance_classes": {
                "realtime_30": {"maximum_p95_total_ms": 33.33, "minimum_observed_fps": 28.5},
                "realtime_24": {"maximum_p95_total_ms": 41.67, "minimum_observed_fps": 22.8},
            },
        }
        self.case = {
            "id": "1080p-primary", "profile": "FULL_1080P_3X",
            "model_input": [640, 360], "model_output": [1920, 1080],
            "canvas": [1920, 1080], "minimum_measured_frames": 2,
        }

    def events(self):
        config = {
            "schemaVersion": 2, "event": "configuration", "runId": "run-1",
            "mode": "QUICKSR_QNN", "tuning": "SUSTAINED", "profile": "FULL_1080P_3X",
            "postprocessMode": "SERIAL", "postprocessQueueCapacity": 0,
            "outputTensorSlotCount": 1, "outputTensorBytesPerSlot": 24_883_200,
            "additionalOverlapTensorBytes": 0,
            "deferredOutputCopy": False, "pinnedOrtOutputTensorSlotCount": 1,
            "additionalPinnedOrtOutputBytes": 0,
            "tensorOutputCopyMeasurement": "measured_inference_thread_bulk_copy",
            "glUploadRoute": "DIRECT_MEDIA3_OUTPUT_TEXTURE",
            "pboUpload": False, "glUploadPboSlotCount": 0,
            "additionalGlUploadPboBytes": 0,
            "glUploadMeasurement": (
                "measured_cpu_gl_upload_and_optional_scale_blit_submission_not_gpu_completion"
            ),
            "qnnRuntimeExpected": True, "modelInputWidth": 640, "modelInputHeight": 360,
            "modelOutputWidth": 1920, "modelOutputHeight": 1080,
            "canvasWidth": 1920, "canvasHeight": 1080,
            "modelVariant": "fixed640x360-3x-full",
            "modelSha256": "a" * 64, "sourceIdentitySha256": "b" * 64,
            "prototypeBuildId": "test-build", "targetAbi": "arm64-v8a",
            **validator.MEASUREMENT_CONTRACT,
        }
        strict = {
            "schemaVersion": 2, "event": "qnn_strict", "runId": "run-1",
            "mode": "QNN_HTP", "profile": "FULL_1080P_3X",
            "modelVariant": "fixed640x360-3x-full",
            "qnnStrict": {
                "registrationStatus": "PASS", "npuSelectionStatus": "PASS",
                "providerConfigurationStatus": "PASS", "backendType": "htp",
                "cpuEpFallbackDisabled": True, "diagnosticOnly": False,
                "selectedNpuDeviceCount": 1, "strictReady": True,
                "providerAssignmentVerified": False, "providerFallbackTraceCaptured": False,
                "evidenceScope": "SESSION_CONFIGURATION_NOT_PER_NODE_PLACEMENT_PROOF",
                "deferredOutputCopy": False, "pinnedOrtOutputTensorSlotCount": 1,
                "glUploadRoute": "DIRECT_MEDIA3_OUTPUT_TEXTURE",
                "pboUpload": False, "glUploadPboSlotCount": 0,
            },
        }
        samples = []
        for index in range(3):
            frame = index + 1
            accepted_ns = 1_000_000_000 + index * 40_000_000
            sample = {
                "frame": frame, "frameId": frame, "generation": 0,
                "generationFrameId": frame, "ptsUs": index * 41667,
                "inputCrc32": f"{frame:08x}", "outputCrc32": f"{frame + 10:08x}",
                "late": False, "ptsWallClockDriftNs": 30_000_000 - index * 1_667_000,
                "acceptedNs": accepted_ns,
                "readbackReadyProxyNs": accepted_ns + 1_000_000,
                "inputCopyStartedNs": accepted_ns + 1_000_000,
                "inputCopiedNs": accepted_ns + 2_000_000,
                "inputHashStartedNs": accepted_ns + 2_000_000,
                "inputHashFinishedNs": accepted_ns + 2_500_000,
                "workerStartedNs": accepted_ns + 3_000_000,
                "outputTensorAcquireStartedNs": accepted_ns + 3_050_000,
                "outputTensorSlotAcquiredNs": accepted_ns + 3_100_000,
                "outputTensorReadyNs": accepted_ns + 3_200_000,
                "preprocessFinishedNs": accepted_ns + 4_000_000,
                "sessionReadyNs": accepted_ns + 4_000_000,
                "inferenceStartedNs": accepted_ns + 4_000_000,
                "inferenceFinishedNs": accepted_ns + 13_000_000,
                "outputPackStartedNs": accepted_ns + 13_000_000,
                "outputPackFinishedNs": accepted_ns + 20_000_000,
                "outputHashStartedNs": accepted_ns + 20_000_000,
                "outputHashFinishedNs": accepted_ns + 21_000_000,
                "directBufferCopyStartedNs": accepted_ns + 21_000_000,
                "directBufferCopyFinishedNs": accepted_ns + 23_000_000,
                "outputReadyNs": accepted_ns + 23_000_000,
                "glUploadStartedNs": accepted_ns + 25_000_000,
                "glUploadFinishedNs": accepted_ns + 30_000_000,
                "outputSubmittedProxyNs": accepted_ns + 30_000_000,
                "observedNs": accepted_ns + 30_000_000,
                "tensorInputCopyNs": 1_000_000, "ortRunNs": 9_000_000,
                "tensorOutputCopyNs": 2_000_000, "finiteScanNs": 0,
                "acceptedCount": frame, "processedCount": frame,
                "lateCount": 0, "droppedCount": 0, "bypassedCount": 0,
                "currentQueueDepth": 0, "maxQueueDepth": 1,
                "flushCount": 0, "seekProxyCount": 0,
            }
            samples.append(sample)
        batch = {
            "schemaVersion": 2, "event": "frame_batch", "runId": "run-1",
            "mode": "QNN_HTP", "tuning": "SUSTAINED", "profile": "FULL_1080P_3X",
            "postprocessMode": "SERIAL",
            "modelInputWidth": 640, "modelInputHeight": 360,
            "modelOutputWidth": 1920, "modelOutputHeight": 1080, "samples": samples,
        }
        return [config, strict, batch]

    def validate(self, events):
        return validator.validate(self.plan, self.case, events, "run-1", [])

    def test_valid_qnn_run_passes_and_classifies_24fps(self):
        result = self.validate(self.events())
        self.assertEqual("PASS", result["overall_gate"])
        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("effect_proxy_realtime_24_throughput", result["performance_class"])
        self.assertEqual("PASS", result["source_cadence_gate"])
        self.assertEqual("source_cadence_matched", result["source_cadence_class"])
        self.assertEqual("PASS", result["source_pts_interval_gate"])
        self.assertEqual("within_nominal_frame_interval", result["latency_class"])
        self.assertEqual("unmeasured", result["final_display_status"])
        self.assertIn("p99", result["metrics"]["outputPackNs"])

    def test_valid_deferred_output_copy_contract_passes(self):
        events = self.events()
        config = events[0]
        config.update({
            "postprocessMode": "OVERLAP",
            "postprocessQueueCapacity": 1,
            "outputTensorSlotCount": 2,
            "additionalOverlapTensorBytes": 24_883_200,
            "outputTensorLayout": "NHWC",
            "deferredOutputCopy": True,
            "pinnedOrtOutputTensorSlotCount": 2,
            "additionalPinnedOrtOutputBytes": 24_883_200,
            "tensorOutputCopyMeasurement": "measured_postprocess_thread_bulk_copy",
            "pboUpload": True,
            "glUploadPboSlotCount": 2,
            "additionalGlUploadPboBytes": 16_588_800,
            "glUploadMeasurement": (
                "measured_cpu_pbo_stage_and_gl_upload_submission_not_gpu_completion"
            ),
        })
        events[1]["qnnStrict"].update({
            "deferredOutputCopy": True,
            "pinnedOrtOutputTensorSlotCount": 2,
            "pboUpload": True,
            "glUploadPboSlotCount": 2,
        })
        events[2]["postprocessMode"] = "OVERLAP"

        result = self.validate(events)

        self.assertEqual("PASS", result["functional_gate"])
        self.assertTrue(result["deferred_output_copy"])

    def test_deferred_output_copy_fails_without_nhwc_overlap(self):
        events = self.events()
        events[0].update({
            "deferredOutputCopy": True,
            "pinnedOrtOutputTensorSlotCount": 2,
            "additionalPinnedOrtOutputBytes": 24_883_200,
            "tensorOutputCopyMeasurement": "measured_postprocess_thread_bulk_copy",
        })
        events[1]["qnnStrict"].update({
            "deferredOutputCopy": True,
            "pinnedOrtOutputTensorSlotCount": 2,
        })

        result = self.validate(events)

        self.assertEqual("FAIL", result["functional_gate"])
        self.assertIn(
            "deferred output copy requires OVERLAP postprocess mode",
            result["failures"],
        )

    def test_pipeline_latency_does_not_downgrade_matched_throughput(self):
        events = self.events()
        for sample in events[2]["samples"]:
            sample["glUploadStartedNs"] += 200_000_000
            sample["glUploadFinishedNs"] += 200_000_000
            sample["outputSubmittedProxyNs"] += 200_000_000
            sample["observedNs"] += 200_000_000

        result = self.validate(events)

        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("effect_proxy_realtime_24_throughput", result["performance_class"])
        self.assertEqual("PASS", result["source_cadence_gate"])
        self.assertEqual("pipelined_over_nominal_frame_interval", result["latency_class"])

    def test_systematic_source_frame_skips_fail_the_frozen_source_cadence(self):
        events = self.events()
        for index, sample in enumerate(events[2]["samples"]):
            sample["ptsUs"] = index * 83334

        result = self.validate(events)

        self.assertEqual("FAIL", result["overall_gate"])
        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("FAIL", result["source_cadence_gate"])
        self.assertEqual("source_pts_interval_mismatch", result["source_cadence_class"])
        self.assertEqual("FAIL", result["source_pts_interval_gate"])
        self.assertEqual("effect_proxy_below_source_cadence", result["performance_class"])

    def test_cadence_run_is_validated_but_never_uses_full_inference_realtime_class(self):
        events = self.events()
        events[0].update({
            "cadenceMode": "CONTENT_AWARE_V1",
            "cadenceAnalyzerVersion": "anime-cadence-analyzer-v1",
            "cadenceMaxReuseStreak": 2,
            "cadenceSubtitleDenseLumaDeltaThreshold": 48,
            "cadenceSubtitleDenseEdgeDeltaThreshold": 32,
            "cadenceSubtitleDenseLocalContrastThreshold": 24,
            "cadenceSubtitleDenseMinChangedPixels": 1,
            "cadenceSubtitleDenseMinHighContrastPixels": 1,
        })
        events[2]["cadenceMode"] = "CONTENT_AWARE_V1"
        for sample in events[2]["samples"]:
            sample.update({
                "cadenceDecision": "PROCESS",
                "cadenceReason": "MOTION",
                "cadenceStreamEpoch": 0,
                "cadenceReferenceGeneration": -1,
                "cadenceReferenceStreamEpoch": -1,
                "cadenceReferenceFrameId": -1,
                "reuseStreak": 0,
                "cadenceAnalysisNs": 1_000,
                "sceneScore": 0.0,
                "subtitleScore": 0.0,
                "motionScore": 0.01,
            })
        events[2]["samples"][1].update({
            "cadenceDecision": "REUSE",
            "cadenceReason": "SMALL_CHANGE",
            "cadenceReferenceGeneration": 0,
            "cadenceReferenceStreamEpoch": 0,
            "cadenceReferenceFrameId": 1,
            "reuseStreak": 1,
        })

        result = self.validate(events)

        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("cadence_effect_proxy_unclassified", result["performance_class"])
        self.assertEqual(1, result["metrics"]["cadence_reused_count"])
        self.assertIn("not_realtime_classified", result["performance_scope"])

    def test_cadence_cross_stream_reference_is_rejected(self):
        events = self.events()
        events[0].update({
            "cadenceMode": "CONTENT_AWARE_V1",
            "cadenceAnalyzerVersion": "anime-cadence-analyzer-v1",
            "cadenceMaxReuseStreak": 2,
            "cadenceSubtitleDenseLumaDeltaThreshold": 48,
            "cadenceSubtitleDenseEdgeDeltaThreshold": 32,
            "cadenceSubtitleDenseLocalContrastThreshold": 24,
            "cadenceSubtitleDenseMinChangedPixels": 1,
            "cadenceSubtitleDenseMinHighContrastPixels": 1,
        })
        events[2]["cadenceMode"] = "CONTENT_AWARE_V1"
        for sample in events[2]["samples"]:
            sample.update({
                "cadenceDecision": "PROCESS",
                "cadenceReason": "MOTION",
                "cadenceStreamEpoch": 1,
                "cadenceReferenceGeneration": -1,
                "cadenceReferenceStreamEpoch": -1,
                "cadenceReferenceFrameId": -1,
                "reuseStreak": 0,
                "cadenceAnalysisNs": 1_000,
                "sceneScore": 0.0,
                "subtitleScore": 0.0,
                "motionScore": 0.01,
            })
        events[2]["samples"][1].update({
            "cadenceDecision": "REUSE",
            "cadenceReason": "SMALL_CHANGE",
            "cadenceReferenceGeneration": 0,
            "cadenceReferenceStreamEpoch": 0,
            "cadenceReferenceFrameId": 1,
            "reuseStreak": 1,
        })

        result = self.validate(events)

        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("crosses input stream" in failure for failure in result["failures"]))

    def test_cadence_threshold_configuration_is_pinned(self):
        events = self.events()
        events[0]["cadenceMode"] = "CONTENT_AWARE_V1"
        events[2]["cadenceMode"] = "CONTENT_AWARE_V1"
        for sample in events[2]["samples"]:
            sample.update({
                "cadenceDecision": "PROCESS", "cadenceReason": "MOTION",
                "cadenceStreamEpoch": 0, "cadenceReferenceGeneration": -1,
                "cadenceReferenceStreamEpoch": -1, "cadenceReferenceFrameId": -1,
                "reuseStreak": 0, "cadenceAnalysisNs": 1_000,
                "sceneScore": 0.0, "subtitleScore": 0.0, "motionScore": 0.01,
            })

        result = self.validate(events)

        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("cadenceAnalyzerVersion" in item for item in result["failures"]))

    def test_cpu_batch_is_rejected(self):
        events = self.events()
        events[2]["mode"] = "CPU"
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_overlap_mode_and_memory_bound_are_reported(self):
        events = self.events()
        events[0].update({
            "postprocessMode": "OVERLAP",
            "postprocessQueueCapacity": 1,
            "outputTensorSlotCount": 2,
            "additionalOverlapTensorBytes": 24_883_200,
        })
        events[2]["postprocessMode"] = "OVERLAP"
        result = self.validate(events)
        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("OVERLAP", result["postprocess_mode"])

    def test_native_output_packer_requires_self_test_and_matching_frame_batches(self):
        events = self.events()
        events[0].update({
            "outputPacker": "NATIVE_NEON",
            "outputPackerSelfTest": "PASS",
        })
        events[2]["outputPacker"] = "NATIVE_NEON"

        result = self.validate(events)

        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("NATIVE_NEON", result["output_packer"])

        events[0]["outputPackerSelfTest"] = "NOT_RUN"
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("outputPackerSelfTest" in failure for failure in result["failures"]))

    def test_frame_batch_mode_must_match_configuration(self):
        events = self.events()
        events[2]["postprocessMode"] = "OVERLAP"
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("postprocessMode" in failure for failure in result["failures"]))

    def test_missing_strict_qnn_attestation_is_rejected(self):
        events = self.events()
        del events[1]["qnnStrict"]["registrationStatus"]
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("qnn strict registrationStatus" in failure for failure in result["failures"]))

    def test_missing_qnn_strict_event_is_rejected(self):
        events = self.events()
        del events[1]
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("expected exactly one qnn_strict event" in failure for failure in result["failures"]))

    def test_cpu_fallback_not_disabled_is_rejected(self):
        events = self.events()
        events[1]["qnnStrict"]["cpuEpFallbackDisabled"] = False
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("cpuEpFallbackDisabled" in failure for failure in result["failures"]))

    def test_unknown_qnn_evidence_scope_is_rejected(self):
        events = self.events()
        events[1]["qnnStrict"]["evidenceScope"] = "UNRECOGNIZED_SCOPE"
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("evidenceScope" in failure for failure in result["failures"]))

    def test_per_node_or_trace_attestation_cannot_overclaim_configuration_scope(self):
        events = self.events()
        events[1]["qnnStrict"]["providerAssignmentVerified"] = True
        events[1]["qnnStrict"]["providerFallbackTraceCaptured"] = True
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("providerAssignmentVerified" in failure for failure in result["failures"]))
        self.assertTrue(any("providerFallbackTraceCaptured" in failure for failure in result["failures"]))

    def test_zero_selected_qnn_npu_devices_is_rejected(self):
        events = self.events()
        events[1]["qnnStrict"]["selectedNpuDeviceCount"] = 0
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("selectedNpuDeviceCount" in failure for failure in result["failures"]))

    def test_dimension_mismatch_is_rejected(self):
        events = self.events()
        events[0]["modelOutputWidth"] = 1280
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_device_error_is_rejected(self):
        events = self.events() + [{"event": "error", "runId": "run-1", "stage": "player", "message": "boom"}]
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_insufficient_frames_are_rejected(self):
        events = self.events()
        events[2]["samples"] = events[2]["samples"][:2]
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_missing_raw_nanosecond_stage_is_rejected(self):
        events = self.events()
        del events[2]["samples"][1]["glUploadStartedNs"]
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("glUploadStartedNs" in failure for failure in result["failures"]))

    def test_queue_depth_above_bounded_contract_is_rejected(self):
        events = self.events()
        events[2]["samples"][1]["maxQueueDepth"] = 3
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("bounded worker queue" in failure for failure in result["failures"]))

    def test_missing_frame_event_is_rejected(self):
        events = self.events()
        del events[2]["samples"][1]
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("missing or out of order" in failure for failure in result["failures"]))

    def test_out_of_order_frame_event_is_rejected(self):
        events = self.events()
        events[2]["samples"][1], events[2]["samples"][2] = (
            events[2]["samples"][2], events[2]["samples"][1]
        )
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("missing or out of order" in failure for failure in result["failures"]))

    def test_duplicate_frame_event_is_rejected(self):
        events = self.events()
        events[2]["samples"].append(dict(events[2]["samples"][1]))
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("duplicate frameId" in failure for failure in result["failures"]))

    def test_unmeasured_final_display_cannot_be_claimed_measured(self):
        events = self.events()
        events[0]["finalDisplayMeasurement"] = "measured"
        result = self.validate(events)
        self.assertEqual("FAIL", result["functional_gate"])
        self.assertTrue(any("finalDisplayMeasurement" in failure for failure in result["failures"]))

    def test_log_loader_accepts_logcat_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "device.log"
            path.write_text("I/QuickSRBenchmark: " + json.dumps(self.events()[0]) + "\n", encoding="utf-8")
            events, errors = validator.load_events(path, "run-1")
        self.assertEqual(1, len(events))
        self.assertEqual([], errors)

    def test_log_loader_rejects_truncated_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "device.log"
            path.write_text('{"schemaVersion":2,"event":"frame_batch"\n', encoding="utf-8")
            events, errors = validator.load_events(path, "run-1")
        self.assertEqual([], events)
        self.assertTrue(any("line 1" in error for error in errors))

    def test_cli_records_plan_and_raw_log_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_path = root / "plan.json"
            log_path = root / "device.log"
            output_path = root / "report.json"
            plan = dict(self.plan)
            plan["cases"] = [self.case]
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            log_path.write_text(
                "\n".join("I/QuickSRBenchmark: " + json.dumps(event) for event in self.events())
                + "\n",
                encoding="utf-8",
            )
            previous_argv = sys.argv
            try:
                sys.argv = [
                    "validate_android_qnn_resolution_log.py", "--plan", str(plan_path),
                    "--case", "1080p-primary", "--run-id", "run-1", "--log", str(log_path),
                    "--output", str(output_path),
                ]
                self.assertEqual(0, validator.main())
            finally:
                sys.argv = previous_argv
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(3, report["schema_version"])
            self.assertEqual(validator.VALIDATOR_VERSION, report["validator_version"])
            self.assertEqual(hashlib.sha256(plan_path.read_bytes()).hexdigest(), report["plan_sha256"])
            self.assertEqual(hashlib.sha256(log_path.read_bytes()).hexdigest(), report["raw_log_sha256"])


if __name__ == "__main__":
    unittest.main()
