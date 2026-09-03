from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import platform
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, __version__ as pillow_version

from benchmark_runtime import quality_metrics


ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "anime-candidate-contract"
DEFAULT_PROFILES = HERE / "degradation-profiles.json"
DEFAULT_ASSETS = HERE / "open-assets.json"
PROFILE_IDS = ("clean-lanczos", "legacy-soft-jpeg-q35")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def draw_pixel_text(
    draw: ImageDraw.ImageDraw,
    origin: tuple[int, int],
    text: str,
    scale: int,
    fill: tuple[int, int, int] = (250, 250, 250),
) -> None:
    glyphs = {
        "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
        "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
        "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
        "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
        "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
        "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
        "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
        "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
        "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        " ": ("00000",) * 7,
    }
    x0, y0 = origin
    cursor = x0
    for character in text:
        rows = glyphs[character]
        for row_index, row in enumerate(rows):
            for column_index, bit in enumerate(row):
                if bit == "1":
                    left = cursor + column_index * scale
                    top = y0 + row_index * scale
                    draw.rectangle((left, top, left + scale - 1, top + scale - 1), fill=fill)
        cursor += 6 * scale


def synthetic_lineart_reference(width: int = 1280, height: int = 720) -> Image.Image:
    if width % 2 or height % 2 or width < 128 or height < 72:
        raise ValueError("fixture dimensions must be even and at least 128x72")
    image = Image.new("RGB", (width, height), (232, 222, 202))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, width - 1, height - 1), outline=(10, 10, 16), width=max(1, width // 320))
    draw.polygon(
        (
            (width * 8 // 100, height * 76 // 100),
            (width * 27 // 100, height * 18 // 100),
            (width * 48 // 100, height * 77 // 100),
        ),
        fill=(217, 87, 73),
        outline=(18, 22, 30),
    )
    draw.ellipse(
        (width * 46 // 100, height * 13 // 100, width * 82 // 100, height * 78 // 100),
        fill=(90, 157, 211),
        outline=(18, 22, 30),
        width=max(2, width // 256),
    )
    for index in range(1, 12):
        x = width * index // 12
        color = (15, 20, 28) if index % 2 else (252, 246, 224)
        draw.line((x, height // 12, width - x // 3, height * 10 // 12), fill=color, width=1 + index % 3)
    return image


def add_pixel_subtitle(image: Image.Image, high_contrast: bool, text: str = "ANIME SR TEST 24") -> Image.Image:
    result = image.copy()
    draw = ImageDraw.Draw(result)
    if high_contrast:
        background = (8, 10, 16)
        foreground = (250, 250, 250)
        outline = foreground
    else:
        # Deliberately below the current cadence dense-subtitle luma threshold.
        background = (104, 108, 116)
        foreground = (135, 139, 147)
        outline = (123, 127, 135)
    draw.rectangle(
        (image.width * 12 // 100, image.height * 80 // 100,
         image.width * 88 // 100, image.height * 96 // 100),
        fill=background,
        outline=outline,
        width=max(1, image.width // 640),
    )
    text_scale = max(1, min(image.width // 180, image.height // 90))
    text_width = len(text) * 6 * text_scale
    draw_pixel_text(
        draw,
        ((image.width - text_width) // 2, image.height * 84 // 100),
        text,
        text_scale,
        fill=foreground,
    )
    return result


def synthetic_lineart_subtitle_reference(width: int = 1280, height: int = 720) -> Image.Image:
    return add_pixel_subtitle(synthetic_lineart_reference(width, height), True)


def degradation_profiles(path: Path = DEFAULT_PROFILES) -> dict[str, dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    profiles = {item["id"]: item for item in payload["route_image_profiles"]}
    if set(PROFILE_IDS) - set(profiles):
        raise ValueError("required clean and legacy degradation profiles are missing")
    return {profile_id: profiles[profile_id] for profile_id in PROFILE_IDS}


def degrade(reference: Image.Image, profile: dict[str, Any]) -> tuple[Image.Image, bytes | None]:
    prepared = reference
    if profile["pre_blur_radius"] > 0:
        prepared = prepared.filter(ImageFilter.GaussianBlur(radius=profile["pre_blur_radius"]))
    low = prepared.resize((reference.width // 2, reference.height // 2), Image.Resampling.LANCZOS)
    if profile["jpeg_quality"] is None:
        return low, None
    encoded = io.BytesIO()
    low.save(
        encoded,
        format="JPEG",
        quality=profile["jpeg_quality"],
        subsampling=profile["jpeg_subsampling"],
        optimize=False,
        progressive=False,
    )
    payload = encoded.getvalue()
    with Image.open(io.BytesIO(payload)) as decoded:
        return decoded.convert("RGB"), payload


def crop_layout(image: Image.Image, layout: str) -> Image.Image:
    if layout == "1:1":
        side = min(image.size)
        left = (image.width - side) // 2
        top = (image.height - side) // 2
        return image.crop((left, top, left + side, top + side))
    if layout != "16:9":
        raise ValueError(f"unsupported layout: {layout}")
    target_aspect = 16 / 9
    if image.width / image.height > target_aspect:
        width = round(image.height * target_aspect)
        left = (image.width - width) // 2
        return image.crop((left, 0, left + width, image.height))
    height = round(image.width / target_aspect)
    top = (image.height - height) // 2
    return image.crop((0, top, image.width, top + height))


def verified_open_references(assets_path: Path = DEFAULT_ASSETS) -> list[tuple[str, str, Image.Image, dict[str, Any]]]:
    manifest = json.loads(assets_path.read_text(encoding="utf-8"))
    cache_root = ROOT / manifest["cache_root"]
    references: list[tuple[str, str, Image.Image, dict[str, Any]]] = []
    for asset in manifest["assets"]:
        if asset["kind"] != "image" or asset["domain"] != "open-comic-illustration":
            continue
        path = cache_root / asset["file_name"]
        if not path.is_file() or path.stat().st_size != asset["bytes"] or file_sha256(path) != asset["sha256"]:
            raise ValueError(f"open asset is missing or unverified: {asset['id']}")
        with Image.open(path) as original:
            for layout in asset["benchmark_layouts"]:
                cropped = crop_layout(original.convert("RGB"), layout)
                size = (1280, 720) if layout == "16:9" else (720, 720)
                reference = cropped.resize(size, Image.Resampling.LANCZOS)
                references.append((asset["id"], layout, reference, asset["license"]))
    return references


def prepare_contract(output: Path, include_open_assets: bool) -> dict[str, Any]:
    output = output.resolve()
    inputs = output / "inputs"
    references = output / "references"
    baselines = output / "lanczos"
    for directory in (inputs, references, baselines):
        directory.mkdir(parents=True, exist_ok=True)
    original_license = {
        "spdx": "LicenseRef-Project-Original-Fixture",
        "attribution": "Generated deterministically by project source",
    }
    lineart = synthetic_lineart_reference()
    sources: list[tuple[str, str, Image.Image, dict[str, Any]]] = [
        ("synthetic-lineart-edge", "16:9", lineart, original_license),
        (
            "synthetic-high-contrast-subtitle",
            "16:9",
            add_pixel_subtitle(lineart, True),
            original_license,
        ),
        (
            "synthetic-low-contrast-subtitle",
            "16:9",
            add_pixel_subtitle(lineart, False),
            original_license,
        ),
    ]
    if include_open_assets:
        sources.extend(verified_open_references())

    cases = []
    for source_id, layout, reference, license_info in sources:
        for profile_id, profile in degradation_profiles().items():
            case_id = f"{source_id}--{profile_id}"
            low, jpeg_payload = degrade(reference, profile)
            lanczos = low.resize(reference.size, Image.Resampling.LANCZOS)
            input_path = inputs / f"{case_id}.png"
            reference_path = references / f"{case_id}.png"
            baseline_path = baselines / f"{case_id}.png"
            low.save(input_path)
            reference.save(reference_path)
            lanczos.save(baseline_path)
            cases.append(
                {
                    "id": case_id,
                    "source": source_id,
                    "layout": layout,
                    "license": license_info,
                    "degradation": profile,
                    "input": {
                        "path": input_path.relative_to(output).as_posix(),
                        "width": low.width,
                        "height": low.height,
                        "sha256": file_sha256(input_path),
                    },
                    "reference": {
                        "path": reference_path.relative_to(output).as_posix(),
                        "width": reference.width,
                        "height": reference.height,
                        "sha256": file_sha256(reference_path),
                    },
                    "lanczos": {
                        "path": baseline_path.relative_to(output).as_posix(),
                        "sha256": file_sha256(baseline_path),
                    },
                    "jpeg_bytes": len(jpeg_payload) if jpeg_payload is not None else None,
                }
            )
    contract = {
        "schema_version": 1,
        "status": "prepared-rights-clear-anime-candidate-contract",
        "scale": 2,
        "candidate_output_protocol": "Write one RGB PNG named <case-id>.png into an output directory.",
        "profiles": list(PROFILE_IDS),
        "cases": cases,
        "runtime": {
            "platform": platform.platform(),
            "python": platform.python_version(),
            "numpy": np.__version__,
            "pillow": pillow_version,
        },
        "limits": [
            "The project-original synthetic fixtures check clean/degraded line art and high/low-contrast subtitle edges but are not representative anime quality evidence.",
            "The optional Pepper and Carrot images are comic/illustration assets, not commercial Japanese anime.",
            "This contract is frame-by-frame and does not establish temporal stability or realtime playback.",
        ],
    }
    (output / "contract.json").write_text(json.dumps(contract, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return contract


def to_float(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0


def evaluate_outputs(contract_path: Path, outputs: Path, candidate_id: str, output: Path) -> dict[str, Any]:
    contract_path = contract_path.resolve()
    contract_root = contract_path.parent
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    if contract.get("status") != "prepared-rights-clear-anime-candidate-contract":
        raise ValueError("unrecognized benchmark contract status")
    rows = []
    for case in contract["cases"]:
        candidate_path = outputs.resolve() / f"{case['id']}.png"
        if not candidate_path.is_file():
            raise FileNotFoundError(f"missing candidate output: {candidate_path.name}")
        with Image.open(candidate_path) as image:
            expected = (case["reference"]["width"], case["reference"]["height"])
            if image.mode != "RGB" or image.size != expected:
                raise ValueError(
                    f"candidate output contract mismatch for {case['id']}: "
                    f"expected RGB {expected[0]}x{expected[1]}, observed {image.mode} {image.width}x{image.height}"
                )
        reference = to_float(contract_root / case["reference"]["path"])
        for method, path in (
            ("candidate", candidate_path),
            ("lanczos", contract_root / case["lanczos"]["path"]),
        ):
            measured = quality_metrics(reference, to_float(path))
            rows.append(
                {
                    "case": case["id"],
                    "source": case["source"],
                    "degradation": case["degradation"]["id"],
                    "method": method,
                    "output_sha256": file_sha256(path),
                    **measured,
                }
            )
    candidate_rows = [row for row in rows if row["method"] == "candidate"]
    lanczos_by_case = {row["case"]: row for row in rows if row["method"] == "lanczos"}
    report = {
        "schema_version": 1,
        "status": "observed-offline-candidate-output-quality",
        "candidate_id": candidate_id,
        "contract_sha256": file_sha256(contract_path),
        "case_count": len(candidate_rows),
        "candidate_psnr_wins": sum(row["psnr_db"] > lanczos_by_case[row["case"]]["psnr_db"] for row in candidate_rows),
        "rows": rows,
        "limits": [
            "Metrics cover supplied frame outputs only; inference timing, memory, Android compatibility and temporal stability are not measured.",
            "Global SSIM and edge MAE are diagnostics and do not replace blinded human review.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with output.with_suffix(".csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare or evaluate the rights-clear anime SISR candidate contract")
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    prepare_parser.add_argument("--include-open-assets", action="store_true")
    evaluate_parser = subparsers.add_parser("evaluate")
    evaluate_parser.add_argument("--contract", type=Path, required=True)
    evaluate_parser.add_argument("--outputs", type=Path, required=True)
    evaluate_parser.add_argument("--candidate-id", required=True)
    evaluate_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        result = prepare_contract(args.output, args.include_open_assets)
    else:
        result = evaluate_outputs(args.contract, args.outputs, args.candidate_id, args.output)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
