from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path

import numpy as np


HERE = Path(__file__).resolve().parent


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


plan = load_module("plan_matrix", HERE / "plan_matrix.py")
baseline = load_module("run_fixed2x_baseline", HERE / "run_fixed2x_baseline.py")


class TargetMatrixTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        config = json.loads((HERE / "anime-targets.json").read_text(encoding="utf-8"))
        cls.matrix = plan.build_matrix(config)

    def test_matrix_covers_every_source_target_pair(self) -> None:
        self.assertEqual(self.matrix["route_count"], 18)
        self.assertEqual(len({route["id"] for route in self.matrix["routes"]}), 18)

    def test_every_content_rect_fits_canvas_and_preserves_aspect(self) -> None:
        for route in self.matrix["routes"]:
            source, canvas, rect = route["source"], route["canvas"], route["content_rect"]
            self.assertLessEqual(rect["width"], canvas["width"], route["id"])
            self.assertLessEqual(rect["height"], canvas["height"], route["id"])
            aspect_error = abs(rect["width"] / rect["height"] - source["width"] / source["height"])
            self.assertLess(aspect_error, 0.003, route["id"])

    def test_square_routes_are_pillarboxed_not_stretched(self) -> None:
        for route in self.matrix["routes"]:
            if route["source"]["layout"] != "1:1":
                continue
            rect, canvas = route["content_rect"], route["canvas"]
            self.assertEqual(rect["width"], rect["height"])
            self.assertEqual(rect["height"], canvas["height"])
            self.assertGreater(rect["x"], 0)
            self.assertEqual(rect["y"], 0)

    def test_key_scale_strategies_are_explicit(self) -> None:
        routes = {route["id"]: route for route in self.matrix["routes"]}
        self.assertEqual(routes["anime-720p-16x9-to-1080p"]["quality_chain"], [{"kind": "neural", "scale": 1.5}])
        self.assertEqual(routes["anime-360p-16x9-to-1440p"]["quality_chain"], [{"kind": "neural", "scale": 4.0}])
        self.assertEqual(
            routes["anime-360p-16x9-to-2160p"]["quality_chain"],
            [{"kind": "neural", "scale": 3.0}, {"kind": "neural", "scale": 2.0}],
        )

    def test_854_wide_480p_uses_nominal_route_plus_pixel_aspect_adjustment(self) -> None:
        routes = {route["id"]: route for route in self.matrix["routes"]}
        route = routes["anime-480p-16x9-to-1080p"]
        quality_product = np.prod([item["scale"] for item in route["quality_chain"]])
        realtime_product = np.prod([item["scale"] for item in route["realtime_fallback"]])
        self.assertAlmostEqual(quality_product, route["content_rect"]["scale"], places=9)
        self.assertAlmostEqual(realtime_product, route["content_rect"]["scale"], places=9)
        self.assertEqual(route["quality_availability"], "model-needed")
        self.assertEqual(route["realtime_availability"], "integrated")


class BaselinePrimitiveTests(unittest.TestCase):
    def test_reference_and_area_downsample_are_deterministic(self) -> None:
        first = baseline.anime_like_reference()
        second = baseline.anime_like_reference()
        np.testing.assert_array_equal(first, second)
        low = baseline.downsample_area_2x(first)
        self.assertEqual(first.shape, (720, 1280, 3))
        self.assertEqual(low.shape, (360, 640, 3))
        self.assertTrue(np.isfinite(low).all())

    def test_bilinear_identity(self) -> None:
        image = np.arange(36, dtype=np.float32).reshape(3, 4, 3) / 35.0
        np.testing.assert_allclose(baseline.resize_bilinear(image, 4, 3), image, atol=1e-7)

    def test_metrics_rank_identity_best(self) -> None:
        reference = baseline.anime_like_reference(64, 36)
        shifted = np.clip(reference + 0.1, 0.0, 1.0)
        self.assertTrue(np.isinf(baseline.psnr(reference, reference)))
        self.assertGreater(baseline.global_ssim(reference, reference), baseline.global_ssim(reference, shifted))
        self.assertEqual(baseline.edge_mae(reference, reference), 0.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
