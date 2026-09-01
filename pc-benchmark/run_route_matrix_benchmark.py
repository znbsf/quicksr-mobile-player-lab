from __future__ import annotations

import argparse
import csv
import io
import json
import platform
import statistics
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageFilter, __version__ as pillow_version

from benchmark_runtime import ROOT, file_sha256, quality_metrics
from plan_matrix import build_matrix


HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "route-matrix-run"
DEFAULT_ASSET_ID = "bbb-1080-frame-01000"
DEFAULT_DEGRADATION_ID = "clean-lanczos"
PREVIEW_ROUTES = {
    "anime-360p-16x9-to-1080p",
    "anime-360p-16x9-to-2160p",
    "anime-720p-16x9-to-1080p",
    "anime-360-square-to-1080p",
}


class DynamicRunner:
    def __init__(self, model: dict[str, Any]):
        path = ROOT / model["dynamic_path"]
        if not path.is_file() or file_sha256(path) != model["dynamic_sha256"]:
            raise ValueError(f"dynamic model is missing or unverified: {model['id']}")
        options = ort.SessionOptions()
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        self.scale = float(model["scale"])
        self.model_id = model["id"]
        self.model_sha256 = model["dynamic_sha256"]
        self.session = ort.InferenceSession(path.read_bytes(), options, providers=["CPUExecutionProvider"])

    def infer(self, image: np.ndarray) -> tuple[np.ndarray, float]:
        height, width = image.shape[:2]
        if self.scale == 1.5 and (height % 2 or width % 2):
            raise ValueError(f"1.5x model requires even dimensions, observed {width}x{height}")
        tensor = np.ascontiguousarray(image.transpose(2, 0, 1)[None], dtype=np.float32)
        started = time.perf_counter_ns()
        output = self.session.run(["upscaled_image"], {"image": tensor})[0]
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        values = np.clip(output[0].transpose(1, 2, 0), 0.0, 1.0)
        expected = (int(height * self.scale), int(width * self.scale), 3)
        if values.shape != expected:
            raise ValueError(f"{self.model_id} output mismatch: expected {expected}, observed {values.shape}")
        return values, elapsed_ms


def to_float(image: Image.Image) -> np.ndarray:
    return np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0


def to_image(values: np.ndarray) -> Image.Image:
    return Image.fromarray(np.rint(np.clip(values, 0.0, 1.0) * 255.0).astype(np.uint8))


def master_for_layout(original: Image.Image, layout: str) -> Image.Image:
    if layout == "1:1":
        target_width, target_height = 1, 1
    elif layout == "16:9":
        target_width, target_height = 16, 9
    else:
        raise ValueError(f"unsupported benchmark layout: {layout}")
    target_aspect = target_width / target_height
    observed_aspect = original.width / original.height
    if observed_aspect > target_aspect:
        crop_width = round(original.height * target_aspect)
        left = (original.width - crop_width) // 2
        return original.crop((left, 0, left + crop_width, original.height))
    crop_height = round(original.width / target_aspect)
    top = (original.height - crop_height) // 2
    return original.crop((0, top, original.width, top + crop_height))


def degrade_to_source(
        master: Image.Image,
        source_width: int,
        source_height: int,
        profile: dict[str, Any]) -> tuple[Image.Image, int | None]:
    prepared = master
    if profile["pre_blur_radius"] > 0:
        prepared = prepared.filter(ImageFilter.GaussianBlur(radius=profile["pre_blur_radius"]))
    low = prepared.resize((source_width, source_height), Image.Resampling.LANCZOS)
    if profile["jpeg_quality"] is None:
        return low, None
    payload = io.BytesIO()
    low.save(
        payload,
        format="JPEG",
        quality=profile["jpeg_quality"],
        subsampling=profile["jpeg_subsampling"],
        optimize=False,
        progressive=False,
    )
    encoded = payload.getvalue()
    with Image.open(io.BytesIO(encoded)) as decoded:
        return decoded.convert("RGB"), len(encoded)


def select_assets(manifest: dict[str, Any], selected_ids: list[str]) -> list[dict[str, Any]]:
    by_id = {item["id"]: item for item in manifest["assets"] if item["kind"] == "image"}
    unknown = set(selected_ids) - set(by_id)
    if unknown:
        raise ValueError(f"unknown image asset id(s): {sorted(unknown)}")
    return [by_id[asset_id] for asset_id in selected_ids]


def select_degradations(config: dict[str, Any], selected_ids: list[str]) -> list[dict[str, Any]]:
    by_id = {item["id"]: item for item in config["route_image_profiles"]}
    unknown = set(selected_ids) - set(by_id)
    if unknown:
        raise ValueError(f"unknown route degradation id(s): {sorted(unknown)}")
    return [by_id[profile_id] for profile_id in selected_ids]


def linear_resize(values: np.ndarray, scale: float) -> tuple[np.ndarray, float]:
    height, width = values.shape[:2]
    target = (round(width * scale), round(height * scale))
    started = time.perf_counter_ns()
    result = to_float(to_image(values).resize(target, Image.Resampling.LANCZOS))
    return result, (time.perf_counter_ns() - started) / 1_000_000.0


def save_canvas(values: np.ndarray, route: dict[str, Any], path: Path) -> None:
    canvas = Image.new("RGB", (route["canvas"]["width"], route["canvas"]["height"]), (0, 0, 0))
    canvas.paste(to_image(values), (route["content_rect"]["x"], route["content_rect"]["y"]))
    canvas.save(path)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Execute anime target routes on one or more verified rights-clear images"
    )
    parser.add_argument(
        "--asset",
        action="append",
        help=f"Image asset id; repeat for a corpus. Default: {DEFAULT_ASSET_ID}",
    )
    parser.add_argument(
        "--degradation",
        action="append",
        help=f"Route degradation id; repeat for multiple profiles. Default: {DEFAULT_DEGRADATION_ID}",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    config = json.loads((HERE / "anime-targets.json").read_text(encoding="utf-8"))
    matrix = build_matrix(config)
    assets_manifest = json.loads((HERE / "open-assets.json").read_text(encoding="utf-8"))
    assets = select_assets(assets_manifest, args.asset or [DEFAULT_ASSET_ID])
    degradation_config = json.loads((HERE / "degradation-profiles.json").read_text(encoding="utf-8"))
    degradations = select_degradations(
        degradation_config,
        args.degradation or [DEFAULT_DEGRADATION_ID],
    )
    registry = json.loads((HERE / "model-registry.json").read_text(encoding="utf-8"))
    models = {float(model["scale"]): model for model in registry["models"]}
    runners = {scale: DynamicRunner(model) for scale, model in models.items()}
    output_dir = args.output.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, Any]] = []
    results: list[dict[str, Any]] = []
    report_assets: list[dict[str, Any]] = []
    for asset in assets:
        source_path = ROOT / assets_manifest["cache_root"] / asset["file_name"]
        if (
            not source_path.is_file()
            or source_path.stat().st_size != asset["bytes"]
            or file_sha256(source_path) != asset["sha256"]
        ):
            raise ValueError(
                f"open image asset is missing or unverified: {asset['id']}; "
                "run fetch_open_assets.py first"
            )
        with Image.open(source_path) as opened:
            if opened.size != (asset["width"], asset["height"]):
                raise ValueError(
                    f"asset dimension mismatch for {asset['id']}: expected "
                    f"{asset['width']}x{asset['height']}, observed {opened.width}x{opened.height}"
                )
            original = opened.convert("RGB")
        report_assets.append(
            {
                "id": asset["id"],
                "sha256": asset["sha256"],
                "domain": asset["domain"],
                "benchmark_layouts": asset["benchmark_layouts"],
                "license": asset["license"],
                "repository_policy": asset["repository_policy"],
            }
        )
        allowed_layouts = set(asset["benchmark_layouts"])
        for route in matrix["routes"]:
            source = route["source"]
            if source["layout"] not in allowed_layouts:
                continue
            master = master_for_layout(original, source["layout"])
            target_size = (route["content_rect"]["width"], route["content_rect"]["height"])
            reference = to_float(master.resize(target_size, Image.Resampling.LANCZOS))
            for degradation in degradations:
                low_image, jpeg_bytes = degrade_to_source(
                    master,
                    source["width"],
                    source["height"],
                    degradation,
                )
                started = time.perf_counter_ns()
                lanczos = to_float(low_image.resize(target_size, Image.Resampling.LANCZOS))
                lanczos_ms = (time.perf_counter_ns() - started) / 1_000_000.0
                actual = to_float(low_image)
                stages: list[dict[str, Any]] = []
                for stage in route["quality_chain"]:
                    if stage["kind"] == "neural":
                        runner = runners[float(stage["scale"])]
                        actual, elapsed_ms = runner.infer(actual)
                        stages.append(
                            {
                                "kind": "neural",
                                "scale": stage["scale"],
                                "model": runner.model_id,
                                "model_sha256": runner.model_sha256,
                                "elapsed_ms": elapsed_ms,
                            }
                        )
                    else:
                        actual, elapsed_ms = linear_resize(actual, float(stage["scale"]))
                        stages.append(
                            {"kind": "linear", "scale": stage["scale"], "elapsed_ms": elapsed_ms}
                        )
                final_adjustment_ms = 0.0
                if actual.shape[:2] != (target_size[1], target_size[0]):
                    started = time.perf_counter_ns()
                    actual = to_float(to_image(actual).resize(target_size, Image.Resampling.LANCZOS))
                    final_adjustment_ms = (time.perf_counter_ns() - started) / 1_000_000.0
                neural_metrics = quality_metrics(reference, actual)
                lanczos_metrics = quality_metrics(reference, lanczos)
                neural_ms = sum(stage["elapsed_ms"] for stage in stages if stage["kind"] == "neural")
                total_ms = sum(stage["elapsed_ms"] for stage in stages) + final_adjustment_ms
                result = {
                    "asset": asset["id"],
                    "route": route["id"],
                    "degradation": degradation,
                    "jpeg_bytes": jpeg_bytes,
                    "source": source,
                    "canvas": route["canvas"],
                    "content_rect": route["content_rect"],
                    "quality_chain": route["quality_chain"],
                    "stages": stages,
                    "final_adjustment_ms": final_adjustment_ms,
                    "timing_ms": {
                        "neural": neural_ms,
                        "chain_total": total_ms,
                        "lanczos": lanczos_ms,
                    },
                    "quality": {"quicksr_chain": neural_metrics, "lanczos": lanczos_metrics},
                }
                results.append(result)
                row = {
                    "asset": asset["id"],
                    "domain": asset["domain"],
                    "degradation": degradation["id"],
                    "route": route["id"],
                    "source_width": source["width"],
                    "source_height": source["height"],
                    "target_width": route["canvas"]["width"],
                    "target_height": route["canvas"]["height"],
                    "neural_ms": neural_ms,
                    "chain_total_ms": total_ms,
                    "quicksr_psnr_db": neural_metrics["psnr_db"],
                    "lanczos_psnr_db": lanczos_metrics["psnr_db"],
                    "quicksr_minus_lanczos_psnr_db": (
                        neural_metrics["psnr_db"] - lanczos_metrics["psnr_db"]
                    ),
                }
                rows.append(row)
                if route["id"] in PREVIEW_ROUTES:
                    stem = f"{asset['id']}--{degradation['id']}--{route['id']}"
                    save_canvas(actual, route, output_dir / f"{stem}-quicksr.png")
                    save_canvas(lanczos, route, output_dir / f"{stem}-lanczos.png")
                print(
                    f"{asset['id']} / {degradation['id']} / {route['id']}: "
                    f"{total_ms:.1f} ms, PSNR delta "
                    f"{row['quicksr_minus_lanczos_psnr_db']:+.3f} dB"
                )

    report = {
        "schema_version": 2,
        "status": "observed-pc-rights-clear-image-route-corpus",
        "asset_count": len(report_assets),
        "degradation_count": len(degradations),
        "case_count": len(results),
        "assets": report_assets,
        "models": [{"scale": scale, "id": runner.model_id, "sha256": runner.model_sha256} for scale, runner in runners.items()],
        "summary": {
            "quicksr_psnr_wins": sum(row["quicksr_minus_lanczos_psnr_db"] > 0 for row in rows),
            "lanczos_psnr_wins_or_ties": sum(row["quicksr_minus_lanczos_psnr_db"] <= 0 for row in rows),
            "median_chain_ms": statistics.median(row["chain_total_ms"] for row in rows),
            "max_chain_ms": max(row["chain_total_ms"] for row in rows),
        },
        "runtime": {"platform": platform.platform(), "python": platform.python_version(), "numpy": np.__version__, "onnxruntime": ort.__version__, "pillow": pillow_version},
        "cases": results,
        "limits": [
            "The corpus contains open animation and comic-style art, not representative commercial Japanese anime.",
            "References are resized or center-cropped from the pinned originals; only the Pepper and Carrot originals are native 4K-size assets.",
            "Synthetic degradations do not reproduce every codec, ringing, grain, subtitle or broadcast artifact.",
            "PC CPU timing does not predict Android QNN HTP timing.",
        ],
    }
    (output_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "metrics.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
