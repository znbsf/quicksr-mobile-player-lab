"""Generate rights-clear, all-distinct motion sequences for resident VFI timing."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

from vfi_prefilter import FrameRecord, VfiPrefilter, file_sha256


DEFAULT_LEVELS = [(160, 90), (256, 144), (320, 180), (480, 270), (640, 360)]


def scaled(value: float, scale: float) -> int:
    return max(1, round(value * scale))


def draw_motion_frame(path: Path, width: int, height: int, phase: float) -> None:
    sx, sy = width / 320.0, height / 180.0
    image = Image.new("RGB", (width, height), (244, 238, 218))
    draw = ImageDraw.Draw(image)
    floor_y = round(height * 0.73)
    draw.rectangle((0, floor_y, width, height), fill=(106, 151, 123))
    draw.line((0, floor_y, width, floor_y), fill=(24, 44, 38), width=scaled(3, sy))

    center_x = round(width * (0.26 + phase * 0.078))
    arm_offset = math.sin(phase * math.pi / 3.0) * height * 0.045
    head_top, head_bottom = round(height * 0.27), round(height * 0.49)
    head_rx = scaled(20, sx)
    draw.ellipse(
        (center_x - head_rx, head_top, center_x + head_rx, head_bottom),
        fill=(248, 205, 165), outline=(30, 26, 30), width=scaled(4, min(sx, sy)),
    )
    draw.polygon(
        [
            (center_x - scaled(22, sx), round(height * 0.34)),
            (center_x - scaled(8, sx), round(height * 0.19)),
            (center_x + scaled(25, sx), round(height * 0.31)),
            (center_x + scaled(12, sx), round(height * 0.27)),
        ],
        fill=(55, 42, 75),
    )
    eye_y = round(height * 0.36)
    eye_r = scaled(2, min(sx, sy))
    for eye_x in (center_x - scaled(7, sx), center_x + scaled(8, sx)):
        draw.ellipse((eye_x - eye_r, eye_y - eye_r, eye_x + eye_r, eye_y + eye_r), fill=(30, 26, 30))
    draw.rounded_rectangle(
        (
            center_x - scaled(24, sx), round(height * 0.49),
            center_x + scaled(24, sx), round(height * 0.78),
        ),
        radius=scaled(9, min(sx, sy)), fill=(72, 116, 184),
        outline=(30, 26, 30), width=scaled(4, min(sx, sy)),
    )
    line_width = scaled(7, min(sx, sy))
    shoulder_y = round(height * 0.56)
    draw.line(
        (
            center_x - scaled(20, sx), shoulder_y,
            center_x - scaled(48, sx), round(height * 0.60 + arm_offset),
        ), fill=(30, 26, 30), width=line_width,
    )
    draw.line(
        (
            center_x + scaled(20, sx), shoulder_y,
            center_x + scaled(48, sx), round(height * 0.60 - arm_offset),
        ), fill=(30, 26, 30), width=line_width,
    )
    leg_top = round(height * 0.77)
    draw.line((center_x - scaled(12, sx), leg_top, center_x - scaled(18, sx), round(height * 0.92)), fill=(30, 26, 30), width=line_width)
    draw.line((center_x + scaled(12, sx), leg_top, center_x + scaled(18, sx), round(height * 0.92)), fill=(30, 26, 30), width=line_width)
    # A small foreground accent moves at a different cadence, preventing translation-only pairs.
    accent_x = round(width * (0.80 - phase * 0.035))
    draw.polygon(
        [(accent_x, round(height * 0.22)), (accent_x + scaled(10, sx), round(height * 0.29)), (accent_x, round(height * 0.36)), (accent_x - scaled(10, sx), round(height * 0.29))],
        fill=(232, 92, 82), outline=(30, 26, 30),
    )
    image.save(path)


def generate_level(root: Path, width: int, height: int, padding_multiple: int = 32) -> dict:
    level_id = f"{width}x{height}"
    level_root = root / level_id
    input_root = level_root / "input"
    ground_truth_root = level_root / "ground-truth"
    input_root.mkdir(parents=True, exist_ok=True)
    ground_truth_root.mkdir(parents=True, exist_ok=True)

    for index in range(7):
        draw_motion_frame(input_root / f"frame_{index:03d}.png", width, height, float(index))
    for index in range(6):
        draw_motion_frame(ground_truth_root / f"mid_{index:03d}.png", width, height, index + 0.5)

    engine = VfiPrefilter()
    decisions = []
    input_files = sorted(input_root.glob("*.png"))
    for index, path in enumerate(input_files):
        decision = engine.observe(
            FrameRecord(f"{level_id}-frame-{index}", str(path), file_sha256(path), level_id, 0)
        )
        if index == 0:
            if decision.reason != "NO_PREVIOUS_FRAME":
                raise RuntimeError(decision.to_dict())
        elif decision.decision != "INTERPOLATE" or decision.reason != "DISTINCT_DRAWING":
            raise RuntimeError(f"{level_id} pair {index - 1} was not distinct: {decision.to_dict()}")
        decisions.append(decision.to_dict())

    all_files = input_files + sorted(ground_truth_root.glob("*.png"))
    return {
        "id": level_id,
        "width": width,
        "height": height,
        "padded_width": (width + padding_multiple - 1) // padding_multiple * padding_multiple,
        "padded_height": (height + padding_multiple - 1) // padding_multiple * padding_multiple,
        "source_frame_count": 7,
        "midpoint_count": 6,
        "files": {
            path.relative_to(level_root).as_posix(): {"bytes": path.stat().st_size, "sha256": file_sha256(path)}
            for path in all_files
        },
        "decisions": decisions,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--levels", nargs="*", default=[f"{w}x{h}" for w, h in DEFAULT_LEVELS])
    parser.add_argument("--padding-multiple", type=int, default=32)
    args = parser.parse_args()
    if args.padding_multiple <= 0 or args.padding_multiple > 1024:
        parser.error("--padding-multiple must be in the range 1..1024")
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    levels = []
    for value in args.levels:
        width, height = (int(part) for part in value.lower().split("x", 1))
        levels.append(generate_level(output, width, height, args.padding_multiple))
    manifest = {
        "schema": "anime-vfi-resident-fixture.v1",
        "padding_multiple": args.padding_multiple,
        "rights": {
            "status": "project-generated",
            "external_sources": [],
            "statement": "All source and known-midpoint pixels are generated by this script.",
        },
        "levels": levels,
    }
    path = output / "resident-fixture-manifest.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(path)


if __name__ == "__main__":
    main()
