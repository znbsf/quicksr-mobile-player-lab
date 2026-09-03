from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
from pathlib import Path
import re
import tempfile
from typing import Any

from PIL import Image, ImageDraw, __version__ as pillow_version
import numpy as np

import anime_candidate_benchmark as fixture
from benchmark_runtime import quality_metrics


ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = ROOT / "build" / "pc-benchmark" / "anime-visual-quality-gate"
CANDIDATE_MANIFEST = HERE / "anime-model-candidates.json"
ANIME4K_ARTIFACT_ID = "anime4k-v4.0.1-upscale-cnn-x2-s"
CONTRACT_SCHEMA_VERSION = 2
SUBMISSION_SCHEMA_VERSION = 2
CONTRACT_STATUS = "prepared-rights-clear-anime-visual-quality-gate"
SUBMISSION_STATUS = "declared-host-anime-visual-quality-output"
TEMPORAL_WIDTH = 320
TEMPORAL_HEIGHT = 180
TEMPORAL_FPS = 24


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_anime4k_pin(path: Path = CANDIDATE_MANIFEST) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    artifacts = [
        item for item in manifest.get("artifacts", [])
        if item.get("id") == ANIME4K_ARTIFACT_ID
    ]
    if len(artifacts) != 1:
        raise ValueError("candidate manifest must contain exactly one pinned Anime4K Small artifact")
    artifact = artifacts[0]
    if artifact.get("repository_policy") != "vendored-mit-source-with-notice":
        raise ValueError("Anime4K Small artifact is not approved for vendored source use")
    vendored = (ROOT / str(artifact.get("vendored_path", ""))).resolve()
    try:
        vendored.relative_to(ROOT.resolve())
    except ValueError as failure:
        raise ValueError("Anime4K vendored path escapes the repository") from failure
    if not vendored.is_file():
        raise FileNotFoundError(vendored)
    if vendored.stat().st_size != artifact.get("bytes"):
        raise ValueError("Anime4K vendored source byte count does not match the pinned manifest")
    observed = file_sha256(vendored)
    if observed != artifact.get("sha256"):
        raise ValueError("Anime4K vendored source SHA-256 does not match the pinned manifest")
    return {
        "artifact_id": artifact["id"],
        "source_commit": artifact["source_commit"],
        "bytes": artifact["bytes"],
        "sha256": artifact["sha256"],
        "license": artifact["license"],
        "repository_policy": artifact["repository_policy"],
        "vendored_path": artifact["vendored_path"],
        "source_manifest": path.relative_to(ROOT).as_posix(),
        "source_manifest_sha256": file_sha256(path),
    }


def base_temporal_scene() -> Image.Image:
    image = fixture.synthetic_lineart_reference(TEMPORAL_WIDTH, TEMPORAL_HEIGHT)
    draw = ImageDraw.Draw(image)
    draw.ellipse((126, 42, 202, 128), fill=(244, 202, 171), outline=(18, 22, 30), width=3)
    draw.ellipse((145, 68, 152, 76), fill=(20, 24, 31))
    draw.ellipse((176, 68, 183, 76), fill=(20, 24, 31))
    return image


def marked_scene(marker: int) -> Image.Image:
    image = base_temporal_scene()
    draw = ImageDraw.Draw(image)
    x = 25 + marker * 42
    draw.rectangle((x, 24, x + 20, 44), fill=(35 + marker * 24, 68, 176), outline=(8, 10, 16))
    draw.line((34, 146 - marker * 4, 284, 132 + marker * 3), fill=(20, 24, 31), width=2)
    return image


def slow_pan_frames() -> list[tuple[str, Image.Image]]:
    source = fixture.synthetic_lineart_reference(TEMPORAL_WIDTH + 12, TEMPORAL_HEIGHT)
    return [
        (f"pan-{offset}", source.crop((offset, 0, offset + TEMPORAL_WIDTH, TEMPORAL_HEIGHT)))
        for offset in range(5)
    ]


def mouth_particle_frames() -> list[tuple[str, Image.Image]]:
    frames: list[tuple[str, Image.Image]] = []
    mouths = [(154, 99, 176, 101), (154, 96, 176, 107), (154, 93, 176, 111),
              (154, 96, 176, 107), (154, 99, 176, 101)]
    for index, mouth in enumerate(mouths):
        image = base_temporal_scene()
        draw = ImageDraw.Draw(image)
        draw.ellipse(mouth, fill=(92, 25, 36), outline=(20, 22, 28))
        for particle in range(4):
            x = 220 + particle * 14 + index * (1 + particle % 2)
            y = 52 + particle * 15 - index * (1 + (particle + 1) % 2)
            draw.ellipse((x, y, x + 3, y + 3), fill=(252, 239, 124), outline=(61, 53, 24))
        frames.append((f"mouth-particle-{index}", image))
    return frames


def cut_frames() -> list[tuple[str, Image.Image]]:
    left = marked_scene(0)
    right = Image.new("RGB", (TEMPORAL_WIDTH, TEMPORAL_HEIGHT), (21, 31, 62))
    draw = ImageDraw.Draw(right)
    draw.rectangle((0, 112, TEMPORAL_WIDTH, TEMPORAL_HEIGHT), fill=(179, 66, 54))
    draw.polygon(((42, 112), (132, 28), (220, 112)), fill=(228, 216, 185), outline=(248, 244, 225))
    draw.ellipse((238, 24, 286, 72), fill=(247, 222, 98))
    return [("cut-a", left), ("cut-a", left.copy()), ("cut-b", right), ("cut-b", right.copy())]


def fade_frames() -> list[tuple[str, Image.Image]]:
    source = marked_scene(3)
    black = Image.new("RGB", source.size, (0, 0, 0))
    return [
        (f"fade-{step}", Image.blend(black, source, step / 5.0))
        for step in range(6)
    ]


def subtitle_frames(high_contrast: bool) -> list[tuple[str, Image.Image]]:
    base = base_temporal_scene()
    first = fixture.add_pixel_subtitle(base, high_contrast, "ANIME 24")
    second = fixture.add_pixel_subtitle(base, high_contrast, "SR TEST")
    prefix = "high" if high_contrast else "low"
    return [
        (f"subtitle-{prefix}-none", base),
        (f"subtitle-{prefix}-a", first),
        (f"subtitle-{prefix}-a", first.copy()),
        (f"subtitle-{prefix}-b", second),
    ]


def temporal_sequences() -> list[tuple[str, list[tuple[str, Image.Image]]]]:
    mixed_unique = [marked_scene(index) for index in range(5)]
    mixed = [
        ("mixed-a", mixed_unique[0]),
        ("mixed-b", mixed_unique[1]), ("mixed-b", mixed_unique[1].copy()),
        ("mixed-c", mixed_unique[2]), ("mixed-c", mixed_unique[2].copy()),
        ("mixed-c", mixed_unique[2].copy()),
        ("mixed-d", mixed_unique[3]),
        ("mixed-e", mixed_unique[4]), ("mixed-e", mixed_unique[4].copy()),
    ]
    return [
        ("mixed-one-two-three", mixed),
        ("slow-pan", slow_pan_frames()),
        ("mouth-particle", mouth_particle_frames()),
        ("hard-cut", cut_frames()),
        ("fade", fade_frames()),
        ("high-contrast-subtitle", subtitle_frames(True)),
        ("low-contrast-subtitle", subtitle_frames(False)),
    ]


def prepare_temporal_cases(output: Path) -> list[dict[str, Any]]:
    profiles = fixture.degradation_profiles()
    cases: list[dict[str, Any]] = []
    for scenario, source_frames in temporal_sequences():
        for profile_id, profile in profiles.items():
            case_id = f"{scenario}--{profile_id}"
            input_dir = output / "temporal" / case_id / "inputs"
            reference_dir = output / "temporal" / case_id / "references"
            input_dir.mkdir(parents=True, exist_ok=True)
            reference_dir.mkdir(parents=True, exist_ok=True)
            frames: list[dict[str, Any]] = []
            last_label: str | None = None
            last_process_frame_id: int | None = None
            for index, (label, reference) in enumerate(source_frames, 1):
                low, jpeg_payload = fixture.degrade(reference, profile)
                input_path = input_dir / f"frame-{index:03d}.png"
                reference_path = reference_dir / f"frame-{index:03d}.png"
                low.save(input_path)
                reference.save(reference_path)
                held = label == last_label
                if held and last_process_frame_id is None:
                    raise AssertionError("held temporal frame has no processed reference")
                if not held:
                    last_process_frame_id = index
                frames.append({
                    "frame_id": index,
                    "pts_us": (index - 1) * 1_000_000 // TEMPORAL_FPS,
                    "content_id": label,
                    "expected_decision": "REUSE" if held else "PROCESS",
                    "expected_reference_frame_id": last_process_frame_id if held else None,
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
                    "jpeg_bytes": len(jpeg_payload) if jpeg_payload is not None else None,
                })
                last_label = label
            cases.append({
                "id": case_id,
                "scenario": scenario,
                "degradation": profile,
                "fps": TEMPORAL_FPS,
                "license": {
                    "spdx": "LicenseRef-Project-Original-Fixture",
                    "attribution": "Generated deterministically by project source",
                },
                "frames": frames,
            })
    return cases


def prepare_contract(output: Path) -> dict[str, Any]:
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    anime4k = load_anime4k_pin()
    spatial = fixture.prepare_contract(output / "spatial", include_open_assets=False)
    spatial_path = output / "spatial" / "contract.json"
    temporal_cases = prepare_temporal_cases(output)
    contract = {
        "schema_version": CONTRACT_SCHEMA_VERSION,
        "status": CONTRACT_STATUS,
        "anime4k_source": anime4k,
        "spatial_contract": {
            "path": spatial_path.relative_to(output).as_posix(),
            "sha256": file_sha256(spatial_path),
            "status": spatial["status"],
            "case_count": len(spatial["cases"]),
        },
        "temporal_cases": temporal_cases,
        "submission_protocol": {
            "schema_version": SUBMISSION_SCHEMA_VERSION,
            "status": SUBMISSION_STATUS,
            "image_format": "RGB PNG at each contract reference size",
            "path_scope": "all output paths are relative to and remain under the submission directory",
            "semantics": "declared-oracle-conformance; submitted case/frame, input SHA-256, PTS, decision and cache reference are declarations, not observed runtime proof",
            "runtime_evidence": "unbound until replayable per-frame trace, receipt and execution identity are jointly validated",
        },
        "runtime": {
            "platform": platform.platform(),
            "python": platform.python_version(),
            "numpy": np.__version__,
            "pillow": pillow_version,
        },
        "limits": [
            "Fixtures are deterministic project-original drawings, not representative commercial-anime evidence.",
            "The declared-conformance gate checks submitted bindings, decisions, PTS, cache identity and frame files against the synthetic oracle; it does not prove runtime behavior.",
            "Human rhythm, halo, line and subtitle review remains not-reviewed until separately recorded.",
            "Preparing this contract does not execute mpv, Android, OpenGL ES, QNN or a physical device.",
        ],
    }
    contract_path = output / "contract.json"
    contract_path.write_text(
        json.dumps(contract, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_submission_template(contract_path, output / "submission-template.json")
    return contract


def write_submission_template(contract_path: Path, output: Path) -> None:
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    contract_root = contract_path.parent
    spatial_path = contract_root / contract["spatial_contract"]["path"]
    spatial = json.loads(spatial_path.read_text(encoding="utf-8"))
    template = {
        "schema_version": SUBMISSION_SCHEMA_VERSION,
        "status": SUBMISSION_STATUS,
        "contract_sha256": file_sha256(contract_path),
        "producer_label": "REPLACE_WITH_SUBMISSION_PRODUCER_LABEL",
        "runtime_trace_sha256": None,
        "spatial_outputs": [
            {
                "case_id": case["id"],
                "input_sha256": case["input"]["sha256"],
                "output_path": f"outputs/spatial/{case['id']}.png",
                "output_sha256": "REPLACE_WITH_SUPPLIED_FILE_SHA256",
            }
            for case in spatial["cases"]
        ],
        "temporal_outputs": [
            {
                "case_id": case["id"],
                "frames": [
                    {
                        "frame_id": frame["frame_id"],
                        "pts_us": frame["pts_us"],
                        "input_sha256": frame["input"]["sha256"],
                        "decision": "REPLACE_WITH_DECLARED_PROCESS_OR_REUSE",
                        "reference_frame_id": "REPLACE_WITH_DECLARED_NULL_OR_FRAME_ID",
                        "reference_input_sha256": "REPLACE_WITH_DECLARED_NULL_OR_SHA256",
                        "output_path": (
                            f"outputs/temporal/{case['id']}/frame-{frame['frame_id']:03d}.png"
                        ),
                        "output_sha256": "REPLACE_WITH_SUPPLIED_FILE_SHA256",
                    }
                    for frame in case["frames"]
                ],
            }
            for case in contract["temporal_cases"]
        ],
    }
    output.write_text(json.dumps(template, indent=2) + "\n", encoding="utf-8")


def safe_path(root: Path, relative: object, label: str) -> Path:
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError(f"{label} must be a non-empty relative path")
    path = (root / relative).resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError as failure:
        raise ValueError(f"{label} escapes its declared root") from failure
    if not path.is_file():
        raise FileNotFoundError(path)
    return path


def canonical_json_sha256(value: object) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def deterministic_contract_identity(contract_path: Path, contract: dict[str, Any]) -> dict[str, Any]:
    """Return every canonical field except descriptive host runtime metadata."""
    contract_root = contract_path.parent
    spatial_meta = dict(contract.get("spatial_contract", {}))
    spatial_path = safe_path(
        contract_root, spatial_meta.get("path"), "canonical spatial contract path")
    spatial = json.loads(spatial_path.read_text(encoding="utf-8"))
    normalized_spatial = dict(spatial)
    normalized_spatial.pop("runtime", None)
    spatial_meta.pop("sha256", None)
    spatial_meta["deterministic_contract_sha256"] = canonical_json_sha256(normalized_spatial)

    identity = dict(contract)
    identity.pop("runtime", None)
    identity["spatial_contract"] = spatial_meta
    return identity


def rebuild_and_verify_canonical_contract(
    contract_path: Path, contract: dict[str, Any]
) -> str:
    supplied_identity = deterministic_contract_identity(contract_path, contract)
    with tempfile.TemporaryDirectory(prefix="quicksr-anime-visual-canonical-") as temporary:
        regenerated_root = Path(temporary) / "contract"
        regenerated = prepare_contract(regenerated_root)
        regenerated_path = regenerated_root / "contract.json"
        regenerated_identity = deterministic_contract_identity(regenerated_path, regenerated)
    if supplied_identity != regenerated_identity:
        raise ValueError(
            "visual contract does not match the canonical checked-in generator and source pin")
    return canonical_json_sha256(regenerated_identity)


def index_exact(items: object, key_name: str, expected: set[str], label: str) -> dict[str, dict[str, Any]]:
    if not isinstance(items, list):
        raise ValueError(f"{label} must be a list")
    indexed: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get(key_name), str):
            raise ValueError(f"{label} contains an invalid item")
        key = item[key_name]
        if key in indexed:
            raise ValueError(f"{label} contains duplicate {key_name} {key!r}")
        indexed[key] = item
    observed = set(indexed)
    if observed != expected:
        raise ValueError(
            f"{label} identity set mismatch: missing={sorted(expected - observed)}, "
            f"unknown={sorted(observed - expected)}")
    return indexed


def load_rgb(path: Path, expected: tuple[int, int], label: str) -> np.ndarray:
    with Image.open(path) as image:
        if image.mode != "RGB" or image.size != expected:
            raise ValueError(
                f"{label} must be RGB {expected[0]}x{expected[1]}, "
                f"observed {image.mode} {image.width}x{image.height}")
        return np.asarray(image, dtype=np.float32) / 255.0


def require_hash(path: Path, expected: object, label: str) -> None:
    if not isinstance(expected, str) or len(expected) != 64 or file_sha256(path) != expected:
        raise ValueError(f"{label} SHA-256 does not match its contract")


def metric_record(reference: np.ndarray, actual: np.ndarray) -> dict[str, Any]:
    metrics = quality_metrics(reference, actual)
    infinite = math.isinf(metrics["psnr_db"])
    return {
        "psnr_db": None if infinite else metrics["psnr_db"],
        "psnr_is_infinite": infinite,
        "global_ssim": metrics["global_ssim"],
        "edge_mae": metrics["edge_mae"],
    }


def evaluate(contract_path: Path, submission_path: Path, output: Path) -> dict[str, Any]:
    contract_path = contract_path.resolve()
    submission_path = submission_path.resolve()
    contract_root = contract_path.parent
    submission_root = submission_path.parent
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    submission = json.loads(submission_path.read_text(encoding="utf-8"))
    if (contract.get("schema_version") != CONTRACT_SCHEMA_VERSION
            or contract.get("status") != CONTRACT_STATUS):
        raise ValueError("unsupported visual-quality contract")
    if (submission.get("schema_version") != SUBMISSION_SCHEMA_VERSION
            or submission.get("status") != SUBMISSION_STATUS):
        raise ValueError("unsupported visual-quality submission")
    if submission.get("contract_sha256") != file_sha256(contract_path):
        raise ValueError("submission contract SHA-256 does not match")
    if (not isinstance(submission.get("producer_label"), str)
            or not submission["producer_label"].strip()):
        raise ValueError("submission producer_label must be non-empty")
    submitted_trace_hash = submission.get("runtime_trace_sha256")
    if (submitted_trace_hash is not None
            and not re.fullmatch(r"[0-9a-f]{64}", str(submitted_trace_hash))):
        raise ValueError("runtime_trace_sha256 must be null or a lowercase SHA-256")
    if contract.get("anime4k_source") != load_anime4k_pin():
        raise ValueError("visual contract Anime4K source binding does not match the current pin")
    canonical_identity_sha256 = rebuild_and_verify_canonical_contract(contract_path, contract)

    spatial_contract_path = safe_path(
        contract_root, contract["spatial_contract"].get("path"), "spatial contract path")
    if file_sha256(spatial_contract_path) != contract["spatial_contract"].get("sha256"):
        raise ValueError("spatial contract SHA-256 does not match")
    spatial = json.loads(spatial_contract_path.read_text(encoding="utf-8"))
    if spatial.get("status") != "prepared-rights-clear-anime-candidate-contract":
        raise ValueError("unsupported nested spatial contract")
    spatial_cases = {case["id"]: case for case in spatial.get("cases", [])}
    if len(spatial_cases) != contract["spatial_contract"].get("case_count"):
        raise ValueError("spatial case count does not match the visual contract")
    spatial_outputs = index_exact(
        submission.get("spatial_outputs"), "case_id", set(spatial_cases), "spatial outputs")

    failures: list[dict[str, Any]] = []
    spatial_rows: list[dict[str, Any]] = []
    spatial_output_identity: dict[str, tuple[str, str]] = {}
    for case_id, case in spatial_cases.items():
        declared = spatial_outputs[case_id]
        if declared.get("input_sha256") != case["input"]["sha256"]:
            failures.append({"kind": "declared_same_frame_identity", "case_id": case_id})
        output_path = safe_path(
            submission_root, declared.get("output_path"), f"spatial output {case_id}")
        observed_hash = file_sha256(output_path)
        if declared.get("output_sha256") != observed_hash:
            failures.append({"kind": "output_hash", "case_id": case_id})
        expected_size = (case["reference"]["width"], case["reference"]["height"])
        input_path = safe_path(
            spatial_contract_path.parent, case["input"]["path"], f"input {case_id}")
        reference_path = safe_path(
            spatial_contract_path.parent, case["reference"]["path"], f"reference {case_id}")
        lanczos_path = safe_path(
            spatial_contract_path.parent, case["lanczos"]["path"], f"Lanczos {case_id}")
        require_hash(input_path, case["input"]["sha256"], f"input {case_id}")
        require_hash(reference_path, case["reference"]["sha256"], f"reference {case_id}")
        require_hash(lanczos_path, case["lanczos"]["sha256"], f"Lanczos {case_id}")
        reference = load_rgb(reference_path, expected_size, f"reference {case_id}")
        actual = load_rgb(output_path, expected_size, f"spatial output {case_id}")
        lanczos = load_rgb(lanczos_path, expected_size, f"Lanczos {case_id}")
        spatial_rows.append({
            "case_id": case_id,
            "source": case["source"],
            "degradation": case["degradation"]["id"],
            "input_sha256": case["input"]["sha256"],
            "output_sha256": observed_hash,
            "candidate": metric_record(reference, actual),
            "lanczos": metric_record(reference, lanczos),
        })
        previous = spatial_output_identity.get(observed_hash)
        if previous is not None and previous[0] != case["reference"]["sha256"]:
            failures.append({
                "kind": "declared_same_frame_output_identity", "case_id": case_id,
                "collides_with_case_id": previous[1],
            })
        else:
            spatial_output_identity[observed_hash] = (case["reference"]["sha256"], case_id)

    temporal_case_list = contract.get("temporal_cases", [])
    if not isinstance(temporal_case_list, list):
        raise ValueError("temporal cases must be a list")
    temporal_contracts = {case["id"]: case for case in temporal_case_list}
    if len(temporal_contracts) != len(temporal_case_list):
        raise ValueError("temporal contract contains duplicate case IDs")
    temporal_outputs = index_exact(
        submission.get("temporal_outputs"), "case_id", set(temporal_contracts), "temporal outputs")
    temporal_rows: list[dict[str, Any]] = []
    declared_wrong_reuse = 0
    declared_missed_reuse = 0
    expected_process_frames = 0
    expected_reuse_frames = 0
    pts_failures = 0
    reference_failures = 0
    same_frame_failures = sum(
        item["kind"] in {
            "declared_same_frame_identity", "declared_same_frame_output_identity"}
        for item in failures)
    for case_id, case in temporal_contracts.items():
        declared_case = temporal_outputs[case_id]
        declared_frames = declared_case.get("frames")
        if not isinstance(declared_frames, list):
            raise ValueError(f"temporal output {case_id} frames must be a list")
        expected_frames = case["frames"]
        if [frame.get("frame_id") for frame in expected_frames] != list(
                range(1, len(expected_frames) + 1)):
            raise ValueError(f"temporal contract {case_id} frame IDs are not contiguous")
        previous_pts = -1
        for expected_frame in expected_frames:
            frame_id = expected_frame["frame_id"]
            if not isinstance(expected_frame.get("pts_us"), int) or expected_frame["pts_us"] <= previous_pts:
                raise ValueError(f"temporal contract {case_id} PTS is not strictly increasing")
            previous_pts = expected_frame["pts_us"]
            expected_decision = expected_frame.get("expected_decision")
            expected_reference = expected_frame.get("expected_reference_frame_id")
            if expected_decision == "PROCESS" and expected_reference is not None:
                raise ValueError(f"temporal contract {case_id} processed frame has a reference")
            if expected_decision == "REUSE":
                if not isinstance(expected_reference, int) or not 1 <= expected_reference < frame_id:
                    raise ValueError(f"temporal contract {case_id} reused frame has an invalid reference")
                if (expected_frame["input"]["sha256"]
                        != expected_frames[expected_reference - 1]["input"]["sha256"]):
                    raise ValueError(f"temporal contract {case_id} reuse does not bind an exact hold")
            elif expected_decision != "PROCESS":
                raise ValueError(f"temporal contract {case_id} has an invalid expected decision")
            contract_input = safe_path(
                contract_root, expected_frame["input"]["path"],
                f"temporal input {case_id} frame {frame_id}")
            contract_reference = safe_path(
                contract_root, expected_frame["reference"]["path"],
                f"temporal reference {case_id} frame {frame_id}")
            require_hash(
                contract_input, expected_frame["input"]["sha256"],
                f"temporal input {case_id} frame {frame_id}")
            require_hash(
                contract_reference, expected_frame["reference"]["sha256"],
                f"temporal reference {case_id} frame {frame_id}")
        expected_ids = [frame["frame_id"] for frame in expected_frames]
        declared_ids = [frame.get("frame_id") for frame in declared_frames if isinstance(frame, dict)]
        if declared_ids != expected_ids or len(declared_frames) != len(expected_frames):
            raise ValueError(f"temporal output {case_id} is not the complete ordered frame sequence")
        declared_by_id = {frame["frame_id"]: frame for frame in declared_frames}
        output_hashes: dict[int, str] = {}
        output_reference_identity: dict[str, tuple[str, int]] = {}
        for expected_frame in expected_frames:
            frame_id = expected_frame["frame_id"]
            declared = declared_by_id[frame_id]
            if declared.get("decision") not in {"PROCESS", "REUSE"}:
                raise ValueError(f"{case_id} frame {frame_id} has invalid cadence decision")
            if declared.get("input_sha256") != expected_frame["input"]["sha256"]:
                same_frame_failures += 1
                failures.append({
                    "kind": "declared_same_frame_identity", "case_id": case_id,
                    "frame_id": frame_id,
                })
            if declared.get("pts_us") != expected_frame["pts_us"]:
                pts_failures += 1
                failures.append({
                    "kind": "declared_pts_identity", "case_id": case_id,
                    "frame_id": frame_id,
                })
            expected_decision = expected_frame["expected_decision"]
            if expected_decision == "PROCESS":
                expected_process_frames += 1
            else:
                expected_reuse_frames += 1
            if declared["decision"] == "REUSE" and expected_decision == "PROCESS":
                declared_wrong_reuse += 1
                failures.append({
                    "kind": "declared_wrong_reuse", "case_id": case_id,
                    "frame_id": frame_id,
                })
            elif declared["decision"] == "PROCESS" and expected_decision == "REUSE":
                declared_missed_reuse += 1
                failures.append({
                    "kind": "declared_missed_reuse", "case_id": case_id,
                    "frame_id": frame_id,
                })
            expected_reference = expected_frame["expected_reference_frame_id"]
            expected_reference_input = (
                expected_frames[expected_reference - 1]["input"]["sha256"]
                if expected_reference is not None else None
            )
            declared_reference = declared.get("reference_frame_id")
            declared_reference_input = declared.get("reference_input_sha256")
            invalid_declared_reference = (
                (declared["decision"] == "PROCESS"
                 and (declared_reference is not None or declared_reference_input is not None))
                or (declared["decision"] == "REUSE"
                    and (not isinstance(declared_reference, int)
                         or not 1 <= declared_reference < frame_id
                         or not isinstance(declared_reference_input, str)
                         or len(declared_reference_input) != 64))
            )
            if invalid_declared_reference:
                reference_failures += 1
                failures.append({
                    "kind": "declared_reference_semantics", "case_id": case_id,
                    "frame_id": frame_id,
                })
            if (declared.get("reference_frame_id") != expected_reference
                    or declared.get("reference_input_sha256") != expected_reference_input):
                reference_failures += 1
                failures.append({
                    "kind": "declared_reference_identity", "case_id": case_id,
                    "frame_id": frame_id,
                })
            output_path = safe_path(
                submission_root, declared.get("output_path"),
                f"temporal output {case_id} frame {frame_id}")
            observed_hash = file_sha256(output_path)
            output_hashes[frame_id] = observed_hash
            if declared.get("output_sha256") != observed_hash:
                failures.append({"kind": "output_hash", "case_id": case_id, "frame_id": frame_id})
            previous_output = output_reference_identity.get(observed_hash)
            if (previous_output is not None
                    and previous_output[0] != expected_frame["reference"]["sha256"]):
                same_frame_failures += 1
                failures.append({
                    "kind": "declared_same_frame_output_identity", "case_id": case_id,
                    "frame_id": frame_id, "collides_with_frame_id": previous_output[1],
                })
            else:
                output_reference_identity[observed_hash] = (
                    expected_frame["reference"]["sha256"], frame_id)
            expected_size = (
                expected_frame["reference"]["width"], expected_frame["reference"]["height"])
            reference_path = safe_path(
                contract_root, expected_frame["reference"]["path"],
                f"temporal reference {case_id} frame {frame_id}")
            reference = load_rgb(
                reference_path, expected_size, f"temporal reference {case_id} frame {frame_id}")
            actual = load_rgb(
                output_path, expected_size, f"temporal output {case_id} frame {frame_id}")
            temporal_rows.append({
                "case_id": case_id,
                "scenario": case["scenario"],
                "degradation": case["degradation"]["id"],
                "frame_id": frame_id,
                "declared_pts_us": declared.get("pts_us"),
                "expected_decision": expected_decision,
                "declared_decision": declared["decision"],
                "expected_reference_frame_id": expected_reference,
                "declared_reference_frame_id": declared.get("reference_frame_id"),
                "output_sha256": observed_hash,
                "quality": metric_record(reference, actual),
            })
        for expected_frame in expected_frames:
            frame_id = expected_frame["frame_id"]
            declared = declared_by_id[frame_id]
            if declared["decision"] != "REUSE":
                continue
            reference_frame_id = declared.get("reference_frame_id")
            if not isinstance(reference_frame_id, int) or reference_frame_id not in output_hashes:
                continue
            if output_hashes[frame_id] != output_hashes[reference_frame_id]:
                reference_failures += 1
                failures.append({
                    "kind": "declared_reference_output_identity", "case_id": case_id,
                    "frame_id": frame_id, "reference_frame_id": reference_frame_id,
                })
    declared_conformance_status = "PASS" if not failures else "FAIL"
    report = {
        "schema_version": 2,
        "status": "evaluated-declared-oracle-conformance",
        "declared_oracle_conformance": declared_conformance_status,
        "quality_acceptance": "NOT_SET_METRICS_ONLY_HUMAN_REVIEW_PENDING",
        "runtime_evidence": {
            "status": "NOT_BOUND",
            "submitted_trace_sha256": submitted_trace_hash,
            "reason": "submission fields and a trace hash alone cannot prove execution",
            "required": [
                "replayable per-frame runtime trace bytes",
                "receipt binding trace, contract, outputs, runtime configuration and execution identity",
                "validator replay of the trace against the bound artifacts",
            ],
        },
        "producer_label": submission["producer_label"],
        "contract_sha256": file_sha256(contract_path),
        "canonical_generator_identity_sha256": canonical_identity_sha256,
        "submission_sha256": file_sha256(submission_path),
        "summary": {
            "spatial_case_count": len(spatial_rows),
            "temporal_case_count": len(temporal_contracts),
            "temporal_frame_count": len(temporal_rows),
            "declared_wrong_reuse_count": declared_wrong_reuse,
            "declared_wrong_reuse_rate": (
                declared_wrong_reuse / expected_process_frames if expected_process_frames else 0.0),
            "declared_missed_reuse_count": declared_missed_reuse,
            "declared_missed_reuse_rate": (
                declared_missed_reuse / expected_reuse_frames if expected_reuse_frames else 0.0),
            "expected_process_frame_count": expected_process_frames,
            "expected_reuse_frame_count": expected_reuse_frames,
            "declared_pts_identity_failure_count": pts_failures,
            "declared_reference_identity_failure_count": reference_failures,
            "declared_same_frame_identity_failure_count": same_frame_failures,
            "total_failure_count": len(failures),
        },
        "failures": failures,
        "spatial_rows": spatial_rows,
        "temporal_rows": temporal_rows,
        "limits": [
            "Declared-oracle PASS means the submission declarations and supplied files conform to the synthetic oracle; it is not observed runtime behavior.",
            "PSNR, global SSIM and edge MAE are reported diagnostics without a frozen acceptance threshold.",
            "Human visual and rhythm review is not performed by this evaluator.",
            "A trace SHA-256 is integrity metadata only and cannot independently prove that the declared decisions were executed.",
            "This report does not establish target-device execution, final display cadence or representative anime quality.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare and evaluate deterministic Anime4K/cadence visual-quality gates")
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    evaluate_parser = subparsers.add_parser("evaluate")
    evaluate_parser.add_argument("--contract", type=Path, required=True)
    evaluate_parser.add_argument("--submission", type=Path, required=True)
    evaluate_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        result = prepare_contract(args.output)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    result = evaluate(args.contract, args.submission, args.output)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["declared_oracle_conformance"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
