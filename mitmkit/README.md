# mitmkit

> **Local HTTPS interception proxy for development, with JSONL traffic capture.**  
> Built with Java 25 & GraalVM Native Image. One native binary for macOS, Linux and Windows 11.

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/mitmkit/"><b>Landing Page</b></a> •
  <a href="../README.md"><b>li-tools Suite</b></a>
</p>

---

## ⚡ Why mitmkit?

Inspecting HTTPS traffic should not require a Python environment or a GUI:

- `mitmproxy` needs pip, is hundreds of MB, and behaves differently on Windows (no transparent mode).
- Writing a throwaway proxy script means reimplementing CONNECT tunnels and certificate signing.
- Agent-driven debugging wants machine-readable output, not a TUI.

`mitmkit` is a single native binary with the same command surface on all three platforms:

```bash
mitmkit
```

It starts a local proxy on `127.0.0.1:8080`, generates a root CA on first use, and prints one JSON object per intercepted flow to stdout. Point any HTTP client at it with `-x` and trust the CA once.

## 🚀 Quick Start

```bash
# Generate the local CA (once)
mitmkit ca

# Trust it in your OS (via certkit)
certkit trust install ~/.mitmkit/ca/ca.crt

# Start the proxy and record flows as JSONL
mitmkit run --jsonl

# Send traffic through it
curl -x http://127.0.0.1:8080 --cacert ~/.mitmkit/ca/ca.crt https://example.com/
```

## 📋 Commands

| Command | Purpose |
| --- | --- |
| `mitmkit` / `mitmkit run` | Start the proxy (default `127.0.0.1:8080`, Ctrl+C to stop) |
| `mitmkit ca` | Generate or inspect the local root CA |
| `mitmkit --help` / `--version` | Usage and version |

### Run options

| Option | Effect |
| --- | --- |
| `--host HOST` | Listen address (default `127.0.0.1`; `0.0.0.0` for LAN devices) |
| `--port PORT` | Listen port (default `8080`) |
| `--insecure` | Skip upstream certificate verification (self-signed upstreams) |
| `--trust-local` | Trust loopback upstreams (127.0.0.1, localhost) without verification |
| `--system-proxy` | Set the OS system proxy while running, restore on exit |
| `--url URL` | Replace the request URL with this whole value |
| `--header NAME:VALUE` | Append or replace a request header (repeatable) |
| `--cookie NAME=VALUE` | Append or replace a cookie (repeatable) |
| `--body BODY` | Replace the request body with this whole value |
| `--response-header NAME:VALUE` | Append or replace a response header (repeatable) |
| `--response-body BODY` | Replace the response body with this whole value |
| `--match SUBSTR` | Only apply the simple rewrite flags to URLs containing this substring |
| `--rewrite RULE` | Advanced rewrite rule, repeatable (see below) |
| `--record-body` | Record request and response bodies in the output |
| `--record-headers` | Record request and response headers in the output |
| `--jsonl` | Emit newline-delimited JSON instead of human-readable lines |

### Rewriting traffic

The simple flags replace whole values — headers are appended when absent and replaced
when present, cookies merge into the existing Cookie header:

```bash
mitmkit run --url https://staging.example.com
mitmkit run --header 'X-Debug:1' --cookie 'session=abc'
mitmkit run --body '{"user":"admin"}'
mitmkit run --response-header 'X-Mitm:yes' --response-body '{"ok":true}'
mitmkit run --match /api/login --header 'X-Debug:1'
```

For advanced control, `--rewrite` takes a semicolon-separated rule, repeatable:

| Key | Meaning | Example |
| --- | --- | --- |
| `method` | Only rewrite matching method (default `*`) | `method:POST` |
| `url` | URL substring; with `->` also replaces | `url:/v1->/v2` |
| `setUrl` | Replace the whole URL | `setUrl:https://staging.example.com` |
| `reqHeader` | Request header (append or replace) | `reqHeader:X-Debug:1` |
| `resHeader` | Response header | `resHeader:X-Mitm:yes` |
| `cookie` | Cookie (merged into the Cookie header) | `cookie:session=abc` |
| `body` | Replace the whole request body | `body:{"user":"admin"}` |
| `resBody` | Replace the whole response body | `resBody:{"ok":true}` |

## 📤 Output

Human-readable mode prints one line per flow:

```
1 GET https://example.com/ 200 869ms 0B/589B
```

`--jsonl` mode prints one JSON object per line:

```json
{"id":1,"ts":"2026-11-10T04:27:29.354953Z","method":"GET","url":"https://example.com:443/","status":200,"durationMs":869,"reqBytes":0,"resBytes":589}
```

Every field is stable across macOS, Linux and Windows. Errors are recorded as flows with a `null` status and an `error` message.

## 🔐 CA and trust

- The CA lives in `~/.mitmkit/ca/` (override with `MITMKIT_CA_DIR`).
- `mitmkit ca --json` prints paths, subject and SHA-256 fingerprint.
- `mitmkit ca` refuses to overwrite an existing CA unless `--force` is given.
- Trust installation is handled by `certkit trust install`; mitmkit never modifies the system trust store.

## 🧩 Scope

First release covers **regular proxy mode** only: HTTP (absolute URI) and HTTPS (CONNECT tunnel) with per-host leaf certificates. TUN/transparent proxying, HTTP/2 and WebSocket multiplexing are out of scope.

## 📦 Installation

```bash
# Homebrew (macOS & Linux)
brew install lihuu/tap/mitmkit

# Scoop (Windows)
scoop install mitmkit

# Direct script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s mitmkit
```

## 📄 License

Apache-2.0. BouncyCastle is used under the Bouncy Castle Licence (a permissive, Apache-2.0-compatible license).
