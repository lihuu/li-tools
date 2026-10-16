# Distribution & Windows 11 Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up fictional domain/package placeholders, implement authentic GitHub Pages-based one-line installers (`install.sh` and `install.ps1`), establish multi-platform GitHub Actions CI/Release automation (macOS, Linux, Windows 11), and execute end-to-end Windows 11 verification.

**Architecture:** Replace unlaunched third-party endpoints (`tools.lihu.me`, unreleased npm/brew packages) with zero-cost, self-hosted GitHub ecosystem primitives (GitHub Pages for installer distribution, GitHub Releases for binary assets, and GitHub Actions for cross-platform GraalVM compilation).

**Tech Stack:** POSIX Shell, Windows PowerShell, GitHub Actions, GraalVM Native Image, Java 25, Git.

**Spec:** Referenced from [SPEC.md](../../certkit/SPEC.md) and [SPEC.md](../../gpgkit/SPEC.md).

---

## Global Constraints

- **No Fictional Services**: All installation URLs and package names must be either immediately executable via GitHub or clearly designated as "In Progress / Coming Soon".
- **Real Author & Account**: Repository owner is `lihuu` (https://github.com/lihuu/li-tools). Do not reference personal domain `lihu.me` until personal server services are deployed.
- **Triple-Platform Equity**: Windows 11, macOS, and Linux must all have first-class one-line installation methods.
- **Strict Verification Discipline**: All scripts must validate SHA-256 checksums, respect user PATH environments, and never require administrative elevation (UAC/sudo) unnecessarily.

---

## Review Focus

1. **PowerShell Execution Policy**: Windows users running `irm ... | iex` must not fail on restrictive execution policies (`RemoteSigned`/`Bypass` handling).
2. **Path Deduplication**: Installing multiple times must not pollute the system or user `PATH` environment variable.
3. **GitHub Release Asset Matching**: Install scripts must reliably resolve the latest release tag and match the exact architecture (e.g. `darwin_arm64`, `windows_x86_64`).
4. **Offline / Missing Release Graceful Fallback**: If a binary release asset is not yet uploaded, installer must clearly report "Release asset pending, please build from source via `mvn clean package`" instead of crashing with HTTP 404.
5. **Git for Windows Non-Standard Paths**: On Windows 11, OpenSSL detection must check standard registry/Program Files paths as well as current PATH.

---

## Task 1: Clean Fictional Placeholders & Author Real Installers

**Files:**
- Create: `site/install.sh` (macOS & Linux auto-detection, architecture resolution, download from GitHub Releases to `~/.local/bin/`)
- Create: `site/install.ps1` (Windows 11 PowerShell installer, architecture resolution, PATH management)
- Modify: `site/index.html`, `site/index_zh.html`
- Modify: `site/gpgkit/index.html`, `site/gpgkit/index_zh.html`
- Modify: `site/certkit/index.html`, `site/certkit/index_zh.html`

- [ ] Step 1: Write `site/install.sh` supporting `gpgkit`, `certkit`, or both (`all`).
- [ ] Step 2: Write `site/install.ps1` for native Windows 11 PowerShell execution.
- [ ] Step 3: Update `site/index.html` and `site/index_zh.html` install tabs to feature:
  - Tab 1: Shell (macOS / Linux): `curl -fsSL https://lihuu.github.io/li-tools/install.sh | bash`
  - Tab 2: PowerShell (Windows 11): `irm https://lihuu.github.io/li-tools/install.ps1 | iex`
  - Tab 3: GitHub Releases (Direct download links)
  - Tab 4: Package Managers (Homebrew & NPM tagged as "Coming Soon / In Preparation")
- [ ] Step 4: Update `gpgkit/` and `certkit/` landing pages accordingly.
- [ ] Step 5: Test `site/install.sh` syntax locally (`bash -n site/install.sh`).
- [ ] Step 6: Commit changes with message `fix(site): ground install commands with real GitHub Pages scripts and remove fictional domains`.

---

## Task 2: Cross-Platform CI Matrix Workflow (macOS, Linux, Windows 11)

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] Step 1: Define GitHub Actions matrix for `macos-latest`, `ubuntu-latest`, and `windows-latest`.
- [ ] Step 2: Set up Java 25 via `actions/setup-java@v4` with Maven caching.
- [ ] Step 3: Execute `mvn clean test` across all 3 platforms on every push and pull request.
- [ ] Step 4: Verify that surefire test runs pass on Windows (verifying `WindowsPlatformLogicTest`).
- [ ] Step 5: Commit changes with message `ci: add cross-platform test matrix for macOS, Linux, and Windows 11`.

---

## Task 3: GraalVM Native Image Release Automation

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] Step 1: Configure trigger on `v*` semantic version tags (e.g., `v0.1.0`).
- [ ] Step 2: Setup GraalVM for JDK 25 on:
  - macOS arm64 (`macos-latest`)
  - Linux x86_64 (`ubuntu-latest`)
  - Windows x86_64 (`windows-latest`)
- [ ] Step 3: Build native binaries:
  - Linux: `gpgkit-linux-amd64`, `certkit-linux-amd64`
  - macOS: `gpgkit-darwin-arm64`, `certkit-darwin-arm64`
  - Windows: `gpgkit-windows-amd64.exe`, `certkit-windows-amd64.exe`
- [ ] Step 4: Generate SHA-256 checksums for all release assets.
- [ ] Step 5: Automatically create GitHub Release with release notes and attach assets.
- [ ] Step 6: Commit changes with message `ci: add automated GraalVM Native Image release workflow`.

---

## Task 4: Windows 11 Physical Machine Verification Checklist

**Files:**
- Create: `docs/WINDOWS_11_VERIFICATION.md`

- [ ] Step 1: Author physical verification guide detailing:
  - 1. OpenSSL detection under Git for Windows (`C:\Program Files\Git\usr\bin\openssl.exe`).
  - 2. `certkit ca` root certificate generation.
  - 3. Non-elevated `certkit trust install` via `certutil.exe -addstore -user Root`.
  - 4. Edge / Chrome browser inspection verifying green lock without administrative prompts.
  - 5. `certkit convert` P12 bundling under Windows paths (handling backslashes).
  - 6. `gpgkit backup` and `gpgkit restore` loopback verification in PowerShell.
- [ ] Step 2: Commit checklist with message `docs: add Windows 11 physical verification test guide`.

---

## Task 5: Ecosystem Distribution Channels (Future Preparation)

**Files:**
- Create: `docs/DISTRIBUTION_ROADMAP.md`

- [ ] Step 1: Outline Homebrew Tap repository setup (`lihuu/homebrew-tools`).
- [ ] Step 2: Outline Windows Scoop bucket manifest (`li-tools.json`).
- [ ] Step 3: Outline NPM package strategy (`@lihuu/tools` wrapper CLI).
- [ ] Step 4: Commit roadmap with message `docs: add roadmap for Homebrew, Scoop, and NPM distribution`.
