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

# Scoop (Windows)
scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket
scoop install gpgkit

# Direct Script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s gpgkit
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
Generate a key with an explicit identity. The defaults are RSA 4096, two years, and no passphrase.
```bash
gpgkit gen --uid "Alice Developer <alice@example.com>"
```
For Ed25519 and passphrase protection, supply a readable passphrase file:
```bash
gpgkit gen --uid "Alice Developer <alice@example.com>" --algo ed25519 --passphrase-file /path/to/passphrase
```
Non-interactive / machine-readable JSON output:
```bash
gpgkit gen --json --uid "Alice Developer <alice@example.com>"
```
Output provides:
- `fingerprint`: Full 40-character hex fingerprint.
- `userIds`, `algorithm`, `expires`, `passphrase` and `backupCommand`: Use the returned full fingerprint for configuration and export.

### 3. Git Commit Signing Setup
Directly export the ASCII-armored public key to paste into GitHub/GitLab:
```bash
gpg --armor --export <FINGERPRINT>
```
Configure Git automatically with the generated Key ID:
```bash
git config --global user.signingkey <FINGERPRINT>
git config --global commit.gpgsign true
```

### 4. Sign Files
Generate a detached ASCII-armored signature (`.asc`):
```bash
gpgkit sign release-artifact.tar.gz
```
Use GnuPG directly for an inline clearsigned document:
```bash
gpg --clearsign message.txt
```

### 5. Verify Signatures
Verify a signed file:
```bash
gpgkit verify release-artifact.tar.gz --sig release-artifact.tar.gz.asc
```
Machine-readable verification:
```bash
gpgkit verify --json release-artifact.tar.gz --sig release-artifact.tar.gz.asc
```
JSON output contains:
```json
{
  "ok": true,
  "userIds": ["Alice Developer <alice@example.com>"],
  "fingerprint": "...",
  "status": "good"
}
```

### 6. Backup & Restore
Create an encrypted single-file backup of public keys, secret keys, and trustdb:
```bash
gpgkit backup --out ~/gpg-backup --generate-passphrase-to ~/gpg-backup-passphrase.txt
```
Keep the passphrase apart from the backup. Use the backup file reported by the command when restoring on a new machine. Supply `--yes` only for a verified, intended restore.
```bash
gpgkit restore <backup-file> --passphrase-file ~/gpg-backup-passphrase.txt
```

### 7. Low-level GnuPG operations
Use the system `gpg` binary directly:
```bash
gpg --list-secret-keys --keyid-format LONG
```

---

## Agent Usage Best Practices

1. **Structured Outputs**: Always append `--json` when you need to parse fingerprints, keys, or validation states in automation scripts.
2. **Standard Exit Codes**:
   - `0`: Success
   - `1`: Operation failure, such as a key or file not found
   - `2`: Invalid CLI usage, such as a missing argument
3. Run `gpgkit --version` to check the build and `gpgkit <command> --help` for its synopsis.
