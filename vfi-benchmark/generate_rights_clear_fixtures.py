"""Generate small, rights-clear animation drawings for the offline VFI gate.

Every pixel is created by this script.  No external image, font, model, or media is embedded.
Generated PNG files and the runtime manifest belong in an ignored output directory.
"""

from __future__ import annotations

import argparse
import json
from hashlib import sha256
from pathlib import Path

from PIL import Image, ImageDraw


WIDTH, HEIGHT = 320, 180


def digest(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


def draw_motion_frame(path: Path, x: int, arm_offset: int) -> None:
    image = Image.new("RGB", (WIDTH, HEIGHT), (244, 238, 218))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 132, WIDTH, HEIGHT), fill=(106, 151, 123))
    draw.line((0, 131, WIDTH, 131), fill=(24, 44, 38), width=3)
    draw.ellipse((x - 20, 48, x + 20, 88), fill=(248, 205, 165), outline=(30, 26, 30), width=4)
    draw.polygon([(x - 22, 60), (x - 8, 35), (x + 25, 55), (x + 12, 48)], fill=(55, 42, 75))
    draw.arc((x - 10, 58, x + 12, 76), 15, 150, fill=(30, 26, 30), width=2)
    draw.ellipse((x - 8, 63, x - 4, 68), fill=(30, 26, 30))
    draw.ellipse((x + 6, 63, x + 10, 68), fill=(30, 26, 30))
    draw.rounded_rectangle((x - 24, 88, x + 24, 139), radius=9, fill=(72, 116, 184), outline=(30, 26, 30), width=4)
    draw.line((x - 20, 100, x - 48, 106 + arm_offset), fill=(30, 26, 30), width=7)
    draw.line((x + 20, 100, x + 48, 106 - arm_offset), fill=(30, 26, 30), width=7)
    draw.line((x - 12, 138, x - 18, 164), fill=(30, 26, 30), width=7)
    draw.line((x + 12, 138, x + 18, 164), fill=(30, 26, 30), width=7)
    image.save(path)


def draw_cut_frame(path: Path, variant: int) -> None:
    if variant == 0:
        image = Image.new("RGB", (WIDTH, HEIGHT), (14, 24, 58))
        draw = ImageDraw.Draw(image)
        draw.ellipse((220, 18, 280, 78), fill=(242, 230, 174))
        for x, h in [(20, 70), (72, 105), (132, 60), (185, 95), (255, 80)]:
            draw.rectangle((x, HEIGHT - h, x + 42, HEIGHT), fill=(25, 42, 75), outline=(100, 140, 188), width=2)
            for y in range(HEIGHT - h + 12, HEIGHT - 8, 18):
                draw.rectangle((x + 8, y, x + 14, y + 7), fill=(246, 190, 80))
    else:
        image = Image.new("RGB", (WIDTH, HEIGHT), (232, 72, 62))
        draw = ImageDraw.Draw(image)
        draw.polygon([(28, 22), (294, 70), (170, 164)], fill=(252, 216, 92), outline=(35, 28, 35))
        draw.ellipse((105, 54, 204, 153), fill=(64, 176, 166), outline=(35, 28, 35), width=6)
        draw.line((34, 154, 286, 28), fill=(35, 28, 35), width=8)
    image.save(path)


def event(frame_id: str, filename: str, stream: str, generation: int, expected: str, gt: str | None = None) -> dict:
    result = {
        "frame_id": frame_id,
        "file": filename,
        "stream_id": stream,
        "generation": generation,
        "expected_reason": expected,
    }
    if gt:
        result["ground_truth_midpoint"] = gt
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)

    draw_motion_frame(output / "distinct_0.png", 116, -7)
    draw_motion_frame(output / "distinct_mid_gt.png", 160, 0)
    draw_motion_frame(output / "distinct_1.png", 204, 7)
    (output / "hold_0.png").write_bytes((output / "distinct_0.png").read_bytes())
    (output / "hold_1.png").write_bytes((output / "distinct_0.png").read_bytes())
    (output / "near_hold_0.png").write_bytes((output / "distinct_0.png").read_bytes())
    near = Image.open(output / "distinct_0.png").convert("RGB")
    near_pixels = near.load()
    for y in range(12, 36):
        for x in range(12, 52):
            r, g, b = near_pixels[x, y]
            near_pixels[x, y] = (min(255, r + 3), min(255, g + 3), min(255, b + 3))
    near.save(output / "near_hold_1.png")
    draw_cut_frame(output / "cut_0.png", 0)
    draw_cut_frame(output / "cut_1.png", 1)

    events = [
        event("distinct-0", "distinct_0.png", "motion", 0, "NO_PREVIOUS_FRAME"),
        event("distinct-1", "distinct_1.png", "motion", 0, "DISTINCT_DRAWING", "distinct_mid_gt.png"),
        event("hold-0", "hold_0.png", "hold", 0, "STREAM_EPOCH_RESET"),
        event("hold-1", "hold_1.png", "hold", 0, "HOLD_EXACT"),
        event("near-hold-0", "near_hold_0.png", "near-hold", 0, "STREAM_EPOCH_RESET"),
        event("near-hold-1", "near_hold_1.png", "near-hold", 0, "HOLD_NEAR"),
        event("cut-0", "cut_0.png", "cut", 0, "STREAM_EPOCH_RESET"),
        event("cut-1", "cut_1.png", "cut", 0, "HARD_CUT"),
        event("seek-0", "distinct_0.png", "seek", 0, "STREAM_EPOCH_RESET"),
        event("seek-1", "distinct_1.png", "seek", 1, "GENERATION_RESET"),
        event("epoch-0", "distinct_0.png", "epoch-a", 0, "STREAM_EPOCH_RESET"),
        event("epoch-1", "distinct_1.png", "epoch-b", 0, "STREAM_EPOCH_RESET"),
    ]
    files = sorted({item["file"] for item in events} | {"distinct_mid_gt.png"})
    manifest = {
        "schema": "anime-vfi-rights-clear-fixture.v1",
        "rights": {
            "status": "project-generated",
            "external_sources": [],
            "statement": "All pixels are deterministically generated by generate_rights_clear_fixtures.py.",
        },
        "dimensions": [WIDTH, HEIGHT],
        "files": {name: {"sha256": digest(output / name), "bytes": (output / name).stat().st_size} for name in files},
        "events": events,
    }
    manifest_path = output / "fixture-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(manifest_path)


if __name__ == "__main__":
    main()
