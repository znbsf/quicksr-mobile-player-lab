from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from run_vfi_android_resident_matrix import parse_timing
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

    def test_resident_fixture_has_six_distinct_pairs_per_level(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).with_name("generate_resident_fixtures.py")),
                    "--output-dir", str(root), "--levels", "160x90", "256x144", "320x180",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            manifest = json.loads((root / "resident-fixture-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(3, len(manifest["levels"]))
            for level in manifest["levels"]:
                self.assertEqual(6, len(level["decisions"]) - 1)
                self.assertTrue(all(item["reason"] == "DISTINCT_DRAWING" for item in level["decisions"][1:]))
                self.assertEqual((level["width"] + 31) // 32 * 32, level["padded_width"])
                self.assertEqual((level["height"] + 31) // 32 * 32, level["padded_height"])

    def test_resident_timing_parser_selects_six_midpoints(self) -> None:
        lines = ["VFI_VULKAN_INIT_WALL_NS=25000000", "VFI_MODEL_LOAD_WALL_NS gpu=0 ns=1300000000"]
        for task_id in range(14):
            timestep = 0.5 if task_id in (1, 3, 5, 7, 9, 11) else 0.0
            lines.extend(
                [
                    f"VFI_DECODE_WALL_NS id={task_id} ns={400000 + task_id}",
                    (
                        f"VFI_MODEL_WALL_NS id={task_id} ns={30000000 + task_id} "
                        f"timestep={timestep} width=160 height=90 padded_width=160 padded_height=96"
                    ),
                    f"VFI_ENCODE_WALL_NS id={task_id} ns={2000000 + task_id}",
                ]
            )
        timing = parse_timing("\n".join(lines))
        self.assertEqual([1, 3, 5, 7, 9, 11], [item["id"] for item in timing["midpoint_calls"]])
        self.assertEqual(14, len(timing["model_calls"]))
        self.assertEqual(25_000_000, timing["vulkan_init_wall_time_ns"])
        self.assertEqual(1_300_000_000, timing["model_load"]["wall_time_ns"])


if __name__ == "__main__":
    unittest.main()
