"""Unit tests for the Android QNN native output-packer ABBA summarizer."""

import argparse
import unittest

import summarize_android_qnn_native_packer_abba as summarizer


class SummarizerTests(unittest.TestCase):
    def test_required_matched_occurrences_must_be_positive(self):
        for value in ("0", "-1"):
            with self.assertRaises(argparse.ArgumentTypeError):
                summarizer.positive_int(value)

    def test_percent_change_preserves_regression_direction(self):
        self.assertAlmostEqual(-50.0, summarizer.percent_change(12.0, 6.0))

    def test_device_instrumentation_rejects_zero_tests(self):
        evidence = {
            "schema_version": 1,
            "experiment": "android-qnn-native-output-packer-device-tests",
            "status": "PASS",
            "runner": "androidx.test.runner.AndroidJUnitRunner",
            "discovered_test_count": 0,
            "passed_test_count": 0,
            "per_test_final_status_code": 0,
            "instrumentation_code": -1,
            "scope": "packer_correctness_alpha_boundary_mapping_and_buffer_ownership_only",
            "excluded_claims": [
                "performance",
                "complete_180_frame_crc_cycle",
                "final_display",
                "long_run_memory_or_lifecycle",
            ],
            "raw_device_output_published": False,
        }

        with self.assertRaises(ValueError):
            summarizer.validate_device_instrumentation(evidence)


if __name__ == "__main__":
    unittest.main()
