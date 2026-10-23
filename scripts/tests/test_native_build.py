import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
TOOLS = ("gpgkit", "certkit", "tokenkit", "skillkit")


class NativeBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="native build ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.log = self.root / "calls.log"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        shutil.copy2(REPOSITORY / "pom.xml", self.root / "pom.xml")
        if (REPOSITORY / "build-native.sh").exists():
            shutil.copy2(REPOSITORY / "build-native.sh", self.root / "build-native.sh")
        for tool in TOOLS:
            directory = self.root / tool
            directory.mkdir()
            shutil.copy2(REPOSITORY / tool / "build-native.sh", directory / "build-native.sh")
        self.write_executable("mvn", """#!/bin/sh
set -e
printf 'maven:%s\n' "$*" >> "$BUILD_CALL_LOG"
if [ "${FAIL_MAVEN:-0}" != 0 ]; then exit "$FAIL_MAVEN"; fi
for tool in gpgkit certkit tokenkit skillkit; do
    mkdir -p "$BUILD_FIXTURE_ROOT/$tool/target"
    : > "$BUILD_FIXTURE_ROOT/$tool/target/$tool.jar"
done
""")
        self.write_executable("native-image", """#!/bin/sh
set -e
printf 'native:%s\n' "${PWD##*/}" >> "$BUILD_CALL_LOG"
if [ "${FAIL_NATIVE:-}" = "${PWD##*/}" ]; then exit 9; fi
while [ "$#" -gt 0 ]; do
    if [ "$1" = "-o" ]; then
        shift
        : > "$1"
        exit 0
    fi
    shift
done
exit 1
""")
        self.environment = dict(os.environ)
        self.environment.update(
            PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
            BUILD_CALL_LOG=self.log.as_posix(),
            BUILD_FIXTURE_ROOT=self.root.as_posix(),
        )
        self.environment.pop("FAIL_MAVEN", None)
        self.environment.pop("FAIL_NATIVE", None)

    def write_executable(self, name, text):
        path = self.bin / name
        path.write_text(text, newline="\n")
        path.chmod(0o755)

    def run_build(self, relative, *arguments):
        script = self.root / relative
        self.assertTrue(script.is_file(), "missing build entry point: " + relative)
        return subprocess.run(
            ["sh", str(script), *arguments], cwd=self.root.parent,
            env=self.environment, capture_output=True, text=True, timeout=30,
        )

    def calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def prepare_jar(self, tool):
        target = self.root / tool / "target"
        target.mkdir(exist_ok=True)
        (target / (tool + ".jar")).touch()

    def test_explicit_skip_package_uses_prepared_jar_without_maven(self):
        for tool in TOOLS:
            with self.subTest(tool=tool):
                self.log.unlink(missing_ok=True)
                self.prepare_jar(tool)
                result = self.run_build(tool + "/build-native.sh", "--skip-package")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["native:" + tool], self.calls())
                self.assertTrue((self.root / tool / "target" / tool).is_file())

    def test_batch_packages_once_before_building_all_binaries(self):
        result = self.run_build("build-native.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        self.assertTrue(calls[0].startswith("maven:"), calls)
        self.assertIn("-pl gpgkit,certkit,tokenkit,skillkit -am", calls[0])
        self.assertEqual(["native:" + tool for tool in TOOLS], calls[1:])
        for tool in TOOLS:
            self.assertTrue((self.root / tool / "target" / tool).is_file())

    def test_standalone_still_packages_when_jar_already_exists(self):
        for tool in TOOLS:
            with self.subTest(tool=tool):
                self.log.unlink(missing_ok=True)
                self.prepare_jar(tool)
                result = self.run_build(tool + "/build-native.sh")
                self.assertEqual(0, result.returncode, result.stderr)
                calls = self.calls()
                self.assertEqual(2, len(calls), calls)
                self.assertTrue(calls[0].startswith("maven:"), calls)
                self.assertIn("-pl " + tool + " -am", calls[0])
                self.assertEqual("native:" + tool, calls[1])

    def test_skip_package_without_jar_fails_without_building(self):
        for tool in TOOLS:
            with self.subTest(tool=tool):
                result = self.run_build(tool + "/build-native.sh", "--skip-package")
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("requires target/" + tool + ".jar", result.stderr)
                self.assertEqual([], self.calls())

    def test_maven_failure_stops_batch_before_any_native_build(self):
        self.environment["FAIL_MAVEN"] = "7"
        result = self.run_build("build-native.sh")
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertEqual(1, len(self.calls()))
        self.assertTrue(self.calls()[0].startswith("maven:"))

    def test_native_failure_stops_batch_before_next_tool(self):
        self.environment["FAIL_NATIVE"] = "certkit"
        result = self.run_build("build-native.sh")
        self.assertEqual(9, result.returncode, result.stderr)
        self.assertEqual(["native:gpgkit", "native:certkit"], self.calls()[1:])
        self.assertFalse((self.root / "tokenkit/target/tokenkit").exists())
        self.assertFalse((self.root / "skillkit/target/skillkit").exists())

    def test_unknown_or_extra_arguments_fail_before_building(self):
        scripts = ["build-native.sh"] + [tool + "/build-native.sh" for tool in TOOLS]
        for script in scripts:
            for arguments in (("--unknown",), ("--skip-package", "extra")):
                with self.subTest(script=script, arguments=arguments):
                    result = self.run_build(script, *arguments)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertIn("usage:", result.stderr)
                    self.assertEqual([], self.calls())


if __name__ == "__main__":
    unittest.main()
