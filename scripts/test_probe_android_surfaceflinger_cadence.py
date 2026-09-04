"""Unit tests for the SurfaceFlinger actual-present cadence probe."""

import unittest

from probe_android_surfaceflinger_cadence import (
    extract_telemetry_identity,
    parse_latency_output,
    summarize_present_records,
)


class SurfaceFlingerCadenceProbeTests(unittest.TestCase):
    def test_parses_only_valid_actual_present_records(self):
        refresh, records = parse_latency_output(
            "8333333\n"
            "0 0 0\n"
            "100 110 105\n"
            "200 9223372036854775807 205\n"
            "300 310 305\n"
        )

        self.assertEqual(8_333_333, refresh)
        self.assertEqual([(100, 110, 105), (300, 310, 305)], records)

    def test_perfect_24fps_actual_present_sequence(self):
        interval = 1_000_000_000 // 24
        records = {
            index * interval: (1_000_000_000 + index * interval, index * interval)
            for index in range(100)
        }

        summary = summarize_present_records(records, 24.0)

        self.assertAlmostEqual(24.0, summary["actual_present_fps"], places=5)
        self.assertEqual(0, summary["duplicate_actual_present_times"])
        self.assertEqual(0, summary["intervals_over_1_5_source_frames"])
        self.assertEqual(0, summary["intervals_under_0_5_source_frames"])

    def test_duplicate_present_and_two_frame_gap_are_exposed(self):
        interval = 1_000_000_000 // 24
        records = {
            0: (1_000_000_000, 0),
            interval: (1_000_000_000, interval),
            2 * interval: (1_000_000_000 + 2 * interval, 2 * interval),
        }

        summary = summarize_present_records(records, 24.0)

        self.assertEqual(1, summary["duplicate_actual_present_times"])
        self.assertEqual(1, summary["intervals_over_1_5_source_frames"])
        self.assertEqual(1, summary["implied_missing_present_intervals"])

    def test_original_identity_does_not_require_qnn_event(self):
        identity = extract_telemetry_identity(
            [
                {
                    "event": "configuration",
                    "runId": "baseline-1",
                    "mode": "ORIGINAL",
                    "benchmarkRoute": "MEDIA3_DISPLAY_BASELINE",
                    "neuralProcessingEnabled": False,
                    "qnnStrictRequired": False,
                    "prototypeBuildId": "build-1",
                    "sourceIdentitySha256": "a" * 64,
                }
            ],
            "baseline-1",
            "ORIGINAL",
        )

        self.assertEqual("ORIGINAL", identity["video_mode"])
        self.assertFalse(identity["neural_processing_enabled"])
        self.assertNotIn("model_variant", identity)

    def test_qnn_identity_fails_closed_without_strict_event(self):
        with self.assertRaisesRegex(RuntimeError, "qnn_strict"):
            extract_telemetry_identity(
                [
                    {
                        "event": "configuration",
                        "runId": "qnn-1",
                        "mode": "QUICKSR_QNN",
                        "benchmarkRoute": "MEDIA3_QUICKSR_EFFECT",
                        "neuralProcessingEnabled": True,
                        "qnnStrictRequired": True,
                        "modelVariant": "model-a",
                    }
                ],
                "qnn-1",
                "QUICKSR_QNN",
            )

    def test_qnn_identity_pins_deferred_output_copy(self):
        identity = extract_telemetry_identity(
            [
                {
                    "event": "configuration",
                    "runId": "qnn-deferred",
                    "mode": "QUICKSR_QNN",
                    "benchmarkRoute": "MEDIA3_QUICKSR_EFFECT",
                    "neuralProcessingEnabled": True,
                    "qnnStrictRequired": True,
                    "modelVariant": "model-a",
                    "deferredOutputCopy": True,
                    "pinnedOrtOutputTensorSlotCount": 2,
                    "additionalPinnedOrtOutputBytes": 24_883_200,
                    "tensorOutputCopyMeasurement": "measured_postprocess_thread_bulk_copy",
                    "glUploadRoute": "DIRECT_MEDIA3_OUTPUT_TEXTURE",
                    "pboUpload": True,
                    "glUploadPboSlotCount": 2,
                },
                {
                    "event": "qnn_strict",
                    "runId": "qnn-deferred",
                    "modelVariant": "model-a",
                    "qnnStrict": {
                        "strictReady": True,
                        "deferredOutputCopy": True,
                        "pinnedOrtOutputTensorSlotCount": 2,
                        "glUploadRoute": "DIRECT_MEDIA3_OUTPUT_TEXTURE",
                        "pboUpload": True,
                        "glUploadPboSlotCount": 2,
                    },
                },
            ],
            "qnn-deferred",
            "QUICKSR_QNN",
        )

        self.assertTrue(identity["deferred_output_copy"])
        self.assertEqual(2, identity["pinned_ort_output_tensor_slot_count"])


if __name__ == "__main__":
    unittest.main()
