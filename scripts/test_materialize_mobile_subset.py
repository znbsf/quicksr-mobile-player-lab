"""Contract tests for the local-only rights-clear mobile subset materializer."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import materialize_mobile_subset as materializer  # noqa: E402


class MobileSubsetContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(
            (ROOT / "contracts" / "mobile-rights-clear-subset.json").read_text(encoding="utf-8")
        )
        cls.assets = json.loads((ROOT / "pc-benchmark" / "open-assets.json").read_text(encoding="utf-8"))

    def test_contract_has_required_coverage_and_only_pinned_sources(self) -> None:
        materializer.validate_contract_structure(self.contract, self.assets)
        clips = self.contract["clips"]
        self.assertEqual(3, sum(clip["tier"] == "quick-functional" for clip in clips))
        self.assertEqual(8, sum(clip["tier"] == "core" for clip in clips))
        self.assertEqual({24, 30}, {clip["output"]["fps"] for clip in clips})
        self.assertEqual({360, 480, 720}, {clip["output"]["height"] for clip in clips})
        self.assertTrue(all(clip["output"]["frame_count"] >= 135 for clip in clips))
        self.assertEqual({"16:9", "1:1"}, {clip["output"]["content_layout"] for clip in clips})
        self.assertEqual({"clean-lanczos", "blur-jpeg-q35"}, {clip["degradation_id"] for clip in clips})
        known_assets = {asset["id"] for asset in self.assets["assets"]}
        self.assertTrue(all(clip["source"]["asset_id"] in known_assets for clip in clips))

    def test_square_content_rect_is_pillarboxed_without_stretching(self) -> None:
        self.assertEqual(
            {"x": 280, "y": 0, "width": 720, "height": 720},
            materializer.content_rect(1280, 720, "1:1"),
        )
        self.assertEqual(
            {"x": 0, "y": 0, "width": 1280, "height": 720},
            materializer.content_rect(1280, 720, "16:9"),
        )

    def test_frame_plan_has_deterministic_pts_and_native_frame_cadence(self) -> None:
        clip = next(clip for clip in self.contract["clips"] if clip["id"] == "quick-bbb-360p-24-clean")
        source_frames = [
            materializer.SourceFrame(
                asset_id="bbb-1080-sequence-01000-01014",
                source_frame_number=1000 + index,
                path=ROOT / "build" / "pc-benchmark" / "assets" / f"fixture-{index}.png",
                relative_path=f"build/pc-benchmark/assets/fixture-{index}.png",
                sha256=f"{index:064x}",
            )
            for index in range(15)
        ]
        plan = materializer.frame_plan(clip, source_frames)
        self.assertEqual(clip["output"]["frame_count"], len(plan))
        self.assertEqual([0, 41666, 83333], [entry["pts_us"] for entry in plan[:3]])
        self.assertEqual([1000, 1000, 1001], [entry["source_frame_number"] for entry in plan[:3]])
        expected_last = 1000 + (((clip["output"]["frame_count"] - 1) * 15 // 24) % 15)
        self.assertEqual(expected_last, plan[-1]["source_frame_number"])

    def test_hash_verifier_fails_closed_when_observed_bytes_do_not_match(self) -> None:
        target = Path(__file__).resolve()
        expected = materializer.sha256_file(target)
        with mock.patch.object(materializer, "sha256_file", return_value="0" * 64):
            with self.assertRaisesRegex(materializer.ContractError, "SHA-256 mismatch"):
                materializer.verify_file(target, target.stat().st_size, expected)


if __name__ == "__main__":
    unittest.main(verbosity=2)
