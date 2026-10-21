# gpgkit

**gpgkit puts the whole life of a GPG key behind one command.** Generate, back up, restore, renew,
sign, verify, publish the public key, and check that any of it still works — each one a single
command, with none of the long `gpg` invocations left to remember.

[中文说明 →](README.zh-CN.md)

It exists for one specific failure: **you make a key on one machine, move to another, the key is
gone, and so you stop using GPG altogether.** That is why the first-class citizen here is not
signing — it is **verifiable backup and restore**:

- every `backup` has to prove, on the spot, that the archive **can be restored** (a round-trip
  check);
- every `restore` has to prove, on the spot, that the restored key **produces a signature that
  verifies**.

Neither is optional, and neither prints "probably fine". When it cannot prove the claim, it exits
with code 1 and leaves no half-written files behind.

gpgkit does **not** implement cryptography. `gpg` is the engine; `openssl` is used only to generate
a random passphrase. Its job is **orchestration, verification, and record-keeping** — and pinning
down the external-command flags that are easy to forget and expensive to get wrong.

## Requirements

| | |
|---|---|
| `gpg` | required — it is the engine. macOS: `brew install gnupg` |
| JDK 25 | to build from source |
| GraalVM for JDK 25 | only for the native build |

The native executable needs **no JDK on the machine that runs it**; that is the whole point of
building it.

## Build

**Native — the artifact you install.** Needs GraalVM for JDK 25 in `JAVA_HOME` (or discoverable via
`/usr/libexec/java_home -v 25`):

```shell
./build-native.sh
./target/gpgkit --help
```

This produces the single executable `./target/gpgkit`. If it reports that `native-image` is
missing, `JAVA_HOME` points at a plain JDK rather than at GraalVM. To install it:

```shell
cp target/gpgkit ~/.local/bin/gpgkit
```

**JVM — much faster to iterate on**, for development only:

```shell
mvn -q package
java -jar target/gpgkit.jar --help
```

Both paths compile the same sources and run the same code; only the packaging differs. The
distribution target is the native executable.

## Quick start

**Back up.** A key with no passphrase still gets encrypted on the way out — there is no unencrypted
mode:

```shell
gpgkit backup --out ~/gpg-backup --generate-passphrase-to ~/gpg-backup-passphrase.txt
```

On success you get the export and encryption steps, a line confirming the **round-trip check
passed**, the path of `manifest.json`, and finally a **ready-to-paste restore command**. Keep that
command with the backup files.

**Then keep the two files apart.** The ciphertext and the passphrase must travel by different
routes — a synced folder for one, a password manager for the other. Where to put them, and why it
matters: [docs/backup-placement.md](docs/backup-placement.md). This is the one step the tool cannot
do for you.

**Restore on the other machine:**

```shell
gpgkit restore ~/gpg-backup/<that file>.asc.gpg
```

On success you get the imported fingerprint, its expiry date, and a line confirming the
**sign-and-verify round trip passed**. It then tells you what to do next (set
`git config user.signingkey`, re-publish the public key, and so on).

## Global options and exit codes

Every subcommand accepts:

| Option | Effect |
|---|---|
| `--json` | machine-readable output on stdout |
| `--quiet` | the short answer only, no explanations |
| `--help` | usage |

Exit codes are a fixed contract that scripts and CI can rely on:

| Code | Meaning |
|---|---|
| `0` | success |
| `1` | failure; the reason is on stderr |
| `2` | usage error |

**No private key, passphrase, or token is ever printed** — only fingerprints, dates, checksums,
and paths. Pasting gpgkit's output into an issue or a chat is safe.

Two environment variables exist, and only these two:

| Variable | Effect |
|---|---|
| `GPGKIT_BACKUP_DIR` | overrides the default backup directory (`~/.gpgkit/backups`), which is where `doctor` looks |
| `GPGKIT_PASSPHRASE_FILE` | overrides the default passphrase file (`~/.gpgkit/backup-passphrase.txt`) |

`GPGKIT_PASSPHRASE_FILE` is an environment variable rather than a `doctor` flag on purpose: a
passphrase passed on the command line ends up in the shell history.

## Commands

| Command | In one line |
|---|---|
| [`status`](#gpgkit-status) | what keys this machine has, and how long they last |
| [`gen`](#gpgkit-gen) | generate a new key pair |
| [`backup`](#gpgkit-backup) | back up (the core command) |
| [`restore`](#gpgkit-restore) | restore |
| [`extend`](#gpgkit-extend) | renew the expiry date |
| [`sign`](#gpgkit-sign) | sign release artifacts |
| [`verify`](#gpgkit-verify) | verify a signature or a checksum list |
| [`publish`](#gpgkit-publish) | push the public key to a keyserver |
| [`doctor`](#gpgkit-doctor) | one command to answer "can this machine still sign, and still publish?" |

### `gpgkit status`

```shell
gpgkit status [--refresh]
```

Lists the local keys: fingerprint, algorithm/length, creation date, **expiry date and days
remaining**, usage flags (SC/E), and whether the private key is present here. It also makes a
best-effort check of whether the public key is visible on a keyserver (using a fast local cache by
default, or querying the remote keyserver when `--refresh` is passed) — **failing that check does
not change the exit code**, because an unreachable network should not fail a local query.

The output ends with a one-line verdict, which is the whole point of the command:

- `OK`
- `WARN: 90 天内到期` (expires within 90 days)
- `WARN: 没有可用签名密钥` (no usable signing key)

On a brand-new machine with **no keys at all**, this command still has to say something useful
rather than print empty tables.

If you get the 90-day warning, read [docs/key-expiry.md](docs/key-expiry.md).

### `gpgkit gen`

```shell
gpgkit gen [--uid "Name <mail>"] [--expire 2y] [--algo rsa4096|ed25519]
           [--passphrase-file F | --no-passphrase] [--publish]
```

Defaults: `rsa4096`, `2y`, and **no passphrase**. Without `--uid`, the user id comes from
`git config user.name` and `user.email`.

It prints the **full fingerprint** (not a short id), the creation and expiry dates, and tells you
that the next step is `backup`. That prompt is serious: **do not skip the backup.** For a key with
no passphrase, the private key file *is* the credential — losing it means losing the identity.

`--publish` pushes the public key to a keyserver right after generating (see
[`publish`](#gpgkit-publish)).

There is deliberately **no `--yes`**: `gen` never asks a question, so it does not need one.
No passphrase means headless signing works — see the note in [docs/key-expiry.md](docs/key-expiry.md).

### `gpgkit backup`

```shell
gpgkit backup [--key <id|all>] [--out DIR]
              [--passphrase-file F | --generate-passphrase-to F]
              [--dest <spec>]...
```

This is the core of the tool. It does five things in order:

1. **Export** the secret key (armored), the public key, and the relevant **ownertrust/trustdb
   entries** — restore trust state or the restored key can sign but its trust relationships are
   gone.
2. **Encrypt** with **AES256 symmetric encryption**. The passphrase comes from `--passphrase-file F`,
   or gpgkit generates a random one and writes it to `F` (`--generate-passphrase-to F`) — **a
   generated passphrase is never printed to stdout**, it only lands in the file you named.
   **There is no "unencrypted" option**: a backup is always encrypted. If plaintext were allowed,
   the round-trip check would degrade into comparing a file against itself — a hollow check buying
   formal compliance — and an unencrypted copy of a private key on disk is a copy of the identity.
3. **Round-trip check (cannot be skipped)**: decrypt the result and compare it **byte for byte**
   with the original export. On a mismatch it fails and **writes nothing to any destination** —
   better nothing at all than an archive you believe is good.
4. **Write the destinations**: `--dest` may be given more than once.
5. Write `manifest.json` **beside each destination**, and print a **ready-to-paste restore
   command**.

The two destination forms:

| Form | Where it goes |
|---|---|
| `file:/abs/path` | an absolute path on this machine |
| `onedrive:<relative path>` | `~/Library/CloudStorage/OneDrive-Personal/<relative path>` |

Without `--dest`, it writes one copy to `file:<out>` and reminds you that no other destination is
configured.

`manifest.json` records: fingerprint, user ids, algorithm, created/expires, the **sha256 of the
ciphertext**, when it was backed up, **where the passphrase is** (a free-text note for a human —
**not the passphrase itself**), and the restore command verbatim. Six months from now, that file
tells you where to look for the passphrase and what to type.

Where the ciphertext and the passphrase each belong, and **why they must stay apart**:
[docs/backup-placement.md](docs/backup-placement.md).

### `gpgkit restore`

```shell
gpgkit restore FILE [--passphrase-file F] [--keyring DIR] [--yes]
```

1. Decrypt, then `gpg --import` (secret and public), and restore ownertrust.
2. **Acceptance (cannot be skipped)**: **sign a temporary payload with the imported private key and
   verify it back**. If that fails, exit code 1 — and it **names the step that broke**: decryption,
   import, or signing.
3. Print the fingerprint, the expiry date, and **what to do next**: set
   `git config user.signingkey`, re-publish the public key, and so on.

The full machine-migration procedure, including how to choose the transfer channel and what
success looks like at each step: [docs/migrate-to-new-machine.md](docs/migrate-to-new-machine.md).

### `gpgkit extend`

```shell
gpgkit extend [--key <id>] [--expire 2y] [--publish]
```

Renewal **only rewrites the self-signature; it does not touch the key material** — so **signatures
you have already published are unaffected**, and nobody's past verification result turns red
because of the renewal. This is the right answer as an expiry date approaches, instead of
generating a new key pair and trying to get the world to switch.

It prints **old expiry date → new expiry date** and reminds you that the self-signature changed, so
the public key must be **published again** — otherwise the keyserver keeps serving the old one.

### `gpgkit sign`

```shell
gpgkit sign <file>... [--checksums sha256] [--out DIR] [--key <id>]
```

For release artifacts. Each file gets a detached signature `<file>.asc`. With `--checksums` it also
produces `SHA256SUMS` and `SHA256SUMS.asc`, the latter being a signature over the list itself, so
that trusting one file is enough.

The output lists **every produced file with its size in bytes**, so you can check how many things
were signed and how many were uploaded.

### `gpgkit verify`

```shell
gpgkit verify <file> [--sig <file.asc>] [--checksums FILE]
```

Verifies a detached signature. Given `--checksums`, it also checks every entry against the list.
**Any mismatch names the file it belongs to** — no "1 file did not match", which is not something
anyone can act on.

### `gpgkit publish`

```shell
gpgkit publish [--key <id>] [--keyserver hkps://keyserver.ubuntu.com]
```

Pushes the public key to a keyserver. On failure it gives you **an actionable next step** — try
another keyserver, check the network proxy — rather than a bare error.

### `gpgkit doctor`

```shell
gpgkit doctor
```

One command to answer "can this machine still sign, and still publish?". It checks:

- where `gpg` is and what version it is;
- whether a signing key exists, and whether **fewer than 90 days** remain;
- **actually signs something** (a temporary payload) and verifies it back — "it can sign" is
  proven, not guessed;
- if backup settings exist: whether the archive is there and **whether it can be decrypted** (when
  the passphrase is obtainable);
- keyserver reachability;
- whether `<id>central</id>` exists in `~/.m2/settings.xml`, which Maven Central needs.

That last item **reports only present/absent and never prints the token**.

Each line is `PASS`, `WARN`, or `FAIL`, and **the exit code follows from whether there was a
FAIL** — so it can go straight into a pre-release script.

Run it after migrating machines, before publishing, and any time GPG crosses your mind.

## Documentation

| Document | When to read it |
|---|---|
| [docs/backup-placement.md](docs/backup-placement.md) | where the ciphertext goes, where the passphrase goes, why they must stay apart |
| [docs/migrate-to-new-machine.md](docs/migrate-to-new-machine.md) | moving to a new machine, start to finish |
| [docs/key-expiry.md](docs/key-expiry.md) | the key is expiring, or already expired |
| [SPEC.md](SPEC.md) | the interface contract; change it first, then the code |

Detailed docs (SPEC.md, docs/) are currently in Chinese. So are the human-readable verdict strings
that `status` prints — the machine-readable half of that output (`--json`) carries a language-free
level (`OK`/`WARN`) precisely so scripts never have to parse the prose.

## License

MIT — see [LICENSE](LICENSE).
