---
name: certkit
description: Use when creating local SSL/TLS root CAs, issuing development certificates with SANs, checking cert validity, or converting between PEM/P12/DER formats.
---

# certkit: AI Agent Skill Guide

`certkit` is an ergonomic, zero-friction SSL/TLS certificate management CLI. It eliminates OpenSSL command agony, config file boilerplate, and complex system trust store manipulation across macOS, Linux, and Windows 11.

## Trigger Conditions

Use this skill whenever the user or task requires:
1. Setting up a local development HTTPS server with trusted browser "green lock".
2. Initializing a local development Root CA (`~/.local-ca/ca.crt` or custom path).
3. Automatically installing/uninstalling root CA into system trust store (macOS Keychain, Linux ca-certificates, Windows Root Store).
4. Generating TLS certificates with Subject Alternative Names (SANs) for domains (`localhost`, `*.dev.local`) and IP addresses (`127.0.0.1`, `10.0.0.1`).
5. Inspecting local certificate files or querying remote `host:port` TLS connections.
6. Converting certificates between PEM, PKCS#12 (`.p12`/`.pfx`), and binary DER formats (e.g. for Java, Docker, or iOS).

## Installation

```bash
# Homebrew (macOS & Linux)
brew install lihuu/tap/certkit

# Scoop (Windows)
scoop bucket add lihuu https://github.com/lihuu/my-scoop-bucket
scoop install certkit

# Direct Script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s certkit
```

---

## Command Reference

### 1. Environment Doctor
Verify OpenSSL availability, version, and trust store status:
```bash
certkit doctor
certkit doctor --json
```

### 2. Initialize Local Root CA
Generate a self-signed root CA and automatically install it into the operating system trust store:
```bash
certkit ca
```
Optional custom name and validity:
```bash
certkit ca --name "Acme Corp Dev CA" --days 3650
```
Machine-readable output:
```bash
certkit ca --json
```

### 3. Generate TLS Certificates with SANs
Generate ECDSA (default) or RSA certificate signed by the local CA:
```bash
certkit gen localhost 127.0.0.1 app.dev.local
```
Or self-signed (without CA):
```bash
certkit gen --self-signed localhost 127.0.0.1
```
Specify output directory, validity, and key type:
```bash
certkit gen --out-dir ./certs --days 365 --key-type rsa4096 mydomain.local
```
Machine-readable JSON output:
```bash
certkit gen --json localhost 127.0.0.1
```
JSON returns:
- `cert`: Path to public certificate (`localhost.crt`).
- `key`: Path to private key (`localhost.key`, mode 0600).
- `sans`: Array of SAN entries (e.g. `["DNS:localhost", "IP:127.0.0.1"]`).
- `trusted`: Boolean indicating whether signed by trusted local root CA.

### 4. Check & Inspect Certificates
Inspect local certificate file:
```bash
certkit check localhost.crt
certkit check --json localhost.crt
```
Inspect remote server TLS certificate and handshake:
```bash
certkit check google.com:443
certkit check --json github.com:443
```
Output includes Subject CN, Issuer CN, validity window, remaining days, SANs, key algorithm, and system trust status.

### 5. Format Conversion
Convert PEM pair to PKCS#12 (`.p12` / `.pfx`) for Tomcat, Spring Boot, or Windows IIS:
```bash
certkit convert cert.pem --key key.pem --to p12 --out bundle.p12 --password changeit
```
Extract PEM from PKCS#12 bundle:
```bash
certkit convert bundle.p12 --to pem --out cert.pem --password changeit
```
Convert PEM to binary DER:
```bash
certkit convert cert.pem --to der --out cert.der
```

### 6. Trust Store Management
Manually install or verify an existing root CA:
```bash
certkit trust install ca.crt
certkit trust check ca.crt
```
Cleanly remove CA from system trust store:
```bash
certkit trust uninstall ca.crt
```

### 7. Escape Hatch (Raw OpenSSL pass-through)
Pass commands directly to underlying OpenSSL:
```bash
certkit raw -- x509 -in localhost.crt -noout -text
```

---

## Agent Usage Best Practices

1. **Automation & Non-Interactive Execution**: Always pass `--json` to parse certificate metadata and output file paths cleanly in code.
2. **Standard Exit Codes**:
   - `0`: Success
   - `1`: User error (certificate expired, invalid SAN format, file not found)
   - `2`: System error (OpenSSL missing, insufficient trust store permissions)
3. **Headless & CI Execution**:
   - On Windows, `certkit ca` automatically detects headless sessions and falls back cleanly to the LocalMachine Root store if running with administrative rights, avoiding UI dialog hangs.
   - On Linux, `certkit trust install` safely checks for sudo/root permissions and prints the exact non-elevated command advice if privileges are needed.
