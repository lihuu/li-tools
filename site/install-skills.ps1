# li-tools AI Agent Skill Installer for Windows PowerShell
# Installs gpgkit, certkit, tokenkit & skillkit skills into AI agent skill directories.
[CmdletBinding()]
Param(
    [switch]$Default,
    [switch]$All,
    [string]$Target,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Host @"
li-tools Agent Skills Installer for Windows PowerShell

Usage:
  .\install-skills.ps1 [options]
  irm https://tools.lihu.me/install-skills.ps1 | iex

Options:
  -Default       Install only to standard directory (~/.agents/skills) [Default]
  -All           Install to all detected agent directories
  -Target <dir>  Install to a custom directory
  -Help          Show this help message
"@
    exit 0
}

$baseUrl = "https://tools.lihu.me"
$userHome = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::UserProfile)
$standardDir = [System.IO.Path]::Combine($userHome, ".agents", "skills")

$scriptDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptDir) -and $MyInvocation.MyCommand.Path) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}

$mode = "default"
if ($All) {
    $mode = "all"
} elseif (![string]::IsNullOrWhiteSpace($Target)) {
    $mode = "custom"
} elseif ([Environment]::UserInteractive -and -not $Default -and $Host.UI.RawUI.KeyAvailable -ne $null) {
    try {
        Write-Host "🤖 li-tools Agent Skills Installer" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "Select target installation directory:"
        Write-Host "  1) Standard agent directory: $standardDir (Recommended, default)" -ForegroundColor Yellow
        Write-Host "  2) All detected agent directories (~/.agents/skills, ~/.gemini/skills, ~/.claude/skills, etc.)"
        Write-Host "  3) Custom directory"
        Write-Host ""
        $choice = Read-Host "Enter choice [1-3] (default 1)"
        if ($choice -eq "2") {
            $mode = "all"
        } elseif ($choice -eq "3") {
            $customPath = Read-Host "Enter custom skills directory path"
            if (![string]::IsNullOrWhiteSpace($customPath)) {
                $mode = "custom"
                $Target = $customPath
            }
        }
    } catch {
        $mode = "default"
    }
}

$targetDirs = @()
if ($mode -eq "custom") {
    $targetDirs += $Target
} elseif ($mode -eq "all") {
    $targetDirs += $standardDir
    $geminiDir = [System.IO.Path]::Combine($userHome, ".gemini", "skills")
    if (Test-Path -Path $geminiDir) { $targetDirs += $geminiDir }
    $claudeDir = [System.IO.Path]::Combine($userHome, ".claude", "skills")
    if (Test-Path -Path $claudeDir) { $targetDirs += $claudeDir }
    if (Test-Path -Path ".ai\skills") { $targetDirs += (Get-Item ".ai\skills").FullName }
} else {
    $targetDirs += $standardDir
}

$uniqueDirs = $targetDirs | Select-Object -Unique

Write-Host "🤖 Installing li-tools Agent Skills (gpgkit, certkit, tokenkit & skillkit)..." -ForegroundColor Cyan

$skills = @("gpgkit", "certkit", "tokenkit", "skillkit")

foreach ($dir in $uniqueDirs) {
    Write-Host "  → Target: $dir" -ForegroundColor DarkGray
    foreach ($skill in $skills) {
        $destDir = [System.IO.Path]::Combine($dir, $skill)
        $destFile = [System.IO.Path]::Combine($destDir, "SKILL.md")
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null

        $localSrc = if ($scriptDir) { [System.IO.Path]::Combine($scriptDir, $skill, "SKILL.md") } else { $null }

        if ($localSrc -and (Test-Path -Path $localSrc)) {
            Copy-Item -Path $localSrc -Destination $destFile -Force
            Write-Host "    ✔ Installed $skill skill -> $destFile (local)" -ForegroundColor Green
        } else {
            try {
                Invoke-RestMethod -Uri "$baseUrl/$skill/SKILL.md" -OutFile $destFile
                Write-Host "    ✔ Installed $skill skill -> $destFile" -ForegroundColor Green
            } catch {
                Write-Warning "    ⚠ Failed to download $skill skill: $_"
            }
        }
    }
}

Write-Host ""
Write-Host "✅ All skills installed successfully!" -ForegroundColor Green
Write-Host "AI coding agents will now automatically recognize and operate gpgkit, certkit, tokenkit & skillkit." -ForegroundColor Cyan
