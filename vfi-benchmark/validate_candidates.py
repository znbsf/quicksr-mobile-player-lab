from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> None:
    path = Path(__file__).with_name("candidates.json")
    data = json.loads(path.read_text(encoding="utf-8"))
    assert data["schema"] == "anime-vfi-candidates.v1"
    candidates = data["candidates"]
    assert len(candidates) == 2
    assert sum(item["status"] == "advanced-offline-device-probe" for item in candidates) == 1
    for item in candidates:
        assert len(item["source"]["commit"]) == 40
        assert item["source"]["url"].startswith("https://github.com/")
        assert item["source"]["license"] == "MIT"
        assert item["model"]["weight_license_status"] == "no-independent-weight-license-found"
        assert item["model"]["redistribution"] == "blocked-pending-rights-review"
        assert item["model"]["parameter_bytes"] > 0
        assert item["model"]["operators"]
    print(f"VFI CANDIDATE CHECK: PASS ({len(candidates)} candidates, 1 advanced device probe)")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"VFI CANDIDATE CHECK: FAIL: {error}", file=sys.stderr)
        raise
