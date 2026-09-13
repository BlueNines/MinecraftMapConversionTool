#!/usr/bin/env python3
"""Check that a conversion output looks like a usable 1.12.2 world.

Usage: smoke_check.py <output-directory>

This is the assertion half of the downgrade smoke test. It is kept as a real
file rather than inlined into the workflow so it can be run locally against a
conversion you already have on disk.
"""

import glob
import json
import os
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: smoke_check.py <output-directory>", file=sys.stderr)
        return 2

    out = sys.argv[1]
    problems = []

    # A loadable world needs level.dat, and the report is the point of the tool.
    for name in ("level.dat", "conversion-report.json"):
        if not os.path.isfile(os.path.join(out, name)):
            problems.append(f"missing {name}")

    regions = glob.glob(os.path.join(out, "region", "*.mca"))
    print(f"region files: {len(regions)}")
    if not regions:
        problems.append("no region files written")

    report = {}
    report_path = os.path.join(out, "conversion-report.json")
    if os.path.isfile(report_path):
        with open(report_path, encoding="utf-8") as handle:
            report = json.load(handle)

    blocks = report.get("blocks") or {}
    total = blocks.get("totalBlocks", 0)
    distinct = blocks.get("distinctTypes", 0)
    print(f"blocks converted: {total} across {distinct} types")
    # The 1.15.2 sample world sits at Y >= 0 already, so a working downgrade has
    # to produce blocks. Zero means everything was dropped on the floor - which
    # is exactly what happened before Y shifting existed.
    if total <= 0:
        problems.append(f"converted block count is {total}")
    if distinct <= 0:
        problems.append("no block types recorded")

    shift = report.get("shift") or {}
    print(f"Y shift applied: {shift.get('applied')}, amount: {shift.get('amountY')}")

    # Unmapped blocks are expected (1.12.2 has no warped/bamboo blocks), but the
    # built-in approximations should keep the outright losses at zero, and the
    # report must say how many were lost so the user can act on it.
    unmapped = report.get("unmapped") or {}
    lost = unmapped.get("blocksLost")
    print(f"blocks lost: {lost} (distinct unmapped: {unmapped.get('distinctUnmapped')})")
    if lost is None:
        problems.append("report does not record how many blocks were lost")

    changed = report.get("changed") or {}
    print(f"chunks written: {changed.get('chunksWritten')}, unchanged: {changed.get('chunksUnchanged')}")

    if problems:
        print("FAILED: " + "; ".join(problems))
        return 1

    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
