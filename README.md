# li-tools

> **Modern, zero-runtime-dependency CLI security & developer tools matrix.**  
> A curated suite of high-velocity developer tools designed to eliminate the cognitive burden of archaic command-line utilities.

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/"><b>Landing Pages & Docs</b></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk" alt="Java 25">
  <img src="https://img.shields.io/badge/GraalVM-Native%20Image-blue?style=flat-square&logo=graalvm" alt="GraalVM">
  <img src="https://img.shields.io/badge/Platforms-macOS%20%7C%20Linux%20%7C%20Windows%2011-success?style=flat-square" alt="Platforms">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blueviolet?style=flat-square" alt="License">
</p>

---

## 📦 Tools in the Box

| Tool | Mission | Highlights | Status |
| :--- | :--- | :--- | :--- |
| **[`gpgkit`](gpgkit/)** | Full lifecycle management of GPG keys | Zero runtime dependencies, guaranteed loopback test verification, one-command backup/restore/extend/diagnose | `Active` |
| **[`certkit`](certkit/)** | Modern SSL/TLS certificate Swiss Army knife | Convention over configuration, trusted local Root CA, smart domain/IP SAN auto-sniffing, single-argument format conversion, triple-platform trust | `Active` |
| **[`tokenkit`](tokenkit/)** | Modern cross-platform encrypted token manager | Embedded encrypted SQLite, AES-256-GCM + PBKDF2, sub-10ms terminal shell injection (`tokenkit env`), cross-machine encrypted migration bundles | `Active` |

---

## 💡 Core Principles

1. **Convention over Configuration**:
   Sensible defaults are standard best practices. No more memorizing tedious flags. Output paths and directories default to sane locations, and 90% of daily operations run with zero configuration.
2. **Three-Tier Architecture**:
   - **Fast Convention Tier**: Intuitive verbs matching human habits with smart parameter sniffing (e.g. `certkit gen localhost`);
   - **Fine Control Tier**: Full support for flags and overrides when precision is needed;
   - **Raw Passthrough Tier**: Built-in escape hatch via the `raw` sub-command (e.g., `certkit raw ...`) directly orchestrating the underlying engine without limitation.
3. **Triple-Platform Equity (Cross-Platform Parity)**:
   First-class support across **macOS**, **Linux**, and **Windows 11**. Deep native integrations with system trust stores (macOS Keychain, Linux CA anchors, and Windows 11 `certutil` current-user non-elevated injection).
4. **Zero Runtime Dependencies**:
   Powered by Java 25 and compiled with **GraalVM Native Image**. Produces self-contained, millisecond-startup binaries. End users do not need a JVM or JRE installed.
5. **Contract First**:
   Every sub-tool maintains an authoritative `SPEC.md` specification. Every exit code, flag, and convention is documented and rigorously tested.

---

## 📂 Monorepo Architecture

```text
li-tools/
├── pom.xml                     # Multi-module parent POM (Java 25, JUnit 5, Picocli dependency management)
├── cli-common/                 # Shared CLI foundation (OutputStyle, JsonWriter, CliRunner shell, formatters)
│   ├── src/
│   └── pom.xml
├── site/                       # Modern landing pages deployed to GitHub Pages
│   ├── index.html              # Suite portal homepage
│   ├── gpgkit/index.html       # gpgkit landing page
│   └── certkit/index.html      # certkit landing page
├── gpgkit/                     # GPG key lifecycle manager (Pure JDK, zero runtime dependencies)
│   ├── SPEC.md                 # Contract specifications
│   ├── build-native.sh         # GraalVM Native Image compilation script
│   └── src/
├── certkit/                    # SSL/TLS certificate toolkit (Picocli + OpenSSL orchestration)
│   ├── SPEC.md                 # Contract specifications
│   ├── build-native.sh         # GraalVM Native Image compilation script
│   └── src/
├── tokenkit/                   # Universal encrypted token manager (Picocli + embedded SQLite)
│   ├── SPEC.md                 # Contract specifications
│   ├── build-native.sh         # GraalVM Native Image compilation script
│   └── src/
├── .github/workflows/          # CI/CD and GitHub Pages automation
└── README.md
```

## 🧩 Shared CLI Architecture (`cli-common`)

All tools in `li-tools` build upon the unified `cli-common` engine, ensuring identical interaction UX, exit code semantics, and developer experience across the entire matrix:

- **Unified CLI Runner Shell (`CliRunner`, `GlobalOptionsMixin`)**: Standardized Picocli execution harness providing uniform flags (`--json`, `--quiet`, `--no-color`, `--debug`), strict exit code contracts (`0 = OK`, `1 = FAILURE`, `2 = USAGE`), and automatic UTF-8 stream pinning.
- **Terminal Visual Styling (`OutputStyle`)**: Automatic detection of interactive TTY vs piped output, full compliance with the [NO_COLOR](https://no-color.org/) standard, and unified semantic glyphs (`✔`, `⚠`, `✖`, `ℹ`, `💡`).
- **Zero-Reflection Streaming JSON (`JsonWriter`)**: Lightweight, GraalVM Native Image-friendly JSON serializer without runtime reflection.
- **Terminal Formatters**: Auto-aligning key-value printers (`KeyValueFormatter`), Unicode box-drawing tables with ANSI-safe width calculation (`TableFormatter`), and secret masking utilities (`TextMasker`).
- **Platform & System Bridges**: Native clipboard interaction (`ClipboardUtil`), POSIX/PowerShell/Fish environment variable escaping (`ShellFormatter`), and OS detection (`Platform`).

Adding a new tool to `li-tools` requires zero boilerplate: simply depend on `cli-common` to inherit the exact shell, flags, formatting, and exit protocol.


## 🚀 Installation

Choose your preferred package manager or installer:

### 1. Homebrew (macOS & Linux)
```bash
# Add lihuu's official tap
brew tap lihuu/tap

# Install tools
brew install gpgkit certkit

# Or install individually without tapping first:
brew install lihuu/tap/gpgkit
brew install lihuu/tap/certkit
```

### 2. Scoop (Windows)
```powershell
# Add lihuu's official bucket
scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket

# Install tools
scoop install gpgkit certkit
```

### 3. One-Line Script Installer (macOS, Linux & Windows 11)
```bash
# macOS / Linux
curl -fsSL https://tools.lihu.me/install.sh | bash

# Windows 11 (PowerShell)
irm https://tools.lihu.me/install.ps1 | iex
```

---

## 🛠️ Quick Start & Build from Source

### Requirements
- **Java 25** (e.g. OpenJDK 25 or GraalVM for JDK 25)
- **Maven 3.9+**
- Underlying engines: `gpg` (for gpgkit), `openssl` (for certkit)

For Native builds, use the latest official GraalVM CE compatible with Java 25. See the [environment setup guide (中文)](docs/DEVELOPMENT_ENVIRONMENT.md) for installation and toolchain checks; the currently verified release is CE 25.4.4.1.1.

### Run Tests Across All Modules
Execute from the repository root:
```bash
mvn clean test
```

### Compile Native Executable (GraalVM Native Image)
Requires GraalVM for JDK 25 in your `JAVA_HOME`:

All three tools use Native Image's default optimization level (O2). See the [build settings and measurements](docs/NATIVE_BUILD.md) for the rationale.

```bash
# Compile standalone native binary for gpgkit (output: gpgkit/target/gpgkit)
cd gpgkit && ./build-native.sh

# Compile standalone native binary for certkit (output: certkit/target/certkit)
cd certkit && ./build-native.sh
```

See the [Native Image build evaluation (中文)](docs/NATIVE_BUILD_EVALUATION.md) for measured results, safety constraints, and proposed next steps.

---

## 🤖 AI Agent Integration (agentskills.io Standard)

All tools in `li-tools` provide native, machine-readable `SKILL.md` specifications tailored for AI coding agents (Claude Code, Gemini CLI, Cursor, Copilot CLI, OpenCode):

- **Suite Skill Guide**: [`SKILL.md`](SKILL.md) (Hosted at [https://tools.lihu.me/SKILL.md](https://tools.lihu.me/SKILL.md))
- **gpgkit Skill Guide**: [`gpgkit/SKILL.md`](gpgkit/SKILL.md) (Hosted at [https://tools.lihu.me/gpgkit/SKILL.md](https://tools.lihu.me/gpgkit/SKILL.md))
- **certkit Skill Guide**: [`certkit/SKILL.md`](certkit/SKILL.md) (Hosted at [https://tools.lihu.me/certkit/SKILL.md](https://tools.lihu.me/certkit/SKILL.md))

### Install Skills for Your AI Agents
Install skills directly into your local AI agent skill paths (`~/.agents/skills/`, `~/.gemini/skills/`, `~/.claude/skills/`):
```bash
# macOS / Linux
curl -fsSL https://tools.lihu.me/install-skills.sh | bash

# Windows 11 (PowerShell)
irm https://tools.lihu.me/install-skills.ps1 | iex
```

---

## 🌐 Documentation & Web Pages

- **[Live Website & Landing Pages](https://tools.lihu.me/)**
- **[`gpgkit Documentation`](gpgkit/README.md)** (Available in [English](gpgkit/README.md) and [中文](gpgkit/README.zh-CN.md))
- **[`certkit Documentation`](certkit/README.md)** (Available in [English](certkit/README.md) and [中文](certkit/README_zh.md))

---

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE) for details.
