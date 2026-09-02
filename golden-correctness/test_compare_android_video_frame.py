"""Focused tests for the Android video-frame PC golden comparator."""

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_android_video_frame as comparator


class VideoFrameComparatorTests(unittest.TestCase):
    def test_compare_tensors_respects_relative_tolerance(self):
        pc = np.asarray([[[[1.0, 2.0]]]], dtype=np.float32)
        android = np.asarray([[[[1.001, 2.002]]]], dtype=np.float32)
        result = comparator.compare_tensors(android, pc, 0.001, 0.001)
        self.assertEqual(0, result["mismatchCount"])
        self.assertEqual(0, result["nonfiniteCount"])

    def test_nonfinite_comparison_metrics_stay_strict_json(self):
        pc = np.asarray([[[[1.0]]]], dtype=np.float32)
        android = np.asarray([[[[np.nan]]]], dtype=np.float32)
        result = comparator.compare_tensors(android, pc, 0.001, 0.001)
        self.assertEqual(1, result["nonfiniteCount"])
        self.assertIsNone(result["maxAbsoluteError"])
        self.assertIsNone(result["maxRelativeError"])
        self.assertIsNone(result["meanAbsoluteError"])
        json.dumps(result, allow_nan=False)

    def test_strict_qnn_attestation_fails_closed(self):
        plan = {
            "required_qnn_strict": {
                "registrationStatus": "PASS",
                "npuSelectionStatus": "PASS",
                "providerConfigurationStatus": "PASS",
                "backendType": "htp",
                "cpuEpFallbackDisabled": True,
                "minimumSelectedNpuDeviceCount": 1,
                "strictReady": True,
            }
        }
        metadata = {"qnnStrict": {"registrationStatus": "PASS", "selectedNpuDeviceCount": 0}}
        failures = comparator.strict_qnn_failures(metadata, plan)
        self.assertGreaterEqual(len(failures), 1)
        self.assertTrue(any("selectedNpuDeviceCount" in item for item in failures))

    def test_load_tensor_rejects_hash_mismatch(self):
        shape = [1, 3, 1, 1]
        raw = np.arange(3, dtype="<f4").tobytes()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "input.f32le").write_bytes(raw)
            spec = {
                "file": "input.f32le",
                "dtype": "float32",
                "byteOrder": "little-endian",
                "shape": shape,
                "elementCount": 3,
                "bytes": len(raw),
                "sha256LittleEndianFloat32": hashlib.sha256(b"wrong").hexdigest(),
            }
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                comparator.load_tensor(root, spec, "input")

    def test_rgb8_conversion_preserves_nchw_channels(self):
        value = np.asarray([[[[0.0]], [[0.5]], [[1.0]]]], dtype=np.float32)
        rgb = comparator.rgb8_from_nchw(value)
        self.assertEqual((1, 1, 3), rgb.shape)
        self.assertEqual([0, 128, 255], rgb[0, 0].tolist())

    def test_resolve_within_rejects_output_outside_ignored_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            allowed = root / "device-results"
            accepted = comparator.resolve_within(allowed / "run" / "comparison", allowed, "output directory")
            self.assertEqual((allowed / "run" / "comparison").resolve(), accepted)
            with self.assertRaisesRegex(ValueError, "Git-ignored"):
                comparator.resolve_within(root / "published-output", allowed, "output directory")

    def test_explicit_evidence_root_stays_below_device_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original_root = comparator.DEVICE_RESULTS_ROOT
            comparator.DEVICE_RESULTS_ROOT = root / "device-results"
            try:
                selected = comparator.resolve_evidence_root(
                    comparator.DEVICE_RESULTS_ROOT / "android-video-evidence-retry"
                )
                self.assertEqual(
                    (comparator.DEVICE_RESULTS_ROOT / "android-video-evidence-retry").resolve(),
                    selected,
                )
                with self.assertRaisesRegex(ValueError, "must be a child"):
                    comparator.resolve_evidence_root(comparator.DEVICE_RESULTS_ROOT)
            finally:
                comparator.DEVICE_RESULTS_ROOT = original_root


if __name__ == "__main__":
    unittest.main()
