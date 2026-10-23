---
name: skillkit
description: Use when managing, publishing, or distributing AI agent skills across multiple agents (Claude Code, Codex, Cursor, Gemini CLI, OpenCode, Antigravity, Hermes) with zero npm/npx dependencies.
---

# skillkit: AI Agent Skill Guide

`skillkit` is a modern, high-performance CLI tool for managing a personal skills collection as a distribution source. It installs own or external skills globally across all supported AI coding agents, updates them in parallel using Java 25 virtual threads, and publishes personal content plus upstream metadata to a Git remote without external `skills`, `npm`, or `npx` dependencies.

## Trigger Conditions

Use this skill whenever the user or task requires:
1. Installing an AI agent skill across all supported agents on the host in a single command.
2. Managing a personal skills repository (local authoring in `skills/` and upstream Git tracking in `skillkit.json`).
3. Updating installed skills in parallel with bounded virtual threads (`skillkit update --jobs 8`).
4. Publishing a personal skills collection to a remote Git repository (`skillkit push`) with scoped commits.
5. Distributing skills across machines by subscribing to a personal or upstream Git repository.
6. Auditing, listing, or cleanly uninstalling skills without touching original drafts or upstream records.

## Installation

```bash
# Direct Script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s skillkit

# Direct Script (Windows 11 PowerShell)
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/skillkit

# Scoop (Windows)
scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket
scoop install skillkit
```

---

## Command Reference

### 1. Install Skills (`install`)

Installs one or more skills globally across all supported agent directories simultaneously.

```bash
# Install own skill: automatically discovered from personal repo's skills/ directory
skillkit install my-skill

# Install third-party skill: automatically records upstream source in skillkit.json
skillkit install example-skill --repo owner/repo

# Install from specific branch, tag, or commit
skillkit install example-skill --repo owner/repo --ref main

# Install from local directory
skillkit install local-tool --repo /path/to/other-skills

# Batch parallel installation (default jobs = 4)
skillkit install skill-a skill-b skill-c --jobs 8

# Install as symlinks instead of copying content
skillkit install my-skill --symlink

# Dry-run preview
skillkit install my-skill --dry-run
```

### 2. Update Skills (`update`)

Updates all currently installed skills managed by the personal repository, or specific named skills, in parallel.

```bash
# Update all installed skills
skillkit update

# Update specific skills with increased concurrency
skillkit update example-skill my-skill --jobs 8

# Preview pending updates
skillkit update --dry-run
```

### 3. Uninstall Skills (`uninstall`)

Removes skills from all global agent targets while safely preserving personal originals in `skills/` and upstream configurations in `skillkit.json`.

```bash
# Uninstall from all agent roots
skillkit uninstall old-skill

# Dry-run preview
skillkit uninstall old-skill --dry-run
```

### 4. List Installed Skills (`list`)

Lists all installed skills, showing their installation mode (copy/symlink), source, resolved commit revision, and targets.

```bash
# Human-readable table
skillkit list

# Machine-readable JSON output for Agent parsing
skillkit list --json
```

### 5. Publish Personal Repository (`push`)

Automatically commits additions, modifications, and deletions strictly within `skills/` and `skillkit.json`, and pushes the current branch to origin.

```bash
# Push with default commit message
skillkit push

# Push with custom commit message
skillkit push -m "feat: add docker-optimizer and update web-search"

# Initialize Git (main branch) and set remote origin on first push
skillkit push --repo owner/my-skills

# Dry-run preview (verifies without committing, altering Git config, or pushing)
skillkit push --dry-run
```

---

## Dual Content Architecture

1. **Own / Customized Skills (`skills/`)**:
   Place own skills directly under `skills/<skill-name>/` (containing `SKILL.md`). Category subdirectories are supported. No registration or import command is required.
2. **Third-Party Upstreams (`skillkit.json`)**:
   Installing external skills via `--repo owner/repo` automatically records source metadata in `<repository>/skillkit.json`:
   ```json
   {
     "version": 1,
     "upstreams": [
       {
         "name": "example",
         "source": "owner/repo",
         "path": "skills/example",
         "ref": "main",
         "revision": "a1b2c3d4..."
       }
     ]
   }
   ```
   Other machines cloning or consuming your repository can install these upstream skills directly.

---

## Supported AI Agent Targets

A single `install` command distributes skills to all of the following agent directories:

| Agent | Target Directory |
| :--- | :--- |
| Codex, Pi, ZCode, Shared Agents | `~/.agents/skills/` |
| Claude Code | `${CLAUDE_CONFIG_DIR:-~/.claude}/skills/` |
| Qwen Code | `~/.qwen/skills/` |
| Cursor | `~/.cursor/skills/` |
| Gemini CLI | `~/.gemini/skills/` |
| Hermes | `${HERMES_HOME:-~/.hermes}/skills/` |
| OpenCode | `${XDG_CONFIG_HOME:-~/.config}/opencode/skills/` |
| Antigravity | `~/.gemini/antigravity/skills/` |

---

## Key Safety & Design Tenets

- **No Node/npm/npx**: Pure native binary with zero runtime dependencies.
- **Unrelated Work Protection**: `skillkit push` only commits `skills/` and `skillkit.json`, leaving any other staged files in the Git workspace untouched.
- **Local Modification Defense**: If an installed skill directory has been manually modified, `skillkit` refuses to overwrite it during update or uninstall without explicit user action.
- **Virtual Thread Concurrency**: Source downloads and evaluations run concurrently using Java 25 virtual threads with file-based locking (`.skillkit.lock`) to prevent race conditions.
