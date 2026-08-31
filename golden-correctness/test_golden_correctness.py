from __future__ import annotations

import base64
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

from compare_android_output import (
    compare_tensors,
    preserve_pc_golden_bundle,
    validate_derived_model_linkage,
)
from generate_pc_golden import android_rgb_gradient_nchw, canonical_f32le_bytes


class GoldenCorrectnessTests(unittest.TestCase):
    @staticmethod
    def _write_synthetic_pc_bundle(directory: Path) -> tuple[Path, dict, bytes]:
        """Create rights-neutral test-only bytes instead of depending on private evidence."""
        directory.mkdir(parents=True, exist_ok=True)
        input_raw = canonical_f32le_bytes(android_rgb_gradient_nchw())
        output_raw = np.linspace(0.0, 1.0, 49152, dtype="<f4").tobytes(order="C")
        input_name = "synthetic-input.f32le.raw.b64"
        output_name = "synthetic-output.f32le.raw.b64"
        (directory / input_name).write_text(
            base64.b64encode(input_raw).decode("ascii") + "\n",
            encoding="ascii",
        )
        (directory / output_name).write_text(
            base64.b64encode(output_raw).decode("ascii") + "\n",
            encoding="ascii",
        )
        manifest = {
            "schemaVersion": "1.0.0",
            "kind": "synthetic-pc-golden-test-fixture",
            "status": "PASS",
            "model": {
                "file": "quicksrnet-small-2x-opset17.onnx",
                "bytes": 93994,
                "sha256": (
                    "3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce"
                ),
            },
            "input": {
                "generator": "DeterministicInputs.rgbGradientNchw-v1",
                "shape": [1, 3, 64, 64],
                "elementCount": 12288,
                "sha256LittleEndianFloat32": hashlib.sha256(input_raw).hexdigest(),
                "artifact": input_name,
                "decodedBytes": len(input_raw),
            },
            "pcGoldenOutput": {
                "name": "upscaled_image",
                "shape": [1, 3, 128, 128],
                "elementCount": 49152,
                "sha256LittleEndianFloat32": hashlib.sha256(output_raw).hexdigest(),
                "artifact": output_name,
                "decodedBytes": len(output_raw),
            },
            "toleranceContract": {
                "absoluteTolerance": 0.0001,
                "relativeTolerance": 0.0001,
                "allowedMismatchCount": 0,
                "allowedNonfiniteCount": 0,
            },
        }
        manifest_path = directory / "pc-golden-manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return manifest_path, manifest, output_raw

    @staticmethod
    def _derived_fixture() -> tuple[dict, dict, bytes]:
        prototype_root = Path(__file__).resolve().parent.parent
        manifest_path = prototype_root / "derived-models" / "derivation-manifest.json"
        raw = manifest_path.read_bytes()
        manifest = json.loads(raw.decode("utf-8"))
        artifact = manifest["artifacts"]["fixed64_pre_shuffle_core"]
        pc_case = next(
            item
            for item in manifest["pc_ort_validation"]["cases"]
            if item["id"] == "android-rgb-gradient-64"
        )
        receipt = {
            "status": "PASS",
            "backendRequested": "XNNPACK_CORE_HYBRID",
            "planSha256": manifest["p2_plan"]["sha256"],
            "model": {
                "variant": "fixed64-pre-shuffle-core",
                "derived": True,
                "asset": Path(artifact["path"]).name,
                "expectedBytes": artifact["bytes"],
                "observedBytes": artifact["bytes"],
                "expectedSha256": artifact["sha256"],
                "observedSha256": artifact["sha256"],
                "canonicalSourceSha256": manifest["source"]["sha256"],
                "derivationManifestSha256": hashlib.sha256(raw).hexdigest(),
            },
            "inputIdentity": {
                "sha256LittleEndianFloat32": manifest["frozen_correctness_gate"][
                    "android_input_sha256_little_endian_float32"
                ]
            },
            "sessionContract": {
                "outputName": artifact["output"]["name"],
                "outputShape": artifact["output"]["shape"],
            },
            "modelOutputIdentity": {
                "shape": artifact["output"]["shape"],
                "elementCount": 49152,
                "sha256LittleEndianFloat32": pc_case[
                    "core_raw_output_sha256_little_endian_float32"
                ],
            },
            "applicationPostprocess": {
                "kind": "application-crd-pixel-shuffle",
                "includedInOrtRunLatency": False,
                "inputShape": artifact["output"]["shape"],
                "outputShape": [1, 3, 128, 128],
            },
            "structuralSanityValidation": {
                "shape": [1, 3, 128, 128],
                "elementCount": 49152,
            },
        }
        return receipt, manifest, raw

    @staticmethod
    def _qnn_dcr_fixture() -> tuple[dict, dict, bytes, dict, bytes]:
        prototype_root = Path(__file__).resolve().parent.parent
        manifest_path = prototype_root / "derived-models" / "derivation-manifest.json"
        manifest_raw = manifest_path.read_bytes()
        manifest = json.loads(manifest_raw.decode("utf-8"))
        artifact = manifest["artifacts"]["fixed64_dcr_full"]
        pc_case = next(
            item
            for item in manifest["pc_ort_validation"]["cases"]
            if item["id"] == "android-rgb-gradient-64"
        )
        plan_path = prototype_root / "prototype-plan-p3-qnn.json"
        plan_raw = plan_path.read_bytes()
        plan = json.loads(plan_raw.decode("utf-8"))
        receipt = {
            "status": "PASS",
            "backendRequested": "QNN_HTP_DCR_STRICT",
            "planSha256": hashlib.sha256(plan_raw).hexdigest(),
            "model": {
                "variant": "fixed64-dcr-full",
                "derived": True,
                "asset": Path(artifact["path"]).name,
                "expectedBytes": artifact["bytes"],
                "observedBytes": artifact["bytes"],
                "expectedSha256": artifact["sha256"],
                "observedSha256": artifact["sha256"],
                "canonicalSourceSha256": manifest["source"]["sha256"],
                "derivationManifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
            },
            "inputIdentity": {
                "sha256LittleEndianFloat32": manifest["frozen_correctness_gate"][
                    "android_input_sha256_little_endian_float32"
                ]
            },
            "sessionContract": {
                "outputName": artifact["output"]["name"],
                "outputShape": artifact["output"]["shape"],
            },
            "modelOutputIdentity": {
                "shape": artifact["output"]["shape"],
                "elementCount": 49152,
                "sha256LittleEndianFloat32": pc_case["dcr_full_vs_canonical"][
                    "actual_sha256_little_endian_float32"
                ],
            },
            "structuralSanityValidation": {
                "shape": [1, 3, 128, 128],
                "elementCount": 49152,
            },
        }
        return receipt, manifest, manifest_raw, plan, plan_raw

    def test_android_input_generator_matches_observed_receipt(self) -> None:
        raw = canonical_f32le_bytes(android_rgb_gradient_nchw())
        self.assertEqual(
            hashlib.sha256(raw).hexdigest(),
            "cc13c100d394903d5c9ccde7a44aab63660e266099077063a0a0de326f5b9fc9",
        )

    def test_comparator_accepts_values_inside_contract(self) -> None:
        golden = np.asarray([0.0, 0.5, 1.0], dtype=np.float32)
        android = np.asarray([0.00005, 0.5001, 0.9999], dtype=np.float32)
        result = compare_tensors(android, golden, 0.0001, 0.0001)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["mismatchCount"], 0)

    def test_comparator_rejects_large_error_and_nonfinite(self) -> None:
        golden = np.asarray([0.0, 0.5, 1.0], dtype=np.float32)
        android = np.asarray([0.01, np.nan, 1.0], dtype=np.float32)
        result = compare_tensors(android, golden, 0.0001, 0.0001)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["mismatchCount"], 2)
        self.assertEqual(result["nonfiniteCount"], 1)

    def test_derived_core_requires_real_manifest_and_full_lineage(self) -> None:
        receipt, manifest, raw = self._derived_fixture()
        linkage = validate_derived_model_linkage(
            receipt,
            manifest["source"]["sha256"],
            manifest["frozen_correctness_gate"][
                "android_input_sha256_little_endian_float32"
            ],
            [1, 3, 128, 128],
            manifest,
            raw,
        )
        self.assertEqual(linkage["variant"], "fixed64-pre-shuffle-core")
        self.assertEqual(linkage["pcDerivationEquivalence"]["status"], "pass")
        with self.assertRaisesRegex(ValueError, "manifest hash"):
            validate_derived_model_linkage(
                receipt,
                manifest["source"]["sha256"],
                manifest["frozen_correctness_gate"][
                    "android_input_sha256_little_endian_float32"
                ],
                [1, 3, 128, 128],
                manifest,
                raw + b" ",
            )

    def test_qnn_dcr_requires_hash_linked_p3_execution_plan(self) -> None:
        receipt, manifest, manifest_raw, plan, plan_raw = self._qnn_dcr_fixture()
        linkage = validate_derived_model_linkage(
            receipt,
            manifest["source"]["sha256"],
            manifest["frozen_correctness_gate"][
                "android_input_sha256_little_endian_float32"
            ],
            [1, 3, 128, 128],
            manifest,
            manifest_raw,
            plan,
            plan_raw,
        )
        self.assertEqual(linkage["variant"], "fixed64-dcr-full")
        self.assertEqual(linkage["executionPlanKind"], "p3-qnn-htp-infrastructure")
        self.assertEqual(
            linkage["executionPlanSha256"], hashlib.sha256(plan_raw).hexdigest()
        )

        with self.assertRaisesRegex(ValueError, "requires supplied P3 execution plan"):
            validate_derived_model_linkage(
                receipt,
                manifest["source"]["sha256"],
                manifest["frozen_correctness_gate"][
                    "android_input_sha256_little_endian_float32"
                ],
                [1, 3, 128, 128],
                manifest,
                manifest_raw,
            )

        with self.assertRaisesRegex(ValueError, "plan hash linkage"):
            validate_derived_model_linkage(
                receipt,
                manifest["source"]["sha256"],
                manifest["frozen_correctness_gate"][
                    "android_input_sha256_little_endian_float32"
                ],
                [1, 3, 128, 128],
                manifest,
                manifest_raw,
                plan,
                plan_raw + b" ",
            )

    def test_pc_golden_bundle_is_self_contained(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, manifest, _ = self._write_synthetic_pc_bundle(root / "source")
            target = root / "preserved"
            evidence = preserve_pc_golden_bundle(source, manifest, target)
            self.assertTrue((target / "pc-golden-manifest.json").is_file())
            self.assertTrue((target / manifest["input"]["artifact"]).is_file())
            self.assertTrue((target / manifest["pcGoldenOutput"]["artifact"]).is_file())
            self.assertEqual(
                hashlib.sha256((target / "pc-golden-manifest.json").read_bytes()).hexdigest(),
                evidence["manifest"]["sha256"],
            )

    def test_derived_core_case_is_self_contained_and_independently_validated(self) -> None:
        directory = Path(__file__).resolve().parent
        derivation_manifest_path = (
            directory.parent / "derived-models" / "derivation-manifest.json"
        )

        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            pc_manifest_path, pc_manifest, final_output = (
                self._write_synthetic_pc_bundle(temporary_path / "pc-source")
            )
            final_output_sha256 = hashlib.sha256(final_output).hexdigest()
            receipt, _, _ = self._derived_fixture()
            receipt["runId"] = "fixture-derived-core-self-contained"
            receipt["structuralSanityValidation"].update(
                {"sha256LittleEndianFloat32": final_output_sha256}
            )
            receipt["outputArtifact"] = {
                "file": "fixture-derived-core.output.f32le",
                "bytes": len(final_output),
                "sha256": final_output_sha256,
                "dtype": "float32",
                "byteOrder": "little-endian",
                "shape": pc_manifest["pcGoldenOutput"]["shape"],
            }
            receipt_path = temporary_path / "fixture-receipt.json"
            output_path = temporary_path / "fixture-derived-core.output.f32le"
            case_directory = temporary_path / "derived-case"
            result_path = case_directory / "android-vs-pc-comparison.json"
            receipt_path.write_text(
                json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            output_path.write_bytes(final_output)

            compare = subprocess.run(
                [
                    sys.executable,
                    str(directory / "compare_android_output.py"),
                    "--manifest",
                    str(pc_manifest_path),
                    "--android-receipt",
                    str(receipt_path),
                    "--android-output",
                    str(output_path),
                    "--derivation-manifest",
                    str(derivation_manifest_path),
                    "--result",
                    str(result_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(compare.returncode, 0, compare.stderr or compare.stdout)
            validate = subprocess.run(
                [
                    sys.executable,
                    str(directory / "validate_golden_case.py"),
                    "--case-dir",
                    str(case_directory),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(validate.returncode, 0, validate.stderr or validate.stdout)
            validation = json.loads(
                (case_directory / "validation-result.json").read_text(encoding="utf-8")
            )
            self.assertEqual(validation["status"], "PASS")
            self.assertEqual(validation["checks"]["modelLineageKind"], "derived")
            self.assertEqual(
                validation["checks"]["derivedManifestAndPlanLinkage"], "PASS"
            )
            frozen_validation = (case_directory / "validation-result.json").read_bytes()
            read_only_validate = subprocess.run(
                [
                    sys.executable,
                    str(directory / "validate_golden_case.py"),
                    "--case-dir",
                    str(case_directory),
                    "--no-write",
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(
                read_only_validate.returncode,
                0,
                read_only_validate.stderr or read_only_validate.stdout,
            )
            self.assertEqual(
                (case_directory / "validation-result.json").read_bytes(),
                frozen_validation,
            )
            for artifact in (
                "pc-golden-manifest.json",
                pc_manifest["input"]["artifact"],
                pc_manifest["pcGoldenOutput"]["artifact"],
                "android-receipt.json.raw.b64",
                "android-output.f32le.raw.b64",
                "derivation-manifest.json.raw.b64",
            ):
                self.assertTrue((case_directory / artifact).is_file(), artifact)


if __name__ == "__main__":
    unittest.main()
