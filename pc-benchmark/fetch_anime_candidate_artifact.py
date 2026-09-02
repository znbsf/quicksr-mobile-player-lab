from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent
DEFAULT_MANIFEST = HERE / "anime-model-candidates.json"
DEFAULT_CACHE = ROOT / "build" / "anime-candidate-cache"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def select_artifact(manifest: dict[str, Any], artifact_id: str) -> dict[str, Any]:
    matches = [item for item in manifest.get("artifacts", []) if item.get("id") == artifact_id]
    if len(matches) != 1:
        raise ValueError(f"manifest must contain exactly one artifact {artifact_id!r}")
    artifact = matches[0]
    if not str(artifact.get("url", "")).startswith("https://"):
        raise ValueError("artifact URL must use HTTPS")
    if artifact.get("license") in (None, "NOASSERTION"):
        raise ValueError("artifact license is not clear")
    if "never-commit" not in artifact.get("repository_policy", ""):
        raise ValueError("artifact does not declare a source-only repository policy")
    return artifact


def fetch(artifact: dict[str, Any], cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    name = Path(urllib.parse.urlparse(artifact["url"]).path).name
    if not name:
        raise ValueError("artifact URL has no file name")
    output = cache / name
    if output.is_file():
        if output.stat().st_size != artifact["bytes"] or file_sha256(output) != artifact["sha256"]:
            raise ValueError(f"existing cache entry does not match manifest: {output.name}")
        return output
    partial = cache / f".{name}.{os.getpid()}.part"
    try:
        with urllib.request.urlopen(artifact["url"]) as response, partial.open("xb") as stream:
            while block := response.read(1024 * 1024):
                stream.write(block)
        if partial.stat().st_size != artifact["bytes"]:
            raise ValueError(f"artifact byte count mismatch for {name}")
        observed = file_sha256(partial)
        if observed != artifact["sha256"]:
            raise ValueError(f"artifact SHA-256 mismatch for {name}: {observed}")
        partial.replace(output)
        return output
    finally:
        if partial.exists():
            partial.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch one license-cleared anime candidate artifact into ignored cache")
    parser.add_argument("artifact_id")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    artifact = select_artifact(manifest, args.artifact_id)
    output = fetch(artifact, args.cache.resolve())
    print(json.dumps({"status": "verified", "artifact_id": artifact["id"], "file": output.name, "bytes": output.stat().st_size, "sha256": file_sha256(output)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
