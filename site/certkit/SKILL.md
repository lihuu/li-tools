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
Generate a local root CA:
```bash
certkit ca
```

If the CA already exists and needs to be trusted, run `certkit trust install` to install it. Use `certkit ca --trust` to create and install a fresh CA in one command when the trust change is intended.
Optional custom name and validity:
```bash
certkit ca --name "Acme Corp Dev CA" --days 3650
```
Machine-readable output:
```bash
certkit ca --json
```

### 3. Generate TLS Certificates with SANs
Choose one of the following generation examples per output directory. Use `--out` to keep successive certificates in separate directories.

Generate an ECDSA certificate signed by the local CA if available:
```bash
certkit gen localhost 127.0.0.1 app.dev.local
```
Or self-signed (without CA):
```bash
certkit gen --self-signed localhost 127.0.0.1
```
Specify output directory, validity, and key type:
```bash
certkit gen --out ./certs --days 365 --algo rsa4096 mydomain.local
```
Machine-readable JSON output:
```bash
certkit gen --json localhost 127.0.0.1
```
JSON returns:
- `cert` and `key`: Absolute paths to `cert.pem` and `key.pem`.
- `sans`: Array of SAN entries (e.g. `["DNS:localhost", "IP:127.0.0.1"]`).
- `fullchain`, `signedByCa` and `caCert`: Describe the generated chain and signer. Use `certkit trust check <ca.crt>` to inspect OS trust.

Existing output files are preserved unless `--force` is explicit. `ca --json` reports `trustStatus` as `not_requested`, `installed`, or `failed`. If installation fails, the CA files are kept, exit status is `1`, and the same CA can be installed later with `certkit trust install`.

### 4. Check & Inspect Certificates
Inspect local certificate file:
```bash
certkit check cert.pem
certkit check --json cert.pem
```
Inspect remote server TLS certificate and handshake:
```bash
certkit check google.com:443
certkit check --json github.com:443
```
Output includes Subject CN, Issuer CN, validity window, remaining days, SANs, and signature algorithm.

### 5. Format Conversion
Convert PEM pair to PKCS#12 (`.p12` / `.pfx`) for Tomcat, Spring Boot, or Windows IIS:
```bash
certkit convert cert.pem bundle.p12 --key key.pem
```
Extract PEM from PKCS#12 bundle:
```bash
certkit convert bundle.p12 restored.pem
```
Convert PEM to binary DER:
```bash
certkit convert cert.pem cert.der
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
certkit raw -- x509 -in cert.pem -noout -text
```

---

## Agent Usage Best Practices

1. Use `--json` with `gen`, `ca`, `check`, or `doctor`. It can appear before or after the subcommand.
2. Exit status is `0` for success, `1` for an operation failure and `2` for invalid CLI usage.
3. Use `ca` for generation only in CI. Request OS trust separately when needed. The platform may require permissions or interactive authorization.
4. `raw -- ...` returns OpenSSL's output and exit status.
