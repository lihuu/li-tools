#!/usr/bin/env python3
"""
Benchmark dukit's scan against the platform's du on the same trees.

This measures the shipped native executable (or any dukit binary), not the JVM: the
distribution target is the native image, and JIT warmup would otherwise dominate small
trees. Each command runs once to warm the page cache, then the requested number of times.
Wall time is reported as the median, because the first post-warmup run can still catch
filesystem work; CPU time is the median of the user+sys deltas reported for the children.

Usage:
    python3 scripts/bench-scan.py [PATH ...] [--runs N] [--binary PATH]

dukit is invoked as `dukit scan PATH --depth 0 --quiet`: the scan still walks and
aggregates the whole tree, while the report stays out of the measurement. The depth flag
only limits output, so this is the scan engine's cost, not the reporter's.
"""

import argparse
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import time

try:
    import resource
except ImportError:  # Windows: wall time is still measurable, CPU time is not exposed this way.
    resource = None

REPOSITORY = Path(__file__).resolve().parents[1]


def run_once(command):
    """Run one command. Returns (wall_ms, cpu_ms, user_ms, sys_ms) or None on failure."""
    before = resource.getrusage(resource.RUSAGE_CHILDREN) if resource is not None else None
    started = time.perf_counter()
    result = subprocess.run(
        [str(argument) for argument in command],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    wall_ms = (time.perf_counter() - started) * 1000.0
    if result.returncode != 0:
        print(f"command failed ({result.returncode}): {' '.join(map(str, command))}", file=sys.stderr)
        print(result.stderr.strip(), file=sys.stderr)
        return None
    if before is None:
        return wall_ms, None, None, None
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    user_ms = (after.ru_utime - before.ru_utime) * 1000.0
    sys_ms = (after.ru_stime - before.ru_stime) * 1000.0
    return wall_ms, user_ms + sys_ms, user_ms, sys_ms


def measure(command, runs):
    if run_once(command) is None:
        return None
    samples = [run_once(command) for _ in range(runs)]
    if any(sample is None for sample in samples):
        return None
    walls = [sample[0] for sample in samples]
    cpus = [sample[1] for sample in samples if sample[1] is not None]
    users = [sample[2] for sample in samples if sample[2] is not None]
    systems = [sample[3] for sample in samples if sample[3] is not None]
    return {
        "wall_median": statistics.median(walls),
        "wall_min": min(walls),
        "cpu_median": statistics.median(cpus) if cpus else None,
        "user_median": statistics.median(users) if users else None,
        "sys_median": statistics.median(systems) if systems else None,
    }


def format_row(label, result):
    if result["cpu_median"] is None:
        cpu = "-"
    else:
        cpu = (f"{result['cpu_median'] / 1000:.3f}s "
               f"({result['user_median'] / 1000:.3f}u+{result['sys_median'] / 1000:.3f}s)")
    return (f"{label:<34} {result['wall_median'] / 1000:>8.3f}s "
            f"{result['wall_min'] / 1000:>8.3f}s {cpu:>28}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", type=Path, help="directories to scan (default: current directory)")
    parser.add_argument("--runs", type=int, default=5, help="measured runs per command (default: 5)")
    parser.add_argument("--binary", type=Path, default=REPOSITORY / "dukit" / "target" / "dukit",
                        help="dukit executable (default: dukit/target/dukit)")
    parser.add_argument("--no-du", action="store_true", help="skip the du baseline")
    arguments = parser.parse_args()

    if arguments.runs < 1:
        parser.error("--runs must be positive")
    if not arguments.binary.is_file():
        parser.error(f"dukit binary not found: {arguments.binary} (build it first)")
    paths = [path.resolve() for path in arguments.paths] or [Path.cwd()]
    for path in paths:
        if not path.is_dir():
            parser.error(f"not a directory: {path}")

    du = None if arguments.no_du else shutil.which("du")
    if not arguments.no_du and du is None:
        print("du not found; measuring dukit only", file=sys.stderr)

    print(f"binary: {arguments.binary}")
    print(f"runs:   {arguments.runs} (after one warmup)")
    print(f"{'command':<34} {'wall med':>9} {'min':>9} {'cpu (user+sys)':>28}")
    for path in paths:
        print(f"\n{path}")
        dukit = measure([arguments.binary, "scan", path, "--depth", "0", "--quiet"], arguments.runs)
        if dukit is None:
            return 1
        baseline = measure([du, "-s", path], arguments.runs) if du else None
        print(format_row("dukit scan --depth 0", dukit))
        if baseline is not None:
            print(format_row("du -s", baseline))
            print(f"{'wall ratio (du / dukit)':<34} {baseline['wall_median'] / dukit['wall_median']:.2f}x")
    return 0


if __name__ == "__main__":
    sys.exit(main())
