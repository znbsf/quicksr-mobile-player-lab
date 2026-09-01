"""Unit tests for the structured Android QNN Logcat validator."""

import json
import tempfile
import unittest
from pathlib import Path

import validate_android_qnn_resolution_log as validator


class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.plan = {
            "plan_id": "test-plan",
            "warmup_frames": 1,
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
            "schemaVersion": 1, "event": "configuration", "runId": "run-1",
            "mode": "QUICKSR_QNN", "tuning": "SUSTAINED", "profile": "FULL_1080P_3X",
            "qnnRuntimeExpected": True, "modelInputWidth": 640, "modelInputHeight": 360,
            "modelOutputWidth": 1920, "modelOutputHeight": 1080,
            "canvasWidth": 1920, "canvasHeight": 1080,
        }
        samples = []
        for frame in range(3):
            sample = {field: 1 for field in validator.TIMING_FIELDS}
            sample.update({"frame": frame, "ptsUs": frame * 41667,
                           "observedNs": 1_000_000_000 + frame * 40_000_000})
            sample["totalProcessingMs"] = 30
            samples.append(sample)
        batch = {
            "schemaVersion": 1, "event": "frame_batch", "runId": "run-1",
            "mode": "QNN_HTP", "tuning": "SUSTAINED", "profile": "FULL_1080P_3X",
            "modelInputWidth": 640, "modelInputHeight": 360,
            "modelOutputWidth": 1920, "modelOutputHeight": 1080, "samples": samples,
        }
        return [config, batch]

    def validate(self, events):
        return validator.validate(self.plan, self.case, events, "run-1", [])

    def test_valid_qnn_run_passes_and_classifies_24fps(self):
        result = self.validate(self.events())
        self.assertEqual("PASS", result["functional_gate"])
        self.assertEqual("realtime_24", result["performance_class"])

    def test_cpu_batch_is_rejected(self):
        events = self.events()
        events[1]["mode"] = "CPU"
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_dimension_mismatch_is_rejected(self):
        events = self.events()
        events[0]["modelOutputWidth"] = 1280
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_device_error_is_rejected(self):
        events = self.events() + [{"event": "error", "runId": "run-1", "stage": "player", "message": "boom"}]
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_insufficient_frames_are_rejected(self):
        events = self.events()
        events[1]["samples"] = events[1]["samples"][:2]
        self.assertEqual("FAIL", self.validate(events)["functional_gate"])

    def test_log_loader_accepts_logcat_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "device.log"
            path.write_text("I/QuickSRBenchmark: " + json.dumps(self.events()[0]) + "\n", encoding="utf-8")
            events, errors = validator.load_events(path, "run-1")
        self.assertEqual(1, len(events))
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
