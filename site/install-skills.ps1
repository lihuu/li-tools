# li-tools AI Agent Skill Installer for Windows PowerShell
# Installs gpgkit, certkit, tokenkit & skillkit skills into AI agent skill directories.
$ErrorActionPreference = "Stop"

$baseUrl = "https://tools.lihu.me"

Write-Host "🤖 Installing li-tools Agent Skills (gpgkit, certkit, tokenkit & skillkit)..." -ForegroundColor Cyan

$userHome = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::UserProfile)
$targetDirs = @()

# 1. Standard cross-runtime agent directory (~/.agents/skills/)
$targetDirs += [System.IO.Path]::Combine($userHome, ".agents", "skills")

# 2. Gemini CLI personal skills directory (~/.gemini/skills/)
$geminiDir = [System.IO.Path]::Combine($userHome, ".gemini", "skills")
if (Test-Path -Path $geminiDir) {
    $targetDirs += $geminiDir
}

# 3. Claude Code skills directory (~/.claude/skills/)
$claudeDir = [System.IO.Path]::Combine($userHome, ".claude", "skills")
if (Test-Path -Path $claudeDir) {
    $targetDirs += $claudeDir
}

# 4. Workspace-local .ai/skills directory if present
if (Test-Path -Path ".ai\skills") {
    $targetDirs += (Get-Item ".ai\skills").FullName
}

$uniqueDirs = $targetDirs | Select-Object -Unique

foreach ($dir in $uniqueDirs) {
    Write-Host "  → Target: $dir" -ForegroundColor DarkGray
    $gpgkitDir = [System.IO.Path]::Combine($dir, "gpgkit")
    $certkitDir = [System.IO.Path]::Combine($dir, "certkit")
    $tokenkitDir = [System.IO.Path]::Combine($dir, "tokenkit")
    $skillkitDir = [System.IO.Path]::Combine($dir, "skillkit")

    New-Item -ItemType Directory -Force -Path $gpgkitDir | Out-Null
    New-Item -ItemType Directory -Force -Path $certkitDir | Out-Null
    New-Item -ItemType Directory -Force -Path $tokenkitDir | Out-Null
    New-Item -ItemType Directory -Force -Path $skillkitDir | Out-Null

    try {
        $gpgkitDest = [System.IO.Path]::Combine($gpgkitDir, "SKILL.md")
        Invoke-RestMethod -Uri "$baseUrl/gpgkit/SKILL.md" -OutFile $gpgkitDest
        Write-Host "    ✔ Installed gpgkit skill -> $gpgkitDest" -ForegroundColor Green
    } catch {
        Write-Warning "    ⚠ Failed to download gpgkit skill: $_"
    }

    try {
        $certkitDest = [System.IO.Path]::Combine($certkitDir, "SKILL.md")
        Invoke-RestMethod -Uri "$baseUrl/certkit/SKILL.md" -OutFile $certkitDest
        Write-Host "    ✔ Installed certkit skill -> $certkitDest" -ForegroundColor Green
    } catch {
        Write-Warning "    ⚠ Failed to download certkit skill: $_"
    }

    try {
        $tokenkitDest = [System.IO.Path]::Combine($tokenkitDir, "SKILL.md")
        Invoke-RestMethod -Uri "$baseUrl/tokenkit/SKILL.md" -OutFile $tokenkitDest
        Write-Host "    ✔ Installed tokenkit skill -> $tokenkitDest" -ForegroundColor Green
    } catch {
        Write-Warning "    ⚠ Failed to download tokenkit skill: $_"
    }

    try {
        $skillkitDest = [System.IO.Path]::Combine($skillkitDir, "SKILL.md")
        Invoke-RestMethod -Uri "$baseUrl/skillkit/SKILL.md" -OutFile $skillkitDest
        Write-Host "    ✔ Installed skillkit skill -> $skillkitDest" -ForegroundColor Green
    } catch {
        Write-Warning "    ⚠ Failed to download skillkit skill: $_"
    }
}

Write-Host ""
Write-Host "✅ All skills installed successfully!" -ForegroundColor Green
Write-Host "AI coding agents will now automatically recognize and operate gpgkit, certkit, tokenkit & skillkit." -ForegroundColor Cyan
