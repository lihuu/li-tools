# li-tools

> Modern, zero-runtime-dependency CLI security & developer tools matrix.
> 专为开发者设计的现代命令行安全与开发小工具集合，消除陈旧命令行的“高昂认知税”。

## Tools in the Box

| Tool | Mission | Language & Target | Status |
| :--- | :--- | :--- | :--- |
| **[`gpgkit`](gpgkit/)** | GPG 密钥全生命周期管理（生成 / 备份 / 还原 / 续期 / 签名 / 验签 / 发布 / 自检），让“换机器丢密钥”彻底消失 | Java 25 + GraalVM Native Image | Active |
| **[`certkit`](certkit/)** | SSL/TLS 证书管理（带 SAN 本地证书、自建 CA、到期与链路巡检、无痛转换），告别反人类的 OpenSSL 命令 | Java 25 + GraalVM Native Image | Planning / Skeleton |

## Core Principles

1. **Zero Runtime Dependencies**: Native Image 编译，开箱即用，目标机器无需安装 JRE/JDK。
2. **Contract First (`SPEC.md`)**: 先立规格契约，再动手实现。每一个退出码、参数、环境变量都有据可查。
3. **Non-Interactive by Default**: 绝不在无头服务器或后台管道中弹出交互式提示阻断流程。
4. **Verified Loops**: 备份必带可还原性回环校验，签发必带可验证性闭环。

## Monorepo Structure

```text
li-tools/
├── pom.xml                 # Multi-module parent POM (Java 25)
├── gpgkit/                 # Sub-module: GPG toolkit
│   ├── SPEC.md             # Contract specifications
│   ├── build-native.sh     # GraalVM native-image build
│   └── src/
├── certkit/                # Sub-module: TLS/Certificate toolkit
│   ├── SPEC.md
│   ├── build-native.sh
│   └── src/
└── README.md
```

## Quick Start & Build

Build all modules across the monorepo:
```bash
mvn clean test
```

Build native executables:
```bash
# Build gpgkit native binary
cd gpgkit && ./build-native.sh

# Build certkit native binary
cd certkit && ./build-native.sh
```
