from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from run_vfi_android_resident_matrix import parse_timing, validate_fixture_manifest
from vfi_prefilter import FrameRecord, VfiPrefilter, file_sha256


def timing_stderr(
    model_ids: list[int] | None = None,
    decode_ids: list[int] | None = None,
    encode_ids: list[int] | None = None,
) -> str:
    model_ids = list(range(14)) if model_ids is None else model_ids
    decode_ids = list(range(14)) if decode_ids is None else decode_ids
    encode_ids = list(range(14)) if encode_ids is None else encode_ids
    lines = ["VFI_VULKAN_INIT_WALL_NS=25000000", "VFI_MODEL_LOAD_WALL_NS gpu=0 ns=1300000000"]
    lines.extend(f"VFI_DECODE_WALL_NS id={task_id} ns={400000 + task_id}" for task_id in decode_ids)
    for task_id in model_ids:
        timestep = 0.5 if task_id in (1, 3, 5, 7, 9, 11) else 0.0
        lines.append(
            f"VFI_MODEL_WALL_NS id={task_id} ns={30000000 + task_id} "
            f"timestep={timestep} width=160 height=90 padded_width=160 padded_height=96"
        )
    lines.extend(f"VFI_ENCODE_WALL_NS id={task_id} ns={2000000 + task_id}" for task_id in encode_ids)
    return "\n".join(lines)


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

    def test_resident_fixture_supports_candidate_padding_multiple(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).with_name("generate_resident_fixtures.py")),
                    "--output-dir", str(root), "--levels", "160x90", "--padding-multiple", "128",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            manifest = json.loads((root / "resident-fixture-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(128, manifest["padding_multiple"])
            self.assertEqual(256, manifest["levels"][0]["padded_width"])
            self.assertEqual(128, manifest["levels"][0]["padded_height"])
            validate_fixture_manifest(manifest, root)

    def test_resident_timing_parser_selects_six_midpoints(self) -> None:
        timing = parse_timing(timing_stderr())
        self.assertEqual([1, 3, 5, 7, 9, 11], [item["id"] for item in timing["midpoint_calls"]])
        self.assertEqual(14, len(timing["model_calls"]))
        self.assertEqual(25_000_000, timing["vulkan_init_wall_time_ns"])
        self.assertEqual(1_300_000_000, timing["model_load"]["wall_time_ns"])

    def test_resident_timing_parser_rejects_id_gap(self) -> None:
        gap_with_unexpected_id = list(range(13)) + [14]
        for stage in ("model", "decode", "encode"):
            arguments = {f"{stage}_ids": gap_with_unexpected_id}
            with self.subTest(stage=stage), self.assertRaisesRegex(RuntimeError, f"{stage} task ids must be exactly"):
                parse_timing(timing_stderr(**arguments))

    def test_resident_timing_parser_rejects_duplicate_ids_in_every_stage(self) -> None:
        duplicate_and_gap = list(range(13)) + [12]
        for stage in ("model", "decode", "encode"):
            arguments = {f"{stage}_ids": duplicate_and_gap}
            with self.subTest(stage=stage), self.assertRaisesRegex(RuntimeError, f"{stage} task ids must be exactly"):
                parse_timing(timing_stderr(**arguments))

    def test_resident_manifest_validation_rejects_changed_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(
                [
                    sys.executable, str(Path(__file__).with_name("generate_resident_fixtures.py")),
                    "--output-dir", str(root), "--levels", "160x90",
                ],
                check=True, capture_output=True, text=True,
            )
            manifest = json.loads((root / "resident-fixture-manifest.json").read_text(encoding="utf-8"))
            changed = root / "160x90" / "input" / "frame_006.png"
            changed.write_bytes(changed.read_bytes() + b"changed-after-manifest")
            with self.assertRaisesRegex(RuntimeError, "fixture identity mismatch"):
                validate_fixture_manifest(manifest, root)

    def test_resident_manifest_validation_rejects_schema_metadata_and_file_set_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(
                [
                    sys.executable, str(Path(__file__).with_name("generate_resident_fixtures.py")),
                    "--output-dir", str(root), "--levels", "160x90",
                ],
                check=True, capture_output=True, text=True,
            )
            original = json.loads((root / "resident-fixture-manifest.json").read_text(encoding="utf-8"))
            cases = []
            wrong_schema = json.loads(json.dumps(original))
            wrong_schema["schema"] = "unexpected"
            cases.append((wrong_schema, "schema"))
            wrong_padding = json.loads(json.dumps(original))
            wrong_padding["levels"][0]["padded_height"] += 32
            cases.append((wrong_padding, "invalid padded_height"))
            missing_identity = json.loads(json.dumps(original))
            del missing_identity["levels"][0]["files"]["ground-truth/mid_005.png"]
            cases.append((missing_identity, "exactly 7 inputs and 6 ground truths"))
            for manifest, message in cases:
                with self.subTest(message=message), self.assertRaisesRegex(RuntimeError, message):
                    validate_fixture_manifest(manifest, root)


if __name__ == "__main__":
    unittest.main()
