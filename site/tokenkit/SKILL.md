---
name: tokenkit
description: Use when securely managing, storing, retrieving, masking, or injecting API keys and sensitive environment tokens with sub-10ms shell export and zero plaintext leaks.
---

# tokenkit: AI Agent Skill Guide

`tokenkit` is a modern, cross-platform encrypted universal token manager. It replaces dangerous plaintext `.env` files and unprotected shell startup scripts (`~/.zshrc`, `~/.bashrc`, `$PROFILE`) with an embedded encrypted SQLite database (AES-256-GCM + PBKDF2) and OS-native master keychains.

## Trigger Conditions

Use this skill whenever the user or task requires:
1. Securely storing, retrieving, or deleting sensitive API keys (OpenAI, Anthropic, GitHub, AWS, Stripe, etc.).
2. Injecting decrypted tokens into terminal sessions sub-10ms (`tokenkit env`) without slowing down shell startup.
3. Running a build command, script, or server process with decrypted environment variables without polluting the parent terminal context (`tokenkit run -- npm start`).
4. Isolating tokens across development profiles (`default`, `work`, `staging`, `prod`).
5. Copying a sensitive secret directly to the system clipboard without displaying plaintext on screen (`tokenkit get KEY --clip`).
6. Exporting and importing tokens across operating systems (macOS, Windows 11, Linux) via password-protected migration bundles (`.tkbundle`).
7. Taking automated, zero-lock local database backups.

## Installation

```bash
# Direct Script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s tokenkit

# Direct Script (Windows 11 PowerShell)
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/tokenkit

# Scoop (Windows)
scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket
scoop install tokenkit
```

---

## Command Reference

### 1. Store or Update Token (`set`)
```bash
# Explicit value
tokenkit set OPENAI_API_KEY sk-proj-xxxxxx

# Pipe from standard input (great for multi-line private keys / certs)
cat id_rsa | tokenkit set SERVER_SSH_KEY

# Assign to profile with metadata
tokenkit set AWS_ACCESS_KEY_ID AKIAXXXX --profile work --tag cloud,infra --desc "Production AWS"
```

### 2. Retrieve Token Value (`get`)
```bash
# Output raw plaintext to stdout (no trailing newline, pipeline-friendly)
tokenkit get OPENAI_API_KEY

# Copy directly to OS clipboard without screen display
tokenkit get GITHUB_TOKEN --clip

# Retrieve from specific profile
tokenkit get AWS_ACCESS_KEY_ID --profile work
```

### 3. Shell Environment Export (`env` — Sub-10ms)
Designed to run in shell profiles (`.zshrc`, `.bashrc`, PowerShell `$PROFILE`) with zero perceptible delay:
```bash
# Evaluate in zsh / bash
eval "$(tokenkit env)"

# Output specific shell syntax
tokenkit env --shell zsh    # export KEY="VAL"
tokenkit env --shell pwsh   # $env:KEY = "VAL"
tokenkit env --shell fish   # set -gx KEY "VAL"

# Export specific profile
tokenkit env --profile work
```

### 4. Process Injection (`run`)
Runs any command with decrypted tokens injected into child process environment without leaking to the shell session:
```bash
tokenkit run -- npm run dev
tokenkit run --profile work -- aws s3 ls
tokenkit run -- python main.py
```

### 5. List Tokens (`list`)
```bash
# Masked human-readable table (e.g. sk-pr••••a8B3)
tokenkit list

# Filter by profile or tag
tokenkit list --profile all
tokenkit list --tag cloud

# Machine-readable JSON output for Agent parsing
tokenkit list --json
```

### 6. Profile Management (`profile`)
```bash
tokenkit profile list
tokenkit profile create work --desc "Company Cloud Tokens"
tokenkit profile use work
tokenkit profile rm work
```

### 7. Backup & Restore (`backup`)
```bash
# Create local timestamped snapshot via SQLite VACUUM INTO
tokenkit backup create

# List snapshots
tokenkit backup list

# Restore database from snapshot
tokenkit backup restore ~/.config/tokenkit/backups/tokenkit-backup-YYYYMMDD.db
```

### 8. Cross-Machine Migration (`export` & `import`)
```bash
# Export encrypted bundle (.tkbundle)
tokenkit export --out ./my-tokens.tkbundle --passphrase "StrongTransferPass!"

# Export all profiles
tokenkit export --all

# Export plain JSON
tokenkit export --unencrypted

# Import with default conflict skip (--skip)
tokenkit import ./my-tokens.tkbundle --passphrase "StrongTransferPass!"

# Import with overwrite
tokenkit import ./my-tokens.tkbundle --overwrite
```

### 9. Remove Tokens (`rm`)
```bash
tokenkit rm OPENAI_API_KEY
tokenkit rm KEY1 KEY2 -y
tokenkit rm AWS_SECRET_ACCESS_KEY --profile work
```

---

## Security Architecture for AI Agents

1. **Authenticated Encryption (AEAD)**: All token values are stored as `AES/GCM/NoPadding` ciphertexts with 12-byte cryptographically secure random nonces and 16-byte authentication tags. Any database tampering immediately triggers `AEADBadTagException`.
2. **Master Keychain Integration**:
   - macOS: Stored in user login Keychain via `/usr/bin/security`.
   - Windows 11: Encrypted using DPAPI `CryptProtectData`.
   - Linux: Stored in user-only file mode `0600` (`~/.config/tokenkit/.master.key`).
3. **Canary Verification**: Database contains an encrypted canary token verifying master key validity before any operation executes.
4. **Memory Scrubbing**: Passphrases and plaintext buffers are zero-wiped (`Arrays.fill(..., (byte)0)`) after cryptographic execution.
