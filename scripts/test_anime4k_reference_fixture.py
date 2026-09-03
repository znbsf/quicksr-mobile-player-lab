import tempfile
import unittest
from pathlib import Path

import anime4k_reference_fixture as fixture


class Anime4kReferenceFixtureTests(unittest.TestCase):
    def test_fixed_fixture_is_deterministic_and_contains_mid_gray(self):
        first = fixture.fixed_opaque_rgb()
        second = fixture.fixed_opaque_rgb()
        self.assertEqual(first, second)
        self.assertEqual(fixture.INPUT_WIDTH * fixture.INPUT_HEIGHT * 3, len(first))
        self.assertIn(bytes((128, 128, 128)), first)

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = fixture.prepare(root, fixture.VENDORED_SHADER_PATH)
            self.assertEqual(manifest["schema_version"], fixture.MANIFEST_SCHEMA_VERSION)
            self.assertEqual(manifest["fixture_id"], fixture.FIXTURE_ID)
            self.assertEqual(manifest["anime4k_source"]["sha256"], fixture.SHADER_SHA256)
            verified, input_path = fixture.verify_manifest(
                root / "anime4k-reference-manifest.json")
            self.assertEqual(verified, manifest)
            self.assertEqual(fixture.file_sha256(input_path), manifest["input"]["file_sha256"])

    def test_uniform_pair_is_only_a_declared_match_and_changed_pixel_is_diff(self):
        payload = bytes([64, 128, 192]) * (fixture.OUTPUT_WIDTH * fixture.OUTPUT_HEIGHT)
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = fixture.prepare(root, fixture.VENDORED_SHADER_PATH)
            manifest_path = root / "anime4k-reference-manifest.json"
            input_sha256 = manifest["input"]["file_sha256"]
            android = root / "android.ppm"
            mpv = root / "mpv.ppm"
            fixture.write_ppm(
                android, fixture.OUTPUT_WIDTH, fixture.OUTPUT_HEIGHT, payload)
            fixture.write_ppm(mpv, fixture.OUTPUT_WIDTH, fixture.OUTPUT_HEIGHT, payload)
            exact = fixture.compare_outputs(
                android,
                mpv,
                manifest_path,
                input_sha256,
                input_sha256,
                fixture.file_sha256(android),
                fixture.file_sha256(mpv),
            )
            self.assertTrue(exact["exact"])
            self.assertEqual(exact["status"], "DECLARED_PIXEL_MATCH_ONLY")
            self.assertNotEqual(exact["status"], "PASS")
            self.assertEqual(
                exact["runtime_equivalence"],
                "NOT_ESTABLISHED_NO_REPLAYABLE_CAPTURE_RECEIPT")
            self.assertTrue(exact["psnr_is_infinite"])
            self.assertEqual(1.0, exact["global_ssim"])
            self.assertEqual(0.0, exact["edge_mae"])

            changed = bytearray(payload)
            changed[0] += 1
            fixture.write_ppm(
                mpv, fixture.OUTPUT_WIDTH, fixture.OUTPUT_HEIGHT, bytes(changed))
            result = fixture.compare_outputs(
                android,
                mpv,
                manifest_path,
                input_sha256,
                input_sha256,
                fixture.file_sha256(android),
                fixture.file_sha256(mpv),
            )
            self.assertFalse(result["exact"])
            self.assertEqual(1, result["mismatch_pixels"])
            self.assertEqual(1, result["max_channel_error_u8"])
            self.assertFalse(result["psnr_is_infinite"])
            self.assertLess(result["global_ssim"], 1.0)

            with self.assertRaisesRegex(ValueError, "declared Android output"):
                fixture.compare_outputs(
                    android,
                    mpv,
                    manifest_path,
                    input_sha256,
                    input_sha256,
                    "0" * 64,
                    fixture.file_sha256(mpv),
                )


if __name__ == "__main__":
    unittest.main()
