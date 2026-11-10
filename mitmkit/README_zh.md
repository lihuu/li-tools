# mitmkit

> **本地 HTTPS 抓包代理，JSONL 流量记录。**  
> 基于 Java 25 与 GraalVM Native Image，macOS、Linux、Windows 11 一个 native 单文件。

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/mitmkit/"><b>产品页</b></a> •
  <a href="../README_zh.md"><b>li-tools 套件</b></a>
</p>

---

## ⚡ 为什么需要 mitmkit？

抓 HTTPS 流量不该需要 Python 环境或图形界面：

- `mitmproxy` 依赖 pip，体积几百 MB，且 Windows 不支持透明模式，三平台体验不一致。
- 自己写临时代理脚本，等于重写 CONNECT 隧道和证书签发。
- Agent 调试需要机器可读的输出，而不是 TUI。

`mitmkit` 是单个 native 文件，三平台命令面完全一致：

```bash
mitmkit
```

启动后监听 `127.0.0.1:8080`，首次使用自动生成根 CA，并把每条流量以 JSON 对象逐行打印到 stdout。任何 HTTP 客户端用 `-x` 指向它，装一次信任即可。

## 🚀 快速开始

```bash
# 生成本地 CA（一次）
mitmkit ca

# 用 certkit 装系统信任
certkit trust install ~/.mitmkit/ca/ca.crt

# 启动代理，输出 JSONL
mitmkit run --jsonl

# 走代理发流量
curl -x http://127.0.0.1:8080 --cacert ~/.mitmkit/ca/ca.crt https://example.com/
```

## 📋 命令

| 命令 | 用途 |
| --- | --- |
| `mitmkit` / `mitmkit run` | 启动代理（默认 `127.0.0.1:8080`，Ctrl+C 停止） |
| `mitmkit ca` | 生成或检查本地根 CA |
| `mitmkit --help` / `--version` | 帮助与版本 |

### run 选项

| 选项 | 作用 |
| --- | --- |
| `--host HOST` | 监听地址（默认 `127.0.0.1`；`0.0.0.0` 允许局域网设备） |
| `--port PORT` | 监听端口（默认 `8080`） |
| `--insecure` | 跳过上游证书校验（自签上游场景） |
| `--body` | 记录请求/响应体 |
| `--headers` | 记录请求/响应头 |
| `--jsonl` | 输出 JSONL 而非人类可读行 |

## 📤 输出

人类可读模式一行一条：

```
1 GET https://example.com/ 200 869ms 0B/589B
```

`--jsonl` 模式一行一个 JSON：

```json
{"id":1,"ts":"2026-11-10T04:27:29.354953Z","method":"GET","url":"https://example.com:443/","status":200,"durationMs":869,"reqBytes":0,"resBytes":589}
```

三平台字段完全一致。错误记为 `status: null` 加 `error` 信息的 flow。

## 🔐 CA 与信任

- CA 位于 `~/.mitmkit/ca/`（可用 `MITMKIT_CA_DIR` 覆盖）。
- `mitmkit ca --json` 输出路径、Subject 与 SHA-256 指纹。
- `mitmkit ca` 默认拒绝覆盖已有 CA，除非加 `--force`。
- 信任安装交给 `certkit trust install`；mitmkit 从不修改系统信任库。

## 🧩 范围

首期只做**显式代理模式**：HTTP（绝对 URI）与 HTTPS（CONNECT 隧道），按 host 动态签发叶子证书。TUN/透明代理、HTTP/2 与 WebSocket 多路复用不在范围内。

## 📦 安装

```bash
# Homebrew（macOS 与 Linux）
brew install lihuu/tap/mitmkit

# Scoop（Windows）
scoop install mitmkit

# 直装脚本（macOS 与 Linux）
curl -fsSL https://tools.lihu.me/install.sh | bash -s mitmkit
```

## 📄 许可

Apache-2.0。BouncyCastle 使用 Bouncy Castle Licence（宽松、兼容 Apache-2.0）。
