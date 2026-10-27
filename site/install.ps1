# ==============================================================================
# li-tools installer for Windows 11 (PowerShell)
# Installs gpgkit.exe, certkit.exe, tokenkit.exe, and skillkit.exe to $HOME\.local\bin
# Usage in PowerShell:
#   irm https://tools.lihu.me/install.ps1 | iex
#   .\install.ps1 -Target skillkit -Force
# ==============================================================================
[CmdletBinding()]
param(
    [string]$Target = "all",
    [switch]$Force,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Host @"
li-tools Windows Installer

Usage:
  .\install.ps1 [options]
  irm https://tools.lihu.me/install.ps1 | iex

Options:
  -Target <tool>  Install specific tool (all, gpgkit, certkit, tokenkit, skillkit) [Default: all]
  -Force          Force reinstallation even if already installed and up to date
  -Help           Show this help message
"@
    exit 0
}

$Repo = "lihuu/li-tools"
$InstallDir = Join-Path $HOME ".local\bin"

Write-Host "⚡ li-tools Windows Installer" -ForegroundColor Cyan
Write-Host "Target directory: $InstallDir"

if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}

$Arch = if ([System.Environment]::Is64BitOperatingSystem) { "amd64" } else { "x86" }
$Platform = "windows-$Arch"

function Install-Tool([string]$ToolName) {
    $BinaryName = "$ToolName.exe"
    $ZipAsset = "$ToolName-$Platform.zip"
    $RawAsset = "$ToolName-$Platform.exe"
    $DestPath = Join-Path $InstallDir $BinaryName
    $ZipUrl = "https://github.com/$Repo/releases/latest/download/$ZipAsset"
    $RawUrl = "https://github.com/$Repo/releases/latest/download/$RawAsset"

    # Idempotency check: skip if binary exists and is functional
    if (-not $Force -and (Test-Path $DestPath)) {
        try {
            $verOutput = (& $DestPath --version 2>$null)
            if ($verOutput) {
                $currentVer = ($verOutput -split '\s+')[1]
                Write-Host "✔ $ToolName is already installed ($currentVer) -> $DestPath (skipped, use -Force to reinstall)" -ForegroundColor Green
                return
            }
        } catch {
            # Continue to download if execution failed
        }
    }

    Write-Host "⬇️  Downloading $ToolName from GitHub Releases..."
    $installed = $false
    # Try .zip archive first
    $TempZip = Join-Path $env:TEMP "$ToolName-$Platform-$PID.zip"
    try {
        Invoke-WebRequest -Uri $ZipUrl -OutFile $TempZip -UseBasicParsing
        Expand-Archive -Path $TempZip -DestinationPath $InstallDir -Force
        Remove-Item -Path $TempZip -Force -ErrorAction SilentlyContinue
        $installed = $true
    } catch {
        # Fallback to raw .exe
        try {
            Invoke-WebRequest -Uri $RawUrl -OutFile $DestPath -UseBasicParsing
            $installed = $true
        } catch {
            $installed = $false
        }
    }

    if ($installed) {
        Write-Host "✔ Successfully installed $ToolName to $DestPath" -ForegroundColor Green
    } else {
        Write-Host "ℹ Release asset '$ZipAsset' is not yet published in GitHub Releases." -ForegroundColor Yellow
        Write-Host "  To build from source on Java 25:" -ForegroundColor Gray
        Write-Host "    git clone https://github.com/$Repo.git" -ForegroundColor Gray
        Write-Host "    cd li-tools\$ToolName; mvn clean package" -ForegroundColor Gray
    }
}

switch ($Target.ToLower()) {
    "gpgkit" { Install-Tool "gpgkit" }
    "certkit" { Install-Tool "certkit" }
    "tokenkit" { Install-Tool "tokenkit" }
    "skillkit" { Install-Tool "skillkit" }
    default  {
        Install-Tool "gpgkit"
        Install-Tool "certkit"
        Install-Tool "tokenkit"
        Install-Tool "skillkit"
    }
}

# Update User PATH if not present
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -notlike "*$InstallDir*") {
    Write-Host "`nℹ Adding $InstallDir to user PATH..." -ForegroundColor Cyan
    [Environment]::SetEnvironmentVariable("Path", "$UserPath;$InstallDir", "User")
    $env:Path += ";$InstallDir"
    Write-Host "✔ User PATH updated! Please restart your terminal to reload." -ForegroundColor Green
}

Write-Host "`n✨ Installation complete!" -ForegroundColor Cyan
