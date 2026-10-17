# @lihuu/tools

> Developer Tool Suite: **gpgkit** (GPG key lifecycle) + **certkit** (TLS/SSL certificate management).

Zero-dependency CLI tool suite wrapper that downloads and executes official GraalVM native binaries for macOS, Linux, and Windows.

## Installation

```bash
# Global install (installs both gpgkit and certkit)
npm install -g @lihuu/tools

# Or run directly via npx
npx @lihuu/tools gpgkit status
npx @lihuu/tools certkit doctor
```

## Included Tools

### 1. `gpgkit`
One command for the whole life of a GPG key.
- `gpgkit status`: check local key status and health
- `gpgkit gen`: generate primary signing key with safe defaults
- `gpgkit backup`: back up your keys with automatic loopback verification
- `gpgkit restore <file>`: restore key from an encrypted archive
- `gpgkit doctor`: validate machine signing and publishing health

### 2. `certkit`
Zero-dependency CLI for enterprise PKI and certificate management without OpenSSL agony.
- `certkit gen --domain test.local`: generate server certificate with SAN
- `certkit ca init`: initialize local root CA
- `certkit check example.com:443`: inspect TLS handshake, expiry, chain
- `certkit convert cert.p12 --to pem`: convert PKCS12 / DER / PEM
- `certkit trust add my-ca.crt`: safely install CA into system trust store

## Documentation

Full documentation, guides, and agent skills: [https://lihuu.github.io/li-tools/](https://lihuu.github.io/li-tools/)
