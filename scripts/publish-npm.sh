#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Checking npm authentication status..."
if ! CURRENT_USER=$(npm whoami 2>/dev/null); then
  echo "Error: You are not logged into npm."
  echo "Please run: npm login"
  echo "Then rerun: ./scripts/publish-npm.sh"
  exit 1
fi

echo "==> Logged in as: $CURRENT_USER"

PACKAGES=("gpgkit" "certkit")

for pkg in "${PACKAGES[@]}"; do
  PKG_DIR="$ROOT_DIR/npm/packages/$pkg"
  PKG_NAME=$(node -p "require('$PKG_DIR/package.json').name")
  PKG_VER=$(node -p "require('$PKG_DIR/package.json').version")
  
  echo "==> Publishing $PKG_NAME@$PKG_VER..."
  (cd "$PKG_DIR" && npm publish --access public)
  echo "✓ Successfully published $PKG_NAME@$PKG_VER"
done

echo "==> All packages published successfully!"
