---
name: li-tools
description: Use when managing developer security tooling, SSL/TLS certificates (certkit), disk usage analysis (dukit), GPG keys (gpgkit), AI agent skills (skillkit), and encrypted tokens (tokenkit) across macOS, Linux, and Windows 11.
---

# li-tools: Suite AI Agent Skill Guide

`li-tools` is an open-source, AI-first developer security suite powered by a unified CLI shell (`cli-common`):
1. **`certkit`**: One command for local development SSL/TLS certificates, root CA creation, multi-SAN generation, format conversion (PEM/P12/DER), and automated system trust store installation.
2. **`dukit`**: One command for cross-platform disk usage analysis, ncdu-inspired full-screen TUI, occupied space by default via FFM `st_blocks`, safe Trash deletion, and streaming JSON export.
3. **`gpgkit`**: One command for GPG key generation, Git commit signing, file signing, verification, and backup.
4. **`skillkit`**: One command for managing personal skills repositories and parallel fanout distribution to 10+ AI coding agents.
5. **`tokenkit`**: One command for encrypted local API key / secret management, shell environment injection (`tokenkit env`), and cross-machine backup/restore.

All tools share an identical interaction contract powered by `cli-common`:
- **Unified Global Options**: `--json` (machine-readable streaming JSON for Agent consumption), `--quiet` (minimal output), `--no-color` ([NO_COLOR](https://no-color.org/) compliant), `--debug` (detailed diagnostics).
- **Strict Exit Code Contract**: `0` = Success, `1` = Operation failure (single-line actionable stderr, no raw unhandled stack traces), `2` = Usage/syntax error.
- **Zero Config Agony**: Instant, sensible defaults (Ed25519 for GPG, ECDSA P-256 for TLS, SQLite + AES-256-GCM for tokens).
- **Triple-Platform Equity**: Native performance and identical CLI contracts across macOS (Apple Silicon & Intel), Linux (x86_64), and Windows 11.

---

## When to Use Which Tool

| Developer Task | Recommended Command | Tool |
|---|---|---|
| Create HTTPS "green lock" certs for local development | `certkit ca` & `certkit gen localhost 127.0.0.1` | `certkit` |
| Inspect a cert file or live remote HTTPS connection | `certkit check <file>` or `certkit check <host:443>` | `certkit` |
| Convert between PEM, P12/PFX, and DER for Java / Docker | `certkit convert <file> --to p12 --password <pw>` | `certkit` |
| Scan disk space and browse interactive full-screen TUI | `dukit` or `dukit scan <dir>` | `dukit` |
| Export streaming directories-only JSON disk tree | `dukit export <dir> --json` | `dukit` |
| Configure Git commit signing for GitHub/GitLab | `gpgkit gen "Name <email>"` & `gpgkit export <email>` | `gpgkit` |
| Sign / verify release artifacts, tarballs, or text | `gpgkit sign <file>` & `gpgkit verify <file>` | `gpgkit` |
| Backup or restore GPG secret keys to a new machine | `gpgkit backup` & `gpgkit restore` | `gpgkit` |
| Manage personal skills and fanout to 10+ AI agents | `skillkit install <name>` or `skillkit update` | `skillkit` |
| Store API keys, tokens, or passwords securely | `tokenkit set <name> <value>` | `tokenkit` |
| Inject project tokens into shell or run process | `eval "$(tokenkit env -p <profile>)"` or `tokenkit run -p <profile> -- <cmd>` | `tokenkit` |
| Backup or migrate encrypted tokens across machines | `tokenkit backup` & `tokenkit restore <bundle.enc>` | `tokenkit` |

---

## Installation via AI Agent

### 1. One-Shot Agent Prompt
If an AI agent needs to install the tools in a workspace:
```text
Please install li-tools (certkit, dukit, gpgkit, skillkit & tokenkit):
- Homebrew (macOS/Linux): `brew tap lihuu/tap && brew install certkit dukit gpgkit skillkit tokenkit`
- Scoop (Windows): `scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket && scoop install certkit dukit gpgkit skillkit tokenkit`
- macOS / Linux Shell: `curl -fsSL https://tools.lihu.me/install.sh | bash`
- Windows PowerShell: `irm https://tools.lihu.me/install.ps1 | iex`
- Verify with `certkit --version`, `dukit --version`, `gpgkit --help`, `skillkit --version`, and `tokenkit --version`.
```

### 2. Install Agent Skills Directly
Install skills into your AI agent environment (`~/.agents/skills/`, `~/.gemini/skills/`, `~/.claude/skills/`, or local `.ai/skills/`):
```bash
# macOS / Linux:
curl -fsSL https://tools.lihu.me/install-skills.sh | bash

# Windows PowerShell:
irm https://tools.lihu.me/install-skills.ps1 | iex
```

---

## Dedicated Sub-Skills
- **certkit Skill**: `https://tools.lihu.me/certkit/SKILL.md`
- **dukit Skill**: `https://tools.lihu.me/dukit/SKILL.md`
- **gpgkit Skill**: `https://tools.lihu.me/gpgkit/SKILL.md`
- **skillkit Skill**: `https://tools.lihu.me/skillkit/SKILL.md`
- **tokenkit Skill**: `https://tools.lihu.me/tokenkit/SKILL.md`
