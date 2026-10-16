# li-tools

> Modern, zero-runtime-dependency CLI security & developer tools matrix.  
> 专为开发者设计的现代命令行安全与开发小工具集合，消除陈旧命令行的“高昂认知税”。

---

## 📦 Tools in the Box

| Tool | Mission | Highlights | Status |
| :--- | :--- | :--- | :--- |
| **[`gpgkit`](gpgkit/)** | GPG 密钥全生命周期管理，让“换机器丢密钥”彻底消失 | 零运行时依赖、强制回环可验证性校验、一键备份/还原/续期/自检 | `Active` |
| **[`certkit`](certkit/)** | SSL/TLS 证书生命周期管理，告别反人类的 OpenSSL 参数 | 约定大于配置、一键受信任 Root CA、智能域名/IP 嗅探、单参数互转、跨平台 Trust | `Active` |

---

## 💡 Core Principles (核心设计哲学)

1. **Convention over Configuration (约定大于配置)**：
   默认行为即最佳实践。消灭冗余的参数记忆负担，默认路径自动管理，常用配置零参数即可运行。
2. **Three-Tier Architecture (三层架构设计)**：
   - **快捷约定层**：符合人类本能的直觉动词与智能嗅探；
   - **精细控制层**：完整保留所有专业级标志参数与覆盖选项；
   - **原生透传层**：通过 `raw` 子命令（如 `certkit raw ...`）直达底层原语，永远保留 100% 逃生门。
3. **Cross-Platform Parity (三端平权)**：
   全套工具原生支持 **macOS**、**Linux** 与 **Windows 11**。针对各平台的凭据与信任存储（macOS Keychain、Windows CertUtil、Linux CA Anchors）做深度适配与自动降级。
4. **Zero Runtime Dependencies (零运行时依赖)**：
   基于 Java 25 与 GraalVM Native Image，编译产物为独立的单二进制执行文件，目标机器无需安装 JRE/JDK。
5. **Contract First (契约先行)**：
   每个子模块均维护权威的 `SPEC.md` 规格契约。每一个退出码、参数、默认约定均有据可查。

---

## 📂 Monorepo Architecture

```text
li-tools/
├── pom.xml                     # Multi-module parent POM (Java 25, JUnit 5, Picocli dependency management)
├── gpgkit/                     # GPG lifecycle manager (Pure JDK zero runtime dependency)
│   ├── SPEC.md                 # Contract specifications
│   ├── build-native.sh         # Native Image build script
│   └── src/
├── certkit/                    # Modern SSL/TLS toolkit (Picocli + OpenSSL orchestration)
│   ├── SPEC.md                 # Contract specifications
│   ├── build-native.sh         # Native Image build script
│   └── src/
└── README.md
```

---

## 🛠️ Quick Start & Build

### 全模块构建与自动化测试
在仓库根目录执行，一键完成所有模块的编译与单元/集成测试：
```bash
mvn clean test
```

### 原生二进制编译 (GraalVM Native Image)
```bash
# 编译 gpgkit 单文件原生可执行文件 (产物位于 gpgkit/target/gpgkit)
cd gpgkit && ./build-native.sh

# 编译 certkit 单文件原生可执行文件 (产物位于 certkit/target/certkit)
cd certkit && ./build-native.sh
```

---

## 📖 Sub-Tool Quick References

- **[`gpgkit/README.md`](gpgkit/README.md)**：包含 `status`, `gen`, `backup`, `restore`, `extend`, `sign`, `publish`, `doctor` 的完整命令与备份放置规范。
- **[`certkit/README.md`](certkit/README.md)**：包含 `gen`, `ca`, `check`, `convert`, `trust`, `doctor`, `raw` 的 30 秒上手指南与三端受信任配置说明。
