# certkit

> **One command for the whole life of SSL/TLS certificates: generate dev certs with SAN, manage trusted local Root CA, inspect, and convert — without OpenSSL agony.**  
> A developer-first SSL/TLS certificate toolkit adopting "Convention over Configuration" to eliminate the cognitive burden of archaic OpenSSL flags.

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://lihuu.github.io/li-tools/certkit/"><b>Landing Page</b></a> •
  <a href="../README.md"><b>li-tools Suite</b></a>
</p>

---

## ⚡ Why certkit?

Configuring HTTPS for local development, microservices, or staging environments using raw OpenSSL commands is notoriously frustrating:
- Modern browsers (Chrome, Edge, Safari) strictly require **SAN (Subject Alternative Name)**; traditional `CN`-only certificates immediately trigger security warnings.
- Issuing a SAN-enabled certificate requires either authoring an ugly temporary `openssl.cnf` or memorizing dozens of obscure command-line flags.
- Achieving green-lock trust in browsers requires manually generating a Root CA and memorizing different trust-store injection commands for macOS, Linux, and Windows.
- Format conversion (`PEM` <-> `PKCS#12` <-> `DER`) requires searching through notes every single time.

`certkit` encapsulates these workflows into **intuitive, developer-friendly verbs**.

---

## 🚀 30-Second Quick Start

### 1. Need a local certificate? Zero config needed!
```bash
# Generates a valid certificate and private key (mode 0600) for localhost and 127.0.0.1
certkit gen

# Support multiple domains and private IPs via positional arguments (auto-sniffs IP vs DNS)
certkit gen dev.local api.test 127.0.0.1 192.168.1.100
```

### 2. Want green-lock trust in your browser?
```bash
# 1. Initialize your machine's trusted local Root CA (stored under ~/.certkit/ca/ and injected into system trust)
certkit ca

# 2. Any subsequent 'gen' command is automatically signed by your local Root CA!
certkit gen myapp.local
# ➜ Produces cert.pem, key.pem, and fullchain.pem
# ➜ Open Chrome / Edge / Safari and enjoy instant HTTPS green-lock status!
```

### 3. Inspect certificates (No more multi-hundred-line ASN.1 dumps)
```bash
# Inspect local certificate ./cert.pem
certkit check

# Probe remote service (auto-appends port 443 if omitted)
certkit check github.com

# Structured, machine-readable JSON output
certkit --json check cert.pem
```

### 4. Effortless format conversion
```bash
# Automatically finds matching key.pem in the same directory and exports cert.p12 (default password: changeit)
certkit convert cert.pem

# Generate both .p12 and .der in one shot
certkit convert cert.pem --all

# Unpack PKCS#12 bundle back to text PEM certificates and keys
certkit convert bundle.p12
```

### 5. Triple-platform system trust management
```bash
# Check if the local Root CA is trusted by the host OS
certkit trust check

# Install root certificate to system or user trust store
certkit trust install

# Uninstall from trust store
certkit trust uninstall
```

### 6. Raw escape hatch (100% passthrough)
For edge cases or advanced OpenSSL commands, execute directly without leaving the CLI:
```bash
certkit raw version
certkit raw s_client -connect github.com:443
certkit raw x509 -in cert.pem -noout -text
```

### 7. Diagnostics and environment health
```bash
certkit doctor
```
Checks OS environment, OpenSSL binary location, version, trust store status, and CA health. If OpenSSL is missing, it prints an actionable, one-line copy-paste installation command tailored to your operating system.

---

## 📐 Architecture & Contracts

- **Implementation**: Java 25 + Picocli + GraalVM Native Image, compiled into a standalone native executable.
- **Three-Tier Architecture**:
  1. **Convention Tier**: Sane defaults, automatic IP/DNS classification, auto-reuse of `~/.certkit/ca/`, default password `changeit`;
  2. **Fine-Control Tier**: Full support for `--key`, `--out`, `--pass`, `--days`, `--algo`, and `--ca-cert`;
  3. **Raw Tier**: `certkit raw` provides an unconstrained escape hatch.
- **Cross-Platform Parity**:
  - **macOS**: Injects into user login keychain without password prompt (`security`);
  - **Windows 11**: Native `certutil.exe -addstore -user Root` non-elevated injection, automatic detection of Git for Windows OpenSSL;
  - **Linux**: Auto-detects Debian/Ubuntu, RHEL/CentOS, and Arch CA trust anchors.

See the complete contract specification in [SPEC.md](SPEC.md).
