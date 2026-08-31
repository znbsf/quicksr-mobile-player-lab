from __future__ import annotations

import hashlib
import json
import unittest
from collections import Counter

import onnx
from onnx import helper

import derive_quicksrnet_fixed512 as derive


def file_sha256(path: derive.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def value_shape(value_info: onnx.ValueInfoProto) -> list[int]:
    return [int(dim.dim_value) for dim in value_info.type.tensor_type.shape.dim]


class Fixed512DerivationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.canonical = onnx.load(
            derive.base.CANONICAL_PATH, load_external_data=False
        )
        cls.model = onnx.load(derive.MODEL_PATH, load_external_data=False)

    def test_earlier_inputs_are_unchanged(self) -> None:
        self.assertEqual(
            file_sha256(derive.base.CORE_MODEL_PATH),
            derive.fixed256.EXPECTED_FIXED64_CORE_SHA256,
        )
        self.assertEqual(
            file_sha256(derive.base.DCR_MODEL_PATH),
            derive.fixed256.EXPECTED_FIXED64_DCR_SHA256,
        )
        self.assertEqual(
            file_sha256(derive.base.MANIFEST_PATH),
            derive.fixed256.EXPECTED_FIXED64_MANIFEST_SHA256,
        )
        self.assertEqual(
            file_sha256(derive.fixed256.MODEL_PATH),
            derive.EXPECTED_FIXED256_MODEL_SHA256,
        )
        self.assertEqual(
            file_sha256(derive.fixed256.MANIFEST_PATH),
            derive.EXPECTED_FIXED256_MANIFEST_SHA256,
        )

    def test_graph_is_fixed512_dcr(self) -> None:
        onnx.checker.check_model(self.model, full_check=True)
        self.assertEqual(value_shape(self.model.graph.input[0]), [1, 3, 512, 512])
        self.assertEqual(
            value_shape(self.model.graph.output[0]), [1, 3, 1024, 1024]
        )
        self.assertEqual(
            Counter(node.op_type for node in self.model.graph.node),
            Counter({"Conv": 4, "Clip": 4, "DepthToSpace": 1}),
        )
        d2s = [
            node for node in self.model.graph.node if node.op_type == "DepthToSpace"
        ]
        self.assertEqual(len(d2s), 1)
        attributes = {
            item.name: helper.get_attribute_value(item) for item in d2s[0].attribute
        }
        self.assertEqual(attributes, {"blocksize": 2, "mode": b"DCR"})

    def test_checked_in_model_equals_fresh_derivation(self) -> None:
        fresh = derive.derive_model(self.canonical)
        self.assertEqual(
            derive.base._serialize_model(fresh), derive.MODEL_PATH.read_bytes()
        )

    def test_manifest_records_byte_exact_pc_validation(self) -> None:
        manifest = json.loads(derive.MANIFEST_PATH.read_text(encoding="utf-8"))
        self.assertEqual(manifest["status"], "pass")
        self.assertFalse(manifest["canonical_was_written"])
        self.assertFalse(manifest["fixed64_was_written"])
        self.assertFalse(manifest["fixed256_was_written"])
        self.assertEqual(manifest["artifact"]["sha256"], file_sha256(derive.MODEL_PATH))
        self.assertEqual(
            [case["id"] for case in manifest["pc_ort_validation"]["cases"]],
            ["rgb-gradient-512", "seeded-random-512"],
        )
        for case in manifest["pc_ort_validation"]["cases"]:
            comparison = case["comparison"]
            self.assertEqual(comparison["status"], "pass")
            self.assertEqual(comparison["mismatch_count"], 0)
            self.assertEqual(comparison["nonfinite_count"], 0)
            self.assertEqual(comparison["max_abs_error"], 0.0)
            self.assertTrue(comparison["exact_float32_bytes"])

    def test_pc_ort_equivalence_is_independently_recomputed(self) -> None:
        validation = derive.validate_equivalence(
            derive.base.CANONICAL_PATH.read_bytes(), derive.MODEL_PATH.read_bytes()
        )
        self.assertEqual(validation["status"], "pass")
        self.assertEqual(len(validation["cases"]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
