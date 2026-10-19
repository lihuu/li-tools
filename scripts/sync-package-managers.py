#!/usr/bin/env python3
"""
Sync Homebrew Tap and Scoop Bucket on Release.

Reads binary SHA256 checksums from release-assets/SHA256SUMS.txt and updates:
1. lihuu/homebrew-tap (Formula/*.rb) -> branch 'main'
2. lihuu/my-scoop-bucket (bucket/*.json) -> branch 'master'
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description="Sync package managers on release")
    parser.add_argument("--sums", default="release-assets/SHA256SUMS.txt", help="Path to SHA256SUMS.txt")
    parser.add_argument("--version", help="Release version (e.g. 0.2.0)")
    parser.add_argument("--dry-run", action="store_true", help="Print changes without pushing")
    return parser.parse_args()


def parse_checksums(sums_file: Path) -> dict:
    if not sums_file.is_file():
        raise FileNotFoundError(f"Checksum file not found: {sums_file}")

    checksums = {}
    with open(sums_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            if len(parts) == 2:
                sha, filename = parts[0], parts[1].lstrip("*")
                checksums[filename] = sha
    return checksums


def resolve_version(args_version: str | None) -> str:
    version = args_version
    if not version:
        version = os.environ.get("GITHUB_REF_NAME") or os.environ.get("RELEASE_VERSION")
    if not version:
        raise ValueError("Release version must be provided via --version or GITHUB_REF_NAME")
    return version.lstrip("v")


def update_homebrew_formula(formula_path: Path, tool: str, version: str, darwin_hash: str, linux_hash: str) -> bool:
    content = formula_path.read_text(encoding="utf-8")
    original = content

    # 1. Update version "x.y.z"
    content = re.sub(r'version "[^"]+"', f'version "{version}"', content)

    # 2. Update macOS arm64 url and sha256
    darwin_url = f"https://github.com/lihuu/li-tools/releases/download/v{version}/{tool}-darwin-arm64"
    content = re.sub(
        rf'url "https://github.com/lihuu/li-tools/releases/download/v[^/]+/{tool}-darwin-arm64"',
        f'url "{darwin_url}"',
        content,
    )
    # Match sha256 in on_macos block
    macos_pattern = re.compile(
        r'(on_macos\s+do.*?Hardware::CPU\.arm\?.*?sha256\s+")[a-f0-9]{64}(")',
        re.DOTALL,
    )
    content = macos_pattern.sub(rf"\g<1>{darwin_hash}\g<2>", content)

    # 3. Update Linux amd64 url and sha256
    linux_url = f"https://github.com/lihuu/li-tools/releases/download/v{version}/{tool}-linux-amd64"
    content = re.sub(
        rf'url "https://github.com/lihuu/li-tools/releases/download/v[^/]+/{tool}-linux-amd64"',
        f'url "{linux_url}"',
        content,
    )
    linux_pattern = re.compile(
        r'(on_linux\s+do.*?Hardware::CPU\.intel\?.*?sha256\s+")[a-f0-9]{64}(")',
        re.DOTALL,
    )
    content = linux_pattern.sub(rf"\g<1>{linux_hash}\g<2>", content)

    # 4. Update test assert_match version if checking --version
    content = re.sub(
        rf'assert_match "[0-9.]+", shell_output\("#\{{bin\}}/{tool} --version"\)',
        f'assert_match "{version}", shell_output("#{{bin}}/{tool} --version")',
        content,
    )

    if content != original:
        formula_path.write_text(content, encoding="utf-8")
        return True
    return False


def update_scoop_manifest(manifest_path: Path, tool: str, version: str, windows_hash: str) -> bool:
    with open(manifest_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    data["version"] = version
    if "architecture" in data and "64bit" in data["architecture"]:
        data["architecture"]["64bit"]["url"] = (
            f"https://github.com/lihuu/li-tools/releases/download/v{version}/{tool}-windows-amd64.exe#/{tool}.exe"
        )
        data["architecture"]["64bit"]["hash"] = windows_hash

    content = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    original = manifest_path.read_text(encoding="utf-8")
    if content != original:
        manifest_path.write_text(content, encoding="utf-8")
        return True
    return False


def sync_repository(
    repo_name: str,
    branch: str,
    token: str,
    version: str,
    update_fn,
    dry_run: bool,
):
    print(f"\n==> Syncing {repo_name} (branch: {branch}) for v{version}...")
    temp_dir = tempfile.mkdtemp(prefix=f"sync-{repo_name.replace('/', '-')}-")
    try:
        clone_url = f"https://x-access-token:{token}@github.com/{repo_name}.git"
        subprocess.run(
            ["git", "clone", "--depth", "1", "--branch", branch, clone_url, temp_dir],
            check=True,
            capture_output=True,
            text=True,
        )

        repo_path = Path(temp_dir)
        changed_count = update_fn(repo_path)

        if changed_count == 0:
            print(f"ℹ No changes needed in {repo_name}.")
            return

        print(f"✔ Updated {changed_count} files in {repo_name}.")

        subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=temp_dir, check=True)
        subprocess.run(
            ["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"],
            cwd=temp_dir,
            check=True,
        )

        status = subprocess.run(["git", "status", "--short"], cwd=temp_dir, capture_output=True, text=True).stdout
        print(f"Git status:\n{status.strip()}")

        if dry_run:
            diff = subprocess.run(["git", "diff"], cwd=temp_dir, capture_output=True, text=True).stdout
            print(f"[DRY RUN] Diff for {repo_name}:\n{diff}")
            return

        subprocess.run(["git", "add", "-A"], cwd=temp_dir, check=True)
        subprocess.run(
            ["git", "commit", "-m", f"chore(release): sync package manifests for v{version}"],
            cwd=temp_dir,
            check=True,
        )
        subprocess.run(["git", "push", "origin", branch], cwd=temp_dir, check=True)
        print(f"🚀 Pushed updates to {repo_name} ({branch}) successfully!")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def main():
    args = parse_args()
    token = os.environ.get("ECOSYSTEM_TOKEN") or os.environ.get("GH_TOKEN")

    if not token and not args.dry_run:
        print("⚠️ Warning: ECOSYSTEM_TOKEN not found in environment. Skipping package manager sync.")
        sys.exit(0)

    try:
        version = resolve_version(args.version)
        checksums = parse_checksums(Path(args.sums))
    except Exception as e:
        print(f"❌ Failed to initialize sync: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Starting package manager sync for li-tools v{version}...")
    print(f"Loaded {len(checksums)} checksums from {args.sums}")

    # 1. Update Homebrew Tap
    def update_homebrew(repo_path: Path) -> int:
        formula_dir = repo_path / "Formula"
        if not formula_dir.is_dir():
            print(f"⚠️ Formula directory not found in {repo_path}")
            return 0

        updated = 0
        for formula_file in formula_dir.glob("*.rb"):
            tool = formula_file.stem
            darwin_hash = checksums.get(f"{tool}-darwin-arm64")
            linux_hash = checksums.get(f"{tool}-linux-amd64")
            if not darwin_hash or not linux_hash:
                print(f"  - Skipping {tool}.rb (missing checksums: darwin={bool(darwin_hash)}, linux={bool(linux_hash)})")
                continue

            if update_homebrew_formula(formula_file, tool, version, darwin_hash, linux_hash):
                print(f"  + Updated Formula/{tool}.rb")
                updated += 1
            else:
                print(f"  = Formula/{tool}.rb already up to date")
        return updated

    sync_repository("lihuu/homebrew-tap", "main", token or "dummy", version, update_homebrew, args.dry_run)

    # 2. Update Scoop Bucket
    def update_scoop(repo_path: Path) -> int:
        bucket_dir = repo_path / "bucket"
        if not bucket_dir.is_dir():
            print(f"⚠️ bucket directory not found in {repo_path}")
            return 0

        updated = 0
        for manifest_file in bucket_dir.glob("*.json"):
            tool = manifest_file.stem
            windows_hash = checksums.get(f"{tool}-windows-amd64.exe")
            if not windows_hash:
                print(f"  - Skipping {tool}.json (missing windows checksum)")
                continue

            if update_scoop_manifest(manifest_file, tool, version, windows_hash):
                print(f"  + Updated bucket/{tool}.json")
                updated += 1
            else:
                print(f"  = bucket/{tool}.json already up to date")
        return updated

    sync_repository("lihuu/my-scoop-bucket", "master", token or "dummy", version, update_scoop, args.dry_run)

    print("\n🎉 Package manager sync completed successfully!")


if __name__ == "__main__":
    main()
