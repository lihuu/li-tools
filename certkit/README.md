# certkit

> One command for the whole life of SSL/TLS certificates: generate dev certs with SAN, manage trusted local Root CA, inspect, and convert — without OpenSSL agony.  
> 专为现代开发者设计的 SSL/TLS 证书管理工具，用“约定大于配置”彻底消除反人类的 OpenSSL 认知税。

Part of the [li-tools](../README.md) suite.

---

## ⚡ 为什么需要 certkit？

每次在本地开发、局域网微服务或测试环境配置 HTTPS 时，直接用原生 OpenSSL 的体验堪称灾难：
- 现代浏览器（Chrome/Edge）强制要求 **SAN (Subject Alternative Name)** 扩展，老式 `CN` 证书直接报红；
- 生成带 SAN 的证书要么写一个恶心的 `openssl.cnf`，要么记一长串易错的命令行参数；
- 想要让浏览器全绿受信任，需要手动配自签根 CA，再根据 macOS、Linux、Windows 各自不同的命令注入系统钥匙串；
- 转换格式（PEM <-> P12 <-> DER）每次都要翻笔记。

`certkit` 将这套陈旧繁琐的流程收敛成**符合现代人直觉的几个极简动词**。

---

## 🚀 30 秒上手指南

### 1. 本地开发要证书？零参数直接敲！
```bash
# 在当前目录生成针对 localhost 与 127.0.0.1 的证书与私钥 (0600)
certkit gen

# 支持多个域名和内网 IP（位置参数直接打，工具自动智能嗅探 IP 与域名）
certkit gen dev.local api.test 127.0.0.1 192.168.1.100
```

### 2. 想要浏览器全绿锁？一键搞定受信任 Root CA
```bash
# 1. 一键初始化本地 Root CA（自动保存在 ~/.certkit/ca/，并自动加入系统钥匙串信任！）
certkit ca

# 2. 从此以后敲任何 gen，默认全自动由本地根 CA 签发！
certkit gen myapp.local
# ➜ 自动生成 cert.pem, key.pem 以及 fullchain.pem
# ➜ 打开 Chrome / Edge 访问本地服务，当场享受绿色安全小锁！
```

### 3. 检查证书状态（告别几百行 ASN.1 dump）
```bash
# 检查当前目录 ./cert.pem
certkit check

# 检查远程服务（自动补齐 443 端口）
certkit check github.com

# 输出机器可读结构化 JSON
certkit --json check cert.pem
```

### 4. 格式无痛互转（单参数对切）
```bash
# 自动在同目录抓取 key.pem，密码默认 changeit，产出 cert.p12
certkit convert cert.pem

# 一键把同名 cert.p12 和 cert.der 全部齐整整生成好
certkit convert cert.pem --all

# 还原回文本格式
certkit convert bundle.p12
```

### 5. 跨平台系统信任管理（macOS / Linux / Windows 11）
```bash
# 检查当前根 CA 是否已受系统信任
certkit trust check

# 安装到系统/用户根受信任库
certkit trust install

# 从系统卸载
certkit trust uninstall
```

### 6. 底层原生透传（100% 逃生门）
遇到极端冷门需求，不需要跳出工具，直接原汁原味透传给底层系统 OpenSSL：
```bash
certkit raw version
certkit raw s_client -connect github.com:443
certkit raw x509 -in cert.pem -noout -text
```

### 7. 健康与环境诊断
```bash
certkit doctor
```
一键排查操作系统、OpenSSL 执行路径、版本、钥匙串权限与本地 CA 状态。若未检测到 OpenSSL，会贴心打印当前系统对应的一键复制安装指令。

---

## 📐 规格契约与底层设计

- **实现**：Java 25 + Picocli + GraalVM Native Image，编译为单个独立原生二进制执行文件。
- **三层架构设计**：
  1. **极简约定层**：默认目录、自动 IP/域名嗅探、默认受信任 CA 签发、默认密码 `changeit`；
  2. **精细控制层**：完整保留 `--key`, `--out`, `--pass`, `--days`, `--algo`, `--ca-cert` 选项；
  3. **原生透传层**：`certkit raw` 提供 100% 底层逃生门。
- **跨平台支持**：
  - **macOS**：用户登录钥匙串免密码注入 (`security`)；
  - **Windows 11**：原生 `certutil.exe -addstore -user Root` 免提权注入，自动探测 Git for Windows 内置 OpenSSL；
  - **Linux**：自动适配 Debian/Ubuntu、RHEL/CentOS、Arch 发行版信任库。

详见完整的接口契约文件：[SPEC.md](SPEC.md)。
