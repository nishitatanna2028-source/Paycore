#!/usr/bin/env python3
"""
Turns two k6 result files into a markdown table you can paste into the README.
    python loadtest/compare.py baseline sharded
(reads loadtest/results/<name>.json, written by payment_flow.js)
"""
import json
import pathlib
import sys

results = pathlib.Path(__file__).parent / "results"
names = sys.argv[1:3] or ["baseline", "sharded"]
a, b = (json.loads((results / f"{n}.json").read_text()) for n in names)


for name, r in zip(names, (a, b)):
    if r["errorRatePct"] > 5:
        sys.exit(f"'{name}' had {r['errorRatePct']:.1f}% errors, so its numbers are not valid. "
                 "Fix the cause (usually rate limiting: README > Performance, step 0) and re-run it.")


def pct(old, new):
    if not old:
        return "n/a"
    return f"{(new - old) / old * 100:+.0f}%"


rows = [
    ("Throughput (req/s)", a["throughputRps"], b["throughputRps"]),
    ("authorize p95 (ms)", a["steps"]["authorize"]["p95"], b["steps"]["authorize"]["p95"]),
    ("authorize p99 (ms)", a["steps"]["authorize"]["p99"], b["steps"]["authorize"]["p99"]),
    ("capture p95 (ms)", a["steps"]["capture"]["p95"], b["steps"]["capture"]["p95"]),
    ("capture p99 (ms)", a["steps"]["capture"]["p99"], b["steps"]["capture"]["p99"]),
    ("Error rate (%)", a["errorRatePct"], b["errorRatePct"]),
]
print(f"Load: {a['vus']} concurrent virtual users, {a['durationS']}s, {a['merchants']} merchants\n")
print(f"| Metric | {names[0]} | {names[1]} | Change |")
print("|---|---:|---:|---:|")
for label, x, y in rows:
    print(f"| {label} | {x:.1f} | {y:.1f} | {pct(x, y)} |")
