---
name: jsonkit
description: Use when formatting, highlighting, inspecting, or querying JSON from a file or stdin with RFC 9535 JSONPath, including extracting fields, wildcards, filters, and recursive searches.
---

# jsonkit: JSON Formatting and Query Guide

`jsonkit` is a zero-dependency CLI for the first two things anyone does with JSON: look at it, then pull a value out of it. One command covers both; there are no subcommands and no mode switch.

```bash
jsonkit response.json
jsonkit response.json -p user.name
other-tool --json | jsonkit -p 'items[*].id'
```

## Trigger Conditions

Use this skill whenever the task requires:

1. Pretty-printing or validating a JSON file or pipeline output.
2. Extracting a single field, array element, or nested value.
3. Selecting many values with a wildcard, slice, selector list, or recursive descent.
4. Filtering JSON with RFC 9535 expressions before passing results to another tool.
5. Producing plain, parseable JSON for automation without ANSI escapes.

## Installation

```bash
# Direct script (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s jsonkit

# Direct script (Windows 11 PowerShell)
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/jsonkit

# Scoop (Windows)
scoop install jsonkit
```

Confirm the installation:

```bash
jsonkit --version
```

## Default Gesture

```bash
jsonkit response.json          # format the whole document
jsonkit response.json -p user  # format one selected value
cat response.json | jsonkit    # read stdin
jsonkit - < response.json      # explicit stdin, even in a terminal
```

With no file and no `--path`, `jsonkit` reads stdin when it is piped or redirected and shows help in a terminal.

## Querying

The file and the path are independent arguments; the path may come before or after the file.

```bash
jsonkit -p user.name response.json
jsonkit response.json -p user.name
jsonkit -p '$.items[0].id' response.json
```

Shorthand forms get the document root added by the CLI:

| Shorthand | Engine expression |
| --- | --- |
| `user.name` | `$.user.name` |
| `.user.name` | `$.user.name` |
| `[0].id` | `$[0].id` |
| `['a.b']` | `$['a.b']` |
| `items[?@.status == 'failed'].id` | `$.items[?@.status == 'failed'].id` |

Everything after the root follows RFC 9535:

```bash
# One field
jsonkit -p user.email response.json

# Array index
jsonkit -p 'items[0].id' response.json

# Wildcard
jsonkit -p 'items[*].id' response.json

# Slice
jsonkit -p 'items[0:3].id' response.json

# Selector list
jsonkit -p 'items[0,2].id' response.json

# Recursive descent
jsonkit -p '$..id' response.json

# Filter
jsonkit -p "items[?@.status == 'failed'].id" response.json

# Standard functions
jsonkit -p "items[?match(@.status, 'fail.*')].id" response.json
```

## Output Shapes

- A path made only of the root, field names and indexes is **singular**: it prints the selected JSON value. A missing path exits `1`; a selected array is printed as an array.
- A path with a wildcard, slice, selector list, descendant segment or filter always prints an **array** of matches. No matches prints `[]` with exit `0`; one matched `null` prints `[null]`.

```bash
$ jsonkit -p user.name response.json
"Alice"

$ jsonkit -p 'items[*].missing' response.json
[]
```

## Output Contract

- Two-space indentation, LF line endings, one trailing LF.
- Object member order and number literals are preserved.
- Viewing shows duplicate keys; querying a document with duplicate keys exits `1`.
- Color depends only on stdout being a terminal. `--no-color`, `NO_COLOR`, or redirection produce plain JSON.
- Diagnostics include the file name (or `<stdin>`) and line/column.

## Exit Codes

- `0` success, help or version; a multi-value query with no matches still succeeds with `[]`
- `1` read failure, invalid JSON, duplicate keys in query input, singular path with no match, or a resource limit
- `2` invalid CLI usage or invalid JSONPath

## Agent Recipes

```bash
# Inspect an unknown response first
curl -s "$API" | jsonkit -p '$'

# Extract one token for a shell variable
TOKEN=$(curl -s "$API" | jsonkit -p access_token)

# Collect failed job IDs for the next step
curl -s "$API" | jsonkit -p "jobs[?@.status == 'failed'].id" > failed.json

# Normalize before committing
jsonkit package.json > package.pretty.json && mv package.pretty.json package.json
```

## Notes

- `jsonkit` is read-only: it never edits the input file. Redirect to a new file and move it if normalization is intended.
- The only official command is `jsonkit`; `jk` is a personal shell alias, not installed by the project.
- Query limits are 10,000,000 visited nodes, 1,000,000 result nodes, and 1,000 levels of nesting. Exceeding a limit exits `1`.
