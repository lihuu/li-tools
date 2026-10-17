# li-tools

> **现代、零运行时依赖的命令行安全与开发小工具矩阵。**  
> 专为开发者打造的高生产力命令行工具集，彻底消除陈旧命令行的“高昂认知税”与心智负担。

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://lihuu.github.io/li-tools/"><b>在线落地页与文档</b></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk" alt="Java 25">
  <img src="https://img.shields.io/badge/GraalVM-Native%20Image-blue?style=flat-square&logo=graalvm" alt="GraalVM">
  <img src="https://img.shields.io/badge/Platforms-macOS%20%7C%20Linux%20%7C%20Windows%2011-success?style=flat-square" alt="Platforms">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blueviolet?style=flat-square" alt="License">
</p>

---

## 📦 工具箱成员

| 工具 | 使命定位 | 核心亮点 | 状态 |
| :--- | :--- | :--- | :--- |
| **[`gpgkit`](gpgkit/)** | GPG 密钥全生命周期管理，终结“换机器丢密钥” | 零运行时依赖、强制回环可验证性校验、一键备份/还原/续期/自检 | `Active` |
| **[`certkit`](certkit/)** | 现代 SSL/TLS 证书瑞士军刀，告别反人类的 OpenSSL 参数 | 约定大于配置、一键受信任本地 Root CA、智能域名/IP SAN 嗅探、单参数互转、三端系统受信 | `Active` |

---

## 💡 核心设计哲学 (Core Principles)

1. **约定大于配置 (Convention over Configuration)**：
   默认行为即行业最佳实践。无需死记硬背复杂繁琐的命令行标志，默认路径自动归位，90% 的日常操作直接零配置运行。
2. **三层架构设计 (Three-Tier Architecture)**：
   - **快捷约定层**：符合人类直觉的动词与智能嗅探（如 `certkit gen localhost`）；
   - **精细控制层**：完整保留专业级 Flags 参数，满足定制需求；
   - **原生透传层**：通过 `raw` 子命令（如 `certkit raw ...`）直达底层原生引擎，永远保留 100% 逃生门。
3. **三端系统平权 (Triple-Platform Equity)**：
   原生平齐支持 **macOS**、**Linux** 与 **Windows 11**。针对各平台的系统凭据与根信任存储（macOS Keychain、Linux CA Anchors、Windows 11 `certutil` 当前用户免提权注入）做深度适配。
4. **零运行时依赖 (Zero Runtime Dependencies)**：
   基于 Java 25 编写，经 **GraalVM Native Image** 编译为毫秒级独立可执行二进制文件，目标使用环境无需安装任何 JDK/JRE。
5. **契约先行 (Contract First)**：
   每个子模块均维护权威的 `SPEC.md` 规格契约。每一个退出码、参数、默认约定与行为均经自动化测试严格校验。

---

## 📂 Monorepo 项目结构

```text
li-tools/
├── pom.xml                     # 多模块父 POM (统一管理 Java 25、JUnit 5、Picocli 等依赖版本)
├── site/                       # 现代暗黑极客风格落地页 (自动部署至 GitHub Pages)
│   ├── index.html              # 矩阵统一门户总览页
│   ├── gpgkit/index.html       # gpgkit 专属落地页
│   └── certkit/index.html      # certkit 专属落地页
├── gpgkit/                     # GPG 密钥生命周期管理工具 (纯 JDK 原生标准库，零第三方运行时依赖)
│   ├── SPEC.md                 # 接口与行为规格契约
│   ├── build-native.sh         # GraalVM Native Image 原生编译脚本
│   └── src/
├── certkit/                    # SSL/TLS 证书管理工具 (Picocli + OpenSSL 现代化编排)
│   ├── SPEC.md                 # 接口与行为规格契约
│   ├── build-native.sh         # GraalVM Native Image 原生编译脚本
│   └── src/
├── .github/workflows/          # CI/CD 与 GitHub Pages 自动部署工作流
└── README.md
```

---

## 🛠️ 快速上手与本地构建

### 运行环境要求
- **Java 25**（如 OpenJDK 25 或 GraalVM for JDK 25）
- **Maven 3.9+**
- 底层系统工具依赖：`gpg`（供 gpgkit 使用）、`openssl`（供 certkit 使用）

### 运行全量单元与集成测试
在仓库根目录执行，一键跑通所有模块的全部测试：
```bash
mvn clean test
```

### 编译单文件原生二进制 (GraalVM Native Image)
确保环境变量 `JAVA_HOME` 指向 GraalVM for JDK 25：

```bash
# 编译 gpgkit 单文件原生可执行文件 (产物位于 gpgkit/target/gpgkit)
cd gpgkit && ./build-native.sh

# 编译 certkit 单文件原生可执行文件 (产物位于 certkit/target/certkit)
cd certkit && ./build-native.sh
```

---

## 🤖 AI Agent 深度集成 (agentskills.io 标准)

`li-tools` 套件内的所有工具均原生提供面向 AI 编码智能体（Claude Code, Gemini CLI, Cursor, Copilot CLI, OpenCode 等）的标准 `SKILL.md`：

- **套件总技能文档**: [`SKILL.md`](SKILL.md)（在线访问：[https://lihuu.github.io/li-tools/SKILL.md](https://lihuu.github.io/li-tools/SKILL.md)）
- **gpgkit 技能文档**: [`gpgkit/SKILL.md`](gpgkit/SKILL.md)（在线访问：[https://lihuu.github.io/li-tools/gpgkit/SKILL.md](https://lihuu.github.io/li-tools/gpgkit/SKILL.md)）
- **certkit 技能文档**: [`certkit/SKILL.md`](certkit/SKILL.md)（在线访问：[https://lihuu.github.io/li-tools/certkit/SKILL.md](https://lihuu.github.io/li-tools/certkit/SKILL.md)）

### 为你的 AI 智能体一键安装技能
一键将技能下载并导入到本地 Agent 技能目录（如 `~/.agents/skills/`、`~/.gemini/skills/`、`~/.claude/skills/`）：
```bash
# macOS / Linux
curl -fsSL https://lihuu.github.io/li-tools/install-skills.sh | bash

# Windows 11 (PowerShell)
irm https://lihuu.github.io/li-tools/install-skills.ps1 | iex
```

---

## 🌐 在线文档与展示页面

- **[在线落地页与文档中心](https://lihuu.github.io/li-tools/)**
- **[`gpgkit 说明文档`](gpgkit/README.md)**（提供 [英文版](gpgkit/README.md) 与 [中文版](gpgkit/README.zh-CN.md)）
- **[`certkit 说明文档`](certkit/README.md)**（提供 [英文版](certkit/README.md) 与 [中文版](certkit/README_zh.md)）

---

## 📄 开源许可证

本项目采用 Apache License 2.0 许可证。
