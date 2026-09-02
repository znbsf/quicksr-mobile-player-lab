"""Materialize the pinned, rights-clear mobile video subset only on local storage.

The committed contract is deliberately source-only.  This tool never downloads
media and never overwrites output.  Its default mode verifies the cached source
bytes and prints the derived frame plans; ``--materialize`` is required to write
ignored local MP4s and their local provenance manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "contracts" / "mobile-rights-clear-subset.json"
LOCAL_ARTIFACTS_ROOT = ROOT / "local-artifacts"
DEFAULT_OUTPUT_ROOT = LOCAL_ARTIFACTS_ROOT / "mobile-subset"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
CLIP_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")
LAYOUT_ASPECTS = {"16:9": 16 / 9, "1:1": 1.0}
MINIMUM_ANDROID_MATRIX_FRAMES = 135


class ContractError(ValueError):
    """The source-only subset contract is malformed or no longer matches inputs."""


@dataclass(frozen=True)
class SourceFrame:
    asset_id: str
    source_frame_number: int | None
    path: Path
    relative_path: str
    sha256: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise
    except json.JSONDecodeError as error:
        raise ContractError(f"invalid JSON in {path}: {error}") from error
    if not isinstance(value, dict):
        raise ContractError(f"JSON root must be an object: {path}")
    return value


def require_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise ContractError(f"{label} must be a lowercase SHA-256 hex digest")
    return value


def ensure_inside(path: Path, root: Path, label: str) -> Path:
    resolved_path = path.resolve()
    resolved_root = root.resolve()
    try:
        resolved_path.relative_to(resolved_root)
    except ValueError as error:
        raise ContractError(f"{label} must stay inside {resolved_root}: {resolved_path}") from error
    return resolved_path


def repository_file(relative_path: Any, label: str) -> Path:
    if not isinstance(relative_path, str) or not relative_path:
        raise ContractError(f"{label} must be a non-empty repository-relative path")
    return ensure_inside(ROOT / relative_path, ROOT, label)


def parse_checksum_index(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        parts = raw_line.split(maxsplit=1)
        if len(parts) != 2:
            continue
        digest = parts[0].lower()
        if SHA256_RE.fullmatch(digest):
            result[parts[1].lstrip("* ")] = digest
    return result


def verify_file(path: Path, expected_bytes: int | None, expected_sha256: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"required pinned source is missing: {path}")
    if expected_bytes is not None and path.stat().st_size != expected_bytes:
        raise ContractError(
            f"byte mismatch for {path.name}: expected {expected_bytes}, observed {path.stat().st_size}"
        )
    observed = sha256_file(path)
    if observed != expected_sha256:
        raise ContractError(
            f"SHA-256 mismatch for {path.name}: expected {expected_sha256}, observed {observed}"
        )


def assets_by_id(assets_manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    assets = assets_manifest.get("assets")
    if not isinstance(assets, list):
        raise ContractError("open-assets manifest must contain an assets array")
    result: dict[str, dict[str, Any]] = {}
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("id"), str):
            raise ContractError("open-assets manifest contains an invalid asset")
        if asset["id"] in result:
            raise ContractError(f"duplicate source asset id: {asset['id']}")
        result[asset["id"]] = asset
    return result


def content_rect(width: int, height: int, layout: str) -> dict[str, int]:
    if layout not in LAYOUT_ASPECTS:
        raise ContractError(f"unsupported content layout: {layout}")
    target_aspect = LAYOUT_ASPECTS[layout]
    container_aspect = width / height
    if container_aspect >= target_aspect:
        rect_height = height
        rect_width = round(height * target_aspect)
        rect_x = (width - rect_width) // 2
        return {"x": rect_x, "y": 0, "width": rect_width, "height": rect_height}
    rect_width = width
    rect_height = round(width / target_aspect)
    rect_y = (height - rect_height) // 2
    return {"x": 0, "y": rect_y, "width": rect_width, "height": rect_height}


def validate_clip(
        clip: dict[str, Any],
        asset_map: dict[str, dict[str, Any]],
        degradations: dict[str, dict[str, Any]],
) -> None:
    clip_id = clip.get("id")
    if not isinstance(clip_id, str) or not CLIP_ID_RE.fullmatch(clip_id):
        raise ContractError(f"invalid clip id: {clip_id!r}")
    if clip.get("tier") not in {"quick-functional", "core"}:
        raise ContractError(f"{clip_id} has an unsupported tier")
    source = clip.get("source")
    if not isinstance(source, dict):
        raise ContractError(f"{clip_id} source must be an object")
    asset_id = source.get("asset_id")
    if asset_id not in asset_map:
        raise ContractError(f"{clip_id} references an unknown source asset: {asset_id!r}")
    asset = asset_map[asset_id]
    selection = source.get("selection")
    if not isinstance(selection, dict):
        raise ContractError(f"{clip_id} source selection must be an object")
    if asset.get("kind") == "image":
        if selection != {"kind": "still-image"}:
            raise ContractError(f"{clip_id} must use a still-image selection for {asset_id}")
    elif asset.get("kind") == "image-sequence":
        if selection.get("kind") != "sequence" or selection.get("loop") is not True:
            raise ContractError(f"{clip_id} must use an explicitly looping sequence selection")
        if source.get("native_fps") != asset.get("fps"):
            raise ContractError(f"{clip_id} must record the pinned sequence native FPS")
        first_frame = selection.get("first_frame")
        frame_count = selection.get("frame_count")
        if not isinstance(first_frame, int) or not isinstance(frame_count, int) or frame_count <= 0:
            raise ContractError(f"{clip_id} sequence selection must specify positive integer frames")
        available_start = asset.get("start_frame")
        available_count = asset.get("frame_count")
        if not isinstance(available_start, int) or not isinstance(available_count, int):
            raise ContractError(f"{asset_id} has no valid sequence range")
        if first_frame < available_start or first_frame + frame_count > available_start + available_count:
            raise ContractError(f"{clip_id} selects frames outside the pinned source sequence")
    else:
        raise ContractError(f"{clip_id} references unsupported asset kind: {asset.get('kind')!r}")

    output = clip.get("output")
    if not isinstance(output, dict):
        raise ContractError(f"{clip_id} output must be an object")
    width, height = output.get("width"), output.get("height")
    fps, frame_count = output.get("fps"), output.get("frame_count")
    layout = output.get("content_layout")
    if not all(isinstance(value, int) and value > 0 for value in (width, height, fps, frame_count)):
        raise ContractError(f"{clip_id} output dimensions, fps, and frame count must be positive integers")
    if fps not in {24, 30}:
        raise ContractError(f"{clip_id} FPS must be 24 or 30")
    if frame_count < MINIMUM_ANDROID_MATRIX_FRAMES:
        raise ContractError(
            f"{clip_id} has only {frame_count} encoded frames; at least "
            f"{MINIMUM_ANDROID_MATRIX_FRAMES} are required for the Android matrix warm-up and measured-frame gate"
        )
    if height not in {360, 480, 720}:
        raise ContractError(f"{clip_id} height must be one of 360, 480, or 720")
    rect = content_rect(width, height, layout)
    if rect["width"] > width or rect["height"] > height:
        raise ContractError(f"{clip_id} content rectangle exceeds its container")
    source_aspect = asset.get("width", 0) / asset.get("height", 1)
    if abs(source_aspect - LAYOUT_ASPECTS[layout]) > 0.003:
        raise ContractError(f"{clip_id} source aspect does not match declared content layout")
    if asset.get("kind") == "image" and layout not in set(asset.get("benchmark_layouts", [])):
        raise ContractError(f"{clip_id} uses a layout not allowed by {asset_id}")
    if clip.get("degradation_id") not in degradations:
        raise ContractError(f"{clip_id} references an unknown degradation profile")
    selected_frames = clip.get("comparison_frame_indices")
    if not isinstance(selected_frames, list) or not selected_frames:
        raise ContractError(f"{clip_id} needs at least one comparison frame index")
    if any(not isinstance(index, int) or index < 0 or index >= frame_count for index in selected_frames):
        raise ContractError(f"{clip_id} comparison frame index is outside its output")
    if len(set(selected_frames)) != len(selected_frames):
        raise ContractError(f"{clip_id} comparison frame indices must be unique")


def validate_contract_structure(contract: dict[str, Any], assets_manifest: dict[str, Any]) -> None:
    if contract.get("schema_version") != 1:
        raise ContractError("mobile subset contract schema_version must be 1")
    source_manifest = contract.get("source_assets_manifest")
    if not isinstance(source_manifest, dict):
        raise ContractError("mobile subset contract needs source_assets_manifest")
    if source_manifest.get("path") != "pc-benchmark/open-assets.json":
        raise ContractError("mobile subset contract must reference pc-benchmark/open-assets.json")
    require_sha256(source_manifest.get("sha256"), "source_assets_manifest.sha256")
    policy = contract.get("repository_policy")
    if not isinstance(policy, dict):
        raise ContractError("mobile subset contract needs repository_policy")
    required_policy = {
        "source_only": True,
        "local_artifact_root": "local-artifacts/mobile-subset",
        "raw_media_must_remain_git_ignored": True,
        "network_downloads_permitted": False,
        "overwrite_permitted": False,
    }
    for name, expected in required_policy.items():
        if policy.get(name) != expected:
            raise ContractError(f"repository_policy.{name} must be {expected!r}")
    content_policy = contract.get("content_policy")
    if not isinstance(content_policy, dict) or content_policy.get("layout_policy") != "fit-pad-no-stretch":
        raise ContractError("content policy must require fit-pad-no-stretch")
    if content_policy.get("commercial_anime_permitted") is not False:
        raise ContractError("commercial anime must remain prohibited")
    cadence_policy = contract.get("cadence_policy")
    if not isinstance(cadence_policy, dict) or cadence_policy.get("bbb_sequence_native_fps") != 15:
        raise ContractError("cadence policy must record the BBB 15fps source rate")
    if cadence_policy.get("target_fps_conversion") != "repeat-hold-previous-source-frame":
        raise ContractError("cadence policy must define the deterministic target FPS conversion")
    if cadence_policy.get("target_24_or_30fps_is_not_native_24_or_30fps_motion") is not True:
        raise ContractError("cadence policy must reject a native-motion interpretation")
    degradations = contract.get("degradation_profiles")
    if not isinstance(degradations, dict) or set(degradations) != {"clean-lanczos", "blur-jpeg-q35"}:
        raise ContractError("contract must define clean-lanczos and blur-jpeg-q35")
    if degradations["clean-lanczos"].get("jpeg_quality") is not None:
        raise ContractError("clean-lanczos must not JPEG encode the source")
    if degradations["blur-jpeg-q35"].get("jpeg_quality") != 35:
        raise ContractError("blur-jpeg-q35 must use JPEG quality 35")

    clips = contract.get("clips")
    if not isinstance(clips, list):
        raise ContractError("mobile subset contract needs a clips array")
    tiers = [clip.get("tier") for clip in clips if isinstance(clip, dict)]
    if tiers.count("quick-functional") != 3 or tiers.count("core") != 8 or len(clips) != 11:
        raise ContractError("contract must define exactly 3 quick-functional and 8 core clips")
    clip_ids = [clip.get("id") for clip in clips if isinstance(clip, dict)]
    if len(clip_ids) != len(set(clip_ids)):
        raise ContractError("clip ids must be unique")
    asset_map = assets_by_id(assets_manifest)
    for clip in clips:
        if not isinstance(clip, dict):
            raise ContractError("clips must be objects")
        validate_clip(clip, asset_map, degradations)

    outputs = [clip["output"] for clip in clips]
    if {output["fps"] for output in outputs} != {24, 30}:
        raise ContractError("subset must cover both 24fps and 30fps")
    if {output["height"] for output in outputs} != {360, 480, 720}:
        raise ContractError("subset must cover 360p, 480p, and 720p")
    if {output["content_layout"] for output in outputs} != {"16:9", "1:1"}:
        raise ContractError("subset must cover 16:9 and square content")
    if {clip["degradation_id"] for clip in clips} != {"clean-lanczos", "blur-jpeg-q35"}:
        raise ContractError("subset must cover clean and blur/JPEG degradations")


def load_contract_and_assets(contract_path: Path) -> tuple[dict[str, Any], dict[str, Any], Path]:
    contract_path = ensure_inside(contract_path, ROOT, "contract path")
    contract = read_json(contract_path)
    source_manifest = contract.get("source_assets_manifest")
    if not isinstance(source_manifest, dict):
        raise ContractError("mobile subset contract needs source_assets_manifest")
    source_manifest_path = repository_file(source_manifest.get("path"), "source_assets_manifest.path")
    expected_manifest_sha256 = require_sha256(source_manifest.get("sha256"), "source_assets_manifest.sha256")
    observed_manifest_sha256 = sha256_file(source_manifest_path)
    if observed_manifest_sha256 != expected_manifest_sha256:
        raise ContractError(
            "open-assets manifest hash mismatch: "
            f"expected {expected_manifest_sha256}, observed {observed_manifest_sha256}"
        )
    assets_manifest = read_json(source_manifest_path)
    validate_contract_structure(contract, assets_manifest)
    return contract, assets_manifest, source_manifest_path


def verify_source_for_clip(
        clip: dict[str, Any],
        asset_map: dict[str, dict[str, Any]],
        cache_root: Path,
) -> list[SourceFrame]:
    source = clip["source"]
    asset_id = source["asset_id"]
    asset = asset_map[asset_id]
    selection = source["selection"]
    if asset["kind"] == "image":
        path = cache_root / asset["file_name"]
        verify_file(path, asset["bytes"], require_sha256(asset["sha256"], f"{asset_id}.sha256"))
        return [
            SourceFrame(
                asset_id=asset_id,
                source_frame_number=None,
                path=path,
                relative_path=path.relative_to(ROOT).as_posix(),
                sha256=asset["sha256"],
            )
        ]

    checksum_spec = asset["checksum_index"]
    checksum_path = cache_root / checksum_spec["file_name"]
    verify_file(
        checksum_path,
        checksum_spec["bytes"],
        require_sha256(checksum_spec["sha256"], f"{asset_id}.checksum_index.sha256"),
    )
    checksums = parse_checksum_index(checksum_path)
    result: list[SourceFrame] = []
    first_frame = selection["first_frame"]
    for offset in range(selection["frame_count"]):
        frame_number = first_frame + offset
        file_name = asset["file_template"].format(frame=frame_number)
        expected_sha256 = checksums.get(file_name)
        if expected_sha256 is None:
            raise ContractError(f"checksum index has no hash for {file_name}")
        path = cache_root / asset["directory"] / file_name
        verify_file(path, None, expected_sha256)
        result.append(
            SourceFrame(
                asset_id=asset_id,
                source_frame_number=frame_number,
                path=path,
                relative_path=path.relative_to(ROOT).as_posix(),
                sha256=expected_sha256,
            )
        )
    return result


def verify_all_sources(contract: dict[str, Any], assets_manifest: dict[str, Any]) -> dict[str, list[SourceFrame]]:
    cache_root = repository_file(assets_manifest.get("cache_root"), "open-assets.cache_root")
    asset_map = assets_by_id(assets_manifest)
    result: dict[str, list[SourceFrame]] = {}
    for clip in contract["clips"]:
        clip_id = clip["id"]
        result[clip_id] = verify_source_for_clip(clip, asset_map, cache_root)
    return result


def frame_plan(clip: dict[str, Any], source_frames: list[SourceFrame]) -> list[dict[str, Any]]:
    output = clip["output"]
    frame_count = output["frame_count"]
    fps = output["fps"]
    if len(source_frames) == 1:
        source_indices: Iterable[int] = (0 for _ in range(frame_count))
    else:
        native_fps = clip["source"]["native_fps"]
        source_indices = ((frame_index * native_fps // fps) % len(source_frames) for frame_index in range(frame_count))
    result: list[dict[str, Any]] = []
    for frame_index, source_index in enumerate(source_indices):
        source = source_frames[source_index]
        result.append(
            {
                "frame_index": frame_index,
                "pts_us": frame_index * 1_000_000 // fps,
                "source_index": source_index,
                "source_asset_id": source.asset_id,
                "source_frame_number": source.source_frame_number,
                "source_file": source.relative_path,
                "source_sha256": source.sha256,
            }
        )
    return result


def resolve_ffmpeg(explicit_path: str | None) -> Path:
    if explicit_path:
        candidate = Path(explicit_path).expanduser().resolve()
    else:
        found = shutil.which("ffmpeg")
        if found is None:
            raise ContractError("ffmpeg was not found; pass --ffmpeg with an existing local executable")
        candidate = Path(found).resolve()
    if not candidate.is_file():
        raise ContractError(f"ffmpeg executable does not exist: {candidate}")
    return candidate


def ffmpeg_identity(ffmpeg: Path) -> dict[str, Any]:
    completed = subprocess.run(
        [str(ffmpeg), "-hide_banner", "-version"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        raise ContractError(f"ffmpeg -version failed ({completed.returncode}): {completed.stderr.strip()}")
    first_line = (completed.stdout or completed.stderr).splitlines()
    return {
        "path": str(ffmpeg),
        "sha256": sha256_file(ffmpeg),
        "version_first_line": first_line[0] if first_line else "",
    }


def render_rgb_frame(
        source_frame: SourceFrame,
        output: dict[str, Any],
        degradation: dict[str, Any],
        background: tuple[int, int, int],
) -> bytes:
    try:
        from PIL import Image, ImageFilter
    except ImportError as error:
        raise ContractError("Pillow is required only when --materialize is used") from error
    with Image.open(source_frame.path) as opened:
        image = opened.convert("RGB")
    if degradation["pre_blur_radius"] > 0:
        image = image.filter(ImageFilter.GaussianBlur(radius=degradation["pre_blur_radius"]))
    rect = content_rect(output["width"], output["height"], output["content_layout"])
    scaled = image.resize((rect["width"], rect["height"]), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (output["width"], output["height"]), background)
    canvas.paste(scaled, (rect["x"], rect["y"]))
    if degradation["jpeg_quality"] is not None:
        payload = io.BytesIO()
        canvas.save(
            payload,
            format="JPEG",
            quality=degradation["jpeg_quality"],
            subsampling=degradation["jpeg_subsampling"],
            optimize=False,
            progressive=False,
        )
        with Image.open(io.BytesIO(payload.getvalue())) as decoded:
            canvas = decoded.convert("RGB")
    return canvas.tobytes()


def encode_clip(
        ffmpeg: Path,
        clip: dict[str, Any],
        source_frames: list[SourceFrame],
        degradation: dict[str, Any],
        background: tuple[int, int, int],
        output_path: Path,
) -> dict[str, Any]:
    if output_path.exists():
        raise FileExistsError(f"refusing to overwrite existing output: {output_path}")
    staging_path = output_path.with_name(output_path.stem + ".part.mp4")
    if staging_path.exists():
        raise FileExistsError(f"refusing to overwrite existing staging output: {staging_path}")
    output = clip["output"]
    command = [
        str(ffmpeg),
        "-hide_banner", "-loglevel", "error", "-nostdin", "-n",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-video_size", f"{output['width']}x{output['height']}",
        "-framerate", str(output["fps"]), "-i", "pipe:0",
        "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "28",
        "-pix_fmt", "yuv420p", "-threads", "1",
        "-fflags", "+bitexact", "-flags:v", "+bitexact",
        "-map_metadata", "-1", "-metadata", "creation_time=1970-01-01T00:00:00Z",
        str(staging_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        assert process.stdin is not None
        for entry in frame_plan(clip, source_frames):
            source = source_frames[entry["source_index"]]
            process.stdin.write(render_rgb_frame(source, output, degradation, background))
        process.stdin.close()
        stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
        return_code = process.wait()
    except BaseException:
        process.kill()
        process.wait()
        raise
    if return_code != 0:
        raise ContractError(f"ffmpeg failed for {clip['id']} ({return_code}): {stderr.strip()}")
    if not staging_path.is_file():
        raise ContractError(f"ffmpeg reported success but did not create {staging_path}")
    # A hard link has create-only semantics. Unlike os.replace, it cannot overwrite a file
    # created concurrently between the earlier existence check and final publication.
    try:
        os.link(staging_path, output_path)
    except FileExistsError as error:
        raise FileExistsError(f"refusing to overwrite concurrent output: {output_path}") from error
    staging_path.unlink()
    return {"file": output_path.name, "bytes": output_path.stat().st_size, "sha256": sha256_file(output_path), "ffmpeg_command": command}


def prepare_output_root(output_root: Path) -> Path:
    output_root = ensure_inside(output_root, LOCAL_ARTIFACTS_ROOT, "output root")
    if output_root.exists() and any(output_root.iterdir()):
        raise FileExistsError(f"refusing to write into a non-empty output root: {output_root}")
    output_root.mkdir(parents=True, exist_ok=True)
    return output_root


def materialize(
        contract_path: Path,
        contract: dict[str, Any],
        assets_manifest: dict[str, Any],
        source_manifest_path: Path,
        output_root: Path,
        ffmpeg_path: Path,
) -> Path:
    try:
        from PIL import __version__ as pillow_version
    except ImportError as error:
        raise ContractError("Pillow is required only when --materialize is used") from error
    sources = verify_all_sources(contract, assets_manifest)
    output_root = prepare_output_root(output_root)
    ffmpeg_info = ffmpeg_identity(ffmpeg_path)
    background = tuple(contract["content_policy"]["fit_pad_background_rgb"])
    degradation_profiles = contract["degradation_profiles"]
    local_clips: list[dict[str, Any]] = []
    for clip in contract["clips"]:
        plan = frame_plan(clip, sources[clip["id"]])
        artifact = encode_clip(
            ffmpeg_path,
            clip,
            sources[clip["id"]],
            degradation_profiles[clip["degradation_id"]],
            background,
            output_root / f"{clip['id']}.mp4",
        )
        selected = {entry["frame_index"]: entry for entry in plan}
        local_clips.append(
            {
                "id": clip["id"],
                "tier": clip["tier"],
                "source": {**clip["source"], "verified_frames": plan},
                "output": {**clip["output"], "content_rect": content_rect(clip["output"]["width"], clip["output"]["height"], clip["output"]["content_layout"]), **artifact},
                "degradation": {"id": clip["degradation_id"], **degradation_profiles[clip["degradation_id"]]},
                "comparison_frames": [selected[index] for index in clip["comparison_frame_indices"]],
                "ffmpeg_identity": ffmpeg_info,
                "pillow_identity": {"version": pillow_version},
            }
        )
    local_manifest_path = output_root / contract["materialization"]["local_manifest_file"]
    if local_manifest_path.exists():
        raise FileExistsError(f"refusing to overwrite local manifest: {local_manifest_path}")
    payload = {
        "schema_version": 1,
        "status": "materialized-local-only",
        "contract": {"path": contract_path.relative_to(ROOT).as_posix(), "sha256": sha256_file(contract_path)},
        "source_assets_manifest": {"path": source_manifest_path.relative_to(ROOT).as_posix(), "sha256": sha256_file(source_manifest_path)},
        "ffmpeg": ffmpeg_info,
        "python": {"version": sys.version, "implementation": sys.implementation.name},
        "pillow": {"version": pillow_version},
        "clips": local_clips,
    }
    with local_manifest_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return local_manifest_path


def check_payload(
        contract_path: Path,
        contract: dict[str, Any],
        assets_manifest: dict[str, Any],
        source_manifest_path: Path,
) -> dict[str, Any]:
    sources = verify_all_sources(contract, assets_manifest)
    return {
        "schema_version": 1,
        "status": "check-pass-no-media-written",
        "contract": {"path": contract_path.relative_to(ROOT).as_posix(), "sha256": sha256_file(contract_path)},
        "source_assets_manifest": {"path": source_manifest_path.relative_to(ROOT).as_posix(), "sha256": sha256_file(source_manifest_path)},
        "clips": [
            {
                "id": clip["id"],
                "tier": clip["tier"],
                "frame_count": len(frame_plan(clip, sources[clip["id"]])),
                "comparison_frames": [
                    frame_plan(clip, sources[clip["id"]])[index]
                    for index in clip["comparison_frame_indices"]
                ],
            }
            for clip in contract["clips"]
        ],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify or materialize the local-only, rights-clear mobile video subset."
    )
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--ffmpeg", help="Existing local ffmpeg executable; no downloader is provided.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="Verify source hashes and print frame plans without writing any media (the default).",
    )
    mode.add_argument(
        "--materialize",
        action="store_true",
        help="Write new MP4s and a local manifest. Without this flag, only verify source hashes.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    contract_path = args.contract.resolve()
    contract, assets_manifest, source_manifest_path = load_contract_and_assets(contract_path)
    if not args.materialize:
        print(json.dumps(check_payload(contract_path, contract, assets_manifest, source_manifest_path), ensure_ascii=False, indent=2))
        return 0
    ffmpeg_path = resolve_ffmpeg(args.ffmpeg)
    output_root = args.output_root.resolve()
    local_manifest_path = materialize(
        contract_path,
        contract,
        assets_manifest,
        source_manifest_path,
        output_root,
        ffmpeg_path,
    )
    print(json.dumps({"status": "materialized-local-only", "manifest": str(local_manifest_path)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
