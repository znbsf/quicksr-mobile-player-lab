from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = Path(__file__).with_name("anime-targets.json")


def fit_content(source: dict[str, Any], target: dict[str, Any]) -> dict[str, int | float]:
    """Fit source inside the target canvas without cropping or stretching."""
    scale = min(target["width"] / source["width"], target["height"] / source["height"])
    width = min(target["width"], round(source["width"] * scale))
    height = min(target["height"], round(source["height"] * scale))
    return {
        "width": width,
        "height": height,
        "x": (target["width"] - width) // 2,
        "y": (target["height"] - height) // 2,
        "scale": scale,
    }


def _close(left: float, right: float) -> bool:
    return math.isclose(left, right, rel_tol=0.0, abs_tol=1e-9)


def choose_strategy(scale: float, integrated: set[float]) -> dict[str, Any]:
    canonical_scales = [1.5, 2.0, 2.25, 3.0, 4.0, 4.5, 6.0]
    snapped_scale = min(canonical_scales, key=lambda value: abs(value - scale))
    if abs(snapped_scale - scale) / scale > 0.002:
        snapped_scale = scale
    strategy = _choose_canonical_strategy(snapped_scale, integrated)
    if not _close(snapped_scale, scale):
        adjustment = {"kind": "linear", "scale": scale / snapped_scale, "role": "pixel-aspect-fit"}
        strategy["quality_chain"].append(adjustment)
        strategy["realtime_fallback"].append(adjustment)
    strategy["quality_availability"] = _chain_availability(
        strategy["quality_chain"], integrated
    )
    strategy["realtime_availability"] = _chain_availability(
        strategy["realtime_fallback"], integrated
    )
    return strategy


def _chain_availability(chain: list[dict[str, Any]], integrated: set[float]) -> str:
    required = {float(item["scale"]) for item in chain if item["kind"] == "neural"}
    return "integrated" if required.issubset(integrated) else "model-needed"


def _choose_canonical_strategy(scale: float, integrated: set[float]) -> dict[str, Any]:
    exact = [1.5, 2.0, 3.0, 4.0]
    if any(_close(scale, value) for value in exact):
        model_scale = next(value for value in exact if _close(scale, value))
        return {
            "quality_chain": [{"kind": "neural", "scale": model_scale}],
            "realtime_fallback": [{"kind": "neural", "scale": model_scale}],
        }

    if _close(scale, 2.25):
        return {
            "quality_chain": [
                {"kind": "neural", "scale": 3.0},
                {"kind": "linear", "scale": 0.75},
            ],
            "realtime_fallback": [
                {"kind": "neural", "scale": 2.0},
                {"kind": "linear", "scale": 1.125},
            ],
        }

    if _close(scale, 4.5):
        return {
            "quality_chain": [
                {"kind": "neural", "scale": 3.0},
                {"kind": "neural", "scale": 1.5},
            ],
            "realtime_fallback": [
                {"kind": "neural", "scale": 4.0},
                {"kind": "linear", "scale": 1.125},
            ],
        }

    if _close(scale, 6.0):
        return {
            "quality_chain": [
                {"kind": "neural", "scale": 3.0},
                {"kind": "neural", "scale": 2.0},
            ],
            "realtime_fallback": [
                {"kind": "neural", "scale": 4.0},
                {"kind": "linear", "scale": 1.5},
            ],
        }

    larger = next((value for value in exact if value >= scale), 4.0)
    return {
        "quality_chain": [
            {"kind": "neural", "scale": larger},
            {"kind": "linear", "scale": scale / larger},
        ],
        "realtime_fallback": [
            {"kind": "neural", "scale": 2.0},
            {"kind": "linear", "scale": scale / 2.0},
        ],
    }


def build_matrix(config: dict[str, Any]) -> dict[str, Any]:
    integrated = {float(value) for value in config["model_inventory"]["integrated_scales"]}
    routes: list[dict[str, Any]] = []
    for source in config["sources"]:
        for target in config["targets"]:
            content = fit_content(source, target)
            strategy = choose_strategy(float(content["scale"]), integrated)
            routes.append(
                {
                    "id": f'{source["id"]}-to-{target["id"]}',
                    "source": {key: source[key] for key in ("id", "width", "height", "layout")},
                    "canvas": {key: target[key] for key in ("id", "width", "height")},
                    "content_rect": content,
                    **strategy,
                }
            )
    return {
        "schema_version": 1,
        "aspect_policy": config["aspect_policy"],
        "model_inventory": config["model_inventory"],
        "route_count": len(routes),
        "routes": routes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate the anime SR target route matrix")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    matrix = build_matrix(config)
    payload = json.dumps(matrix, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
