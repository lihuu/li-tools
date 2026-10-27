#!/usr/bin/env bash
# li-tools AI Agent Skill Installer (macOS & Linux)
# Installs gpgkit, certkit, tokenkit & skillkit skills into AI agent skill directories.
set -euo pipefail

BASE_URL="https://tools.lihu.me"
STANDARD_DIR="${HOME}/.agents/skills"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || echo "")"

MODE="default"
EXPLICIT_MODE=0
CUSTOM_DIR=""

show_help() {
    cat << 'EOF'
li-tools Agent Skills Installer

Usage:
  install-skills.sh [options]
  curl -fsSL https://tools.lihu.me/install-skills.sh | bash -s -- [options]

Options:
  --default, -d       Install only to standard directory (~/.agents/skills) [Default]
  --all, -a           Install to all detected agent directories
  --target <dir>, -t  Install to a custom directory
  --help, -h          Show this help message
EOF
}

# Parse command line flags
while [[ $# -gt 0 ]]; do
    case "$1" in
        --default|-d)
            MODE="default"
            EXPLICIT_MODE=1
            shift
            ;;
        --all|-a)
            MODE="all"
            EXPLICIT_MODE=1
            shift
            ;;
        --target|-t)
            if [[ -z "${2:-}" ]]; then
                echo "Error: --target requires a directory path argument." >&2
                exit 1
            fi
            MODE="custom"
            EXPLICIT_MODE=1
            CUSTOM_DIR="$2"
            shift 2
            ;;
        --help|-h)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            show_help >&2
            exit 1
            ;;
    esac
done

# If run interactively in a real terminal without explicit flags, let the user choose
if [[ "$EXPLICIT_MODE" -eq 0 ]] && [[ -t 0 ]] && [[ -r /dev/tty ]]; then
    echo "🤖 li-tools Agent Skills Installer"
    echo ""
    echo "Select target installation directory:"
    echo "  1) Standard agent directory: ${STANDARD_DIR} (Recommended, default)"
    echo "  2) All detected agent directories (~/.agents/skills, ~/.gemini/skills, ~/.claude/skills, etc.)"
    echo "  3) Custom directory"
    echo ""
    read -r -p "Enter choice [1-3] (default 1): " choice </dev/tty || choice=""
    case "$choice" in
        2)
            MODE="all"
            ;;
        3)
            read -r -p "Enter custom skills directory path: " custom_input </dev/tty || custom_input=""
            if [[ -n "$custom_input" ]]; then
                MODE="custom"
                CUSTOM_DIR="$custom_input"
            else
                echo "No directory entered, falling back to standard directory."
                MODE="default"
            fi
            ;;
        *)
            MODE="default"
            ;;
    esac
fi

# Determine target directories based on choice
TARGET_DIRS=()

if [[ "$MODE" == "custom" ]]; then
    TARGET_DIRS+=("$CUSTOM_DIR")
elif [[ "$MODE" == "all" ]]; then
    # 1. Standard cross-runtime agent directory (~/.agents/skills/)
    TARGET_DIRS+=("${STANDARD_DIR}")

    # 2. Gemini CLI personal skills directory (~/.gemini/skills/)
    if [[ -d "${HOME}/.gemini/skills" ]]; then
        TARGET_DIRS+=("${HOME}/.gemini/skills")
    fi

    # 3. Claude Code skills directory (~/.claude/skills/)
    if [[ -d "${HOME}/.claude/skills" ]]; then
        TARGET_DIRS+=("${HOME}/.claude/skills")
    fi

    # 4. Workspace-local .ai/skills directory if present
    if [[ -d ".ai/skills" ]]; then
        TARGET_DIRS+=("$(pwd)/.ai/skills")
    fi
else
    # Default: only the standard cross-runtime directory (~/.agents/skills)
    TARGET_DIRS+=("${STANDARD_DIR}")
fi

# De-duplicate directories
UNIQUE_DIRS=($(printf "%s\n" "${TARGET_DIRS[@]}" | sort -u))

echo "🤖 Installing li-tools Agent Skills (gpgkit, certkit, tokenkit & skillkit)..."

SKILLS=("gpgkit" "certkit" "tokenkit" "skillkit")

for dir in "${UNIQUE_DIRS[@]}"; do
    echo "  → Target: ${dir}"
    for skill in "${SKILLS[@]}"; do
        dest_dir="${dir}/${skill}"
        dest_file="${dest_dir}/SKILL.md"
        mkdir -p "${dest_dir}"

        # If local source file exists in script dir, copy directly; otherwise download
        if [[ -n "$SCRIPT_DIR" ]] && [[ -f "${SCRIPT_DIR}/${skill}/SKILL.md" ]]; then
            cp "${SCRIPT_DIR}/${skill}/SKILL.md" "${dest_file}"
            echo "    ✔ Installed ${skill} skill -> ${dest_file} (local)"
        elif curl -fsSL "${BASE_URL}/${skill}/SKILL.md" -o "${dest_file}"; then
            echo "    ✔ Installed ${skill} skill -> ${dest_file}"
        else
            echo "    ⚠ Failed to download ${skill} skill" >&2
        fi
    done
done

echo ""
echo "✅ All skills installed successfully!"
echo "AI coding agents will now automatically recognize and operate gpgkit, certkit, tokenkit & skillkit."
