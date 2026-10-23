#!/usr/bin/env python3
"""
Package release binaries into compressed archives (.tar.gz and .zip)
and generate SHA256SUMS.txt.

Usage:
    python3 scripts/package-release-archives.py --assets-dir release-assets
"""

import argparse
import hashlib
import os
import shutil
import sys
import tarfile
import zipfile
from pathlib import Path

TOOLS = ("gpgkit", "certkit", "tokenkit", "skillkit")
PLATFORMS = ("darwin-arm64", "linux-amd64", "windows-amd64")


def sha256_file(filepath: Path) -> str:
    hasher = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def package_tar_gz(raw_bin: Path, output_tar: Path, entry_name: str):
    print(f"  Packaging {raw_bin.name} -> {output_tar.name} (entry: {entry_name})...")
    with tarfile.open(output_tar, "w:gz") as tar:
        tarinfo = tar.gettarinfo(raw_bin, arcname=entry_name)
        tarinfo.mode = 0o755
        with open(raw_bin, "rb") as f:
            tar.addfile(tarinfo, f)


def package_zip(raw_bin: Path, output_zip: Path, entry_name: str):
    print(f"  Packaging {raw_bin.name} -> {output_zip.name} (entry: {entry_name})...")
    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(raw_bin, arcname=entry_name)


def main():
    parser = argparse.ArgumentParser(description="Package release binaries into archives")
    parser.add_argument("--assets-dir", default="release-assets", help="Directory containing release binaries")
    args = parser.parse_args()

    assets_dir = Path(args.assets_dir)
    if not assets_dir.is_dir():
        print(f"Error: assets directory '{assets_dir}' not found", file=sys.stderr)
        sys.exit(1)

    created_archives = []
    raw_files_to_remove = []

    for tool in TOOLS:
        for platform in PLATFORMS:
            if platform == "windows-amd64":
                raw_name = f"{tool}-{platform}.exe"
                archive_name = f"{tool}-{platform}.zip"
                entry_name = f"{tool}.exe"
            else:
                raw_name = f"{tool}-{platform}"
                archive_name = f"{tool}-{platform}.tar.gz"
                entry_name = tool

            raw_path = assets_dir / raw_name
            archive_path = assets_dir / archive_name

            if raw_path.is_file():
                if platform == "windows-amd64":
                    package_zip(raw_path, archive_path, entry_name)
                else:
                    package_tar_gz(raw_path, archive_path, entry_name)
                created_archives.append(archive_path)
                raw_files_to_remove.append(raw_path)

    if not created_archives:
        print("Warning: No raw binary assets found to package.", file=sys.stderr)

    # Remove raw binaries so only compressed archives and checksums remain
    for raw_path in raw_files_to_remove:
        raw_path.unlink()

    # Generate SHA256SUMS.txt for all remaining files (archives)
    sums_file = assets_dir / "SHA256SUMS.txt"
    lines = []
    for archive_path in sorted(created_archives, key=lambda p: p.name):
        checksum = sha256_file(archive_path)
        lines.append(f"{checksum}  {archive_path.name}\n")

    sums_file.write_text("".join(lines), encoding="utf-8")
    print(f"\n✔ Successfully packaged {len(created_archives)} archives into {assets_dir}")
    print(f"✔ Generated {sums_file}:\n{sums_file.read_text()}")


if __name__ == "__main__":
    main()
