"""Run the public skills installer with isolated targets and downloads."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


INSTALLER = Path(__file__).resolve().parents[2] / "site" / "install-skills.sh"
SKILLS = {"certkit", "dukit", "gpgkit", "jsonkit", "mitmkit", "skillkit", "tokenkit"}


@unittest.skipUnless(os.name == "posix", "requires a POSIX bash environment")
class InstallSkillsTest(unittest.TestCase):
    def test_installs_into_a_target_with_spaces(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "skills target"
            result = subprocess.run(
                ["bash", str(INSTALLER), "--target", str(target)],
                cwd=directory, capture_output=True, text=True, timeout=15,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(SKILLS, {file.parent.name for file in target.glob("*/SKILL.md")})

    def test_reports_failed_downloads_to_automation(self):
        with tempfile.TemporaryDirectory() as directory:
            scratch = Path(directory)
            curl = scratch / "curl"
            curl.write_text("#!/bin/sh\nexit 22\n", encoding="utf-8")
            curl.chmod(0o700)
            target = scratch / "skills"
            result = subprocess.run(
                ["bash", "-s", "--", "--target", str(target)],
                cwd=scratch, env={**os.environ, "PATH": f"{scratch}{os.pathsep}{os.environ['PATH']}"},
                input=INSTALLER.read_text(encoding="utf-8"), capture_output=True, text=True, timeout=15,
            )

            self.assertEqual(1, result.returncode, result.stdout)
            self.assertNotIn("All skills installed successfully", result.stdout)
            self.assertEqual(len(SKILLS), result.stderr.count("Failed to download"))
            self.assertEqual([], list(target.glob("*/SKILL.md")))
