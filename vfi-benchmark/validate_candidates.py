from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> None:
    path = Path(__file__).with_name("candidates.json")
    data = json.loads(path.read_text(encoding="utf-8"))
    evidence = json.loads(
        path.with_name("mobile-candidate-evidence-summary.json").read_text(encoding="utf-8")
    )
    assert data["schema"] == "anime-vfi-candidates.v2"
    assert data["baseline"]["id"] == "rife-ncnn-vulkan-v4.6"
    assert data["baseline"]["status"] == "offline-baseline-not-realtime"
    candidates = data["shortlist"]
    assert len(candidates) == 2
    assert {item["id"] for item in candidates} == {
        "rife-ncnn-vulkan-v4.25-lite",
        "ifrnet-ncnn-vulkan-s-vimeo90k",
    }
    assert sum(item["status"] == "stopped-after-host-and-device-lower-bound-probe" for item in candidates) == 1
    for item in candidates:
        assert len(item["source"]["commit"]) == 40
        assert item["source"]["url"].startswith("https://github.com/")
        assert item["source"]["license"] == "MIT"
        assert item["model"]["weight_license_status"] in {
            "no-independent-weight-license-found",
            "upstream-model-link-mit-claim-found-transformed-weight-review-pending",
        }
        assert item["model"]["redistribution"] == "blocked-pending-rights-review"
        assert item["model"].get("parameter_bytes", item["model"].get("checked_out_parameter_bytes", 0)) > 0
        assert item["model"]["operators"]
    assert len(data["screened_out"]) == 2
    assert data["screened_out"][0]["status"] == "unresolved-name-not-a-candidate"
    assert data["screened_out"][1]["status"] == "credible-mobile-system-not-comparable-in-this-probe"
    assert evidence["schema"] == "anime-vfi-mobile-candidate-evidence.v1"
    assert evidence["prefilter_contract"]["player_path_changed"] is False
    assert evidence["android_device"]["protocol"]["outputs_hash_matched_between_runs"] is True
    assert set(evidence["android_device"]["levels"]) == {"160x90", "256x144", "320x180"}
    assert evidence["decision"]["replace_rife_v4_6"] is False
    assert evidence["decision"]["stop_ifrnet_s"] is True
    assert evidence["decision"]["player_integration"] == "absent"
    print(f"VFI CANDIDATE CHECK: PASS ({len(candidates)} shortlisted, 1 device-probed lower bound)")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"VFI CANDIDATE CHECK: FAIL: {error}", file=sys.stderr)
        raise
