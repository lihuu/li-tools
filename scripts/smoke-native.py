"""Exercise the built native executables with temporary, synthetic data."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


REPOSITORY = Path(__file__).resolve().parents[1]


def run(command, directory, environment, expected=0):
    result = subprocess.run(
        [str(argument) for argument in command],
        cwd=directory, env=environment, capture_output=True,
        text=True, encoding="utf-8", errors="replace", timeout=30,
    )
    if result.returncode != expected:
        raise RuntimeError(
            f"{command}: expected exit {expected}, got {result.returncode}\n"
            f"{result.stdout}\n{result.stderr}"
        )
    if "\x1b" in result.stdout + result.stderr:
        raise AssertionError(f"ANSI escapes in redirected output: {command}")
    return result.stdout


def main():
    version = ET.parse(REPOSITORY / "pom.xml").getroot().findtext(
        "{http://maven.apache.org/POM/4.0.0}version"
    )
    if not version:
        raise ValueError("Root pom.xml must declare the project version")
    suffix = ".exe" if os.name == "nt" else ""
    binaries = {
        tool: REPOSITORY / tool / "target" / (tool + suffix)
        for tool in ("gpgkit", "certkit", "tokenkit", "skillkit", "dukit")
    }
    for binary in binaries.values():
        if not binary.is_file():
            raise FileNotFoundError(f"Build the native executable first: {binary}")

    with tempfile.TemporaryDirectory(prefix="li-tools-native-smoke-") as temporary:
        directory = Path(temporary)
        environment = dict(os.environ)
        environment.update(
            NO_COLOR="1",
            CERTKIT_CA_DIR=str(directory / "unused-ca"),
            GNUPGHOME=str(directory / "gnupg"),
            GPGKIT_KEYSERVER_CACHE=str(directory / "gpg-cache"),
            TOKENKIT_DB_PATH=str(directory / "tokens.db"),
            TOKENKIT_MASTER_KEY="ab" * 32,
        )
        for tool, binary in binaries.items():
            assert run([binary, "--help"], directory, environment).strip(), tool
            run([binary, "--not-a-real-option"], directory, environment, expected=2)
            assert version in run([binary, "--version"], directory, environment), tool
            print(f"{tool}: native CLI PASS", flush=True)

        gpgkit = binaries["gpgkit"]
        assert "--sig" in run([gpgkit, "verify", "--help"], directory, environment)
        run([gpgkit, "verify", "missing.tar.gz", "--sig", "missing.asc", "--json"], directory, environment, expected=1)
        certkit = binaries["certkit"]
        assert "--self-signed" in run([certkit, "gen", "--help"], directory, environment)
        assert version in run([certkit, "gen", "--version"], directory, environment)

        gpgconf = shutil.which("gpgconf")
        if os.name == "posix" and shutil.which("gpg") and gpgconf:
            with tempfile.TemporaryDirectory(prefix="gk-") as keyring:
                signing_env = dict(environment, GNUPGHOME=keyring)
                artifact = directory / "release-fixture.txt"
                artifact.write_text("synthetic release fixture\n", encoding="utf-8")
                try:
                    key = json.loads(run([gpgkit, "gen", "--json", "--uid", "Native Smoke <smoke@example.invalid>",
                                          "--algo", "ed25519"], directory, signing_env))
                    run([gpgkit, "sign", artifact, "--key", key["fingerprint"]], directory, signing_env)
                    verified = json.loads(run([gpgkit, "verify", artifact, "--sig", str(artifact) + ".asc", "--json"],
                                               directory, signing_env))
                    assert verified["ok"] and verified["fingerprint"] == key["fingerprint"], verified
                    print("gpgkit: generate, sign and verify PASS", flush=True)
                finally:
                    run([gpgconf, "--homedir", keyring, "--kill", "gpg-agent"], directory, signing_env)

        keytool = shutil.which("keytool")
        if keytool is None:
            raise FileNotFoundError("keytool must be on PATH from the selected JDK")
        store = directory / "fixture.p12"
        password = "synthetic-fixture-pass"
        run([
            keytool, "-genkeypair", "-alias", "native-smoke", "-keyalg", "RSA",
            "-keysize", "2048", "-dname", "CN=localhost", "-validity", "1",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
            "-keystore", store, "-storepass", password, "-keypass", password,
            "-noprompt",
        ], directory, environment)
        fingerprints = []
        for extension, options in (("pem", ["-rfc"]), ("der", [])):
            certificate = directory / ("fixture." + extension)
            run([
                keytool, "-exportcert", "-alias", "native-smoke", "-keystore", store,
                "-storepass", password, "-file", certificate, *options,
            ], directory, environment)
            info = json.loads(run([
                binaries["certkit"], "check", certificate, "--json",
            ], directory, environment))
            assert info["subjectCn"] == "localhost", info
            assert "IP:127.0.0.1" in info["sans"], info
            fingerprints.append(info["sha256Fingerprint"])
        assert fingerprints[0] == fingerprints[1] and fingerprints[0]
        print("certkit: PEM/DER certificate parsing PASS", flush=True)

        if shutil.which("openssl"):
            leaf = directory / "certs"
            generated = json.loads(run([certkit, "gen", "--self-signed", "--out", leaf, "localhost", "--json"],
                                       directory, environment))
            assert Path(generated["cert"]).resolve() == (leaf / "cert.pem").resolve(), generated
            assert Path(generated["key"]).resolve() == (leaf / "key.pem").resolve(), generated
            original_key = (leaf / "key.pem").read_bytes()
            run([certkit, "gen", "--self-signed", "--out", leaf, "localhost"], directory, environment, expected=1)
            assert (leaf / "key.pem").read_bytes() == original_key
            ca = json.loads(run([certkit, "ca", "--out", directory / "unused-ca", "--json"], directory, environment))
            assert ca["trustStatus"] == "not_requested", ca
            assert Path(ca["cert"]).is_file() and Path(ca["key"]).is_file(), ca
            print("certkit: artifact paths, overwrite protection and CA generation PASS", flush=True)
        else:
            print("certkit: generation checks skipped (openssl not on PATH)", flush=True)

        tokenkit = binaries["tokenkit"]
        run([tokenkit, "set", "NATIVE_SMOKE", "synthetic-native-value"], directory, environment)
        assert run([tokenkit, "get", "NATIVE_SMOKE"], directory, environment) == "synthetic-native-value"
        assert b"synthetic-native-value" not in (directory / "tokens.db").read_bytes()
        if sys.platform.startswith("linux"):
            stubs = directory / "clipboard-stubs"
            stubs.mkdir()
            for command in ("wl-copy", "xclip"):
                stub = stubs / command
                stub.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
                stub.chmod(0o700)
            clipboard_env = dict(environment, PATH=str(stubs) + os.pathsep + environment["PATH"])
            assert run([tokenkit, "get", "NATIVE_SMOKE", "--clip"], directory, clipboard_env, expected=1) == ""
        run([tokenkit, "rm", "NATIVE_SMOKE"], directory, environment, expected=2)
        assert run([tokenkit, "get", "NATIVE_SMOKE"], directory, environment) == "synthetic-native-value"
        removed = json.loads(run([tokenkit, "rm", "NATIVE_SMOKE", "MISSING", "--yes", "--json"],
                                 directory, environment, expected=1))
        assert [entry["status"] for entry in removed] == ["deleted", "not_found"], removed
        run([tokenkit, "get", "NATIVE_SMOKE"], directory, environment, expected=1)
        assert json.loads(run([tokenkit, "list", "--json"], directory, environment)) == []
        print("tokenkit: encrypted SQLite round trip PASS", flush=True)

        skillkit = binaries["skillkit"]
        skills_repo = directory / "skills-repo"
        skills_repo.mkdir()
        skills_home = directory / "skills-home"
        skills_home.mkdir()
        skillkit_args = [skillkit, "-Duser.home=" + str(skills_home), "--repository", skills_repo]
        assert json.loads(run([*skillkit_args, "list", "--json"], directory, environment)) == []
        missing = json.loads(run([*skillkit_args, "install", "missing", "--dry-run", "--json"],
                                 directory, environment, expected=1))
        assert missing[0]["status"] == "failed", missing
        remote = json.loads(run([*skillkit_args, "install", "missing", "--repo", "owner/unfetched", "--dry-run", "--json"],
                                directory, environment))
        assert remote[0]["status"] == "unverified", remote
        assert not (skills_home / ".agents").exists()
        print("skillkit: skills list and offline preview PASS", flush=True)

        dukit = binaries["dukit"]
        scan_root = directory / "dukit-tree" / "inner"
        scan_root.mkdir(parents=True)
        (scan_root / "payload.bin").write_bytes(b"x" * 4096)
        report = run([dukit, "scan", str(scan_root), "--text", "--depth", "1"], directory, environment)
        assert "inner" in report and "4.0 KiB" in report, report
        document = json.loads(run([dukit, "scan", str(scan_root)], directory, environment))
        assert document["meta"]["tool"] == "dukit", document
        assert document["tree"]["size"] == 4096, document
        shallow = json.loads(run([dukit, "scan", str(scan_root), "--depth", "0"], directory, environment))
        assert shallow["tree"]["children"] == [], shallow
        assert shallow["tree"]["size"] == 4096, "aggregates survive the depth cut"
        ranked = json.loads(run([dukit, "top", str(scan_root), "--files", "--json"], directory, environment))
        assert ranked["entries"][0]["size"] == 4096, ranked
        run([dukit, "scan", scan_root, "--text", "--json"], directory, environment, expected=2)
        run([dukit, "browse", str(scan_root)], directory, environment, expected=2)
        print("dukit: scan JSON, depth cut and top ranking PASS", flush=True)


if __name__ == "__main__":
    main()
