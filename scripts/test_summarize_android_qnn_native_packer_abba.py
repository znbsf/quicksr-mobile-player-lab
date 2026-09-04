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


if __name__ == "__main__":
    unittest.main()
