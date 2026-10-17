---
name: gpgkit
description: Use when generating, exporting, signing, verifying, or backing up GPG keys without gnupg configuration complexity.
---

# gpgkit: AI Agent Skill Guide

`gpgkit` is an ergonomic, zero-dependency CLI wrapper for GnuPG (`gpg`). It eliminates configuration agony, key ID hunting, and arcane command flags.

## Trigger Conditions

Use this skill whenever the user or task requires:
1. Generating personal or service GPG keys (supports Ed25519, RSA4096).
2. Exporting public keys for GitHub / GitLab commit signing verification.
3. Signing commits, files, releases, or git tags.
4. Verifying detached or clearsigned signatures.
5. Safely creating encrypted backups or restoring GPG keyrings.
6. Auditing environment readiness and GnuPG installation status.

## Installation

```bash
# Homebrew (macOS & Linux)
brew install lihuu/tap/gpgkit

# Direct Script (macOS & Linux)
curl -fsSL https://lihuu.github.io/li-tools/install.sh | bash -s gpgkit
```

---

## Command Reference

### 1. Environment Doctor
Verify whether `gpg` is installed and ready:
```bash
gpgkit doctor
gpgkit doctor --json
```

### 2. Generate a GPG Key
Generate a modern Ed25519 key (default, recommended):
```bash
gpgkit gen "Alice Developer <alice@example.com>"
```
Or specify RSA 4096-bit:
```bash
gpgkit gen --algo rsa4096 "Alice Developer <alice@example.com>"
```
Non-interactive / machine-readable JSON output:
```bash
gpgkit gen --json "Alice Developer <alice@example.com>"
```
Output provides:
- `fingerprint`: Full 40-character hex fingerprint.
- `keyId`: Short 16-character hex Key ID (for git config).
- `algorithm`: e.g. `ed25519` or `rsa4096`.

### 3. Git Commit Signing Setup
Directly export the ASCII-armored public key to paste into GitHub/GitLab:
```bash
gpgkit export alice@example.com
```
Configure Git automatically with the generated Key ID:
```bash
git config --global user.signingkey <KEY_ID>
git config --global commit.gpgsign true
```

### 4. Sign Files
Generate a detached ASCII-armored signature (`.asc`):
```bash
gpgkit sign release-artifact.tar.gz
```
Generate an inline clearsigned text document:
```bash
gpgkit sign --clearsign message.txt
```

### 5. Verify Signatures
Verify a signed file:
```bash
gpgkit verify release-artifact.tar.gz release-artifact.tar.gz.asc
```
Machine-readable verification:
```bash
gpgkit verify --json release-artifact.tar.gz release-artifact.tar.gz.asc
```
JSON output contains:
```json
{
  "valid": true,
  "signer": "Alice Developer <alice@example.com>",
  "fingerprint": "...",
  "timestamp": "2026-10-16T12:00:00Z"
}
```

### 6. Backup & Restore
Create an encrypted single-file backup of public keys, secret keys, and trustdb:
```bash
gpgkit backup --out ~/gpg-backup.bundle
```
Restore keys onto a new machine:
```bash
gpgkit restore ~/gpg-backup.bundle
```

### 7. Escape Hatch (Raw GPG pass-through)
Pass arguments directly to underlying `gpg` binary:
```bash
gpgkit raw -- --list-secret-keys --keyid-format LONG
```

---

## Agent Usage Best Practices

1. **Structured Outputs**: Always append `--json` when you need to parse fingerprints, keys, or validation states in automation scripts.
2. **Standard Exit Codes**:
   - `0`: Success
   - `1`: User error (key not found, missing argument)
   - `2`: System error (gpg binary missing, permission denied)
3. **Cross-Platform**: Works identically on macOS, Linux, and Windows 11.
