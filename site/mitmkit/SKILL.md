---
name: mitmkit
description: Use when intercepting or inspecting local HTTPS traffic for development, generating a local root CA, or capturing HTTP flows as JSONL through a proxy.
---

# mitmkit: Local HTTPS Interception Guide

`mitmkit` is a single native binary that acts as a local HTTPS interception proxy. It generates a root CA, signs per-host leaf certificates in-process, and records every flow to stdout as newline-delimited JSON — the same command surface on macOS, Linux and Windows.

```bash
mitmkit ca
mitmkit run --jsonl
curl -x http://127.0.0.1:8080 --cacert ~/.mitmkit/ca/ca.crt https://example.com/
```

## Trigger Conditions

Use this skill whenever the task requires:

1. Inspecting HTTPS traffic from a local application or CLI.
2. Generating a local root CA for development interception.
3. Capturing HTTP flows in a machine-readable format for scripts or agents.
4. Routing traffic from a client that honors an explicit proxy setting.
5. Verifying how an application behaves against a self-signed upstream (with `--insecure`).

## Installation

```bash
# Homebrew (macOS & Linux)
brew install lihuu/tap/mitmkit

# Scoop (Windows)
scoop install mitmkit

# Direct script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s mitmkit
```

---

## Command Reference

### 1. Generate or inspect the local CA

```bash
mitmkit ca
mitmkit ca --json
mitmkit ca --force
```

JSON returns `caCert`, `caKey`, `subjectCn`, `sha256Fingerprint` and `notAfter`.

### 2. Start the proxy

```bash
mitmkit
mitmkit run --port 8080 --jsonl
mitmkit run --insecure --body
```

Bare `mitmkit` is equivalent to `mitmkit run`. The process runs until Ctrl+C.

### 3. Trust the CA

```bash
mitmkit run --install-trust
# or, using certkit:
certkit trust install ~/.mitmkit/ca/ca.crt
```

`--install-trust` writes the CA into the OS trust store (login keychain on macOS,
CurrentUser Root on Windows, anchors on Linux) and needs admin rights. Failure
prints a copy-pasteable command instead of stopping the proxy.

### 4. Send traffic through the proxy

```bash
curl -x http://127.0.0.1:8080 --cacert ~/.mitmkit/ca/ca.crt https://example.com/
```

Any client that honors an explicit HTTP proxy setting can be pointed at `127.0.0.1:8080`.

### 5. Rewrite traffic

Simple flags replace whole values; headers are appended when absent and replaced when
present, cookies merge into the existing Cookie header:

```bash
mitmkit run --url https://staging.example.com
mitmkit run --header 'X-Debug:1' --cookie 'session=abc'
mitmkit run --body '{"user":"admin"}'
mitmkit run --response-header 'X-Mitm:yes' --response-body '{"ok":true}'
mitmkit run --match /api/login --header 'X-Debug:1'
```

For advanced control, `--rewrite` takes a semicolon-separated rule with `method`, `url`,
`setUrl`, `reqHeader`, `resHeader`, `cookie`, `body` and `resBody` keys.

## JSONL Fields

Each flow is one JSON object per line:

`id`, `ts`, `method`, `url`, `status`, `durationMs`, `reqBytes`, `resBytes`, `reqHeaders`, `resHeaders`, `reqBody`, `resBody`, `error`

- `status` is an integer or `null` on failure.
- Body fields appear only with `--body`; header fields only with `--headers`.
- Errors are flows with a `null` status and an `error` message.

## Agent Usage Best Practices

1. **Automation**: always pass `--jsonl` and parse stdout; human lines are for terminals.
2. **Exit codes**: `0` success (including clean Ctrl+C), `1` execution failure, `2` usage error.
3. **Scope**: regular proxy mode only — clients that ignore proxy settings (some Go CLIs, mobile devices) are not intercepted.
4. **CA lifecycle**: `mitmkit ca` refuses to overwrite; use `--force` to rebuild.
5. **Trust**: keep trust changes in `certkit` so the OS trust store has one owner.
