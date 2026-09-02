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
runtime = load_module("benchmark_runtime", HERE / "benchmark_runtime.py")
fetch = load_module("fetch_open_assets", HERE / "fetch_open_assets.py")
route_runner = load_module("run_route_matrix_benchmark", HERE / "run_route_matrix_benchmark.py")
corpus_summary = load_module("summarize_route_corpus", HERE / "summarize_route_corpus.py")
candidate_benchmark = load_module("anime_candidate_benchmark", HERE / "anime_candidate_benchmark.py")
candidate_validator = load_module("validate_anime_model_candidates", HERE / "validate_anime_model_candidates.py")
candidate_fetch = load_module("fetch_anime_candidate_artifact", HERE / "fetch_anime_candidate_artifact.py")


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
        availability = route["scale_component_availability"]
        self.assertEqual(availability["pc"]["quality"], "integrated")
        self.assertEqual(availability["android"]["quality"], "integrated")
        self.assertEqual(availability["android"]["realtime"], "integrated")

    def test_all_routes_have_pc_quality_and_android_realtime_paths(self) -> None:
        self.assertEqual(self.matrix["model_inventory"]["android_integrated_scales"], [2.0, 3.0, 4.0])
        self.assertEqual(self.matrix["model_inventory"]["android_phone_qnn_validated_scales"], [2.0])
        for route in self.matrix["routes"]:
            availability = route["scale_component_availability"]
            self.assertEqual(availability["pc"]["quality"], "integrated", route["id"])
            self.assertEqual(availability["android"]["realtime"], "integrated", route["id"])


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


class OpenBenchmarkContractTests(unittest.TestCase):
    def test_open_assets_are_pinned_and_download_only(self) -> None:
        manifest = json.loads((HERE / "open-assets.json").read_text(encoding="utf-8"))
        self.assertEqual({asset["kind"] for asset in manifest["assets"]}, {"image", "image-sequence"})
        image_assets = [asset for asset in manifest["assets"] if asset["kind"] == "image"]
        self.assertEqual(len(image_assets), 3)
        self.assertEqual(
            {asset["license"]["spdx"] for asset in image_assets},
            {"CC-BY-3.0", "CC-BY-4.0"},
        )
        for asset in manifest["assets"]:
            self.assertEqual(asset["repository_policy"], "download-only-never-commit-media")
            self.assertTrue(asset["license"]["attribution"])
            self.assertEqual(len(asset.get("sha256", asset.get("checksum_index", {}).get("sha256", ""))), 64)
        for asset in image_assets:
            self.assertTrue(asset["domain"])
            self.assertTrue(set(asset["benchmark_layouts"]).issubset({"16:9", "1:1"}))
            self.assertTrue(asset["benchmark_layouts"])

    def test_model_registry_has_all_pc_scales_and_android_2x_3x_4x(self) -> None:
        registry = json.loads((HERE / "model-registry.json").read_text(encoding="utf-8"))
        models = {model["scale"]: model for model in registry["models"]}
        self.assertEqual(set(models), {1.5, 2.0, 3.0, 4.0})
        self.assertTrue(all(model["status"] == "integrated" for model in models.values()))
        self.assertTrue(all(model["path"] and len(model["sha256"]) == 64 for model in models.values()))
        self.assertTrue(all(model["dynamic_path"] and len(model["dynamic_sha256"]) == 64 for model in models.values()))
        self.assertEqual(models[2.0]["integration_scope"], "pc-onnx-cpu-and-android-qnn")
        self.assertEqual(models[1.5]["integration_scope"], "pc-onnx-cpu")
        for scale in (3.0, 4.0):
            self.assertEqual(
                models[scale]["integration_scope"],
                "pc-onnx-cpu-and-android-app-qnn-unverified",
            )

    def test_checkpoint_sources_are_pinned_for_every_scale(self) -> None:
        sources = json.loads((HERE / "model-sources.json").read_text(encoding="utf-8"))
        checkpoints = {float(item["scale"]): item for item in sources["checkpoints"]}
        self.assertEqual(set(checkpoints), {1.5, 2.0, 3.0, 4.0})
        self.assertEqual(sources["repository_policy"], "download-only-never-commit-weights")
        for checkpoint in checkpoints.values():
            self.assertTrue(checkpoint["url"].startswith("https://github.com/quic/aimet-model-zoo/releases/"))
            self.assertGreater(checkpoint["bytes"], 0)
            self.assertEqual(len(checkpoint["sha256"]), 64)

    def test_degradation_profiles_match_integrated_model_input(self) -> None:
        profiles = json.loads((HERE / "degradation-profiles.json").read_text(encoding="utf-8"))
        expected = (640, 360)
        for profile in [*profiles["image_profiles"], profiles["video_profile"]]:
            self.assertEqual((profile["input_width"], profile["input_height"]), expected)
        route_profiles = {profile["id"]: profile for profile in profiles["route_image_profiles"]}
        self.assertEqual(set(route_profiles), {"clean-lanczos", "legacy-soft-jpeg-q35"})
        self.assertIsNone(route_profiles["clean-lanczos"]["jpeg_quality"])
        self.assertEqual(route_profiles["legacy-soft-jpeg-q35"]["jpeg_quality"], 35)

    def test_route_corpus_preserves_requested_layout_and_filters_assets(self) -> None:
        from PIL import Image

        wide = Image.new("RGB", (400, 300))
        cropped_wide = route_runner.master_for_layout(wide, "16:9")
        cropped_square = route_runner.master_for_layout(wide, "1:1")
        self.assertAlmostEqual(cropped_wide.width / cropped_wide.height, 16 / 9, places=2)
        self.assertEqual(cropped_square.size, (300, 300))
        manifest = json.loads((HERE / "open-assets.json").read_text(encoding="utf-8"))
        selected = route_runner.select_assets(
            manifest,
            ["peppercarrot-confront-the-dragon-4k", "peppercarrot-imagination-4k-square"],
        )
        self.assertEqual([asset["benchmark_layouts"] for asset in selected], [["16:9"], ["1:1"]])

    def test_corpus_summary_groups_quality_direction(self) -> None:
        def case(asset: str, degradation: str, quicksr_psnr: float, lanczos_psnr: float) -> dict:
            return {
                "asset": asset,
                "degradation": {"id": degradation},
                "source": {"layout": "1:1", "width": 360, "height": 360},
                "canvas": {"width": 1920, "height": 1080},
                "timing_ms": {"chain_total": 100.0},
                "quality": {
                    "quicksr_chain": {
                        "psnr_db": quicksr_psnr,
                        "global_ssim": 0.9,
                        "edge_mae": 0.02,
                    },
                    "lanczos": {
                        "psnr_db": lanczos_psnr,
                        "global_ssim": 0.8,
                        "edge_mae": 0.03,
                    },
                },
            }

        payload = corpus_summary.summarize(
            {
                "status": "observed-pc-rights-clear-image-route-corpus",
                "assets": [{"id": "comic", "domain": "open-comic-illustration"}],
                "cases": [
                    case("comic", "clean-lanczos", 31.0, 30.0),
                    case("comic", "legacy-soft-jpeg-q35", 29.0, 30.0),
                ],
                "limits": ["fixture"],
            }
        )
        self.assertEqual(payload["overall"]["case_count"], 2)
        self.assertEqual(payload["overall"]["quicksr_psnr_wins"], 1)
        self.assertEqual(payload["overall"]["lanczos_psnr_wins_or_ties"], 1)

    def test_checksum_index_parser_accepts_coreutils_format(self) -> None:
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as temporary:
            path = Path(temporary) / "SHA256SUMS"
            path.write_text("a" * 64 + "  frame.png\n" + "b" * 64 + " *other.png\n", encoding="utf-8")
            self.assertEqual(fetch.parse_checksum_index(path), {"frame.png": "a" * 64, "other.png": "b" * 64})

    def test_runtime_metrics_rank_identity_best(self) -> None:
        reference = np.linspace(0.0, 1.0, 6 * 8 * 3, dtype=np.float32).reshape(6, 8, 3)
        shifted = np.clip(reference + 0.05, 0.0, 1.0)
        self.assertTrue(np.isinf(runtime.psnr(reference, reference)))
        self.assertGreater(runtime.global_ssim(reference, reference), runtime.global_ssim(reference, shifted))
        self.assertEqual(runtime.edge_mae(reference, reference), 0.0)


class AnimeCandidateLabTests(unittest.TestCase):
    def test_candidate_manifest_is_fail_closed_and_promotes_exactly_two(self) -> None:
        payload = json.loads((HERE / "anime-model-candidates.json").read_text(encoding="utf-8"))
        result = candidate_validator.validate(payload)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["gpu_shader"], "anime4k-upscale-cnn-x2-s")
        self.assertEqual(result["mobile_neural"], "sesr-m5-2x")
        self.assertEqual(sum(item["decision"].startswith("advance-") for item in payload["candidates"]), 2)

    def test_candidate_manifest_does_not_treat_video_name_as_temporal(self) -> None:
        payload = json.loads((HERE / "anime-model-candidates.json").read_text(encoding="utf-8"))
        candidates = {item["id"]: item for item in payload["candidates"]}
        self.assertFalse(candidates["realesr-animevideov3"]["temporal"])
        self.assertEqual(candidates["realesr-animevideov3"]["decision"], "blocked")

    def test_fetch_allowlist_excludes_unclear_candidate_artifacts(self) -> None:
        payload = json.loads((HERE / "anime-model-candidates.json").read_text(encoding="utf-8"))
        selected = candidate_fetch.select_artifact(payload, "sesr-m5-2x-float32-checkpoint")
        self.assertEqual(selected["license"], "BSD-3-Clause")
        anime4k = candidate_fetch.select_artifact(payload, "anime4k-v4.0.1-upscale-cnn-x2-s")
        self.assertEqual(anime4k["repository_policy"], "vendored-mit-source-with-notice")
        with self.assertRaises(ValueError):
            candidate_fetch.select_artifact(payload, "realesr-animevideov3")

    def test_synthetic_candidate_contract_is_deterministic_and_covers_required_slices(self) -> None:
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as first_temp, TemporaryDirectory() as second_temp:
            first = candidate_benchmark.prepare_contract(Path(first_temp), include_open_assets=False)
            second = candidate_benchmark.prepare_contract(Path(second_temp), include_open_assets=False)
            self.assertEqual(len(first["cases"]), 2)
            self.assertEqual({case["degradation"]["id"] for case in first["cases"]}, set(candidate_benchmark.PROFILE_IDS))
            self.assertTrue(all(case["source"] == "synthetic-lineart-subtitle-edge" for case in first["cases"]))
            self.assertEqual(
                [case["input"]["sha256"] for case in first["cases"]],
                [case["input"]["sha256"] for case in second["cases"]],
            )
            self.assertEqual(
                [case["reference"]["sha256"] for case in first["cases"]],
                [case["reference"]["sha256"] for case in second["cases"]],
            )

    def test_candidate_evaluator_accepts_exact_rgb_outputs_and_fails_missing_cases(self) -> None:
        from tempfile import TemporaryDirectory
        from PIL import Image

        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract = candidate_benchmark.prepare_contract(root / "contract", include_open_assets=False)
            outputs = root / "outputs"
            outputs.mkdir()
            for case in contract["cases"]:
                with Image.open(root / "contract" / case["lanczos"]["path"]) as image:
                    image.convert("RGB").save(outputs / f"{case['id']}.png")
            report = candidate_benchmark.evaluate_outputs(
                root / "contract" / "contract.json",
                outputs,
                "fixture-candidate",
                root / "report.json",
            )
            self.assertEqual(report["case_count"], 2)
            self.assertEqual(report["candidate_psnr_wins"], 0)
            (outputs / f"{contract['cases'][0]['id']}.png").unlink()
            with self.assertRaises(FileNotFoundError):
                candidate_benchmark.evaluate_outputs(
                    root / "contract" / "contract.json",
                    outputs,
                    "fixture-candidate",
                    root / "report-again.json",
                )


if __name__ == "__main__":
    unittest.main(verbosity=2)
