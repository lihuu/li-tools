---
name: dukit
description: Use when analyzing disk usage, finding large directories or files, exporting directory size data as JSON, or interactively browsing what consumes disk space on macOS, Linux, or Windows.
---

# dukit: Disk Usage Analyzer Guide

`dukit` is a cross-platform, zero-dependency CLI tool for analyzing disk usage, inspired by ncdu. It scans a directory tree once, reports sizes sorted descending, exports machine-readable JSON, and offers a line-based interactive browser. All sizes are apparent sizes (logical file sizes); hardlinked content counts once per scan, symlinks are never followed, and directory inodes add nothing.

## Trigger Conditions

Use this skill whenever the user or task requires:
1. Finding which directories or files consume the most disk space.
2. Analyzing what to clean up before freeing disk space.
3. Exporting per-directory size aggregates as JSON for automation, dashboards, or diffing.
4. Interactively drilling into large directories in a terminal.
5. Getting consistent disk usage numbers across macOS, Linux, and Windows.
6. Scanning with exclusions (e.g. skipping `node_modules`, `target`, `.git`).

## Installation

```bash
# Direct Script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s dukit

# Direct Script (Windows 11 PowerShell)
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/dukit

# Scoop (Windows)
scoop install dukit
```

## Core Commands

### `dukit scan [path] [--depth <n>] [--exclude <glob>...] [--json]`

Scans the directory tree once and prints a size-sorted report to stdout.

```bash
# Current directory, one level below the root (default)
dukit scan

# Two levels deep, sorted by size
dukit scan ~/Projects --depth 2

# Skip heavyweight dependency directories wherever they appear
dukit scan ~/Projects --exclude node_modules --exclude .git

# Machine-readable output for scripts and agents
dukit scan ~/Projects --json
```

- `--depth <n>` (default 1) cuts the *display* only; every printed size always covers its whole subtree.
- `--exclude` is repeatable. A bare name (`node_modules`) matches any path component; a pattern containing `/` (`a/*.log`) matches the path relative to the scan root.
- `--json` emits `{ "meta": { tool, version, path, files, directories, errors, generatedAt }, "tree": { name, size, files, directories, errors, children: [...] } }`. Every node appears in both the tree and its parent's `children`; files have empty `children`.
- Unreadable paths are reported as warnings on stderr (first 10) while the scan continues; the report itself goes to stdout.
- Exit codes: `0` success (including partial scans with warnings), `1` the target is missing or not a directory, `2` invalid usage.

### `dukit browse [path] [--exclude <glob>...]`

Scans, then enters a line-based interactive browser (requires an interactive terminal; piped usage exits `2` with a hint to use `scan`).

```bash
dukit browse ~/Projects
```

Interactive commands:
- `[enter]` or `ls` — re-render the current directory
- `cd <n>` — descend into entry `n` (directories only)
- `up` — parent directory; `top` — back to the scan root
- `sort size|name|count` — reorder the listing
- `q` or `quit` — leave (end of input also quits cleanly)

## Size Semantics (Important)

- **Apparent sizes**: the sum of logical file sizes. The JDK exposes no portable allocated-block metric, so dukit deliberately reports apparent sizes to keep numbers identical across filesystems. Compare with `du --apparent-size`.
- **Hardlinks**: content counts exactly once per scan (first occurrence); every hardlink still counts toward `files`.
- **Symlinks**: never followed, never sized, not counted as files.
- **Directories**: their own inode size is not added; a directory's size is purely its content.
- **Errors**: unreadable paths are skipped with a warning, never silently ignored, and counted in `errors` of the JSON output and the subtree that contains them.

## Examples

```bash
# What eats the most space under the user's home, three levels deep
dukit scan ~ --depth 3

# Find the biggest build artifacts across a workspace, ignoring sources
dukit scan . --exclude 'target' --exclude 'dist' --exclude '.git'

# Snapshot disk usage and diff it later with jq
dukit scan /var --json > /tmp/var-usage.json
# ... (disk fills up) ...
dukit scan /var --json > /tmp/var-usage-after.json
diff <(jq -r '.. | select(.name) | "\(.name) \(.size)"' /tmp/var-usage.json) \
     <(jq -r '.. | select(.name) | "\(.name) \(.size)"' /tmp/var-usage-after.json)
```

## Notes

- dukit v1 is read-only; it never deletes or modifies anything.
- The scan is sequential and single-pass; progress shows files, bytes, and the current path on interactive terminals only.
- All output respects `NO_COLOR` and `--no-color`; piped output contains no ANSI escapes.
