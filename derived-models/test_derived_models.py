from __future__ import annotations

import hashlib
import json
import unittest
from collections import Counter

import numpy as np
import onnx
from onnx import helper, numpy_helper

import derive_quicksrnet_fixed64 as derive


def file_sha256(path: derive.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def value_shape(value_info: onnx.ValueInfoProto) -> list[int]:
    return [int(dim.dim_value) for dim in value_info.type.tensor_type.shape.dim]


def initializer_map(model: onnx.ModelProto) -> dict[str, onnx.TensorProto]:
    return {item.name: item for item in model.graph.initializer}


class FrozenTrustAnchorTests(unittest.TestCase):
    def test_source_and_p2_plan_hashes_remain_frozen(self) -> None:
        self.assertEqual(
            file_sha256(derive.CANONICAL_PATH), derive.EXPECTED_CANONICAL_SHA256
        )
        self.assertEqual(
            file_sha256(derive.P2_PLAN_PATH), derive.EXPECTED_P2_PLAN_SHA256
        )

    def test_android_gradient_identity_remains_frozen(self) -> None:
        values = derive.android_rgb_gradient_nchw()
        self.assertEqual(values.shape, derive.INPUT_SHAPE)
        self.assertEqual(
            derive.tensor_sha256(values), derive.EXPECTED_ANDROID_INPUT_SHA256
        )


class PixelShuffleTests(unittest.TestCase):
    def test_crd_channel_and_phase_mapping(self) -> None:
        source = np.arange(12, dtype=np.float32).reshape(1, 12, 1, 1)
        actual = derive.crd_pixel_shuffle(source)
        expected = np.asarray(
            [
                [
                    [[0.0, 1.0], [2.0, 3.0]],
                    [[4.0, 5.0], [6.0, 7.0]],
                    [[8.0, 9.0], [10.0, 11.0]],
                ]
            ],
            dtype=np.float32,
        )
        np.testing.assert_array_equal(actual, expected)


class DerivedGraphContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.canonical = onnx.load(derive.CANONICAL_PATH, load_external_data=False)
        cls.core = onnx.load(derive.CORE_MODEL_PATH, load_external_data=False)
        cls.dcr = onnx.load(derive.DCR_MODEL_PATH, load_external_data=False)

    def test_core_has_fixed_shape_materialized_bounds_and_no_depth_to_space(self) -> None:
        onnx.checker.check_model(self.core, full_check=True)
        self.assertEqual(value_shape(self.core.graph.input[0]), [1, 3, 64, 64])
        self.assertEqual(self.core.graph.output[0].name, "pre_shuffle_output")
        self.assertEqual(value_shape(self.core.graph.output[0]), [1, 12, 64, 64])
        self.assertEqual(
            Counter(node.op_type for node in self.core.graph.node),
            Counter({"Conv": 4, "Clip": 4}),
        )
        self._assert_clip_bounds_are_initializers(self.core)

    def test_dcr_has_fixed_shape_materialized_bounds_and_dcr_mode(self) -> None:
        onnx.checker.check_model(self.dcr, full_check=True)
        self.assertEqual(value_shape(self.dcr.graph.input[0]), [1, 3, 64, 64])
        self.assertEqual(self.dcr.graph.output[0].name, "upscaled_image")
        self.assertEqual(value_shape(self.dcr.graph.output[0]), [1, 3, 128, 128])
        self.assertEqual(
            Counter(node.op_type for node in self.dcr.graph.node),
            Counter({"Conv": 4, "Clip": 4, "DepthToSpace": 1}),
        )
        depth_to_space = [
            node for node in self.dcr.graph.node if node.op_type == "DepthToSpace"
        ]
        self.assertEqual(len(depth_to_space), 1)
        attributes = {
            item.name: helper.get_attribute_value(item)
            for item in depth_to_space[0].attribute
        }
        self.assertEqual(attributes, {"blocksize": 2, "mode": b"DCR"})
        self._assert_clip_bounds_are_initializers(self.dcr)

    def test_dcr_final_weight_and_bias_are_exact_gathers(self) -> None:
        source_initializers = initializer_map(self.canonical)
        dcr_initializers = initializer_map(self.dcr)
        permutation = np.asarray(derive.DCR_GATHER_FROM_CRD, dtype=np.int64)
        for name in ("conv_last.weight", "conv_last.bias"):
            source = numpy_helper.to_array(source_initializers[name])
            actual = numpy_helper.to_array(dcr_initializers[name])
            np.testing.assert_array_equal(actual, source[permutation])

    def test_checked_in_models_equal_a_fresh_deterministic_derivation(self) -> None:
        fresh_core, fresh_dcr, _ = derive.derive_models(self.canonical)
        self.assertEqual(
            derive._serialize_model(fresh_core), derive.CORE_MODEL_PATH.read_bytes()
        )
        self.assertEqual(
            derive._serialize_model(fresh_dcr), derive.DCR_MODEL_PATH.read_bytes()
        )

    def _assert_clip_bounds_are_initializers(self, model: onnx.ModelProto) -> None:
        self.assertNotIn("Constant", [node.op_type for node in model.graph.node])
        initializers = initializer_map(model)
        clips = [node for node in model.graph.node if node.op_type == "Clip"]
        self.assertEqual(len(clips), 4)
        for clip in clips:
            self.assertEqual(len(clip.input), 3)
            minimum = numpy_helper.to_array(initializers[clip.input[1]])
            maximum = numpy_helper.to_array(initializers[clip.input[2]])
            self.assertEqual(minimum.dtype, np.float32)
            self.assertEqual(maximum.dtype, np.float32)
            self.assertEqual(minimum.shape, ())
            self.assertEqual(maximum.shape, ())
            self.assertEqual(float(minimum), 0.0)
            self.assertEqual(float(maximum), 1.0)


class ManifestAndRuntimeValidationTests(unittest.TestCase):
    def test_manifest_hashes_and_exact_pc_results(self) -> None:
        manifest = json.loads(derive.MANIFEST_PATH.read_text(encoding="utf-8"))
        self.assertEqual(manifest["status"], "pass")
        self.assertFalse(manifest["canonical_was_written"])
        self.assertEqual(
            manifest["source"]["sha256"], derive.EXPECTED_CANONICAL_SHA256
        )
        self.assertEqual(
            manifest["p2_plan"]["sha256"], derive.EXPECTED_P2_PLAN_SHA256
        )
        self.assertEqual(
            manifest["artifacts"]["fixed64_pre_shuffle_core"]["sha256"],
            file_sha256(derive.CORE_MODEL_PATH),
        )
        self.assertEqual(
            manifest["artifacts"]["fixed64_dcr_full"]["sha256"],
            file_sha256(derive.DCR_MODEL_PATH),
        )
        cases = manifest["pc_ort_validation"]["cases"]
        self.assertEqual([item["id"] for item in cases], [
            "android-rgb-gradient-64",
            "seeded-random-64",
        ])
        for case in cases:
            for comparison_name in (
                "core_plus_application_crd_pixel_shuffle_vs_canonical",
                "dcr_full_vs_canonical",
            ):
                comparison = case[comparison_name]
                self.assertEqual(comparison["status"], "pass")
                self.assertEqual(comparison["mismatch_count"], 0)
                self.assertEqual(comparison["nonfinite_count"], 0)
                self.assertEqual(comparison["max_abs_error"], 0.0)
                self.assertTrue(comparison["exact_float32_bytes"])

    def test_pc_ort_equivalence_is_independently_recomputed(self) -> None:
        validation = derive.validate_equivalence(
            derive.CANONICAL_PATH.read_bytes(),
            derive.CORE_MODEL_PATH.read_bytes(),
            derive.DCR_MODEL_PATH.read_bytes(),
        )
        self.assertEqual(validation["status"], "pass")
        self.assertEqual(len(validation["cases"]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
