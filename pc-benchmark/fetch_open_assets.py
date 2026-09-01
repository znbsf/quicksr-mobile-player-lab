from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = Path(__file__).with_name("open-assets.json")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path, expected_bytes: int, expected_sha256: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    if path.stat().st_size != expected_bytes:
        raise ValueError(
            f"byte mismatch for {path.name}: expected {expected_bytes}, observed {path.stat().st_size}"
        )
    observed = sha256(path)
    if observed != expected_sha256:
        raise ValueError(
            f"SHA-256 mismatch for {path.name}: expected {expected_sha256}, observed {observed}"
        )


def download(url: str, output: Path, expected_bytes: int, expected_sha256: str) -> str:
    if output.is_file():
        verify(output, expected_bytes, expected_sha256)
        return "verified-cache-hit"
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_suffix(output.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "quicksr-mobile-player-lab/1"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as stream:
            while chunk := response.read(1024 * 1024):
                stream.write(chunk)
        verify(partial, expected_bytes, expected_sha256)
        os.replace(partial, output)
    finally:
        partial.unlink(missing_ok=True)
    return "downloaded-and-verified"


def parse_checksum_index(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        parts = raw_line.split(maxsplit=1)
        if len(parts) == 2 and len(parts[0]) == 64:
            result[parts[1].lstrip("* ")] = parts[0].lower()
    return result


def fetch_asset(asset: dict[str, Any], cache_root: Path) -> list[dict[str, Any]]:
    files: list[dict[str, Any]] = []
    if asset["kind"] == "image":
        output = cache_root / asset["file_name"]
        status = download(asset["url"], output, asset["bytes"], asset["sha256"])
        files.append({"file": output.relative_to(ROOT).as_posix(), "status": status, "sha256": asset["sha256"]})
        return files
    if asset["kind"] != "image-sequence":
        raise ValueError(f'unsupported asset kind: {asset["kind"]}')

    index = asset["checksum_index"]
    index_path = cache_root / index["file_name"]
    index_status = download(index["url"], index_path, index["bytes"], index["sha256"])
    files.append({"file": index_path.relative_to(ROOT).as_posix(), "status": index_status, "sha256": index["sha256"]})
    checksums = parse_checksum_index(index_path)
    sequence_dir = cache_root / asset["directory"]
    for offset in range(asset["frame_count"]):
        frame = asset["start_frame"] + offset * asset["frame_step"]
        file_name = asset["file_template"].format(frame=frame)
        expected = checksums.get(file_name)
        if expected is None:
            raise ValueError(f"upstream checksum index has no entry for {file_name}")
        output = sequence_dir / file_name
        url = asset["url_template"].format(frame=frame)
        # The checksum index pins content. Its byte size is learned only after download.
        if output.is_file() and sha256(output) == expected:
            status = "verified-cache-hit"
        else:
            output.unlink(missing_ok=True)
            output.parent.mkdir(parents=True, exist_ok=True)
            partial = output.with_suffix(output.suffix + ".part")
            request = urllib.request.Request(url, headers={"User-Agent": "quicksr-mobile-player-lab/1"})
            try:
                with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as stream:
                    while chunk := response.read(1024 * 1024):
                        stream.write(chunk)
                observed = sha256(partial)
                if observed != expected:
                    raise ValueError(f"SHA-256 mismatch for {file_name}: expected {expected}, observed {observed}")
                os.replace(partial, output)
                status = "downloaded-and-verified"
            finally:
                partial.unlink(missing_ok=True)
        files.append({"file": output.relative_to(ROOT).as_posix(), "status": status, "sha256": expected})
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch and verify rights-clear local benchmark assets")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--asset", action="append", help="Asset id; repeat to select multiple. Default: all")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    cache_root = ROOT / manifest["cache_root"]
    selected = set(args.asset or [item["id"] for item in manifest["assets"]])
    known = {item["id"] for item in manifest["assets"]}
    unknown = selected - known
    if unknown:
        raise ValueError(f"unknown asset id(s): {sorted(unknown)}")
    report = {"schema_version": 1, "status": "pass", "assets": []}
    for asset in manifest["assets"]:
        if asset["id"] not in selected:
            continue
        report["assets"].append(
            {
                "id": asset["id"],
                "license": asset["license"],
                "repository_policy": asset["repository_policy"],
                "files": fetch_asset(asset, cache_root),
            }
        )
    cache_root.mkdir(parents=True, exist_ok=True)
    report_path = cache_root / "fetch-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
