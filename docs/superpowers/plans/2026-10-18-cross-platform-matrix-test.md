# Tokenkit Cross-Platform Matrix Migration & Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

This plan establishes a safe, end-to-end verification protocol to test `tokenkit`'s encryption, export, and import workflows across macOS (MacBook Pro & Mac mini), Windows 11 Native, and WSL2 Linux. Real user credentials on the local host will be completely wiped before testing, and a standardized set of boundary test tokens will be used.

---

## Target Environments & Access Routes

1. **Host 1 (MacBook Pro)**: macOS Apple Silicon (Local host, native `tokenkit` at `~/.local/bin/tokenkit`).
2. **Host 2 (Windows 11 Native)**: Windows 11 (Reachability: `ssh windows-11`).
3. **Host 3 (WSL2 Linux)**: Ubuntu on Windows 11 (Reachability: `ssh windows-11 "wsl -d Ubuntu-26.04 -- ..."` or `ssh wsl-11`).
4. **Host 4 (Mac mini)**: macOS host (AirDrop or user-provided SSH / path).

---

## Proposed Tasks

### Task 1: Complete Wipe of Real Credentials on Local MacBook Pro
- **Goal**: Ensure zero risk of leaking real user tokens during cross-machine tests.
- **Actions**:
  - Delete all tokens from `~/.config/tokenkit/tokens.db` via `tokenkit rm` (or reset the database file).
  - Verify with `tokenkit list` that output is `No tokens found.`
  - Verify raw SQLite table has 0 rows.

### Task 2: Create Canonical Boundary Test Tokens
- **Goal**: Create challenging test tokens that rigorously test encoding, escaping, and cross-platform compatibility.
- **Boundary Test Cases**:
  1. `TEST_SPECIAL_CHARS`: `p@$$w0rd!#%^&*()_+~`-={}|[]\:;'<>?,./"` (tests shell escaping and non-alphanumeric handling)
  2. `TEST_UNICODE_UTF8`: `密钥_安全测试_Token_🔒_2026` (tests UTF-8 vs Windows UTF-16/GBK cross-platform encoding)
  3. `TEST_MULTILINE_KEY`: Multi-line text with `\n` (tests line preservation in JSON and SQLite)
- **Actions**:
  - Save to profile `default` and a custom profile `cross-test`.
  - Export to encrypted bundle: `test-matrix.bundle` using passphrase `MatrixSync2026!`.

### Task 3: Deploy & Verify `tokenkit` on Windows 11 Native
- **Goal**: Have `tokenkit` ready and executable on Windows 11.
- **Actions**:
  - Check Windows 11 environment (`java`, `powershell`, or copy shaded jar/native binary).
  - Copy `tokenkit.jar` (or compile native on Windows) to Windows host.
  - Verify `tokenkit --version` and `tokenkit --help` work on Windows 11.
  - Test DPAPI master key provider generation on Windows.

### Task 4: Deploy & Verify `tokenkit` on WSL2 Linux
- **Goal**: Have `tokenkit` ready and executable in WSL2 Ubuntu.
- **Actions**:
  - Transfer `tokenkit` jar / binary into WSL2 environment.
  - Verify `tokenkit --version` and `tokenkit --help` in WSL.
  - Test POSIX `0600` keyfile generation on Linux (`~/.config/tokenkit/.master.key`).

### Task 5: Execute Cross-Platform Migration Verification Matrix
- **Subtask 5.1 (MacBook Pro ➔ Windows 11)**:
  - Copy `test-matrix.bundle` to Windows 11.
  - Run `tokenkit import test-matrix.bundle --passphrase "MatrixSync2026!"` on Windows 11.
  - Verify `TEST_SPECIAL_CHARS`, `TEST_UNICODE_UTF8`, `TEST_MULTILINE_KEY` decrypted accurately.
  - Test `tokenkit env --shell pwsh` in Windows PowerShell.
- **Subtask 5.2 (Windows 11 ➔ WSL2 Linux)**:
  - On Windows 11, add a new token: `tokenkit set -p cross-test WIN_ORIGIN "created_on_win11"`.
  - Re-export on Windows 11: `tokenkit export --all -o win-exported.bundle --passphrase "MatrixSync2026!"`.
  - Import into WSL2 Linux: `tokenkit import win-exported.bundle --passphrase "MatrixSync2026!"`.
  - Verify on WSL2 that all tokens (including `WIN_ORIGIN`) are intact.
- **Subtask 5.3 (WSL2 / Windows ➔ Mac mini / MacBook Pro)**:
  - Transfer the bundle back to MacBook Pro and Mac mini.
  - Import on Mac mini / MacBook Pro to complete full circular validation.

### Task 6: Final Cleanup & Restoration
- **Actions**:
  - Remove all test bundles and temporary test tokens across all machines.
  - Leave `tokenkit` clean and ready for user's everyday use.
