---
name: li-tools
description: Use when managing developer security tooling, GPG commit signing keys (gpgkit), and SSL/TLS certificates or local root CAs (certkit) across macOS, Linux, and Windows 11.
---

# li-tools: Suite AI Agent Skill Guide

`li-tools` is an open-source, AI-first developer security suite containing:
1. **`gpgkit`**: One command for GPG key generation, Git commit signing, file signing, verification, and backup.
2. **`certkit`**: One command for local development SSL/TLS certificates, root CA creation, multi-SAN generation, format conversion (PEM/P12/DER), and automated system trust store installation.

Both tools share identical design principles:
- **Zero Config Agony**: Instant, sensible defaults (Ed25519 for GPG, ECDSA P-256 for TLS).
- **Machine-Readable**: All commands support `--json` for predictable automation and Agent consumption.
- **Escape Hatch**: Both tools support `raw -- ...` for passing unadulterated arguments directly to `gpg` or `openssl`.
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

---

## Installation via AI Agent

### 1. One-Shot Agent Prompt
If an AI agent needs to install the tools in a workspace:
```text
Please install li-tools (gpgkit & certkit):
- Homebrew (macOS/Linux): `brew tap lihuu/tap && brew install gpgkit certkit`
- Scoop (Windows): `scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket && scoop install gpgkit certkit`
- macOS / Linux Shell: `curl -fsSL https://lihuu.github.io/li-tools/install.sh | bash`
- Windows PowerShell: `irm https://lihuu.github.io/li-tools/install.ps1 | iex`
- Verify with `gpgkit --help` and `certkit --version`.
```

### 2. Install Agent Skills Directly
Install skills into your AI agent environment (`~/.agents/skills/`, `~/.gemini/skills/`, `~/.claude/skills/`, or local `.ai/skills/`):
```bash
# macOS / Linux:
curl -fsSL https://lihuu.github.io/li-tools/install-skills.sh | bash

# Windows PowerShell:
irm https://lihuu.github.io/li-tools/install-skills.ps1 | iex
```

---

## Dedicated Sub-Skills
- **gpgkit Skill**: `https://lihuu.github.io/li-tools/gpgkit/SKILL.md`
- **certkit Skill**: `https://lihuu.github.io/li-tools/certkit/SKILL.md`
