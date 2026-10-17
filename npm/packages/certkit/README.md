# @lihuu/certkit

> Zero-dependency CLI for enterprise PKI and certificate management without OpenSSL agony.

Zero-dependency CLI wrapper that downloads and executes official GraalVM native binaries for macOS, Linux, and Windows.

## Installation

```bash
# Global install
npm install -g @lihuu/certkit

# Or run directly with npx
npx @lihuu/certkit check example.com:443
```

## Usage

```bash
certkit gen --domain test.local           # Generate server certificate with SAN
certkit ca init --name "My Local Root CA" # Initialize local root CA
certkit check example.com:443             # Inspect TLS handshake, expiry, chain
certkit convert cert.p12 --to pem        # Convert PKCS12 / DER / PEM
certkit trust add my-ca.crt               # Safely install CA into system trust store
certkit doctor                            # Check environment health & trust store
```

## Documentation

Full documentation, guides, and agent skills: [https://lihuu.github.io/li-tools/certkit](https://lihuu.github.io/li-tools/certkit)
