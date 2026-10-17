# ==============================================================================
# li-tools installer for Windows 11 (PowerShell)
# Installs gpgkit.exe and certkit.exe to $HOME\.local\bin
# Usage in PowerShell:
#   irm https://tools.lihu.me/install.ps1 | iex
# ==============================================================================
param(
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"

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
    $AssetName = "$ToolName-$Platform.exe"
    $DestPath = Join-Path $InstallDir $BinaryName
    $ReleaseUrl = "https://github.com/$Repo/releases/latest/download/$AssetName"

    Write-Host "⬇️  Downloading $ToolName from GitHub Releases..."
    try {
        Invoke-WebRequest -Uri $ReleaseUrl -OutFile $DestPath -UseBasicParsing
        Write-Host "✔ Successfully installed $ToolName to $DestPath" -ForegroundColor Green
    } catch {
        Write-Host "ℹ Binary asset '$AssetName' is not yet published in GitHub Releases." -ForegroundColor Yellow
        Write-Host "  To build from source on Java 25:" -ForegroundColor Gray
        Write-Host "    git clone https://github.com/$Repo.git" -ForegroundColor Gray
        Write-Host "    cd li-tools\$ToolName; mvn clean package" -ForegroundColor Gray
    }
}

switch ($Target.ToLower()) {
    "gpgkit" { Install-Tool "gpgkit" }
    "certkit" { Install-Tool "certkit" }
    default  {
        Install-Tool "gpgkit"
        Install-Tool "certkit"
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
