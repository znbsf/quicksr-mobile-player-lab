from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from vfi_prefilter import FrameRecord, VfiPrefilter, file_sha256


class VfiPrefilterTest(unittest.TestCase):
    def test_fixture_decisions_cover_hold_cut_seek_and_epoch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(
                [sys.executable, str(Path(__file__).with_name("generate_rights_clear_fixtures.py")), "--output-dir", str(root)],
                check=True,
                capture_output=True,
                text=True,
            )
            manifest = json.loads((root / "fixture-manifest.json").read_text(encoding="utf-8"))
            engine = VfiPrefilter()
            observed = []
            for item in manifest["events"]:
                path = root / item["file"]
                decision = engine.observe(
                    FrameRecord(item["frame_id"], str(path), file_sha256(path), item["stream_id"], item["generation"])
                )
                observed.append(decision)
                self.assertEqual(item["expected_reason"], decision.reason, item["frame_id"])

            self.assertEqual(1, sum(item.decision == "INTERPOLATE" for item in observed))
            self.assertIn("HOLD_EXACT", [item.reason for item in observed])
            self.assertIn("HOLD_NEAR", [item.reason for item in observed])
            self.assertIn("HARD_CUT", [item.reason for item in observed])
            self.assertIn("GENERATION_RESET", [item.reason for item in observed])
            self.assertGreaterEqual([item.reason for item in observed].count("STREAM_EPOCH_RESET"), 4)

    def test_pair_identity_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(
                [sys.executable, str(Path(__file__).with_name("generate_rights_clear_fixtures.py")), "--output-dir", str(root)],
                check=True,
                capture_output=True,
                text=True,
            )
            frames = []
            for name in ("distinct_0.png", "distinct_1.png"):
                path = root / name
                frames.append(FrameRecord(name, str(path), file_sha256(path), "s", 7))
            first = VfiPrefilter()
            second = VfiPrefilter()
            first.observe(frames[0])
            second.observe(frames[0])
            self.assertEqual(first.observe(frames[1]).pair_identity, second.observe(frames[1]).pair_identity)


if __name__ == "__main__":
    unittest.main()
