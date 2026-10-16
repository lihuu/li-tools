# certkit — 规格 v1（契约）

> 这个文件是**接口契约**。实现者和文档作者都以它为准；任何要改接口的地方，先改这里。
> 目的：把 OpenSSL 恶心繁琐的证书生命周期（自签名、SAN 扩展、本地 CA、检查校验、格式转换）收进**一个命令**，
> 彻底告别手写 openssl.cnf 与几十行反人类参数。

## 0. 为什么存在

每次在本地开发、局域网或者轻量服务配置 TLS 证书，OpenSSL 的交互都极度反人类：
- Chrome/现代浏览器强制要求 SAN（Subject Alternative Name），传统 CN 证书直接报红；
- 生成带 SAN 的自签名证书要么手写 cnf 配置文件，要么传一堆易错的 `-addext` 参数；
- 查看证书信息只能面对 `openssl x509 -text` 的几百行原始 ASN.1 文本；
- 格式互转（PEM <-> P12 <-> DER）每次都要翻笔记。

`certkit` 做的是**编排 + 校验 + 规整输出**：底层调用 `openssl` 或 JDK 安全 API，对外暴露符合直觉的动词。

## 1. 形态

- 模块：`li-tools/certkit`
- 实现：**Java 25 + GraalVM Native Image**，目标产物是**单个独立可执行文件**（`./target/certkit`）。
- **零运行时第三方依赖**：仅用 JDK 内置 API，测试使用 JUnit 5。
- 外部命令一律**显式参数、非交互**。

## 2. 命令面

全局参数：`--json`（机器可读输出）、`--quiet`、`--help`。
**退出码**：`0` 成功；`1` 业务失败；`2` 用法错误。

### 1) `certkit gen`
生成 TLS 私钥与证书（默认包含 SAN 扩展，ECDSA P-256 或 RSA 4096）：
```bash
certkit gen [--domain localhost] [--ip 127.0.0.1] [--days 365] [--ca-cert F --ca-key F] [--out DIR]
```
- 默认：`--domain localhost --ip 127.0.0.1`，ECDSA P-256，有效期 365 天。
- 产物：`cert.pem` (或 fullchain.pem) 与 `key.pem`（权限 0600）。
- 若指定 `--ca-cert` 和 `--ca-key`，则自动用该 CA 签发并打包好证书链。

### 2) `certkit ca`
生成本地专用自建 Root CA 根证书与私钥：
```bash
certkit ca [--name "My Dev CA"] [--days 3650] [--out DIR]
```
- 产物：`ca.crt` 与 `ca.key`，可一键导入系统钥匙串（macOS Keychain / Linux ca-certificates）实现全绿受信任。

### 3) `certkit check <file | host:port>`
检查本地证书文件或远程 HTTPS 服务的证书状态：
```bash
certkit check ./cert.pem
certkit check github.com:443
```
- 规整输出：
  - 域名覆盖列表（CN + SAN）
  - 颁发者（Issuer）
  - 有效期与**剩余天数倒计时**
  - 是否过期 / 90 天内预警
  - 证书链完整性

### 4) `certkit convert`
跨格式无痛转换：
```bash
certkit convert --in cert.pem [--key key.pem] --out cert.p12 [--passphrase-file F]
```
- 支持 PEM、PKCS12 (.p12/.pfx)、DER 互转。
