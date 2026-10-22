"""Exercise the built native executables with temporary, synthetic data."""

import json
import os
from pathlib import Path
import shutil
import subprocess
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
        for tool in ("gpgkit", "certkit", "tokenkit")
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
            if tool != "gpgkit":
                assert version in run([binary, "--version"], directory, environment), tool
            print(f"{tool}: native CLI PASS", flush=True)

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
                binaries["certkit"], "--json", "check", certificate,
            ], directory, environment))
            assert info["subjectCn"] == "localhost", info
            assert "IP:127.0.0.1" in info["sans"], info
            fingerprints.append(info["sha256Fingerprint"])
        assert fingerprints[0] == fingerprints[1] and fingerprints[0]
        print("certkit: PEM/DER certificate parsing PASS", flush=True)

        tokenkit = binaries["tokenkit"]
        run([tokenkit, "set", "NATIVE_SMOKE", "synthetic-native-value"], directory, environment)
        assert run([tokenkit, "get", "NATIVE_SMOKE"], directory, environment) == "synthetic-native-value"
        run([tokenkit, "rm", "NATIVE_SMOKE"], directory, environment)
        run([tokenkit, "get", "NATIVE_SMOKE"], directory, environment, expected=1)
        assert b"synthetic-native-value" not in (directory / "tokens.db").read_bytes()
        print("tokenkit: encrypted SQLite round trip PASS", flush=True)


if __name__ == "__main__":
    main()
