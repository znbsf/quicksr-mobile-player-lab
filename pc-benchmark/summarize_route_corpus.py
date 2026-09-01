from __future__ import annotations

import argparse
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable

from benchmark_runtime import ROOT, file_sha256


DEFAULT_REPORT = ROOT / "build" / "pc-benchmark" / "open-anime-corpus-route-matrix" / "report.json"


def summarize_group(cases: list[dict[str, Any]]) -> dict[str, Any]:
    psnr_deltas = [
        case["quality"]["quicksr_chain"]["psnr_db"]
        - case["quality"]["lanczos"]["psnr_db"]
        for case in cases
    ]
    ssim_deltas = [
        case["quality"]["quicksr_chain"]["global_ssim"]
        - case["quality"]["lanczos"]["global_ssim"]
        for case in cases
    ]
    edge_mae_advantages = [
        case["quality"]["lanczos"]["edge_mae"]
        - case["quality"]["quicksr_chain"]["edge_mae"]
        for case in cases
    ]
    timings = [case["timing_ms"]["chain_total"] for case in cases]
    return {
        "case_count": len(cases),
        "quicksr_psnr_wins": sum(delta > 0 for delta in psnr_deltas),
        "lanczos_psnr_wins_or_ties": sum(delta <= 0 for delta in psnr_deltas),
        "median_psnr_delta_db": statistics.median(psnr_deltas),
        "mean_psnr_delta_db": statistics.fmean(psnr_deltas),
        "median_global_ssim_delta": statistics.median(ssim_deltas),
        "median_edge_mae_advantage": statistics.median(edge_mae_advantages),
        "median_chain_ms": statistics.median(timings),
        "max_chain_ms": max(timings),
    }


def grouped_rows(
        cases: list[dict[str, Any]],
        dimension: str,
        key: Callable[[dict[str, Any]], str]) -> list[dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        groups[key(case)].append(case)
    return [
        {"dimension": dimension, "value": value, **summarize_group(group)}
        for value, group in sorted(groups.items())
    ]


def summarize(report: dict[str, Any]) -> dict[str, Any]:
    if report.get("status") != "observed-pc-rights-clear-image-route-corpus":
        raise ValueError(f"unsupported corpus report status: {report.get('status')}")
    cases = report["cases"]
    asset_domains = {asset["id"]: asset["domain"] for asset in report["assets"]}
    groups: list[dict[str, Any]] = []
    groups.extend(grouped_rows(cases, "asset", lambda case: case["asset"]))
    groups.extend(grouped_rows(cases, "domain", lambda case: asset_domains[case["asset"]]))
    groups.extend(grouped_rows(cases, "degradation", lambda case: case["degradation"]["id"]))
    groups.extend(grouped_rows(cases, "layout", lambda case: case["source"]["layout"]))
    groups.extend(
        grouped_rows(
            cases,
            "source",
            lambda case: (
                f"{case['source']['layout']}:{case['source']['width']}x{case['source']['height']}"
            ),
        )
    )
    groups.extend(
        grouped_rows(
            cases,
            "target",
            lambda case: f"{case['canvas']['width']}x{case['canvas']['height']}",
        )
    )
    return {
        "schema_version": 1,
        "status": "observed-pc-rights-clear-route-corpus-summary",
        "overall": summarize_group(cases),
        "groups": groups,
        "limits": report["limits"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize the rights-clear anime-style route corpus")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report_path = args.report.resolve()
    report = json.loads(report_path.read_text(encoding="utf-8"))
    payload = summarize(report)
    payload["input_report"] = {
        "path": report_path.relative_to(ROOT).as_posix(),
        "sha256": file_sha256(report_path),
    }
    output_path = (
        args.output.resolve()
        if args.output
        else report_path.with_name("summary.json")
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with output_path.with_suffix(".csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(payload["groups"][0]))
        writer.writeheader()
        writer.writerows(payload["groups"])
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
