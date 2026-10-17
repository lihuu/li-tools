# @lihuu/gpgkit

> One command for the whole life of a GPG key: generate, back up, restore, renew, sign, verify, publish, doctor.

Zero-dependency CLI wrapper that downloads and executes official GraalVM native binaries for macOS, Linux, and Windows.

## Installation

```bash
# Global install
npm install -g @lihuu/gpgkit

# Or run directly with npx
npx @lihuu/gpgkit status
```

## Usage

```bash
gpgkit status           # Check local key status and health
gpgkit gen              # Generate a primary signing key with safe defaults
gpgkit backup           # Back up your keys with automatic loopback verification
gpgkit restore <file>   # Restore key from an encrypted archive
gpgkit extend           # Extend key expiration date safely
gpgkit sign <file>      # Sign files with detached armor signature
gpgkit verify <file>    # Verify signature
gpgkit publish          # Publish public key to keyserver
gpgkit doctor           # Validate machine signing and publishing health
```

## Documentation

Full documentation, guides, and agent skills: [https://lihuu.github.io/li-tools/gpgkit](https://lihuu.github.io/li-tools/gpgkit)
