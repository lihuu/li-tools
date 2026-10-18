---
name: li-tools
description: Use when managing developer security tooling, GPG commit signing keys (gpgkit), SSL/TLS certificates or local root CAs (certkit), and encrypted API tokens/keys (tokenkit) across macOS, Linux, and Windows 11.
---

# li-tools: Suite AI Agent Skill Guide

`li-tools` is an open-source, AI-first developer security suite containing:
1. **`gpgkit`**: One command for GPG key generation, Git commit signing, file signing, verification, and backup.
2. **`certkit`**: One command for local development SSL/TLS certificates, root CA creation, multi-SAN generation, format conversion (PEM/P12/DER), and automated system trust store installation.
3. **`tokenkit`**: Modern encrypted universal token manager with embedded SQLite, sub-10ms shell injection (`tokenkit env`), process isolation (`tokenkit run`), and cross-machine migration.

All tools share identical design principles:
- **Zero Config Agony**: Instant, sensible defaults (Ed25519 for GPG, ECDSA P-256 for TLS, AES-256-GCM + PBKDF2 for Tokens).
- **Machine-Readable**: All commands support `--json` for predictable automation and Agent consumption.
- **Escape Hatch**: Full transparency and control over underlying cryptographic operations.
- **Triple-Platform Equity**: Native performance and identical CLI contracts across macOS (Apple Silicon & Intel), Linux (x86_64 & arm64), and Windows 11.

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
| Store API keys encrypted without plaintext in .env or .zshrc | `tokenkit set OPENAI_API_KEY <val>` | `tokenkit` |
| Inject decrypted tokens into current shell session sub-10ms | `eval "$(tokenkit env)"` | `tokenkit` |
| Run isolated child command with decrypted tokens | `tokenkit run -- npm run dev` | `tokenkit` |
| Copy token to clipboard without displaying on screen | `tokenkit get GITHUB_TOKEN --clip` | `tokenkit` |
| Export/import encrypted token bundles across machines | `tokenkit export` & `tokenkit import <bundle>` | `tokenkit` |

---

## Installation via AI Agent

### 1. One-Shot Agent Prompt
If an AI agent needs to install the tools in a workspace:
```text
Please install li-tools (gpgkit, certkit, tokenkit):
- Homebrew (macOS/Linux): `brew tap lihuu/tap && brew install gpgkit certkit tokenkit`
- Scoop (Windows): `scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket && scoop install gpgkit certkit tokenkit`
- macOS / Linux Shell: `curl -fsSL https://tools.lihu.me/install.sh | bash`
- Windows PowerShell: `irm https://tools.lihu.me/install.ps1 | iex`
- Verify with `gpgkit --help`, `certkit --version`, and `tokenkit --version`.
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
- **tokenkit Skill**: `https://tools.lihu.me/tokenkit/SKILL.md`
