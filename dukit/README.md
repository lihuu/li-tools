# dukit

> **Cross-Platform Disk Usage Analyzer: Full-screen interactive TUI, bounded streaming rankings, and directories-only JSON export — find what is eating your disk.**  
> Built with Java 25 & GraalVM Native Image. Zero runtime dependencies, identical behavior across macOS, Linux, and Windows 11.

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/dukit/"><b>Landing Page</b></a> •
  <a href="../README.md"><b>li-tools Suite</b></a>
</p>

---

## ⚡ Why dukit?

When investigating disk usage, developers and automated agents usually face distinct pain points:
- **`ncdu` is tied to ncurses**: It cannot run natively on Windows without WSL or Cygwin, and piping its results into automated scripts or AI workflows is cumbersome.
- **`du` semantics diverge across platforms**: BSD `du` on macOS and GNU `du` on Linux use different flags. Furthermore, sparse files and Copy-on-Write (CoW) filesystems cause apparent file size to differ by orders of magnitude from actual occupied disk space.
- **Node explosion in large trees**: When scanning trees with hundreds of thousands of files (e.g. `/Applications`, large `node_modules`), emitting every file creates multi-megabyte payloads that saturate LLM context windows and crash parsers.

`dukit` solves all of these:
- **Directories-Only Tree Model**: Regular files fold into parent directory metadata (`ownFileSize`, `ownFileCount`). An 87,000-directory tree takes just ~11 MB in JSON (or ~30 KB at `--depth 2`), while aggregates still cover the entire subtree.
- **Accurate Occupied Disk Space**: Reads native `st_blocks` via Java 25 Foreign Function & Memory (FFM) API, matching `du` and Finder out of the box.
- **Full-Screen Interactive TUI Browser**: Alternate screen buffer powered by JLine FFM, working identically across macOS, Linux, and Windows 11 console.
- **Safe Trash Deletion**: Press `d` in the TUI to inspect a modal confirmation dialog and move subtrees safely to the OS Trash (never permanent delete).
- **Direct Files Preview**: Press `t` to reveal up to 10 largest direct files in the current directory without bloating the scan tree.
- **Bounded Streaming Top-N**: Evaluates the biggest directories or files using fixed-size min-heaps, keeping memory constant regardless of disk size.

---

## 🚀 30-Second Quick Start

### 1. Default Interactive Gesture
```bash
# Browse current directory in full-screen TUI
dukit

# Browse a specific directory
dukit /Applications
```

> **Tip**: When output is redirected or piped (e.g. `dukit | jq .`), `dukit` automatically detects non-TTY environments and prints a structured JSON tree without hanging.

### 2. Export Structured JSON Tree (`scan`)
```bash
# Full subtree as pure streaming JSON (directories only)
dukit scan ~/Projects

# Depth cut: Emit top 2 directory levels only (aggregates still describe whole subtrees)
dukit scan ~/Projects --depth 2

# Output indented human-readable text tree
dukit scan ~/Projects --text

# Exclude specific patterns
dukit scan . --exclude node_modules --exclude .git
```

### 3. Streaming Bounded Top Rankings (`top`)
```bash
# Top 20 largest directories (constant memory)
dukit top ~/Projects

# Top 20 largest individual files
dukit top ~/Projects --files

# Only entries of at least 1 GiB, emitted as JSON
dukit top ~/Projects --min-size 1G --json
```

---

## ⌨️ Interactive Browser Keybindings (`browse`)

Run `dukit` or `dukit browse` in any interactive terminal:

| Key | Action |
| :--- | :--- |
| `↑` / `k` | Move cursor up |
| `↓` / `j` | Move cursor down |
| `Enter` / `→` / `l` | Descend into highlighted directory |
| `Backspace` / `←` | Return to parent directory |
| `Home` / `End` | Jump to first / last item |
| `PageUp` / `PageDown` | Scroll one page up / down |
| `s` | Cycle sort order: Size → Name → Count |
| `t` | **Show / hide current directory's top 10 direct files** (display only) |
| `d` | **Move directory to system Trash** (review modal dialog, press `y` to confirm) |
| `q` / `Ctrl+C` | Cleanly exit full-screen mode |

---

## 📊 Size Semantics

| Metric | Meaning | Behavior |
| :--- | :--- | :--- |
| **`diskSize`** | **Actual Occupied Space** | Default everywhere. Reads `st_blocks` through FFM API. Matches `du` and OS file managers. |
| **`size`** | **Apparent (Logical) Size** | Sum of logical byte lengths. Available via `--apparent-size` or in JSON. |

- **Hardlinks**: Shared content is counted once per scan; all hardlinks still contribute to file counts.
- **Symlinks**: Never followed recursively; symlinked scan root is resolved before traversal.
- **Unreadable paths**: Warned on stderr, never aborted silently, counted in metadata `errors`.

---

## 📦 Installation

```bash
# macOS & Linux one-line installer
curl -fsSL https://tools.lihu.me/install.sh | bash -s dukit

# Windows 11 PowerShell one-line installer
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/dukit

# Scoop (Windows 11)
scoop install dukit
```
