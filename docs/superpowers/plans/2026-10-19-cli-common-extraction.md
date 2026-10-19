# CLI Common Extraction & Unified CLI Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract shared CLI interaction infrastructure, terminal styling, streaming JSON generation, layout formatting, cross-platform utilities, and a unified Picocli application runner shell into a shared `cli-common` module, and migrate `tokenkit`, `certkit`, and `gpgkit` to this unified shell for a cohesive user experience.

**Architecture:** Monorepo multi-module pattern. `cli-common` depends on pure JDK 25 and Picocli (leveraging `picocli-codegen` for GraalVM Native Image compatibility). It encapsulates terminal styling (`OutputStyle`), zero-reflection streaming JSON (`JsonWriter`), layout formatters (`TableFormatter`, `KeyValueFormatter`, `TextMasker`), platform & shell adapters (`Platform`, `ShellFormatter`, `ClipboardUtil`), and a unified application runner (`CliRunner`, `GlobalOptionsMixin`, standard exit codes `0/1/2`, stack trace isolation). All 3 consumer tools (`tokenkit`, `certkit`, `gpgkit`) inherit this shell.

**Tech Stack:** Java 25, Picocli 4.7.6, `picocli-codegen`, JetBrains Annotations 26.0.2, JUnit Jupiter 5.11.4, Maven Multi-Module, GraalVM Native Image.

**Spec:** [docs/superpowers/specs/2026-10-19-cli-common-design.md](file:///Users/lihu/git/li-tools/docs/superpowers/specs/2026-10-19-cli-common-design.md) (derived from existing contracts in `gpgkit/SPEC.md`, `certkit/SPEC.md`, `tokenkit/SPEC.md`)

## Global Constraints

- Java 25 compiler release (`<maven.compiler.release>25</maven.compiler.release>`).
- Pure constructor injection; zero dependency injection frameworks, zero classpath dynamic scanning.
- Do not use `var`, including in test code; declare all local types explicitly.
- Public reference parameters and return types must be annotated with `@NotNull` / `@Nullable` from `org.jetbrains:annotations`.
- Standard exit codes: 0 = success, 1 = execution/runtime failure (friendly single-line error on stderr), 2 = usage error (Picocli usage help).
- GraalVM Native Image compatibility: all Picocli annotations must pass through `picocli-codegen`; `JsonWriter` must remain zero-reflection.
- Output streams: stdin/stdout/stderr must be UTF-8 pinned to prevent encoding corruption under `LANG=C`.
- TDD discipline: failing test first, verify failure, implement minimal code, verify pass, commit.

## Review Focus

- When `NO_COLOR` is present or terminal is non-interactive (`TERM=dumb` or piped output), `OutputStyle` must emit plain text without ANSI escape sequences.
- `JsonWriter` must properly escape quotes, backslashes, control characters, and validate balanced brackets without throwing unhandled exceptions.
- `CliRunner` must catch all unhandled exceptions in production and emit `outputStyle.error(...)` to stderr with exit code 1, suppressing Java stack traces unless `--debug` is explicitly passed.
- All existing CLI commands and arguments in `gpgkit`, `certkit`, and `tokenkit` must preserve backward compatibility with all existing test suites.
- `TableFormatter` must measure display width accurately without counting ANSI color escape characters when calculating column alignments.

---

### Task 1: Scaffolding `cli-common` Module & Parent POM Integration

**Files:**
- Create: `.worktrees/cli-common/cli-common/pom.xml`
- Modify: `.worktrees/cli-common/pom.xml`
- Test: `.worktrees/cli-common/cli-common/src/test/java/io/github/lihuu/clicommon/SanityTest.java`

**Interfaces:**
- Consumes: Root `pom.xml` dependency management.
- Produces: Maven module `io.github.lihuu:cli-common` with Picocli, Annotations, and JUnit Jupiter dependencies.

- [ ] **Step 1: Write failing sanity test**

```java
package io.github.lihuu.clicommon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanityTest {
    @Test
    void shouldBootstrapModule() {
        assertTrue(true);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl cli-common`
Expected: FAIL (module not found)

- [ ] **Step 3: Create `cli-common/pom.xml` and register in root `pom.xml`**

Register `<module>cli-common</module>` and define `<dependency>` in root `<dependencyManagement>`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl cli-common`
Expected: PASS (1 test run, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add pom.xml cli-common/
git commit -m "feat(cli-common): scaffold cli-common module and root pom wiring"
```

---

### Task 2: Implement Terminal Visual Styling (`OutputStyle`)

**Files:**
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/style/OutputStyle.java`
- Test: `.worktrees/cli-common/cli-common/src/test/java/io/github/lihuu/clicommon/style/OutputStyleTest.java`

**Interfaces:**
- Consumes: `System.getenv()`, `System.console()`.
- Produces: `OutputStyle` class with methods `success`, `warning`, `error`, `info`, `tip`, `highlight`, `muted`, `bold`, `isAnsi()`.

- [ ] **Step 1: Write unit tests for `OutputStyle`**

Test ANSI formatting, `NO_COLOR` override, `TERM=dumb` override, `--no-color` override, and semantic prefix markers.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OutputStyleTest -pl cli-common`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `OutputStyle.java`**

Implement semantic icons (`✔`, `⚠`, `✖`, `ℹ`, `💡`), ANSI color sequences, and factory `OutputStyle.of(Map<String, String> env, boolean interactive, boolean noColorFlag)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=OutputStyleTest -pl cli-common`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli-common/src/main/java/io/github/lihuu/clicommon/style/OutputStyle.java cli-common/src/test/java/io/github/lihuu/clicommon/style/OutputStyleTest.java
git commit -m "feat(cli-common): implement terminal OutputStyle with semantic status markers"
```

---

### Task 3: Implement Zero-Reflection Streaming `JsonWriter`

**Files:**
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/json/JsonWriter.java`
- Test: `.worktrees/cli-common/cli-common/src/test/java/io/github/lihuu/clicommon/json/JsonWriterTest.java`

**Interfaces:**
- Consumes: None (pure JDK standard library).
- Produces: Fluent `JsonWriter` supporting `beginObject()`, `endObject()`, `beginArray()`, `endArray()`, `name(String)`, `value(String)`, `value(long)`, `value(boolean)`, `nullValue()`, `toString()`.

- [ ] **Step 1: Write comprehensive tests for `JsonWriter`**

Test object serialization, array serialization, nested objects, string escaping (quotes, backslashes, tabs, newlines, UTF-8 unicode), comma separation, and unbalanced document errors.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=JsonWriterTest -pl cli-common`
Expected: FAIL

- [ ] **Step 3: Implement `JsonWriter.java`**

Port and generalize the zero-reflection implementation from `gpgkit.Json`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=JsonWriterTest -pl cli-common`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli-common/src/main/java/io/github/lihuu/clicommon/json/JsonWriter.java cli-common/src/test/java/io/github/lihuu/clicommon/json/JsonWriterTest.java
git commit -m "feat(cli-common): implement zero-reflection streaming JsonWriter"
```

---

### Task 4: Implement Formatting Helpers (`KeyValueFormatter`, `TableFormatter`, `TextMasker`)

**Files:**
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/format/KeyValueFormatter.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/format/TableFormatter.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/format/TextMasker.java`
- Test: `.worktrees/cli-common/cli-common/src/test/java/io/github/lihuu/clicommon/format/FormattersTest.java`

**Interfaces:**
- Consumes: `OutputStyle`.
- Produces:
  - `KeyValueFormatter.format(List<Entry<String, String>> entries, int indent)`
  - `TableFormatter.format(List<Column> columns, List<List<String>> rows)`
  - `TextMasker.mask(String secret)` & `TextMasker.fingerprint(String hex)`

- [ ] **Step 1: Write unit tests for formatters**

Test key-value colon alignment, table column width calculation with ANSI codes, and secret masking.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FormattersTest -pl cli-common`
Expected: FAIL

- [ ] **Step 3: Implement `KeyValueFormatter`, `TableFormatter`, and `TextMasker`**

Support clean indentation, ANSI-safe string length stripping for padding, and unicode dividing borders.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FormattersTest -pl cli-common`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli-common/src/main/java/io/github/lihuu/clicommon/format/ cli-common/src/test/java/io/github/lihuu/clicommon/format/
git commit -m "feat(cli-common): implement KeyValueFormatter, TableFormatter, and TextMasker"
```

---

### Task 5: Implement Platform, Shell, and Clipboard Utilities

**Files:**
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/system/Platform.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/system/ShellFormatter.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/system/ClipboardUtil.java`
- Test: `.worktrees/cli-common/cli-common/src/test/java/io/github/lihuu/clicommon/system/SystemUtilsTest.java`

**Interfaces:**
- Consumes: Java `System.getProperty("os.name")`, `ProcessBuilder`.
- Produces:
  - `Platform.current()`, `Platform.isMac()`, `Platform.isWindows()`, `Platform.isLinux()`
  - `ShellFormatter.formatExport(String shell, String name, String value)`
  - `ClipboardUtil.copyToClipboard(String text)`

- [ ] **Step 1: Write unit tests for Platform, ShellFormatter, and ClipboardUtil**

Test shell export quoting for bash/zsh/fish/pwsh, OS detection fallback, and clipboard null safety.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SystemUtilsTest -pl cli-common`
Expected: FAIL

- [ ] **Step 3: Implement `Platform`, `ShellFormatter`, and `ClipboardUtil`**

Port proven implementations from `certkit.TrustStore` and `tokenkit.util`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=SystemUtilsTest -pl cli-common`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli-common/src/main/java/io/github/lihuu/clicommon/system/ cli-common/src/test/java/io/github/lihuu/clicommon/system/
git commit -m "feat(cli-common): implement Platform, ShellFormatter, and ClipboardUtil"
```

---

### Task 6: Implement Unified CLI Runner Shell & Exit Code Protocol

**Files:**
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/cli/CliExitCode.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/cli/CliExecutionException.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/cli/CliUsageException.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/cli/GlobalOptionsMixin.java`
- Create: `.worktrees/cli-common/cli-common/src/main/java/io/github/lihuu/clicommon/cli/CliRunner.java`
- Test: `.worktrees/cli-common/cli-common/src/test/java/io/github/lihuu/clicommon/cli/CliRunnerTest.java`

**Interfaces:**
- Consumes: Picocli `CommandLine`, `OutputStyle`, UTF-8 streams.
- Produces:
  - `CliExitCode.OK (0)`, `CliExitCode.FAILURE (1)`, `CliExitCode.USAGE (2)`
  - `GlobalOptionsMixin` with `--json`, `--quiet`, `--no-color`, `--debug`
  - `CliRunner.run(Object command, String[] args, PrintWriter out, PrintWriter err, Map<String, String> env, boolean interactive)`

- [ ] **Step 1: Write unit tests for `CliRunner`**

Verify:
- Exit code 0 on normal return.
- Exit code 1 and clean stderr message when `CliExecutionException` is thrown.
- Exit code 2 on Picocli usage/parsing errors or `CliUsageException`.
- `--debug` flag outputs stack trace.
- `--no-color` disables ANSI styling.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CliRunnerTest -pl cli-common`
Expected: FAIL

- [ ] **Step 3: Implement `CliRunner`, `GlobalOptionsMixin`, and exception classes**

Pin UTF-8 streams, handle Picocli lifecycle, wire exit code converters and exception handlers.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=CliRunnerTest -pl cli-common`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli-common/src/main/java/io/github/lihuu/clicommon/cli/ cli-common/src/test/java/io/github/lihuu/clicommon/cli/
git commit -m "feat(cli-common): implement unified CliRunner, GlobalOptionsMixin, and exit code protocol"
```

---

### Task 7: Migrate `tokenkit` to Use `cli-common`

**Files:**
- Modify: `.worktrees/cli-common/tokenkit/pom.xml`
- Modify: `.worktrees/cli-common/tokenkit/src/main/java/io/github/lihuu/tokenkit/cli/OutputStyle.java` -> delete or delegate to `cli-common`
- Modify: `.worktrees/cli-common/tokenkit/src/main/java/io/github/lihuu/tokenkit/util/ShellFormatter.java` -> delegate or delete
- Modify: `.worktrees/cli-common/tokenkit/src/main/java/io/github/lihuu/tokenkit/util/ClipboardUtil.java` -> delegate or delete
- Modify: `.worktrees/cli-common/tokenkit/src/main/java/io/github/lihuu/tokenkit/app/CliApplication.java`
- Modify: `.worktrees/cli-common/tokenkit/src/main/java/io/github/lihuu/tokenkit/cli/ListCommand.java`

- [ ] **Step 1: Update `tokenkit/pom.xml` with `cli-common` dependency**

Add `cli-common` dependency to `tokenkit/pom.xml`.

- [ ] **Step 2: Refactor `tokenkit` classes to import `cli-common` packages**

Replace local utilities with `io.github.lihuu.clicommon.style.OutputStyle`, `ShellFormatter`, `ClipboardUtil`, and `JsonWriter`.

- [ ] **Step 3: Run `tokenkit` tests to verify backward compatibility**

Run: `mvn test -pl tokenkit`
Expected: PASS (all 45 tests pass)

- [ ] **Step 4: Commit**

```bash
git add tokenkit/
git commit -m "refactor(tokenkit): migrate terminal styling, shell formatter, and clipboard to cli-common"
```

---

### Task 8: Migrate `certkit` to Use `cli-common`

**Files:**
- Modify: `.worktrees/cli-common/certkit/pom.xml`
- Modify: `.worktrees/cli-common/certkit/src/main/java/io/github/lihuu/certkit/Main.java`
- Modify: `.worktrees/cli-common/certkit/src/main/java/io/github/lihuu/certkit/CertInfo.java`
- Modify: `.worktrees/cli-common/certkit/src/main/java/io/github/lihuu/certkit/TrustStore.java`

- [ ] **Step 1: Update `certkit/pom.xml` with `cli-common` dependency**

Add `cli-common` dependency to `certkit/pom.xml`.

- [ ] **Step 2: Refactor `certkit.Main` to use `GlobalOptionsMixin` and `OutputStyle`**

Replace hardcoded console strings with `outputStyle.success(...)`, `outputStyle.tip(...)`.
Replace `CertInfo.toJson()` with `JsonWriter`.
Replace `TrustStore.detectPlatform()` with `Platform.current()`.

- [ ] **Step 3: Run `certkit` tests to verify backward compatibility**

Run: `mvn test -pl certkit`
Expected: PASS (all 12 tests pass)

- [ ] **Step 4: Commit**

```bash
git add certkit/
git commit -m "refactor(certkit): migrate to cli-common for styling, json writing, and platform detection"
```

---

### Task 9: Migrate `gpgkit` to Use Unified `cli-common` Shell

**Files:**
- Modify: `.worktrees/cli-common/gpgkit/pom.xml`
- Modify: `.worktrees/cli-common/gpgkit/src/main/java/io/github/lihuu/gpgkit/Main.java`
- Modify: Subcommands in `gpgkit` (`StatusCommand`, `GenCommand`, `BackupCommand`, `RestoreCommand`, etc.)
- Replace: `gpgkit.Json` with `cli-common.json.JsonWriter`

- [ ] **Step 1: Update `gpgkit/pom.xml` with `cli-common` and Picocli dependencies**

Add `cli-common` and `picocli-codegen` compiler plugin to `gpgkit/pom.xml`.

- [ ] **Step 2: Refactor `gpgkit.Main` and command dispatch to use `CliRunner` & Picocli annotations**

Ensure exact flags (`--uid`, `--expire`, `--algo`, `--passphrase-file`, `--no-passphrase`, `--publish`, `--key`, `--out`, `--dest`, `--yes`, `--sig`, `--checksums`, `--keyserver`, `--json`, `--quiet`, `--help`) and exit codes (`0, 1, 2`) are maintained.

- [ ] **Step 3: Run `gpgkit` test suite to verify full contract compliance**

Run: `mvn test -pl gpgkit`
Expected: PASS (all 25 tests pass)

- [ ] **Step 4: Commit**

```bash
git add gpgkit/
git commit -m "refactor(gpgkit): migrate gpgkit to unified cli-common Picocli shell"
```

---

### Task 10: Full Test Suite Verification & Documentation Update

**Files:**
- Modify: `.worktrees/cli-common/README.md`
- Modify: `.worktrees/cli-common/README_zh.md`
- Modify: `.worktrees/cli-common/SKILL.md`

- [ ] **Step 1: Run full Maven test suite across all modules**

Run: `mvn clean test`
Expected: BUILD SUCCESS (all modules `cli-common`, `gpgkit`, `certkit`, `tokenkit` pass 100%)

- [ ] **Step 2: Update documentation and monorepo structure in README**

Document `cli-common` module role and common CLI developer guide for future tools.

- [ ] **Step 3: Commit**

```bash
git add README.md README_zh.md SKILL.md
git commit -m "docs: document cli-common shared shell architecture across li-tools"
```
