---
name: li-tools
description: Use when managing developer security tooling, GPG commit signing keys (gpgkit), SSL/TLS certificates or local root CAs (certkit), and encrypted API tokens/credentials (tokenkit) across macOS, Linux, and Windows 11.
---

# li-tools: Suite AI Agent Skill Guide

`li-tools` is an open-source, AI-first developer security suite powered by a unified CLI shell (`cli-common`):
1. **`gpgkit`**: One command for GPG key generation, Git commit signing, file signing, verification, and backup.
2. **`certkit`**: One command for local development SSL/TLS certificates, root CA creation, multi-SAN generation, format conversion (PEM/P12/DER), and automated system trust store installation.
3. **`tokenkit`**: One command for encrypted local API key / secret management, shell environment injection (`tokenkit env`), and cross-machine backup/restore.

All tools share an identical interaction contract powered by `cli-common`:
- **Unified Global Options**: `--json` (machine-readable streaming JSON for Agent consumption), `--quiet` (minimal output), `--no-color` ([NO_COLOR](https://no-color.org/) compliant), `--debug` (detailed diagnostics).
- **Strict Exit Code Contract**: `0` = Success, `1` = Operation failure (single-line actionable stderr, no raw unhandled stack traces), `2` = Usage/syntax error.
- **Zero Config Agony**: Instant, sensible defaults (Ed25519 for GPG, ECDSA P-256 for TLS, SQLite + AES-256-GCM for tokens).
- **Triple-Platform Equity**: Native performance and identical CLI contracts across macOS (Apple Silicon & Intel), Linux (x86_64), and Windows 11.

---

## When to Use Which Tool

| Developer Task | Recommended Command | Tool |
|---|---|---|
| Configure Git commit signing for GitHub/GitLab | `gpgkit gen "Name <email>"` & `gpgkit export <email>` | `gpgkit` |
| Sign / verify release artifacts, tarballs, or text | `gpgkit sign <file>` & `gpgkit verify <file>` | `gpgkit` |
| Backup or restore GPG secret keys to a new machine | `gpgkit backup` & `gpgkit restore` | `gpgkit` |
| Create HTTPS "green lock" certs for local development | `certkit ca` & `certkit gen localhost 127.0.0.1` | `certkit` |
| Inspect a cert file or live remote HTTPS connection | `certkit check <file>` or `certkit check <host:443>` | `certkit` |
| Convert between PEM, P12/PFX, and DER for Java / Docker | `certkit convert <file> --to p12 --password <pw>` | `certkit` |
| Store API keys, tokens, or passwords securely | `tokenkit set <name> <value>` | `tokenkit` |
| Inject project tokens into shell or run process | `eval "$(tokenkit env -p <profile>)"` or `tokenkit run -p <profile> -- <cmd>` | `tokenkit` |
| Backup or migrate encrypted tokens across machines | `tokenkit backup` & `tokenkit restore <bundle.enc>` | `tokenkit` |

---

## Installation via AI Agent

### 1. One-Shot Agent Prompt
If an AI agent needs to install the tools in a workspace:
```text
Please install li-tools (gpgkit & certkit):
- Homebrew (macOS/Linux): `brew tap lihuu/tap && brew install gpgkit certkit`
- Scoop (Windows): `scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket && scoop install gpgkit certkit`
- macOS / Linux Shell: `curl -fsSL https://tools.lihu.me/install.sh | bash`
- Windows PowerShell: `irm https://tools.lihu.me/install.ps1 | iex`
- Verify with `gpgkit --help` and `certkit --version`.
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
- **gpgkit Skill**: `https://tools.lihu.me/gpgkit/SKILL.md`
- **certkit Skill**: `https://tools.lihu.me/certkit/SKILL.md`
