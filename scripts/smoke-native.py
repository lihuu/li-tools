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


def run(command, directory, environment, expected=0, input_text=None):
    result = subprocess.run(
        [str(argument) for argument in command],
        cwd=directory, env=environment, capture_output=True, input=input_text,
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
        for tool in ("gpgkit", "certkit", "tokenkit", "skillkit", "jsonkit", "dukit", "mitmkit")
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

        jsonkit = binaries["jsonkit"]
        fixture = directory / "json kit fixture.json"
        fixture.write_text(
            '{"user":{"name":"Alice"},"items":[{"id":1,"status":"failed"},'
            '{"id":2,"status":"ok"},{"id":3,"status":"failed"}],'
            '"big":123456789012345678901234567890,"unicode":{"键":"值"}}',
            encoding="utf-8",
        )
        formatted = run([jsonkit, fixture], directory, environment)
        assert json.loads(formatted)["user"]["name"] == "Alice", formatted
        assert '"big": 123456789012345678901234567890' in formatted, formatted
        assert '"键": "值"' in formatted, formatted
        assert run([jsonkit, "-p", "user.name", fixture], directory, environment) == '"Alice"\n'
        assert json.loads(run([jsonkit, "-p", "items[*].id", fixture], directory, environment)) == [1, 2, 3]
        assert json.loads(run([jsonkit, "-p", "items[?@.status == 'failed'].id", fixture],
                              directory, environment)) == [1, 3]
        assert run([jsonkit, "-p", "big", fixture], directory, environment) == "123456789012345678901234567890\n"
        run([jsonkit, "-p", "missing", fixture], directory, environment, expected=1)
        assert run([jsonkit, "-p", "items[*].missing", fixture], directory, environment) == "[]\n"
        run([jsonkit, "-p", "$[", fixture], directory, environment, expected=2)
        piped = run([jsonkit], directory, environment, input_text='{"a":1}')
        assert json.loads(piped) == {"a": 1}, piped
        explicit = run([jsonkit, "-"], directory, environment, input_text='{"a":1}')
        assert json.loads(explicit) == {"a": 1}, explicit
        piped_query = run([jsonkit, "-p", "a"], directory, environment, input_text='{"a":1}')
        assert json.loads(piped_query) == 1, piped_query
        duplicate = directory / "duplicate.json"
        duplicate.write_text('{"a":1,"a":2}', encoding="utf-8")
        duplicate_view = run([jsonkit, duplicate], directory, environment)
        assert duplicate_view.count('"a":') == 2, duplicate_view
        assert json.loads(duplicate_view) == {"a": 2}, duplicate_view
        run([jsonkit, "-p", "a", duplicate], directory, environment, expected=1)
        print("jsonkit: format, JSONPath, stdin and duplicate-key contract PASS", flush=True)

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

        mitmkit = binaries["mitmkit"]
        mitmkit_env = dict(environment, MITMKIT_CA_DIR=str(directory / "mitmkit-ca"))
        ca = json.loads(run([mitmkit, "ca", "--json"], directory, mitmkit_env))
        assert Path(ca["caCert"]).is_file() and Path(ca["caKey"]).is_file(), ca
        run([mitmkit, "ca"], directory, mitmkit_env, expected=1)
        run([mitmkit, "ca", "--force"], directory, mitmkit_env)
        print("mitmkit: CA generation, JSON metadata and overwrite guard PASS", flush=True)

        import time
        import http.server

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                body = b"hello"
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        upstream = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        upstream_port = upstream.server_address[1]
        import threading
        threading.Thread(target=upstream.serve_forever, daemon=True).start()

        proxy_port = 18080
        proxy = subprocess.Popen(
            [str(mitmkit), "run", "--port", str(proxy_port), "--jsonl"],
            cwd=directory, env=mitmkit_env, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        )
        try:
            time.sleep(2)
            # Issue the proxied request at the socket level so no machine-level
            # proxy or no_proxy setting can bypass mitmkit.
            import http.client
            connection = http.client.HTTPConnection("127.0.0.1", proxy_port, timeout=15)
            connection.request("GET", f"http://127.0.0.1:{upstream_port}/hello")
            response = connection.getresponse()
            response.read()
            connection.close()
            assert response.status == 200, response.status
            proxy.terminate()
            proxy.wait(timeout=10)
            flows = [line for line in (proxy.stdout.read() or "").splitlines() if line.startswith("{")]
            assert flows, "mitmkit should emit at least one JSONL flow"
            record = json.loads(flows[0])
            assert record["method"] == "GET" and record["status"] == 200, record
            assert "/hello" in record["url"], record
        finally:
            if proxy.poll() is None:
                proxy.terminate()
                proxy.wait(timeout=10)
            upstream.shutdown()
        print("mitmkit: HTTP interception and JSONL flow PASS", flush=True)


if __name__ == "__main__":
    main()
