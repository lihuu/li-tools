# jsonkit

> **Format, highlight and query JSON with one command.**  
> Built with Java 25 & GraalVM Native Image. Zero runtime dependencies, one native binary for macOS, Linux and Windows 11.

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/jsonkit/"><b>Landing Page</b></a> •
  <a href="../README.md"><b>li-tools Suite</b></a>
</p>

---

## ⚡ Why jsonkit?

Opening JSON should not require remembering a chain of tools:

- `cat file.json | jq .` needs jq, and jq's absence is a recurring setup problem on Windows.
- `python -m json.tool` rewrites numbers and has no path queries.
- Writing a throwaway script for one field is slower than the question itself.

`jsonkit` gives humans and agents the same first move:

```bash
jsonkit response.json
```

It pretty-prints the document with two-space indentation and highlights it on a terminal. When the next question is "what is the value at this path?", add `-p` and use the same command. No subcommands, no mode switch, no second tool.

## 🚀 Quick Start

```bash
# Format a file; colors appear only when stdout is a terminal
jsonkit response.json

# Read from a pipe
curl -s https://api.example.com/users | jsonkit

# Select one value
jsonkit response.json -p user.name

# Select many values
jsonkit -p 'items[*].id' response.json

# Filter with standard JSONPath
jsonkit -p "items[?@.status == 'failed'].id" response.json
```

The command name is `jsonkit`. A shorter `jk` is intentionally left to your own shell alias:

```bash
alias jk=jsonkit
```

## 📦 Installation

```bash
# Shell installer (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s jsonkit

# PowerShell (Windows 11)
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/jsonkit

# Scoop (Windows)
scoop install jsonkit
```

Confirm the binary and version:

```bash
jsonkit --version
```

## 📖 Usage

```text
jsonkit [FILE|-] [-p PATH | --path PATH] [--no-color] [--debug]
jsonkit --help
jsonkit --version
```

| Invocation | Behavior |
| --- | --- |
| `jsonkit response.json` | Pretty-print the whole document |
| `jsonkit response.json -p user.name` | Print the selected value |
| `jsonkit -p '$.items[0].id' response.json` | Use a full JSONPath expression |
| `other-tool --json \| jsonkit -p 'items[*].id'` | Read stdin and print an array of matches |
| `jsonkit -p "items[?@.status == 'failed'].id" response.json` | Filter and print matching IDs |
| `jsonkit` | Read stdin when it is piped or redirected; show help in a terminal |
| `jsonkit -` | Explicitly read stdin, including from a terminal until EOF |

### Single values and arrays

A path made only of the root, field names and indexes is **singular** and prints the selected JSON value:

```bash
$ jsonkit -p user.name response.json
"Alice"

$ jsonkit -p user.missing response.json
jsonkit: path "$.user.missing" matched no value   # exit 1

$ jsonkit -p items response.json
[
  {"id": 1},
  {"id": 2}
]
```

A path with a wildcard, slice, selector list, descendant segment or filter prints an **array** of matches, even when only one value matches:

```bash
$ jsonkit -p 'items[*].id' response.json
[
  1,
  2
]

$ jsonkit -p 'items[*].missing' response.json
[]                                                # exit 0
```

### Shorthand paths

The CLI adds the document root to the three common shorthand forms:

| You write | The engine receives |
| --- | --- |
| `user.name` | `$.user.name` |
| `.user.name` | `$.user.name` |
| `[0].id` | `$[0].id` |
| `['a.b']` | `$['a.b']` |
| `items[?@.status == 'failed'].id` | `$.items[?@.status == 'failed'].id` |

Everything after the root follows [RFC 9535](https://www.rfc-editor.org/rfc/rfc9535.html): field names, indexes, wildcards, slices, selector lists, recursive descent, filters, and the standard `length()`, `count()`, `match()`, `search()` and `value()` functions. The engine is [Treetrail](https://github.com/treetrail/treetrail) 1.0.0 (Apache-2.0), which passes all 715 cases of the [JSONPath Compliance Test Suite](https://github.com/jsonpath-standard/jsonpath-compliance-test-suite).

## 🧭 Engine Selection

The query engine is locked at **Treetrail `jsonpath-core` 1.0.0**:

- License: Apache-2.0.
- Runtime dependencies: only `org.jspecify:jspecify` 1.0.1 (annotations).
- Conformance: passes all 715 cases of the JSONPath Compliance Test Suite at commit `20ebdcc` (2026-10-13), including the five RFC 9535 functions.
- Native Image: pure Java, no reflection or resource metadata; only the FFM `isatty` downcall is registered. On macOS ARM64 with GraalVM CE 25.5.5.0.3 and default O2, the raw native binary measured 15,051,816 bytes (14.35 MiB).
- Limits: configured to 10,000,000 visited nodes, 1,000,000 result nodes and 1,000 levels of nesting; exceeding any limit exits `1`.

The comparison path uses the same Java values: `LinkedHashMap` for objects, `ArrayList` for arrays, and `JsonNumber` values backed by `BigDecimal` for numbers.

## 🎨 Output Contract

- Two-space indentation, LF line endings, one trailing LF.
- Empty objects and arrays print as `{}` and `[]`.
- Object member order is preserved.
- Number literals are preserved exactly: `123456789012345678901234567890` and `1.2300` are never rounded.
- UTF-8 input is accepted with or without a BOM.
- Duplicate object keys are shown when viewing; querying a document with duplicate keys fails with exit `1`.
- Diagnostics include the file name (or `<stdin>`) and a line/column position.
- Color depends only on whether **stdout** is a terminal. `--no-color`, `NO_COLOR`, or redirected stdout produce plain UTF-8 JSON.
- Reading stdin and writing stdout are detected separately: a terminal reading piped input can still colorize, and a redirected result stays plain even when stdin is a terminal.

## 🚦 Exit Codes

| Code | Meaning |
| --- | --- |
| `0` | Success, help or version; a multi-value query with no matches prints `[]` |
| `1` | Read failure, invalid JSON, duplicate keys in query input, singular path with no match, or a resource limit |
| `2` | CLI usage error or invalid JSONPath expression |

## 🛡️ Resource Limits

Queries stop with exit `1` and a diagnostic instead of exhausting the process:

- 10,000,000 visited nodes
- 1,000,000 result nodes
- 1,000 levels of document nesting

JSON parsing uses the same 1,000-level nesting limit.

## 🔧 Build from Source

Requires Java 25 and GraalVM Native Image.

```bash
# From the repository root
mvn -pl jsonkit -am test
./jsonkit/build-native.sh
jsonkit/target/jsonkit --version
```

The native executable is `jsonkit/target/jsonkit` (`jsonkit.exe` on Windows). The FFM `isatty` downcall is registered in `META-INF/native-image/io.github.lihuu/jsonkit/reachability-metadata.json`; the native build also passes `--enable-native-access=ALL-UNNAMED`.

## 🤖 Agent Recipes

```bash
# Inspect the shape of an unknown response without dumping everything
curl -s "$API" | jsonkit -p '$'

# Pull one field for a shell variable
TOKEN=$(curl -s "$API" | jsonkit -p access_token)

# Extract failed job IDs, then hand them to another command
curl -s "$API" | jsonkit -p "jobs[?@.status == 'failed'].id" > failed.json

# Normalize a file before committing it
jsonkit package.json > package.pretty.json && mv package.pretty.json package.json
```
