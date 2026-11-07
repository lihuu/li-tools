---
name: li-tools
description: Use when managing developer security tooling, GPG commit signing keys (gpgkit), SSL/TLS certificates or local root CAs (certkit), encrypted API tokens/keys (tokenkit), personal AI agent skills distribution (skillkit), JSON formatting and RFC 9535 querying (jsonkit), and cross-platform disk usage analysis (dukit) across macOS, Linux, and Windows 11.
---

# li-tools: Suite AI Agent Skill Guide

`li-tools` is an open-source, AI-first developer toolchain containing:
1. **`gpgkit`**: One command for GPG key generation, Git commit signing, file signing, verification, and backup.
2. **`certkit`**: One command for local development SSL/TLS certificates, root CA creation, multi-SAN generation, format conversion (PEM/P12/DER), and automated system trust store installation.
3. **`tokenkit`**: Modern encrypted universal token manager with embedded SQLite, sub-10ms shell injection (`tokenkit env`), process isolation (`tokenkit run`), and cross-machine migration.
4. **`skillkit`**: Personal skills repository manager and parallel distribution CLI for 10+ AI coding agents (Claude Code, Codex, Cursor, Gemini CLI, OpenCode, Antigravity, etc.) with zero npm/npx bloat.
5. **`jsonkit`**: JSON formatter and RFC 9535 JSONPath query CLI: pretty-print files or pipes with terminal highlighting, select single values, wildcard, slice, recursive descent or filter, and emit plain JSON for agents.
6. **`dukit`**: Cross-platform disk usage analyzer: size-sorted scan reports, machine-readable JSON export, and a line-based interactive browser with identical apparent-size numbers on every OS.

Use `--help` and `--version` to identify the installed interface. JSON is command-specific. For example, use `certkit check --json`, `dukit scan` (JSON by default), `gpgkit status --json`, `skillkit list --json`, or `tokenkit list --json`. Use `jsonkit -p <path>` to select values from any of those outputs. For secrets, prefer `tokenkit run`; `get` and `env` can emit plaintext.

---

## When to Use Which Tool

| Developer Task | Recommended Command | Tool |
|---|---|---|
| Configure Git commit signing for GitHub/GitLab | `gpgkit gen --uid "Name <email>"` & `gpg --armor --export <fingerprint>` | `gpgkit` |
| Sign / verify release artifacts, tarballs, or text | `gpgkit sign <file>` & `gpgkit verify <file>` | `gpgkit` |
| Backup or restore GPG secret keys to a new machine | `gpgkit backup` & `gpgkit restore <backup-file>` | `gpgkit` |
| Create HTTPS "green lock" certs for local development | `certkit ca --trust` & `certkit gen localhost 127.0.0.1` | `certkit` |
| Inspect a cert file or live remote HTTPS connection | `certkit check <file>` or `certkit check <host:443>` | `certkit` |
| Convert between PEM, P12/PFX, and DER for Java / Docker | `certkit convert cert.pem bundle.p12 --key key.pem` | `certkit` |
| Store API keys encrypted without plaintext in .env or .zshrc | `tokenkit set OPENAI_API_KEY` (masked prompt) | `tokenkit` |
| Inject decrypted tokens into current shell session sub-10ms | `eval "$(tokenkit env)"` | `tokenkit` |
| Run isolated child command with decrypted tokens | `tokenkit run -- npm run dev` | `tokenkit` |
| Copy token to clipboard without displaying on screen | `tokenkit get GITHUB_TOKEN --clip` | `tokenkit` |
| Export/import encrypted token bundles across machines | `tokenkit export` & `tokenkit import <bundle>` | `tokenkit` |
| Install into shared skills or selected Agent directories | `skillkit install <name> [--agent <target>] [--repo owner/repo]` | `skillkit` |
| Parallel update all installed skills across all agents | `skillkit update --jobs 8` | `skillkit` |
| Publish personal skills repository with scoped commit | `skillkit push [-m "msg"]` | `skillkit` |
| Find what consumes the most disk space | `dukit top <path> --json` | `dukit` |
| Export directory sizes as JSON for automation | `dukit scan <path> --json` | `dukit` |
| Interactively browse big directories | `dukit browse <path>` | `dukit` |
| Pretty-print or validate a JSON file or pipe | `jsonkit response.json` | `jsonkit` |
| Extract one field or many values from JSON | `jsonkit -p "items[?@.status == 'failed'].id" response.json` | `jsonkit` |

---

## Installation via AI Agent

### 1. One-Shot Agent Prompt
If an AI agent needs to install the tools in a workspace:
```text
Please install li-tools (gpgkit, certkit, tokenkit, skillkit, jsonkit, dukit):
- Homebrew (macOS/Linux): `brew tap lihuu/tap && brew install gpgkit certkit tokenkit skillkit jsonkit dukit`
- Scoop (Windows): `scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket && scoop install gpgkit certkit tokenkit skillkit jsonkit dukit`
- macOS / Linux Shell: `curl -fsSL https://tools.lihu.me/install.sh | bash`
- Windows PowerShell: `irm https://tools.lihu.me/install.ps1 | iex`
- Verify with `gpgkit --version`, `certkit --version`, `tokenkit --version`, `skillkit --version`, `jsonkit --version`, and `dukit --version`.
```

### 2. Install Agent Skills Directly
The installer defaults to `~/.agents/skills/`. Use the installer help to select a different target.
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
- **skillkit Skill**: `https://tools.lihu.me/skillkit/SKILL.md`
- **jsonkit Skill**: `https://tools.lihu.me/jsonkit/SKILL.md`
- **dukit Skill**: `https://tools.lihu.me/dukit/SKILL.md`
