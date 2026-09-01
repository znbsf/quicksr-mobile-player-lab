from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULTS = ROOT / "build" / "pc-benchmark"
DIRECTORIES = {1.5: "open-image-1p5x", 2.0: "open-image-2x", 3.0: "open-image-3x", 4.0: "open-image-4x"}


def summarize(report: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    for case in report["cases"]:
        neural = case["methods"]["quicksr"]
        lanczos = case["methods"]["lanczos"]
        p50_ms = case["quicksr_timing_ms"]["p50"]
        rows.append(
            {
                "scale": float(report["model"]["scale"]),
                "degradation": case["degradation"]["id"],
                "quicksr_p50_ms": p50_ms,
                "quicksr_fps_from_p50": 1000.0 / p50_ms,
                "quicksr_psnr_db": neural["psnr_db"],
                "lanczos_psnr_db": lanczos["psnr_db"],
                "quicksr_minus_lanczos_psnr_db": neural["psnr_db"] - lanczos["psnr_db"],
                "quicksr_global_ssim": neural["global_ssim"],
                "lanczos_global_ssim": lanczos["global_ssim"],
                "psnr_winner": "quicksr" if neural["psnr_db"] > lanczos["psnr_db"] else "lanczos",
            }
        )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize the 1.5x/2x/3x/4x open-image benchmark reports")
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--output", type=Path, default=DEFAULT_RESULTS / "open-image-matrix-summary.json")
    args = parser.parse_args()
    rows: list[dict[str, Any]] = []
    reports = []
    for scale, directory in DIRECTORIES.items():
        report_path = args.results / directory / "report.json"
        if not report_path.is_file():
            raise FileNotFoundError(f"missing {scale}x report: {report_path}")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if float(report["model"]["scale"]) != scale:
            raise ValueError(f"scale/report mismatch: expected {scale}, observed {report['model']['scale']}")
        reports.append({"scale": scale, "path": report_path.relative_to(ROOT).as_posix(), "model_sha256": report["model"]["sha256"]})
        rows.extend(summarize(report))
    payload = {
        "schema_version": 1,
        "status": "observed-pc-open-image-scale-matrix",
        "reports": reports,
        "rows": rows,
        "limits": [
            "The source is one open 3D-animation frame, not a representative anime corpus.",
            "PC CPU timing does not predict Android QNN HTP timing.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    csv_path = args.output.with_suffix(".csv")
    with csv_path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
