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

    def test_compare_reports_exact_and_changed_pixel(self):
        payload = bytes([64, 128, 192]) * (fixture.OUTPUT_WIDTH * fixture.OUTPUT_HEIGHT)
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            android = root / "android.ppm"
            mpv = root / "mpv.ppm"
            fixture.write_ppm(
                android, fixture.OUTPUT_WIDTH, fixture.OUTPUT_HEIGHT, payload)
            fixture.write_ppm(mpv, fixture.OUTPUT_WIDTH, fixture.OUTPUT_HEIGHT, payload)
            exact = fixture.compare_outputs(android, mpv)
            self.assertTrue(exact["exact"])
            self.assertTrue(exact["psnr_is_infinite"])
            self.assertEqual(1.0, exact["global_ssim"])
            self.assertEqual(0.0, exact["edge_mae"])

            changed = bytearray(payload)
            changed[0] += 1
            fixture.write_ppm(
                mpv, fixture.OUTPUT_WIDTH, fixture.OUTPUT_HEIGHT, bytes(changed))
            result = fixture.compare_outputs(android, mpv)
            self.assertFalse(result["exact"])
            self.assertEqual(1, result["mismatch_pixels"])
            self.assertEqual(1, result["max_channel_error_u8"])
            self.assertFalse(result["psnr_is_infinite"])
            self.assertLess(result["global_ssim"], 1.0)


if __name__ == "__main__":
    unittest.main()
