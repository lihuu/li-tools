#!/usr/bin/env bash
# ==============================================================================
# li-tools installer (macOS & Linux)
# Installs gpgkit and certkit to ~/.local/bin
# Usage:
#   curl -fsSL https://lihuu.github.io/li-tools/install.sh | bash
#   curl -fsSL https://lihuu.github.io/li-tools/install.sh | bash -s gpgkit
#   curl -fsSL https://lihuu.github.io/li-tools/install.sh | bash -s certkit
# ==============================================================================
set -euo pipefail

REPO="lihuu/li-tools"
INSTALL_DIR="${HOME}/.local/bin"
TARGET="${1:-all}"

echo "⚡ li-tools installer"
echo "Target directory: ${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}"

# Detect OS and Architecture
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "${ARCH}" in
  x86_64|amd64) ARCH_SUFFIX="amd64" ;;
  arm64|aarch64) ARCH_SUFFIX="arm64" ;;
  *) echo "❌ Unsupported architecture: ${ARCH}"; exit 1 ;;
esac

case "${OS}" in
  darwin) PLATFORM="darwin" ;;
  linux)  PLATFORM="linux" ;;
  *) echo "❌ Unsupported OS: ${OS}. For Windows, use install.ps1 in PowerShell."; exit 1 ;;
esac

echo "Detected platform: ${PLATFORM}-${ARCH_SUFFIX}"

install_tool() {
  local tool="$1"
  local binary_name="${tool}"
  local asset_name="${tool}-${PLATFORM}-${ARCH_SUFFIX}"
  local dest="${INSTALL_DIR}/${binary_name}"

  echo "⬇️  Downloading ${tool} from GitHub Releases..."
  local release_url="https://github.com/${REPO}/releases/latest/download/${asset_name}"

  if curl -fsSL -o "${dest}" "${release_url}" 2>/dev/null; then
    chmod +x "${dest}"
    echo "✔ Successfully installed ${tool} to ${dest}"
  else
    echo "ℹ Binary asset '${asset_name}' is not yet published in GitHub Releases."
    echo "  To build from source on Java 25:"
    echo "    git clone https://github.com/${REPO}.git && cd li-tools/${tool} && ./build-native.sh"
  fi
}

case "${TARGET}" in
  gpgkit)
    install_tool "gpgkit"
    ;;
  certkit)
    install_tool "certkit"
    ;;
  all|"")
    install_tool "gpgkit"
    install_tool "certkit"
    ;;
  *)
    echo "❌ Unknown target: ${TARGET}. Valid targets: all, gpgkit, certkit"
    exit 1
    ;;
esac

# PATH Check
if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
  echo ""
  echo "⚠️  Note: ${INSTALL_DIR} is not in your PATH."
  echo "Add the following line to your ~/.zshrc or ~/.bashrc:"
  echo "  export PATH=\"${INSTALL_DIR}:\$PATH\""
fi

echo "✨ Done!"
