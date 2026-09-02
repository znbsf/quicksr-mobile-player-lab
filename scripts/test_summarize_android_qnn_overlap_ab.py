"""Unit tests for the Android QNN overlap A/B summarizer."""

import argparse
import unittest

import summarize_android_qnn_overlap_ab as summarizer


class SummarizerTests(unittest.TestCase):
    def test_required_matched_occurrences_must_be_positive(self):
        for value in ("0", "-1"):
            with self.assertRaises(argparse.ArgumentTypeError):
                summarizer.positive_int(value)

    def test_positive_sequence_frame_count_is_accepted(self):
        self.assertEqual(180, summarizer.positive_int("180"))


if __name__ == "__main__":
    unittest.main()
