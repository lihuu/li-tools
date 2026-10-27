#!/usr/bin/env bash
# ==============================================================================
# li-tools installer (macOS & Linux)
# Installs gpgkit, certkit, tokenkit, and skillkit to ~/.local/bin
# Usage:
#   curl -fsSL https://tools.lihu.me/install.sh | bash
#   curl -fsSL https://tools.lihu.me/install.sh | bash -s -- gpgkit
#   curl -fsSL https://tools.lihu.me/install.sh | bash -s -- --force
# ==============================================================================
set -euo pipefail

REPO="lihuu/li-tools"
INSTALL_DIR="${HOME}/.local/bin"
TARGET="all"
FORCE=false

show_help() {
  cat << 'EOF'
li-tools binary installer

Usage:
  install.sh [target] [options]
  curl -fsSL https://tools.lihu.me/install.sh | bash -s -- [target] [options]

Targets:
  all                 Install all tools (gpgkit, certkit, tokenkit, skillkit) [Default]
  gpgkit              Install GPG key & signature management CLI
  certkit             Install TLS certificate & local CA management CLI
  tokenkit            Install encrypted universal token manager CLI
  skillkit            Install AI agent skills distribution CLI

Options:
  --force, -f         Force reinstallation even if already installed and up to date
  --help, -h          Show this help message
EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force|-f)
      FORCE=true
      shift
      ;;
    --help|-h)
      show_help
      exit 0
      ;;
    gpgkit|certkit|tokenkit|skillkit|all)
      TARGET="$1"
      shift
      ;;
    *)
      echo "❌ Unknown argument: $1" >&2
      show_help >&2
      exit 1
      ;;
  esac
done

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

# Resolve latest version from GitHub Releases for idempotency checking
LATEST_TAG=""
if [ "${FORCE}" != true ]; then
  LATEST_TAG="$(curl -fsSL -o /dev/null -w "%{url_effective}" "https://github.com/${REPO}/releases/latest" 2>/dev/null | awk -F'/' '{print $NF}' || echo "")"
fi
LATEST_VERSION="${LATEST_TAG#v}"

install_tool() {
  local tool="$1"
  local binary_name="${tool}"
  local dest="${INSTALL_DIR}/${binary_name}"
  local archive_asset="${tool}-${PLATFORM}-${ARCH_SUFFIX}.tar.gz"
  local raw_asset="${tool}-${PLATFORM}-${ARCH_SUFFIX}"

  # Idempotency check: skip download if already installed and version matches latest release
  if [ "${FORCE}" != true ] && [ -x "${dest}" ]; then
    local current_version
    current_version="$("${dest}" --version 2>/dev/null | awk '{print $2}' || echo "")"
    if [ -n "${current_version}" ] && [ -n "${LATEST_VERSION}" ] && [ "${current_version}" = "${LATEST_VERSION}" ]; then
      echo "✔ ${tool} is already installed and up to date (${current_version}) -> ${dest} (skipped)"
      return 0
    elif [ -z "${LATEST_VERSION}" ] && [ -n "${current_version}" ]; then
      echo "✔ ${tool} is already installed (${current_version}) -> ${dest} (skipped, use --force to reinstall)"
      return 0
    fi
  fi

  echo "⬇️  Downloading ${tool} from GitHub Releases..."
  local archive_url="https://github.com/${REPO}/releases/latest/download/${archive_asset}"
  local raw_url="https://github.com/${REPO}/releases/latest/download/${raw_asset}"

  local installed=false
  # Try compressed archive first (.tar.gz)
  if curl -fsSL "${archive_url}" 2>/dev/null | tar -xz -C "${INSTALL_DIR}" "${binary_name}" 2>/dev/null; then
    chmod +x "${dest}"
    installed=true
  elif curl -fsSL -o "${dest}" "${raw_url}" 2>/dev/null; then
    chmod +x "${dest}"
    installed=true
  fi

  if [ "${installed}" = true ]; then
    echo "✔ Successfully installed ${tool} to ${dest}"
  else
    echo "ℹ Release asset '${archive_asset}' is not yet published in GitHub Releases."
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
  tokenkit)
    install_tool "tokenkit"
    ;;
  skillkit)
    install_tool "skillkit"
    ;;
  all)
    install_tool "gpgkit"
    install_tool "certkit"
    install_tool "tokenkit"
    install_tool "skillkit"
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
