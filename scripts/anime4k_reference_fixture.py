#!/usr/bin/env python3
"""Generate and compare the fixed opaque Anime4K same-frame reference fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


INPUT_WIDTH = 32
INPUT_HEIGHT = 24
OUTPUT_WIDTH = INPUT_WIDTH * 2
OUTPUT_HEIGHT = INPUT_HEIGHT * 2
SHADER_BYTES = 18_638
SHADER_SHA256 = "4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4"


def fixed_opaque_rgb() -> bytes:
    pixels = bytearray()
    for y in range(INPUT_HEIGHT):
        for x in range(INPUT_WIDTH):
            if 8 <= x < 16 and 6 <= y < 12:
                rgb = (128, 128, 128)  # Transfer-sensitive electrical mid-gray patch.
            elif x in (3, 4, 27) or y in (4, 19):
                rgb = (244, 244, 244)  # Thin line-art edges.
            elif 18 <= x < 30 and 15 <= y < 19:
                rgb = (16, 16, 16) if (x + y) % 3 else (230, 230, 230)
            else:
                rgb = ((x * 17 + y * 3) % 256,
                       (x * 5 + y * 19) % 256,
                       (x * 11 + y * 7) % 256)
            pixels.extend(rgb)
    return bytes(pixels)


def write_ppm(path: Path, width: int, height: int, rgb: bytes) -> None:
    expected = width * height * 3
    if len(rgb) != expected:
        raise ValueError(f"RGB payload is {len(rgb)} bytes, expected {expected}")
    path.write_bytes(f"P6\n{width} {height}\n255\n".encode("ascii") + rgb)


def read_ppm(path: Path) -> tuple[int, int, bytes]:
    payload = path.read_bytes()
    try:
        magic, dimensions, maximum, rgb = payload.split(b"\n", 3)
        width_text, height_text = dimensions.split()
    except ValueError as failure:
        raise ValueError(f"unsupported PPM header: {path}") from failure
    if magic != b"P6" or maximum != b"255":
        raise ValueError(f"expected binary P6 PPM with max value 255: {path}")
    width, height = int(width_text), int(height_text)
    if len(rgb) != width * height * 3:
        raise ValueError(f"truncated or oversized PPM payload: {path}")
    return width, height, rgb


def verify_shader(shader_path: Path) -> None:
    payload = shader_path.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    observed = hashlib.sha256(payload).hexdigest()
    if len(payload) != SHADER_BYTES or observed != SHADER_SHA256:
        raise ValueError(
            f"pinned shader mismatch: bytes={len(payload)}, sha256={observed}")


def compare_outputs(android_path: Path, mpv_path: Path) -> dict[str, object]:
    android_width, android_height, android_rgb = read_ppm(android_path)
    mpv_width, mpv_height, mpv_rgb = read_ppm(mpv_path)
    expected_dimensions = (OUTPUT_WIDTH, OUTPUT_HEIGHT)
    if (android_width, android_height) != expected_dimensions:
        raise ValueError(f"Android output must be {OUTPUT_WIDTH}x{OUTPUT_HEIGHT}")
    if (mpv_width, mpv_height) != expected_dimensions:
        raise ValueError(f"mpv output must be {OUTPUT_WIDTH}x{OUTPUT_HEIGHT}")
    errors = [abs(left - right) for left, right in zip(android_rgb, mpv_rgb)]
    mismatch_pixels = sum(
        any(errors[index + channel] != 0 for channel in range(3))
        for index in range(0, len(errors), 3))
    return {
        "status": "PASS" if not any(errors) else "DIFF",
        "width": OUTPUT_WIDTH,
        "height": OUTPUT_HEIGHT,
        "android_sha256": hashlib.sha256(android_rgb).hexdigest(),
        "mpv_sha256": hashlib.sha256(mpv_rgb).hexdigest(),
        "mae_u8": sum(errors) / len(errors),
        "max_channel_error_u8": max(errors, default=0),
        "mismatch_pixels": mismatch_pixels,
        "exact": not any(errors),
    }


def prepare(output_dir: Path, shader_path: Path) -> dict[str, object]:
    verify_shader(shader_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    input_path = output_dir / "anime4k-reference-input-32x24.ppm"
    write_ppm(input_path, INPUT_WIDTH, INPUT_HEIGHT, fixed_opaque_rgb())
    manifest = {
        "input": input_path.name,
        "input_sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
        "input_dimensions": [INPUT_WIDTH, INPUT_HEIGHT],
        "expected_output_dimensions": [OUTPUT_WIDTH, OUTPUT_HEIGHT],
        "alpha_contract": "opaque; PPM RGB is equivalent to alpha=255",
        "shader_sha256": SHADER_SHA256,
        "gate": "Run both pinned mpv and Android adapters, then compare their P6 PPM outputs.",
    }
    (output_dir / "anime4k-reference-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--output-dir", type=Path, required=True)
    prepare_parser.add_argument("--shader", type=Path, required=True)
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--android", type=Path, required=True)
    compare_parser.add_argument("--mpv", type=Path, required=True)
    args = parser.parse_args()
    result = (prepare(args.output_dir, args.shader) if args.command == "prepare"
              else compare_outputs(args.android, args.mpv))
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
