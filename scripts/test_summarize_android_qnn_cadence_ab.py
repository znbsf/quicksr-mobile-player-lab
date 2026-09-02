import json
from pathlib import Path
import tempfile
import unittest

import summarize_android_qnn_cadence_ab as cadence


class CadenceSummarizerTests(unittest.TestCase):
    def write_log(self, mode: str, run_id: str, decisions: list[tuple[str, str]]) -> Path:
        directory = Path(tempfile.mkdtemp())
        path = directory / f"{mode}.log"
        config = {
            "schemaVersion": 2,
            "event": "configuration",
            "runId": run_id,
            "mode": "QUICKSR_QNN",
            "tuning": "SUSTAINED",
            "postprocessMode": "SERIAL",
            "cadenceMode": mode,
            "profile": "FULL_720P",
            "modelSha256": "model",
            "sourceIdentitySha256": "source",
            "targetAbi": "arm64-v8a",
        }
        events = [config]
        processed = 0
        reused = 0
        streak = 0
        reference = -1
        for index, (decision, reason) in enumerate(decisions, 1):
            if decision == "PROCESS":
                processed += 1
                streak = 0
                reference = index
                reference_generation = -1
                reference_stream_epoch = -1
                reference_frame = -1
            else:
                reused += 1
                streak += 1
                reference_generation = 0
                reference_stream_epoch = 0
                reference_frame = reference
            sample = {
                "frameId": index,
                "generation": 0,
                "cadenceStreamEpoch": 0,
                "ptsUs": (index - 1) * 41667,
                "observedNs": index * 41667000,
                "cadenceDecision": decision,
                "cadenceReason": reason,
                "cadenceReferenceGeneration": reference_generation,
                "cadenceReferenceStreamEpoch": reference_stream_epoch,
                "cadenceReferenceFrameId": reference_frame,
                "reuseStreak": streak,
                "cadenceAnalysisNs": 1000,
                "maxQueueDepth": 1,
                "droppedCount": 0,
                "cadenceProcessedCount": processed,
                "cadenceReusedCount": reused,
            }
            events.append({
                "schemaVersion": 2,
                "event": "frame_batch",
                "runId": run_id,
                "samples": [sample],
            })
        path.write_text("\n".join(json.dumps(event) for event in events), encoding="utf-8")
        return path

    def test_compare_reports_reduction_and_proxy_boundary(self):
        off_path = self.write_log("OFF", "off", [("PROCESS", "DISABLED")] * 4)
        on_path = self.write_log(
            "CONTENT_AWARE_V1",
            "on",
            [
                ("PROCESS", "GENERATION_START"),
                ("REUSE", "EXACT_INPUT"),
                ("PROCESS", "MOTION"),
                ("REUSE", "SMALL_CHANGE"),
            ],
        )
        report = cadence.compare(
            self.with_binding(cadence.summarize(off_path, "OFF", "off")),
            self.with_binding(
                cadence.summarize(on_path, "CONTENT_AWARE_V1", "on", 15, 24)
            ),
        )

        self.assertEqual(0.5, report["inference_reduction_fraction"])
        self.assertEqual(
            "effect_output_submit_proxy_not_final_display_or_quality",
            report["scope"],
        )
        alignment = report["on"]["known_hold_alignment"]
        self.assertEqual(2, alignment["expected_hold_reused"])
        self.assertEqual(0, alignment["source_motion_false_reuse_count"])

    def test_reuse_cannot_cross_generation(self):
        path = self.write_log(
            "CONTENT_AWARE_V1",
            "on",
            [("PROCESS", "GENERATION_START"), ("REUSE", "EXACT_INPUT")],
        )
        text = path.read_text(encoding="utf-8").replace(
            '"cadenceReferenceGeneration": 0',
            '"cadenceReferenceGeneration": 1',
        )
        path.write_text(text, encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "crosses generation"):
            cadence.summarize(path, "CONTENT_AWARE_V1", "on")

    def test_hold_alignment_restarts_phase_at_each_stream_epoch(self):
        samples = []
        decisions = ["PROCESS", "REUSE", "PROCESS", "REUSE"]
        for epoch, base_pts in ((0, 0), (1, 166_667)):
            for index, decision in enumerate(decisions):
                samples.append({
                    "cadenceStreamEpoch": epoch,
                    "ptsUs": base_pts + index * 41_667,
                    "cadenceDecision": decision,
                })

        alignment = cadence.hold_alignment(samples, 15, 24)

        self.assertEqual(4, alignment["expected_hold_reused"])
        self.assertEqual(0, alignment["source_motion_false_reuse_count"])

    def test_missing_output_frame_fails_closed(self):
        path = self.write_log(
            "CONTENT_AWARE_V1",
            "on",
            [("PROCESS", "GENERATION_START"), ("REUSE", "EXACT_INPUT"),
             ("PROCESS", "MOTION")],
        )
        lines = path.read_text(encoding="utf-8").splitlines()
        path.write_text("\n".join(lines[:2] + lines[3:]), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "one-output FIFO"):
            cadence.summarize(path, "CONTENT_AWARE_V1", "on")

    def test_error_and_non_pass_terminal_fail_closed(self):
        path = self.write_log("OFF", "off", [("PROCESS", "DISABLED")])
        base = path.read_text(encoding="utf-8")
        path.write_text(base + "\n" + json.dumps({
            "schemaVersion": 2, "event": "error", "runId": "off",
        }), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "device error"):
            cadence.summarize(path, "OFF", "off")
        path.write_text(base + "\n" + json.dumps({
            "schemaVersion": 2, "event": "terminal", "runId": "off", "status": "FAIL",
        }), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "non-PASS terminal"):
            cadence.summarize(path, "OFF", "off")

    def test_tuning_and_postprocess_mismatch_are_rejected(self):
        off = self.with_binding(cadence.summarize(
            self.write_log("OFF", "off", [("PROCESS", "DISABLED")]), "OFF", "off"
        ))
        on = self.with_binding(cadence.summarize(
            self.write_log("CONTENT_AWARE_V1", "on", [("PROCESS", "MOTION")]),
            "CONTENT_AWARE_V1", "on",
        ))
        on["tuning"] = "BURST"
        with self.assertRaisesRegex(ValueError, "tuning"):
            cadence.compare(off, on)
        on["tuning"] = off["tuning"]
        on["postprocess_mode"] = "OVERLAP"
        with self.assertRaisesRegex(ValueError, "postprocess_mode"):
            cadence.compare(off, on)

    def test_media_or_receipt_mismatch_is_rejected(self):
        off = self.with_binding(cadence.summarize(
            self.write_log("OFF", "off", [("PROCESS", "DISABLED")]), "OFF", "off"
        ))
        on = self.with_binding(cadence.summarize(
            self.write_log("CONTENT_AWARE_V1", "on", [("PROCESS", "MOTION")]),
            "CONTENT_AWARE_V1", "on",
        ))
        on["validation_binding"] = dict(on["validation_binding"])
        on["validation_binding"]["receipt_sha256"] = "different"
        with self.assertRaisesRegex(ValueError, "validation_binding"):
            cadence.compare(off, on)

    @staticmethod
    def with_binding(summary: dict) -> dict:
        summary["validation_binding"] = {
            "plan_id": "plan", "plan_sha256": "p" * 64, "case_id": "720p-baseline",
            "clip_id": "clip", "clip_sha256": "c" * 64,
            "receipt_sha256": "r" * 64, "source_manifest_sha256": "s" * 64,
        }
        return summary


if __name__ == "__main__":
    unittest.main()
