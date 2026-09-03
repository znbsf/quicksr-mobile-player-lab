from __future__ import annotations

import json
from pathlib import Path
import shutil
import tempfile
import unittest

from PIL import Image

import anime_visual_quality_gate as gate


class AnimeVisualQualityGateTests(unittest.TestCase):
    def prepare(self, root: Path) -> tuple[Path, dict]:
        contract_root = root / "contract"
        contract = gate.prepare_contract(contract_root)
        return contract_root / "contract.json", contract

    def make_passing_submission(self, root: Path, contract_path: Path, contract: dict) -> Path:
        output_root = root / "outputs"
        spatial_contract_path = contract_path.parent / contract["spatial_contract"]["path"]
        spatial = json.loads(spatial_contract_path.read_text(encoding="utf-8"))
        spatial_outputs = []
        for case in spatial["cases"]:
            source = spatial_contract_path.parent / case["reference"]["path"]
            destination = output_root / "spatial" / f"{case['id']}.png"
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            spatial_outputs.append({
                "case_id": case["id"],
                "input_sha256": case["input"]["sha256"],
                "output_path": destination.relative_to(root).as_posix(),
                "output_sha256": gate.file_sha256(destination),
            })

        temporal_outputs = []
        for case in contract["temporal_cases"]:
            observed_frames = []
            output_by_frame: dict[int, Path] = {}
            for frame in case["frames"]:
                destination = (
                    output_root / "temporal" / case["id"]
                    / f"frame-{frame['frame_id']:03d}.png"
                )
                destination.parent.mkdir(parents=True, exist_ok=True)
                reference_frame_id = frame["expected_reference_frame_id"]
                if reference_frame_id is None:
                    source = contract_path.parent / frame["reference"]["path"]
                else:
                    source = output_by_frame[reference_frame_id]
                shutil.copyfile(source, destination)
                output_by_frame[frame["frame_id"]] = destination
                reference_input = (
                    case["frames"][reference_frame_id - 1]["input"]["sha256"]
                    if reference_frame_id is not None else None
                )
                observed_frames.append({
                    "frame_id": frame["frame_id"],
                    "pts_us": frame["pts_us"],
                    "input_sha256": frame["input"]["sha256"],
                    "decision": frame["expected_decision"],
                    "reference_frame_id": reference_frame_id,
                    "reference_input_sha256": reference_input,
                    "output_path": destination.relative_to(root).as_posix(),
                    "output_sha256": gate.file_sha256(destination),
                })
            temporal_outputs.append({"case_id": case["id"], "frames": observed_frames})

        submission = {
            "schema_version": gate.SUBMISSION_SCHEMA_VERSION,
            "status": gate.SUBMISSION_STATUS,
            "contract_sha256": gate.file_sha256(contract_path),
            "producer_label": "unit-test-oracle-filled-submission",
            "runtime_trace_sha256": None,
            "spatial_outputs": spatial_outputs,
            "temporal_outputs": temporal_outputs,
        }
        submission_path = root / "submission.json"
        submission_path.write_text(json.dumps(submission, indent=2) + "\n", encoding="utf-8")
        return submission_path

    def test_contract_is_deterministic_and_covers_all_required_slices(self) -> None:
        with tempfile.TemporaryDirectory() as first_temp, tempfile.TemporaryDirectory() as second_temp:
            first_path, first = self.prepare(Path(first_temp))
            second_path, second = self.prepare(Path(second_temp))
            scenarios = {case["scenario"] for case in first["temporal_cases"]}
            self.assertEqual(scenarios, {
                "mixed-one-two-three", "slow-pan", "mouth-particle", "hard-cut",
                "fade", "high-contrast-subtitle", "low-contrast-subtitle",
            })
            self.assertEqual(len(first["temporal_cases"]), 14)
            self.assertEqual(
                [case["frames"][0]["input"]["sha256"] for case in first["temporal_cases"]],
                [case["frames"][0]["input"]["sha256"] for case in second["temporal_cases"]],
            )
            first_spatial = json.loads(
                (first_path.parent / first["spatial_contract"]["path"]).read_text(encoding="utf-8"))
            second_spatial = json.loads(
                (second_path.parent / second["spatial_contract"]["path"]).read_text(encoding="utf-8"))
            self.assertEqual(len(first_spatial["cases"]), 6)
            self.assertEqual(
                {case["source"] for case in first_spatial["cases"]},
                {"synthetic-lineart-edge", "synthetic-high-contrast-subtitle",
                 "synthetic-low-contrast-subtitle"},
            )
            self.assertEqual(
                [case["input"]["sha256"] for case in first_spatial["cases"]],
                [case["input"]["sha256"] for case in second_spatial["cases"]],
            )
            mixed = next(
                case for case in first["temporal_cases"]
                if case["id"] == "mixed-one-two-three--clean-lanczos")
            self.assertEqual(
                [frame["expected_decision"] for frame in mixed["frames"]],
                ["PROCESS", "PROCESS", "REUSE", "PROCESS", "REUSE", "REUSE",
                 "PROCESS", "PROCESS", "REUSE"],
            )

    def test_oracle_filled_submission_passes_only_declared_conformance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract_path, contract = self.prepare(root)
            submission_path = self.make_passing_submission(root, contract_path, contract)
            submission = json.loads(submission_path.read_text(encoding="utf-8"))
            # A hash by itself is not a replayable trace or an execution receipt.
            submission["runtime_trace_sha256"] = "a" * 64
            submission_path.write_text(json.dumps(submission, indent=2) + "\n", encoding="utf-8")
            report = gate.evaluate(contract_path, submission_path, root / "report.json")
            self.assertEqual(report["status"], "evaluated-declared-oracle-conformance")
            self.assertEqual(report["declared_oracle_conformance"], "PASS")
            self.assertNotIn("machine_gate", report)
            self.assertNotIn("observed_runtime", report)
            self.assertEqual(report["runtime_evidence"]["status"], "NOT_BOUND")
            self.assertEqual(report["runtime_evidence"]["submitted_trace_sha256"], "a" * 64)
            self.assertIn("cannot prove execution", report["runtime_evidence"]["reason"])
            self.assertEqual(report["summary"]["declared_wrong_reuse_count"], 0)
            self.assertEqual(report["summary"]["declared_wrong_reuse_rate"], 0.0)
            self.assertEqual(report["summary"]["declared_missed_reuse_count"], 0)
            self.assertEqual(report["summary"]["declared_missed_reuse_rate"], 0.0)
            self.assertEqual(report["summary"]["temporal_case_count"], 14)
            self.assertEqual(report["summary"]["spatial_case_count"], 6)
            for row in [*report["spatial_rows"], *report["temporal_rows"]]:
                metrics = row.get("candidate", row.get("quality"))
                self.assertIn("psnr_db", metrics)
                self.assertIn("global_ssim", metrics)
                self.assertIn("edge_mae", metrics)

    def test_declared_reuse_pts_and_reference_mismatch_fail_conformance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract_path, contract = self.prepare(root)
            submission_path = self.make_passing_submission(root, contract_path, contract)
            submission = json.loads(submission_path.read_text(encoding="utf-8"))
            mixed = next(
                case for case in submission["temporal_outputs"]
                if case["case_id"] == "mixed-one-two-three--clean-lanczos")
            mixed["frames"][0]["decision"] = "REUSE"
            mixed["frames"][2]["decision"] = "PROCESS"
            mixed["frames"][3]["pts_us"] += 1
            mixed["frames"][4]["reference_frame_id"] = 1
            submission_path.write_text(json.dumps(submission, indent=2) + "\n", encoding="utf-8")
            report = gate.evaluate(contract_path, submission_path, root / "failed-report.json")
            self.assertEqual(report["declared_oracle_conformance"], "FAIL")
            self.assertEqual(report["runtime_evidence"]["status"], "NOT_BOUND")
            self.assertEqual(report["summary"]["declared_wrong_reuse_count"], 1)
            self.assertGreater(report["summary"]["declared_wrong_reuse_rate"], 0.0)
            self.assertEqual(report["summary"]["declared_missed_reuse_count"], 1)
            self.assertGreater(report["summary"]["declared_missed_reuse_rate"], 0.0)
            self.assertEqual(report["summary"]["declared_pts_identity_failure_count"], 1)
            self.assertGreaterEqual(
                report["summary"]["declared_reference_identity_failure_count"], 1)

    def test_missing_frame_and_contract_hash_mismatch_raise(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract_path, contract = self.prepare(root)
            submission_path = self.make_passing_submission(root, contract_path, contract)
            submission = json.loads(submission_path.read_text(encoding="utf-8"))
            submission["contract_sha256"] = "0" * 64
            submission_path.write_text(json.dumps(submission, indent=2) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "contract SHA-256"):
                gate.evaluate(contract_path, submission_path, root / "report.json")

            submission["contract_sha256"] = gate.file_sha256(contract_path)
            submission["temporal_outputs"][0]["frames"].pop()
            submission_path.write_text(json.dumps(submission, indent=2) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "complete ordered frame sequence"):
                gate.evaluate(contract_path, submission_path, root / "report.json")

            forged_contract = json.loads(contract_path.read_text(encoding="utf-8"))
            mixed = next(
                case for case in forged_contract["temporal_cases"]
                if case["id"] == "mixed-one-two-three--clean-lanczos")
            held_frame = mixed["frames"][2]
            self.assertEqual(held_frame["expected_decision"], "REUSE")
            held_frame["expected_decision"] = "PROCESS"
            held_frame["expected_reference_frame_id"] = None
            contract_path.write_text(
                json.dumps(forged_contract, indent=2) + "\n", encoding="utf-8")
            resigned_submission = self.make_passing_submission(
                root, contract_path, forged_contract)
            with self.assertRaisesRegex(ValueError, "canonical checked-in generator"):
                gate.evaluate(
                    contract_path, resigned_submission, root / "forged-report.json")

    def test_same_frame_and_reused_output_identity_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract_path, contract = self.prepare(root)
            submission_path = self.make_passing_submission(root, contract_path, contract)
            submission = json.loads(submission_path.read_text(encoding="utf-8"))
            submission["spatial_outputs"][0]["input_sha256"] = "0" * 64
            mixed = next(
                case for case in submission["temporal_outputs"]
                if case["case_id"] == "mixed-one-two-three--clean-lanczos")
            reused = mixed["frames"][2]
            reused_path = root / reused["output_path"]
            with Image.open(reused_path) as image:
                changed = image.copy()
            changed.putpixel((0, 0), (0, 0, 0))
            changed.save(reused_path)
            reused["output_sha256"] = gate.file_sha256(reused_path)
            wrong_process = mixed["frames"][3]
            wrong_process_path = root / wrong_process["output_path"]
            shutil.copyfile(root / mixed["frames"][1]["output_path"], wrong_process_path)
            wrong_process["output_sha256"] = gate.file_sha256(wrong_process_path)
            submission_path.write_text(json.dumps(submission, indent=2) + "\n", encoding="utf-8")

            report = gate.evaluate(contract_path, submission_path, root / "failed-report.json")
            self.assertEqual(report["declared_oracle_conformance"], "FAIL")
            self.assertEqual(report["runtime_evidence"]["status"], "NOT_BOUND")
            self.assertEqual(report["summary"]["declared_same_frame_identity_failure_count"], 2)
            self.assertGreaterEqual(
                report["summary"]["declared_reference_identity_failure_count"], 1)
            self.assertIn(
                "declared_reference_output_identity",
                {item["kind"] for item in report["failures"]})
            self.assertIn(
                "declared_same_frame_output_identity",
                {item["kind"] for item in report["failures"]})


if __name__ == "__main__":
    unittest.main(verbosity=2)
