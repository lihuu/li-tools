#!/usr/bin/env bash
# li-tools AI Agent Skill Installer (macOS & Linux)
# Installs gpgkit, certkit & tokenkit skills into AI agent skill directories.
set -euo pipefail

BASE_URL="https://tools.lihu.me"

echo "🤖 Installing li-tools Agent Skills (gpgkit, certkit & tokenkit)..."

# Determine target directories
TARGET_DIRS=()

# 1. Standard cross-runtime agent directory (~/.agents/skills/)
TARGET_DIRS+=("${HOME}/.agents/skills")

# 2. Gemini CLI personal skills directory (~/.gemini/skills/)
if [ -d "${HOME}/.gemini/skills" ]; then
    TARGET_DIRS+=("${HOME}/.gemini/skills")
fi

# 3. Claude Code skills directory (~/.claude/skills/)
if [ -d "${HOME}/.claude/skills" ]; then
    TARGET_DIRS+=("${HOME}/.claude/skills")
fi

# 4. Workspace-local .ai/skills directory if present
if [ -d ".ai/skills" ]; then
    TARGET_DIRS+=("$(pwd)/.ai/skills")
fi

# De-duplicate directories
UNIQUE_DIRS=($(printf "%s\n" "${TARGET_DIRS[@]}" | sort -u))

for dir in "${UNIQUE_DIRS[@]}"; do
    echo "  → Target: ${dir}"
    mkdir -p "${dir}/gpgkit" "${dir}/certkit" "${dir}/tokenkit"
    
    # Download gpgkit SKILL.md
    if curl -fsSL "${BASE_URL}/gpgkit/SKILL.md" -o "${dir}/gpgkit/SKILL.md"; then
        echo "    ✔ Installed gpgkit skill -> ${dir}/gpgkit/SKILL.md"
    else
        echo "    ⚠ Failed to download gpgkit skill" >&2
    fi

    # Download certkit SKILL.md
    if curl -fsSL "${BASE_URL}/certkit/SKILL.md" -o "${dir}/certkit/SKILL.md"; then
        echo "    ✔ Installed certkit skill -> ${dir}/certkit/SKILL.md"
    else
        echo "    ⚠ Failed to download certkit skill" >&2
    fi

    # Download tokenkit SKILL.md
    if curl -fsSL "${BASE_URL}/tokenkit/SKILL.md" -o "${dir}/tokenkit/SKILL.md"; then
        echo "    ✔ Installed tokenkit skill -> ${dir}/tokenkit/SKILL.md"
    else
        echo "    ⚠ Failed to download tokenkit skill" >&2
    fi
done

echo ""
echo "✅ All skills installed successfully!"
echo "AI coding agents (Claude, Gemini CLI, Cursor, OpenCode, Codex) will now automatically discover and use gpgkit, certkit & tokenkit."
