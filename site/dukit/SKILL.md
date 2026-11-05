---
name: dukit
description: Use when analyzing disk usage, finding the largest directories or files, exporting directory size data as JSON, or browsing what consumes disk space on macOS, Linux, or Windows.
---

# dukit: Disk Usage Analyzer Guide

`dukit` is a cross-platform, zero-dependency CLI tool for analyzing disk usage, inspired by ncdu. It scans a directory tree once and answers three questions: what does the whole tree look like (JSON), what are the biggest directories or files (top), and how do I explore it interactively (browse).

**The tree contains directories only.** Regular files are folded into their parent directory's counters (`ownFileSize`, `ownFileCount`) instead of becoming nodes. This keeps output proportional to the directory count rather than the file count — on a real `/Applications` scan that is 87k nodes instead of 660k. Use `top --files` for a ranked file report, or press `t` in the browser to reveal the current directory's largest direct files.

## Trigger Conditions

Use this skill whenever the user or task requires:
1. Finding which directories or files consume the most disk space.
2. Deciding what to clean up before freeing disk space.
3. Exporting per-directory size aggregates as JSON for automation or diffing.
4. Drilling into a large directory interactively in a terminal.
5. Getting consistent disk usage numbers across macOS, Linux, and Windows.

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

## Default Gesture

Run it with no subcommand in a terminal and it opens the full-screen browser directly:

```bash
dukit                 # browse the current directory
dukit /Applications   # browse a specific directory
dukit | head -c 500   # piped: a JSON report instead, no error
```

Subcommands are for when you want one specific shape of output.

## Commands

### `dukit scan [path] [--depth <n>] [--exclude <glob>...] [--text]`

Scans the tree and prints every directory with its size. **Output is JSON by default.**

```bash
dukit scan /Applications              # full directory tree as JSON (every level)
dukit scan /Applications --depth 2    # 2 levels: ~30 KB instead of ~11 MB
dukit scan /Applications --text       # human-readable tree instead of JSON
dukit scan . --exclude node_modules --exclude .git
```

- `--depth <n>` limits how many levels of `children` are emitted. **Aggregates always cover the whole subtree**, so a depth-cut tree still reports correct totals. Default `-1` = every level; `0` = root only. This is the cheapest way to keep a payload small.
- `--exclude` is repeatable. A bare name matches any path component; a pattern containing `/` matches relative to the scan root.
- `--text` prints an indented tree for humans; `--depth` applies there too.

JSON shape:

```json
{
  "meta": { "tool", "version", "path", "size", "files", "directories", "errors", "generatedAt" },
  "tree": {
    "name": "Applications",
    "size": 69664451262,          // whole subtree
    "files": 573263,              // whole subtree
    "directories": 87482,         // whole subtree
    "errors": 0,
    "ownFileSize": 27600,         // files directly inside this directory
    "ownFileCount": 3,
    "children": [ ... ]           // subdirectories only
  }
}
```

### `dukit top [path] [--limit <n>] [--files] [--min-size <size>] [--json]`

Ranks the largest entries. Streams the scan with bounded heaps, so memory stays proportional to `--limit`, not to the volume — this is the right command for "what is big" questions.

```bash
dukit top /Applications                    # 20 largest directories (default)
dukit top /Applications --files            # 20 largest individual files
dukit top /Applications --limit 50 --json  # machine-readable, ~4 KB
dukit top /Applications --min-size 1G      # only entries at least 1 GiB
```

- Default output is a human-readable ranked list; `--json` emits `{ meta, entries: [{ path, size }] }`.
- `--limit` defaults to 20. `--min-size` accepts `B`, `K/KiB`, `M/MiB`, `G/GiB`, `T/TiB`.
- The scan root itself is never ranked (it is the query, not a discovery).
- Rankings may contain nested paths (a directory and its child can both appear). That is intentional: it answers "where is the space" globally. For a layered view use `scan --depth`.

### `dukit browse [path] [--exclude <glob>...]`

Scans, then opens a full-screen browser on the terminal's alternate screen (like ncdu or
lazygit). Requires an interactive terminal; piped usage exits `2` with a hint to use `scan`.

```
↑ / ↓ or k / j   move the selection
enter / → / l    descend into the selected directory
backspace / ←    return to the parent
home / end       jump to the first or last entry
page up / down   move a screenful
s                cycle sort: size -> name -> count
t                show/hide the current directory's largest direct files
q or ctrl-c      quit
```

The listing always starts sorted by size, largest first, with the first entry selected.
Pressing `t` appends up to ten largest regular files that sit directly in the current
directory, largest first; those rows are display-only (Enter does nothing on them). The scan
retains this bounded preview only for the browser — `scan` JSON stays directories-only, so
its payload does not grow. Empty directories are still enterable — that is how you find out
they are empty. A terminal that cannot be driven in raw mode falls back to a line-based
browser automatically.

JLine supplies the platform work (raw mode, key decoding, terminal size) and its FFM
provider drives the Windows console through the Foreign Function & Memory API, so the same
full-screen UI runs on macOS, Linux, and Windows, including in the native binary.

## Size Semantics (Important)

- **Apparent sizes**: the sum of logical file sizes. The JDK exposes no portable allocated-block metric, so dukit reports apparent sizes to keep numbers identical across filesystems. Compare with `du --apparent-size`.
- **Hardlinks**: content counts exactly once per scan (first occurrence); every hardlink still counts toward `files`. **This requires a file key, which java.nio.file does not expose on Windows** — there each link's content is counted separately. macOS and Linux de-duplicate correctly.
- **Symlinks**: never followed, never sized, not counted as files. A symlinked *root* is resolved before scanning.
- **Directories**: only their contents contribute; the directory inode's own size is not added.
- **Errors**: unreadable paths are skipped with a warning on stderr, never silently ignored, and counted in `errors`.

## Agent Recipes

```bash
# Step 1: which top-level directories are big? (small payload, fast to reason about)
dukit scan /Applications --depth 1

# Step 2: drill into the biggest one, one more level
dukit scan /Applications/Xcode-beta.app --depth 2

# Or skip straight to the answer: the 20 biggest directories anywhere
dukit top /Applications --limit 20 --json

# Find the files worth deleting
dukit top /Applications --files --limit 50 --min-size 500M --json

# Snapshot and diff two scans over time
dukit scan /var --depth 3 > before.json
# ... time passes ...
dukit scan /var --depth 3 > after.json
diff <(jq -r '.tree | .. | objects | "\(.name) \(.size)"' before.json) \
     <(jq -r '.tree | .. | objects | "\(.name) \(.size)"' after.json)
```

## Exit Codes

- `0` success (including partial scans with unreadable paths)
- `1` target missing or not a directory
- `2` invalid CLI usage, or `browse` without an interactive terminal

## Notes

- dukit can move directories to the system Trash, but **only from the interactive browser**: `d` in the full-screen UI, `delete <n>` in the line-based one. There is no CLI delete command, no `--yes`/`--dry-run`, and dukit never requests a permanent delete. On Windows the target goes to the Recycle Bin; when the Recycle Bin is disabled or full, the shell may warn and then delete permanently, which dukit does not suppress.
- Output respects `NO_COLOR` and `--no-color`; piped output contains no ANSI escapes.
- Traversal is parallel and adaptive: wide directory levels fan out across workers, narrow ones stay inline, so small trees pay no penalty. Measured on a 59 GB / 660k-file tree: 3.2s versus ncdu's 8s.
- Progress shows files, bytes, and the current path on interactive terminals only.
