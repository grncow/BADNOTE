#!/usr/bin/env python3
"""Measure production Android pen ingestion; evaluate against the same-device baseline."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe") if os.name == "nt" else "adb")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.device]
    fingerprint = subprocess.check_output([*adb, "shell", "getprop", "ro.build.fingerprint"], text=True).strip()
    command = [*adb, "shell", "am", "instrument", "-w", "-e", "penInputOnly", "true",
               "com.inkforge.note4.debug.test/com.inkforge.notesstudio.NativeSmokeInstrumentation"]
    execution = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    log = args.output.with_suffix(".log")
    log.write_text(execution.stdout + execution.stderr, encoding="utf-8")
    prefix = "INSTRUMENTATION_RESULT: penInputTrials="
    encoded = next((line[len(prefix):] for line in execution.stdout.splitlines() if line.startswith(prefix)), None)
    if execution.returncode or "INSTRUMENTATION_RESULT: passed=true" not in execution.stdout or encoded is None:
        raise SystemExit(f"Pen instrumentation failed; see {log}")
    trials = json.loads(encoded)
    report = {"device": args.device, "fingerprint": fingerprint,
              "scope": "production MotionEvent dispatch with synchronous surface sink; excludes GPU, display and physical pen latency",
              "points": 1024, "historyPerFullMove": 8, "warmupRuns": 1, "measuredRuns": 3,
              "trials": trials, "p95Ms": statistics.median(row["wallP95Ms"] for row in trials),
              "cpuP95Ms": statistics.median(row["cpuP95Ms"] for row in trials), "status": "baseline"}
    if args.baseline:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        assert all(baseline[key] == report[key] for key in ("device", "fingerprint", "scope", "points", "historyPerFullMove", "warmupRuns", "measuredRuns")), "Incomparable baseline"
        report["baselineP95Ms"] = baseline["p95Ms"]
        report["improvementPercent"] = (1 - report["p95Ms"] / baseline["p95Ms"]) * 100
        report["status"] = "pass" if report["p95Ms"] < 8 and report["improvementPercent"] >= 50 and all(row["storedSamples"] == 1024 for row in trials) else "fail"
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    if report["status"] == "fail":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
