from __future__ import annotations

import argparse
import csv
import json
import platform
import statistics
import subprocess
from pathlib import Path
from typing import Any

import imageio_ffmpeg
import numpy as np
import onnxruntime as ort
from PIL import Image, __version__ as pillow_version

from benchmark_runtime import ROOT, OrtModelRunner, file_sha256, load_model_spec, percentile, quality_metrics
from fetch_open_assets import parse_checksum_index


HERE = Path(__file__).resolve().parent
DEFAULT_ASSETS = HERE / "open-assets.json"
DEFAULT_DEGRADATIONS = HERE / "degradation-profiles.json"
DEFAULT_REGISTRY = HERE / "model-registry.json"
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "open-video-2x"


def find_sequence(manifest: dict[str, Any], asset_id: str) -> dict[str, Any]:
    matches = [item for item in manifest["assets"] if item["id"] == asset_id]
    if len(matches) != 1 or matches[0]["kind"] != "image-sequence":
        raise ValueError(f"expected one image-sequence asset named {asset_id}")
    return matches[0]


def encode_raw(
    ffmpeg: str,
    frames: list[np.ndarray],
    source_width: int,
    source_height: int,
    fps: int,
    output: Path,
    codec: str,
    crf: int,
    pixel_format: str,
    preset: str,
    scale: tuple[int, int] | None = None,
) -> None:
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{source_width}x{source_height}", "-r", str(fps), "-i", "pipe:0",
    ]
    if scale is not None:
        command += ["-vf", f"scale={scale[0]}:{scale[1]}:flags=lanczos"]
    command += ["-an", "-c:v", codec, "-preset", preset, "-crf", str(crf), "-bf", "0", "-pix_fmt", pixel_format, "-movflags", "+faststart", str(output)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame in frames:
            process.stdin.write(np.ascontiguousarray(frame, dtype=np.uint8).tobytes())
        process.stdin.close()
        stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
        return_code = process.wait()
    finally:
        if process.stdin and not process.stdin.closed:
            process.stdin.close()
    if return_code != 0:
        raise RuntimeError(f"ffmpeg encode failed ({return_code}): {stderr}")


def decode_raw(ffmpeg: str, video: Path, width: int, height: int) -> list[np.ndarray]:
    result = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(video), "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"],
        check=True, capture_output=True,
    )
    frame_bytes = width * height * 3
    if len(result.stdout) % frame_bytes:
        raise ValueError("decoded raw video length is not an integral frame count")
    return [np.frombuffer(result.stdout, dtype=np.uint8, count=frame_bytes, offset=index).reshape(height, width, 3).copy() for index in range(0, len(result.stdout), frame_bytes)]


def resize(frame: np.ndarray, width: int, height: int, resampling: Image.Resampling) -> np.ndarray:
    return np.asarray(Image.fromarray(frame).resize((width, height), resampling), dtype=np.uint8)


def temporal_delta_mae(reference: list[np.ndarray], actual: list[np.ndarray]) -> float:
    errors = []
    for index in range(1, len(reference)):
        ref_delta = reference[index].astype(np.float32) - reference[index - 1].astype(np.float32)
        actual_delta = actual[index].astype(np.float32) - actual[index - 1].astype(np.float32)
        errors.append(float(np.abs(ref_delta - actual_delta).mean() / 255.0))
    return statistics.fmean(errors) if errors else 0.0


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a rights-clear short-video degradation and 2x ONNX benchmark")
    parser.add_argument("--asset", default="bbb-1080-sequence-01000-01014")
    parser.add_argument("--model", default="quicksrnet-small-2x-640x360")
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.warmups < 0:
        raise ValueError("warmups must be >= 0")

    manifest = json.loads(DEFAULT_ASSETS.read_text(encoding="utf-8"))
    asset = find_sequence(manifest, args.asset)
    cache_root = ROOT / manifest["cache_root"]
    sequence_dir = cache_root / asset["directory"]
    model = load_model_spec(DEFAULT_REGISTRY, args.model)
    runner = OrtModelRunner(model)
    profile = json.loads(DEFAULT_DEGRADATIONS.read_text(encoding="utf-8"))["video_profile"]
    output_dir = args.output.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    reference_frames: list[np.ndarray] = []
    source_frames: list[dict[str, Any]] = []
    checksum_spec = asset["checksum_index"]
    checksum_path = cache_root / checksum_spec["file_name"]
    if (
        not checksum_path.is_file()
        or checksum_path.stat().st_size != checksum_spec["bytes"]
        or file_sha256(checksum_path) != checksum_spec["sha256"]
    ):
        raise ValueError("sequence checksum index is missing or unverified; run fetch_open_assets.py first")
    checksums = parse_checksum_index(checksum_path)
    for offset in range(asset["frame_count"]):
        frame_number = asset["start_frame"] + offset * asset["frame_step"]
        file_name = asset["file_template"].format(frame=frame_number)
        path = sequence_dir / file_name
        if not path.is_file():
            raise FileNotFoundError(f"verified sequence is incomplete; run fetch_open_assets.py: {path.name}")
        expected_sha256 = checksums.get(file_name)
        observed_sha256 = file_sha256(path)
        if expected_sha256 is None or observed_sha256 != expected_sha256:
            raise ValueError(f"sequence frame is unverified: {file_name}")
        with Image.open(path) as image:
            if image.size != (asset["width"], asset["height"]):
                raise ValueError(f"asset dimension mismatch for {file_name}: observed {image.size}")
            reference_frames.append(np.asarray(image.convert("RGB").resize((model.output_shape[3], model.output_shape[2]), Image.Resampling.LANCZOS), dtype=np.uint8))
        source_frames.append({"file_name": file_name, "sha256": observed_sha256})

    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    fps = int(asset["fps"])
    lr_video = output_dir / "degraded-360p-h264.mp4"
    encode_raw(
        ffmpeg, reference_frames, model.output_shape[3], model.output_shape[2], fps, lr_video,
        profile["codec"], profile["crf"], profile["pixel_format"], profile["preset"],
        (profile["input_width"], profile["input_height"]),
    )
    low_frames = decode_raw(ffmpeg, lr_video, profile["input_width"], profile["input_height"])
    if len(low_frames) != len(reference_frames):
        raise ValueError(f"frame-count mismatch: source {len(reference_frames)}, decoded {len(low_frames)}")

    first_float = low_frames[0].astype(np.float32) / 255.0
    runner.warmup(first_float, args.warmups)
    neural_frames: list[np.ndarray] = []
    bilinear_frames: list[np.ndarray] = []
    lanczos_frames: list[np.ndarray] = []
    timings: list[float] = []
    rows: list[dict[str, Any]] = []
    for index, (reference_u8, low_u8) in enumerate(zip(reference_frames, low_frames)):
        neural, elapsed = runner.infer(low_u8.astype(np.float32) / 255.0)
        timings.append(elapsed)
        neural_u8 = np.rint(neural * 255.0).astype(np.uint8)
        bilinear_u8 = resize(low_u8, model.output_shape[3], model.output_shape[2], Image.Resampling.BILINEAR)
        lanczos_u8 = resize(low_u8, model.output_shape[3], model.output_shape[2], Image.Resampling.LANCZOS)
        neural_frames.append(neural_u8)
        bilinear_frames.append(bilinear_u8)
        lanczos_frames.append(lanczos_u8)
        reference = reference_u8.astype(np.float32) / 255.0
        for method, frame in (("quicksr", neural_u8), ("bilinear", bilinear_u8), ("lanczos", lanczos_u8)):
            measured = quality_metrics(reference, frame.astype(np.float32) / 255.0)
            rows.append({"frame": index, "method": method, "inference_ms": elapsed if method == "quicksr" else None, **measured})

    encode_raw(ffmpeg, reference_frames, model.output_shape[3], model.output_shape[2], fps, output_dir / "reference-720p.mp4", "libx264", 18, "yuv420p", "medium")
    encode_raw(ffmpeg, neural_frames, model.output_shape[3], model.output_shape[2], fps, output_dir / "quicksr-720p.mp4", "libx264", 18, "yuv420p", "medium")
    encode_raw(ffmpeg, lanczos_frames, model.output_shape[3], model.output_shape[2], fps, output_dir / "lanczos-720p.mp4", "libx264", 18, "yuv420p", "medium")

    summary: dict[str, Any] = {}
    for method, frames in (("quicksr", neural_frames), ("bilinear", bilinear_frames), ("lanczos", lanczos_frames)):
        method_rows = [row for row in rows if row["method"] == method]
        summary[method] = {
            "mean_psnr_db": statistics.fmean(row["psnr_db"] for row in method_rows),
            "mean_global_ssim": statistics.fmean(row["global_ssim"] for row in method_rows),
            "mean_edge_mae": statistics.fmean(row["edge_mae"] for row in method_rows),
            "temporal_delta_mae": temporal_delta_mae(reference_frames, frames),
        }
    report = {
        "schema_version": 1,
        "status": "observed-pc-open-video-baseline",
        "asset": {
            "id": asset["id"], "frame_count": len(reference_frames), "fps": fps,
            "checksum_index_sha256": checksum_spec["sha256"], "source_frames": source_frames,
            "license": asset["license"], "repository_policy": asset["repository_policy"],
        },
        "degradation": {**profile, "encoded_bytes": lr_video.stat().st_size, "encoded_sha256": file_sha256(lr_video)},
        "model": {"id": model.id, "scale": model.scale, "sha256": model.sha256, "provider": "CPUExecutionProvider"},
        "inference": {
            "frames": len(timings), "mean_ms": statistics.fmean(timings), "p50_ms": statistics.median(timings),
            "p95_nearest_rank_ms": percentile(timings, 0.95), "throughput_fps": 1000.0 / statistics.fmean(timings), "samples_ms": timings,
        },
        "quality": summary,
        "runtime": {"platform": platform.platform(), "python": platform.python_version(), "numpy": np.__version__, "onnxruntime": ort.__version__, "pillow": pillow_version, "ffmpeg": imageio_ffmpeg.get_ffmpeg_version()},
        "limits": [
            "The one-second clip validates a deterministic video pipeline, not sustained playback or thermal behavior.",
            "Temporal delta MAE is a no-motion-compensation diagnostic, not a perceptual temporal metric.",
            "Big Buck Bunny is open 3D animation, not a representative Japanese anime corpus.",
            "CPUExecutionProvider throughput does not predict Android QNN HTP throughput."
        ],
    }
    (output_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "frame-metrics.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["frame", "method", "inference_ms", "psnr_db", "global_ssim", "edge_mae"])
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
