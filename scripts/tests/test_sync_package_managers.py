import json
from pathlib import Path
import tempfile
import unittest
import sys

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import importlib.util
spec = importlib.util.spec_from_file_location("sync_package_managers", SCRIPTS_DIR / "sync-package-managers.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class SyncPackageManagersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_extract_tools_from_checksums(self):
        checksums = {
            "gpgkit-darwin-arm64.tar.gz": "hash1",
            "gpgkit-linux-amd64.tar.gz": "hash2",
            "gpgkit-windows-amd64.zip": "hash3",
            "newtool-darwin-arm64.tar.gz": "hash4",
            "newtool-linux-amd64.tar.gz": "hash5",
            "newtool-windows-amd64.zip": "hash6",
            "SHA256SUMS.txt": "hash7",
        }
        tools = sync.extract_tools_from_checksums(checksums)
        self.assertEqual(tools, {"gpgkit", "newtool"})

    def test_homebrew_creates_new_formula_when_missing(self):
        tap_dir = self.root / "homebrew-tap"
        formula_dir = tap_dir / "Formula"
        formula_dir.mkdir(parents=True)

        checksums = {
            "skillkit-darwin-arm64.tar.gz": "aaaa" * 16,
            "skillkit-linux-amd64.tar.gz": "bbbb" * 16,
        }

        updated = sync.update_homebrew_repo(tap_dir, "0.5.0", checksums)
        self.assertEqual(updated, 1)

        formula_file = formula_dir / "skillkit.rb"
        self.assertTrue(formula_file.is_file())
        content = formula_file.read_text(encoding="utf-8")
        self.assertIn("class Skillkit < Formula", content)
        self.assertIn('version "0.5.0"', content)
        self.assertIn("aaaa" * 16, content)
        self.assertIn("bbbb" * 16, content)
        self.assertIn('bin.install "skillkit"', content)
        self.assertIn('assert_match "0.5.0", shell_output("#{bin}/skillkit --version")', content)

    def test_homebrew_updates_existing_formula(self):
        tap_dir = self.root / "homebrew-tap"
        formula_dir = tap_dir / "Formula"
        formula_dir.mkdir(parents=True)

        formula_file = formula_dir / "gpgkit.rb"
        formula_file.write_text("""class Gpgkit < Formula
  desc "Old desc"
  homepage "https://tools.lihu.me/gpgkit"
  version "0.3.0"
  license "Apache-2.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/lihuu/li-tools/releases/download/v0.3.0/gpgkit-darwin-arm64.tar.gz"
      sha256 "1111111111111111111111111111111111111111111111111111111111111111"

      def install
        bin.install "gpgkit"
      end
    end
  end

  on_linux do
    if Hardware::CPU.intel?
      url "https://github.com/lihuu/li-tools/releases/download/v0.3.0/gpgkit-linux-amd64.tar.gz"
      sha256 "2222222222222222222222222222222222222222222222222222222222222222"

      def install
        bin.install "gpgkit"
      end
    end
  end

  test do
    assert_match "0.3.0", shell_output("#{bin}/gpgkit --version")
  end
end
""", encoding="utf-8")

        checksums = {
            "gpgkit-darwin-arm64.tar.gz": "3333" * 16,
            "gpgkit-linux-amd64.tar.gz": "4444" * 16,
        }

        updated = sync.update_homebrew_repo(tap_dir, "0.4.0", checksums)
        self.assertEqual(updated, 1)

        content = formula_file.read_text(encoding="utf-8")
        self.assertIn('version "0.4.0"', content)
        self.assertIn("3333" * 16, content)
        self.assertIn("4444" * 16, content)
        self.assertIn('assert_match "0.4.0"', content)

    def test_scoop_creates_new_manifest_when_missing(self):
        bucket_repo = self.root / "my-scoop-bucket"
        bucket_dir = bucket_repo / "bucket"
        bucket_dir.mkdir(parents=True)

        checksums = {
            "skillkit-windows-amd64.zip": "cccc" * 16,
        }

        updated = sync.update_scoop_repo(bucket_repo, "0.5.0", checksums)
        self.assertEqual(updated, 1)

        manifest_file = bucket_dir / "skillkit.json"
        self.assertTrue(manifest_file.is_file())
        data = json.loads(manifest_file.read_text(encoding="utf-8"))
        self.assertEqual(data["version"], "0.5.0")
        self.assertEqual(data["architecture"]["64bit"]["hash"], "cccc" * 16)
        self.assertEqual(data["bin"], "skillkit.exe")
        self.assertIn("v0.5.0/skillkit-windows-amd64.zip", data["architecture"]["64bit"]["url"])

    def test_scoop_updates_existing_manifest(self):
        bucket_repo = self.root / "my-scoop-bucket"
        bucket_dir = bucket_repo / "bucket"
        bucket_dir.mkdir(parents=True)

        manifest_file = bucket_dir / "tokenkit.json"
        manifest_file.write_text(json.dumps({
            "version": "0.3.0",
            "description": "Token manager",
            "homepage": "https://tools.lihu.me/tokenkit",
            "license": "Apache-2.0",
            "architecture": {
                "64bit": {
                    "url": "https://github.com/lihuu/li-tools/releases/download/v0.3.0/tokenkit-windows-amd64.zip",
                    "hash": "oldhash"
                }
            },
            "bin": "tokenkit.exe",
            "checkver": {"github": "https://github.com/lihuu/li-tools"},
            "autoupdate": {
                "architecture": {
                    "64bit": {
                        "url": "https://github.com/lihuu/li-tools/releases/download/v$version/tokenkit-windows-amd64.zip"
                    }
                }
            }
        }, indent=2) + "\n", encoding="utf-8")

        checksums = {
            "tokenkit-windows-amd64.zip": "5555" * 16,
        }

        updated = sync.update_scoop_repo(bucket_repo, "0.4.0", checksums)
        self.assertEqual(updated, 1)

        data = json.loads(manifest_file.read_text(encoding="utf-8"))
        self.assertEqual(data["version"], "0.4.0")
        self.assertEqual(data["architecture"]["64bit"]["hash"], "5555" * 16)
        self.assertIn("v0.4.0/tokenkit-windows-amd64.zip", data["architecture"]["64bit"]["url"])

    def test_skips_when_missing_checksums(self):
        tap_dir = self.root / "homebrew-tap"
        (tap_dir / "Formula").mkdir(parents=True)
        # only darwin, missing linux
        checksums = {"tool-darwin-arm64.tar.gz": "hash"}
        updated = sync.update_homebrew_repo(tap_dir, "0.4.0", checksums)
        self.assertEqual(updated, 0)
        self.assertFalse((tap_dir / "Formula" / "tool.rb").exists())


if __name__ == "__main__":
    unittest.main()
