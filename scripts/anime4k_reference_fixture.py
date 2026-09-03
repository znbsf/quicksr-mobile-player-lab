#!/usr/bin/env python3
"""Generate and compare the fixed opaque Anime4K same-frame reference fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics


INPUT_WIDTH = 32
INPUT_HEIGHT = 24
OUTPUT_WIDTH = INPUT_WIDTH * 2
OUTPUT_HEIGHT = INPUT_HEIGHT * 2
ROOT = Path(__file__).resolve().parents[1]
MANIFEST_SCHEMA_VERSION = 2
MANIFEST_STATUS = "prepared-anime4k-fixed-reference-fixture"
FIXTURE_ID = "anime4k-fixed-opaque-32x24-v1"
INPUT_FILE_NAME = "anime4k-reference-input-32x24.ppm"
SHADER_ARTIFACT_ID = "anime4k-v4.0.1-upscale-cnn-x2-s"
SHADER_SOURCE_COMMIT = "4029bf701ecaa15f163cdc49cffe5501c1acf410"
SHADER_BYTES = 18_638
SHADER_SHA256 = "4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4"
VENDORED_SHADER_PATH = ROOT / "app/src/main/assets/anime4k/Anime4K_Upscale_CNN_x2_S.txt"


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
    payload = shader_path.read_bytes()
    observed = hashlib.sha256(payload).hexdigest()
    if len(payload) != SHADER_BYTES or observed != SHADER_SHA256:
        raise ValueError(
            f"pinned shader mismatch: bytes={len(payload)}, sha256={observed}")


def quality_metrics(reference: bytes, actual: bytes, width: int, height: int) -> dict[str, object]:
    if len(reference) != len(actual) or len(reference) != width * height * 3:
        raise ValueError("metric payload dimensions do not match")
    normalized_error = [
        (left - right) / 255.0 for left, right in zip(reference, actual)
    ]
    mse = statistics.fmean(error * error for error in normalized_error)
    channel_ssim = []
    for channel in range(3):
        left = [value / 255.0 for value in reference[channel::3]]
        right = [value / 255.0 for value in actual[channel::3]]
        left_mean = statistics.fmean(left)
        right_mean = statistics.fmean(right)
        left_var = statistics.fmean((value - left_mean) ** 2 for value in left)
        right_var = statistics.fmean((value - right_mean) ** 2 for value in right)
        covariance = statistics.fmean(
            (left_value - left_mean) * (right_value - right_mean)
            for left_value, right_value in zip(left, right)
        )
        c1, c2 = 0.01**2, 0.03**2
        channel_ssim.append(
            ((2 * left_mean * right_mean + c1) * (2 * covariance + c2))
            / ((left_mean**2 + right_mean**2 + c1) * (left_var + right_var + c2))
        )

    def edge_axis(horizontal: bool) -> float:
        total = 0.0
        count = 0
        y_stop = height if horizontal else height - 1
        x_stop = width - 1 if horizontal else width
        for y in range(y_stop):
            for x in range(x_stop):
                next_x = x + 1 if horizontal else x
                next_y = y if horizontal else y + 1
                offset = (y * width + x) * 3
                next_offset = (next_y * width + next_x) * 3
                for channel in range(3):
                    reference_edge = reference[next_offset + channel] - reference[offset + channel]
                    actual_edge = actual[next_offset + channel] - actual[offset + channel]
                    total += abs(reference_edge - actual_edge) / 255.0
                    count += 1
        return total / count

    infinite = mse == 0.0
    return {
        "psnr_db": None if infinite else 10.0 * math.log10(1.0 / mse),
        "psnr_is_infinite": infinite,
        "global_ssim": statistics.fmean(channel_ssim),
        "edge_mae": (edge_axis(True) + edge_axis(False)) / 2.0,
    }


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_manifest(input_path: Path) -> dict[str, object]:
    width, height, rgb = read_ppm(input_path)
    return {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "status": MANIFEST_STATUS,
        "fixture_id": FIXTURE_ID,
        "input": {
            "path": input_path.name,
            "file_sha256": file_sha256(input_path),
            "rgb_sha256": hashlib.sha256(rgb).hexdigest(),
            "dimensions": [width, height],
        },
        "expected_output_dimensions": [OUTPUT_WIDTH, OUTPUT_HEIGHT],
        "alpha_contract": "opaque; PPM RGB is equivalent to alpha=255",
        "anime4k_source": {
            "artifact_id": SHADER_ARTIFACT_ID,
            "source_commit": SHADER_SOURCE_COMMIT,
            "bytes": SHADER_BYTES,
            "sha256": SHADER_SHA256,
            "vendored_path": VENDORED_SHADER_PATH.relative_to(ROOT).as_posix(),
        },
        "comparison_semantics": {
            "status": "declared-pixel-comparison-only",
            "runtime_equivalence_requires": "replayable capture trace, receipt and execution identity",
        },
    }


def verify_manifest(manifest_path: Path) -> tuple[dict[str, object], Path]:
    manifest_path = manifest_path.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    input_value = manifest.get("input")
    if not isinstance(input_value, dict):
        raise ValueError("fixture manifest input is invalid")
    relative = input_value.get("path")
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError("fixture manifest input path must be relative")
    input_path = (manifest_path.parent / relative).resolve()
    try:
        input_path.relative_to(manifest_path.parent)
    except ValueError as failure:
        raise ValueError("fixture manifest input path escapes its directory") from failure
    if not input_path.is_file():
        raise FileNotFoundError(input_path)
    if input_path.name != INPUT_FILE_NAME:
        raise ValueError("fixture manifest input file name is not canonical")
    verify_shader(VENDORED_SHADER_PATH)
    width, height, rgb = read_ppm(input_path)
    if (width, height) != (INPUT_WIDTH, INPUT_HEIGHT) or rgb != fixed_opaque_rgb():
        raise ValueError("fixture manifest input is not the canonical prepared frame")
    expected = canonical_manifest(input_path)
    if manifest != expected:
        raise ValueError("fixture manifest does not match the canonical generator and source pin")
    return manifest, input_path


def compare_outputs(
    android_path: Path,
    mpv_path: Path,
    manifest_path: Path,
    android_input_sha256: str,
    mpv_input_sha256: str,
    android_output_sha256: str,
    mpv_output_sha256: str,
) -> dict[str, object]:
    manifest, input_path = verify_manifest(manifest_path)
    fixture_input_sha256 = file_sha256(input_path)
    if android_input_sha256 != fixture_input_sha256 or mpv_input_sha256 != fixture_input_sha256:
        raise ValueError("declared Android/mpv input SHA-256 does not match the prepared fixture")
    observed_android_file_sha256 = file_sha256(android_path)
    observed_mpv_file_sha256 = file_sha256(mpv_path)
    if android_output_sha256 != observed_android_file_sha256:
        raise ValueError("declared Android output SHA-256 does not match the supplied file")
    if mpv_output_sha256 != observed_mpv_file_sha256:
        raise ValueError("declared mpv output SHA-256 does not match the supplied file")
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
        "status": "DECLARED_PIXEL_MATCH_ONLY" if not any(errors) else "DECLARED_PIXEL_DIFF",
        "declared_pixel_comparison": "MATCH" if not any(errors) else "DIFF",
        "runtime_equivalence": "NOT_ESTABLISHED_NO_REPLAYABLE_CAPTURE_RECEIPT",
        "manifest_sha256": file_sha256(manifest_path),
        "fixture_id": manifest["fixture_id"],
        "fixture_input_sha256": fixture_input_sha256,
        "width": OUTPUT_WIDTH,
        "height": OUTPUT_HEIGHT,
        "android_declared_input_sha256": android_input_sha256,
        "mpv_declared_input_sha256": mpv_input_sha256,
        "android_output_file_sha256": observed_android_file_sha256,
        "mpv_output_file_sha256": observed_mpv_file_sha256,
        "android_rgb_sha256": hashlib.sha256(android_rgb).hexdigest(),
        "mpv_rgb_sha256": hashlib.sha256(mpv_rgb).hexdigest(),
        "mae_u8": sum(errors) / len(errors),
        "max_channel_error_u8": max(errors, default=0),
        "mismatch_pixels": mismatch_pixels,
        "exact": not any(errors),
        **quality_metrics(mpv_rgb, android_rgb, OUTPUT_WIDTH, OUTPUT_HEIGHT),
    }


def prepare(output_dir: Path, shader_path: Path) -> dict[str, object]:
    verify_shader(shader_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    input_path = output_dir / INPUT_FILE_NAME
    write_ppm(input_path, INPUT_WIDTH, INPUT_HEIGHT, fixed_opaque_rgb())
    manifest = canonical_manifest(input_path)
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
    compare_parser.add_argument("--manifest", type=Path, required=True)
    compare_parser.add_argument("--android-input-sha256", required=True)
    compare_parser.add_argument("--mpv-input-sha256", required=True)
    compare_parser.add_argument("--android-output-sha256", required=True)
    compare_parser.add_argument("--mpv-output-sha256", required=True)
    args = parser.parse_args()
    result = (
        prepare(args.output_dir, args.shader)
        if args.command == "prepare"
        else compare_outputs(
            args.android,
            args.mpv,
            args.manifest,
            args.android_input_sha256,
            args.mpv_input_sha256,
            args.android_output_sha256,
            args.mpv_output_sha256,
        )
    )
    print(json.dumps(result, indent=2))
    return (
        0
        if args.command == "prepare" or result["declared_pixel_comparison"] == "MATCH"
        else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
