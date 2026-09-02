from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
DEFAULT_MANIFEST = HERE / "anime-model-candidates.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
VALID_LEVELS = {"confirmed-upstream", "derived-from-pinned-source", "local-observation", "open"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate(payload: dict[str, Any]) -> dict[str, Any]:
    require(payload.get("schema_version") == 1, "unsupported schema_version")
    require(payload.get("scope", "").endswith("interpolation is excluded."), "scope must exclude interpolation")
    require(set(payload.get("evidence_levels", [])) == VALID_LEVELS, "evidence levels are incomplete")

    artifacts = payload.get("artifacts", [])
    artifact_ids = set()
    for artifact in artifacts:
        artifact_id = artifact.get("id")
        require(isinstance(artifact_id, str) and artifact_id not in artifact_ids, "artifact ids must be unique")
        artifact_ids.add(artifact_id)
        require(str(artifact.get("url", "")).startswith("https://"), f"artifact {artifact_id} URL must use HTTPS")
        require(isinstance(artifact.get("bytes"), int) and artifact["bytes"] > 0, f"artifact {artifact_id} needs bytes")
        require(bool(SHA256.fullmatch(str(artifact.get("sha256", "")))), f"artifact {artifact_id} needs SHA-256")
        require(bool(COMMIT.fullmatch(str(artifact.get("source_commit", "")))), f"artifact {artifact_id} needs source commit")
        require(artifact.get("license") not in (None, "NOASSERTION"), f"artifact {artifact_id} license is not clear")
        require("never-commit" in artifact.get("repository_policy", ""), f"artifact {artifact_id} must stay untracked")

    candidates = payload.get("candidates", [])
    candidate_ids = set()
    for candidate in candidates:
        candidate_id = candidate.get("id")
        require(isinstance(candidate_id, str) and candidate_id not in candidate_ids, "candidate ids must be unique")
        candidate_ids.add(candidate_id)
        require(candidate.get("class") in {"gpu-shader", "mobile-sisr", "temporal-reference"}, f"bad class: {candidate_id}")
        require(isinstance(candidate.get("temporal"), bool), f"candidate {candidate_id} needs temporal boolean")
        require(bool(COMMIT.fullmatch(str(candidate.get("upstream", {}).get("commit", "")))), f"candidate {candidate_id} needs full commit")
        for section, field in (("code_license", "status"), ("weights", "status"), ("training_data", "status"), ("io", "status")):
            require(candidate.get(section, {}).get(field) in VALID_LEVELS, f"candidate {candidate_id} has invalid {section} status")
        size = candidate.get("size", {})
        require(size.get("parameter_status") in VALID_LEVELS, f"candidate {candidate_id} has invalid parameter status")
        require(size.get("memory_status") in VALID_LEVELS, f"candidate {candidate_id} has invalid memory status")
        require(candidate.get("decision") in {"advance-gpu-shader", "advance-mobile-neural", "hold-gpu-quality-ab", "blocked", "reference-only"}, f"candidate {candidate_id} has invalid decision")
        if candidate["weights"].get("artifact_id"):
            require(candidate["weights"]["artifact_id"] in artifact_ids, f"candidate {candidate_id} references unknown artifact")
        if candidate["benchmark"].get("eligible"):
            require(candidate["weights"].get("status") == "confirmed-upstream", f"eligible candidate {candidate_id} lacks confirmed weights")
        if candidate["class"] == "temporal-reference":
            require(candidate["temporal"] is True and candidate["decision"] == "reference-only", f"temporal candidate {candidate_id} escaped reference-only scope")

    promotion = payload.get("promotion", {})
    gpu = promotion.get("gpu_shader")
    neural = promotion.get("mobile_neural")
    require(gpu in candidate_ids and neural in candidate_ids, "promotion references unknown candidate")
    require(sum(c["decision"] == "advance-gpu-shader" for c in candidates) == 1, "exactly one GPU shader must advance")
    require(sum(c["decision"] == "advance-mobile-neural" for c in candidates) == 1, "exactly one mobile neural model must advance")
    by_id = {candidate["id"]: candidate for candidate in candidates}
    require(by_id[gpu]["decision"] == "advance-gpu-shader", "GPU promotion/decision mismatch")
    require(by_id[neural]["decision"] == "advance-mobile-neural", "mobile promotion/decision mismatch")
    require(by_id[gpu]["temporal"] is False and by_id[neural]["temporal"] is False, "promoted candidates must be frame SISR")
    return {
        "status": "PASS",
        "candidate_count": len(candidates),
        "artifact_count": len(artifacts),
        "gpu_shader": gpu,
        "mobile_neural": neural,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the fail-closed anime model evidence manifest")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    result = validate(json.loads(args.manifest.read_text(encoding="utf-8")))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
