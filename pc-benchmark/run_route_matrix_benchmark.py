from __future__ import annotations

import argparse
import csv
import json
import platform
import statistics
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image, __version__ as pillow_version

from benchmark_runtime import ROOT, file_sha256, quality_metrics
from plan_matrix import build_matrix


HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "route-matrix-run"
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
    if layout != "1:1":
        return original.copy()
    side = min(original.size)
    left = (original.width - side) // 2
    top = (original.height - side) // 2
    return original.crop((left, top, left + side, top + side))


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
    parser = argparse.ArgumentParser(description="Execute all 18 anime target routes on one verified open-animation frame")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    config = json.loads((HERE / "anime-targets.json").read_text(encoding="utf-8"))
    matrix = build_matrix(config)
    assets_manifest = json.loads((HERE / "open-assets.json").read_text(encoding="utf-8"))
    asset = next(item for item in assets_manifest["assets"] if item["id"] == "bbb-1080-frame-01000")
    source_path = ROOT / assets_manifest["cache_root"] / asset["file_name"]
    if not source_path.is_file() or source_path.stat().st_size != asset["bytes"] or file_sha256(source_path) != asset["sha256"]:
        raise ValueError("open image asset is missing or unverified; run fetch_open_assets.py first")
    registry = json.loads((HERE / "model-registry.json").read_text(encoding="utf-8"))
    models = {float(model["scale"]): model for model in registry["models"]}
    runners = {scale: DynamicRunner(model) for scale, model in models.items()}
    output_dir = args.output.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with Image.open(source_path) as opened:
        original = opened.convert("RGB")
    rows: list[dict[str, Any]] = []
    results: list[dict[str, Any]] = []
    for route in matrix["routes"]:
        source = route["source"]
        master = master_for_layout(original, source["layout"])
        low_image = master.resize((source["width"], source["height"]), Image.Resampling.LANCZOS)
        target_size = (route["content_rect"]["width"], route["content_rect"]["height"])
        reference = to_float(master.resize(target_size, Image.Resampling.LANCZOS))
        started = time.perf_counter_ns()
        lanczos = to_float(low_image.resize(target_size, Image.Resampling.LANCZOS))
        lanczos_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        actual = to_float(low_image)
        stages: list[dict[str, Any]] = []
        for stage in route["quality_chain"]:
            if stage["kind"] == "neural":
                runner = runners[float(stage["scale"])]
                actual, elapsed_ms = runner.infer(actual)
                stages.append({"kind": "neural", "scale": stage["scale"], "model": runner.model_id, "model_sha256": runner.model_sha256, "elapsed_ms": elapsed_ms})
            else:
                actual, elapsed_ms = linear_resize(actual, float(stage["scale"]))
                stages.append({"kind": "linear", "scale": stage["scale"], "elapsed_ms": elapsed_ms})
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
            "id": route["id"], "source": source, "canvas": route["canvas"], "content_rect": route["content_rect"],
            "quality_chain": route["quality_chain"], "stages": stages, "final_adjustment_ms": final_adjustment_ms,
            "timing_ms": {"neural": neural_ms, "chain_total": total_ms, "lanczos": lanczos_ms},
            "quality": {"quicksr_chain": neural_metrics, "lanczos": lanczos_metrics},
        }
        results.append(result)
        rows.append({
            "route": route["id"], "source_width": source["width"], "source_height": source["height"],
            "target_width": route["canvas"]["width"], "target_height": route["canvas"]["height"],
            "neural_ms": neural_ms, "chain_total_ms": total_ms,
            "quicksr_psnr_db": neural_metrics["psnr_db"], "lanczos_psnr_db": lanczos_metrics["psnr_db"],
            "quicksr_minus_lanczos_psnr_db": neural_metrics["psnr_db"] - lanczos_metrics["psnr_db"],
        })
        if route["id"] in PREVIEW_ROUTES:
            save_canvas(actual, route, output_dir / f"{route['id']}-quicksr.png")
            save_canvas(lanczos, route, output_dir / f"{route['id']}-lanczos.png")
        print(f"{route['id']}: {total_ms:.1f} ms, PSNR delta {rows[-1]['quicksr_minus_lanczos_psnr_db']:+.3f} dB")

    report = {
        "schema_version": 1,
        "status": "observed-pc-open-image-18-route-matrix",
        "route_count": len(results),
        "asset": {"id": asset["id"], "sha256": asset["sha256"], "license": asset["license"], "repository_policy": asset["repository_policy"]},
        "models": [{"scale": scale, "id": runner.model_id, "sha256": runner.model_sha256} for scale, runner in runners.items()],
        "summary": {
            "quicksr_psnr_wins": sum(row["quicksr_minus_lanczos_psnr_db"] > 0 for row in rows),
            "lanczos_psnr_wins_or_ties": sum(row["quicksr_minus_lanczos_psnr_db"] <= 0 for row in rows),
            "median_chain_ms": statistics.median(row["chain_total_ms"] for row in rows),
            "max_chain_ms": max(row["chain_total_ms"] for row in rows),
        },
        "runtime": {"platform": platform.platform(), "python": platform.python_version(), "numpy": np.__version__, "onnxruntime": ort.__version__, "pillow": pillow_version},
        "routes": results,
        "limits": [
            "The open source is one 1080p 3D-animation frame, not a representative anime corpus.",
            "The 1440p and 4K references are resized from 1080p, not native-resolution ground truth.",
            "This is a clean Lanczos degradation; compression/noise robustness is measured separately.",
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
