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

from benchmark_runtime import ROOT, OrtModelRunner, file_sha256, load_model_spec, percentile, quality_metrics


HERE = Path(__file__).resolve().parent
DEFAULT_ASSETS = HERE / "open-assets.json"
DEFAULT_DEGRADATIONS = HERE / "degradation-profiles.json"
DEFAULT_REGISTRY = HERE / "model-registry.json"
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "open-image-2x"


def image_to_float(image: Image.Image) -> np.ndarray:
    return np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0


def float_to_image(values: np.ndarray) -> Image.Image:
    return Image.fromarray(np.rint(np.clip(values, 0.0, 1.0) * 255.0).astype(np.uint8))


def degrade(reference: Image.Image, profile: dict[str, Any]) -> tuple[Image.Image, bytes | None]:
    source = reference
    if profile["pre_blur_radius"] > 0:
        source = source.filter(ImageFilter.GaussianBlur(radius=profile["pre_blur_radius"]))
    low = source.resize((profile["input_width"], profile["input_height"]), Image.Resampling.LANCZOS)
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
        return decoded.convert("RGB"), encoded


def find_asset(manifest: dict[str, Any], asset_id: str) -> dict[str, Any]:
    matches = [item for item in manifest["assets"] if item["id"] == asset_id]
    if len(matches) != 1 or matches[0]["kind"] != "image":
        raise ValueError(f"expected one image asset named {asset_id}")
    return matches[0]


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark the registered 2x model on a rights-clear open frame")
    parser.add_argument("--asset", default="bbb-1080-frame-01000")
    parser.add_argument("--model", default="quicksrnet-small-2x-640x360")
    parser.add_argument("--iterations", type=int, default=10)
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.iterations < 1 or args.warmups < 0:
        raise ValueError("iterations must be >= 1 and warmups must be >= 0")

    assets_manifest = json.loads(DEFAULT_ASSETS.read_text(encoding="utf-8"))
    asset = find_asset(assets_manifest, args.asset)
    source_path = ROOT / assets_manifest["cache_root"] / asset["file_name"]
    if not source_path.is_file() or source_path.stat().st_size != asset["bytes"] or file_sha256(source_path) != asset["sha256"]:
        raise ValueError("open image asset is missing or unverified; run fetch_open_assets.py first")
    model = load_model_spec(DEFAULT_REGISTRY, args.model)
    runner = OrtModelRunner(model)
    degradations = json.loads(DEFAULT_DEGRADATIONS.read_text(encoding="utf-8"))["image_profiles"]
    output_dir = args.output.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    with Image.open(source_path) as original:
        if original.size != (asset["width"], asset["height"]):
            raise ValueError(f"asset dimension mismatch: expected {asset['width']}x{asset['height']}, observed {original.size}")
        reference_image = original.convert("RGB").resize(
            (model.output_shape[3], model.output_shape[2]), Image.Resampling.LANCZOS
        )
    reference = image_to_float(reference_image)
    reference_image.save(output_dir / "reference.png")
    rows: list[dict[str, Any]] = []
    cases: list[dict[str, Any]] = []
    for profile in degradations:
        low_image, jpeg_payload = degrade(reference_image, profile)
        low = image_to_float(low_image)
        runner.warmup(low, args.warmups)
        samples: list[float] = []
        neural = None
        for _ in range(args.iterations):
            neural, elapsed = runner.infer(low)
            samples.append(elapsed)
        assert neural is not None
        baselines: dict[str, tuple[np.ndarray, float]] = {}
        for method, resampling in (("bilinear", Image.Resampling.BILINEAR), ("lanczos", Image.Resampling.LANCZOS)):
            started = time.perf_counter_ns()
            result = low_image.resize((model.output_shape[3], model.output_shape[2]), resampling)
            elapsed = (time.perf_counter_ns() - started) / 1_000_000.0
            baselines[method] = (image_to_float(result), elapsed)

        stem = profile["id"]
        low_image.save(output_dir / f"{stem}-lr.png")
        if jpeg_payload is not None:
            (output_dir / f"{stem}-lr.jpg").write_bytes(jpeg_payload)
        float_to_image(neural).save(output_dir / f"{stem}-quicksr.png")
        for method, (values, _) in baselines.items():
            float_to_image(values).save(output_dir / f"{stem}-{method}.png")

        methods = {"quicksr": (neural, statistics.median(samples)), **baselines}
        method_metrics = {}
        for method, (values, timing_ms) in methods.items():
            measured = quality_metrics(reference, values)
            method_metrics[method] = {**measured, "timing_ms": timing_ms}
            rows.append({"asset": asset["id"], "degradation": stem, "method": method, "timing_ms": timing_ms, **measured})
        cases.append(
            {
                "degradation": profile,
                "jpeg_bytes": len(jpeg_payload) if jpeg_payload is not None else None,
                "quicksr_timing_ms": {
                    "iterations": args.iterations,
                    "mean": statistics.fmean(samples),
                    "p50": statistics.median(samples),
                    "p95_nearest_rank": percentile(samples, 0.95),
                    "samples": samples,
                },
                "methods": method_metrics,
            }
        )

    report = {
        "schema_version": 1,
        "status": "observed-pc-open-image-baseline",
        "asset": {
            "id": asset["id"], "sha256": asset["sha256"], "bytes": asset["bytes"],
            "license": asset["license"], "repository_policy": asset["repository_policy"],
        },
        "model": {"id": model.id, "scale": model.scale, "sha256": model.sha256, "provider": "CPUExecutionProvider"},
        "runtime": {"platform": platform.platform(), "python": platform.python_version(), "numpy": np.__version__, "onnxruntime": ort.__version__, "pillow": pillow_version},
        "cases": cases,
        "limits": [
            "Big Buck Bunny is open 3D animation, not a representative Japanese anime corpus.",
            "Global SSIM and edge MAE are diagnostics; perceptual and human review remain open.",
            "CPUExecutionProvider timing does not predict Android QNN HTP timing."
        ],
    }
    (output_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "metrics.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["asset", "degradation", "method", "timing_ms", "psnr_db", "global_ssim", "edge_mae"])
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
