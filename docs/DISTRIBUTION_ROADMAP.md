# 生态包分发路线图 (Distribution Roadmap)

本项目旨在为开发者提供最顺滑、开箱即用的跨平台开发安全套件。当前已支持全平台一键脚本（`install.sh` / `install.ps1`）与 GitHub Releases 独立二进制直链分发。下一阶段将逐步接入主流系统包管理器。

---

## 1. Homebrew Tap (macOS & Linux)

### 1.1 仓库架构
- **仓库命名**: `https://github.com/lihuu/homebrew-tools`
- **Formula 命名**:
  - `gpgkit.rb`
  - `certkit.rb`

### 1.2 Formula 示例模板 (`certkit.rb`)
```ruby
class Certkit < Formula
  desc "One command for SSL/TLS certificates without OpenSSL agony"
  homepage "https://tools.lihu.me/certkit/"
  version "0.1.0"
  
  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/lihuu/li-tools/releases/download/v#{version}/certkit-darwin-arm64"
      sha256 "CHECKSUM_DARWIN_ARM64"
    else
      url "https://github.com/lihuu/li-tools/releases/download/v#{version}/certkit-darwin-amd64"
      sha256 "CHECKSUM_DARWIN_AMD64"
    end
  end

  on_linux do
    url "https://github.com/lihuu/li-tools/releases/download/v#{version}/certkit-linux-amd64"
    sha256 "CHECKSUM_LINUX_AMD64"
  end

  def install
    bin.install "certkit-#{OS.kernel_name.downcase}-#{Hardware::CPU.arch}" => "certkit"
  end

  test do
    system "#{bin}/certkit", "--version"
  end
end
```

### 1.3 用户安装体验
```bash
brew tap lihuu/tools
brew install certkit gpgkit
```

---

## 2. Windows Scoop Bucket (Windows 11)

### 2.1 Bucket 架构
- **仓库**: `https://github.com/lihuu/scoop-tools` 或提交至 Scoop 社区 `extras` bucket
- **Manifest 示例 (`certkit.json`)**:
```json
{
  "version": "0.1.0",
  "description": "One command for SSL/TLS certificates without OpenSSL agony",
  "homepage": "https://tools.lihu.me/certkit/",
  "license": "Apache-2.0",
  "architecture": {
    "64bit": {
      "url": "https://github.com/lihuu/li-tools/releases/download/v0.1.0/certkit-windows-amd64.exe#/certkit.exe",
      "hash": "CHECKSUM_WINDOWS_AMD64"
    }
  },
  "bin": "certkit.exe",
  "checkver": "github",
  "autoupdate": {
    "architecture": {
      "64bit": {
        "url": "https://github.com/lihuu/li-tools/releases/download/v$version/certkit-windows-amd64.exe#/certkit.exe"
      }
    }
  }
}
```

### 2.2 用户安装体验
```powershell
scoop bucket add lihuu https://github.com/lihuu/scoop-tools
scoop install certkit gpgkit
```

---

## 3. NPM Global CLI Wrapper (跨全平台)

### 3.1 架构设计
- **Package 名称**: `@lihuu/gpgkit` 与 `@lihuu/certkit`
- **机制**:
  - `package.json` 中的 `bin` 字段指向 Node.js 包装器 `bin/certkit.js`；
  - `postinstall` 钩子基于 `process.platform` 与 `process.arch` 从 GitHub Releases 自动下载原生可执行文件至 `node_modules/.bin`；
  - 如果用户网络受限，自动回退提示下载源码或使用独立二进制。

### 3.2 用户安装体验
```bash
npm install -g @lihuu/certkit
certkit --version
```

---

## 4. GitHub Actions 自动更新流水线
当发布新 Tag 时，通过 `.github/workflows/release.yml` 自动计算新二进制文件的 SHA-256 哈希值，并通过 `scripts/sync-package-managers.py` 携带 `ECOSYSTEM_TOKEN` 自动更新以下生态源：
- **Homebrew Tap**: `https://github.com/lihuu/homebrew-tap` (`Formula/*.rb`)
- **Scoop Bucket**: `https://github.com/lihuu/my-scoop-bucket` (`bucket/*.json`)

实现打 Tag 发版即全渠道自动同步上架。
