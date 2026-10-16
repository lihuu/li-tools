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

## 2. 核心约定（Convention over Configuration）

1. **默认 CA 存放目录**：`~/.certkit/ca/`（含 `ca.crt` 与 `ca.key`）。环境变量 `CERTKIT_CA_DIR` 可覆盖。
2. **默认信任联动**：运行 `certkit ca` 默认自动尝试将 CA 注入系统钥匙串受信任（可用 `--no-trust` 跳过）。
3. **默认签发联动**：运行 `certkit gen` 时，若检测到 `~/.certkit/ca/` 存在，**默认自动使用该 CA 签发**（产物直接受信任全绿！）；否则退回单机自签并温馨提示。
4. **智能参数嗅探**：`certkit gen [hosts...]` 接收纯位置参数，自动识别 IPv4/IPv6 与域名，无需分别指定 `-d` 或 `--ip`。不传时默认 `localhost` + `127.0.0.1`。
5. **默认就地落盘**：默认在当前目录直接生成 `cert.pem` 与 `key.pem`（若 CA 签发则同时产出 `fullchain.pem`）。

## 3. 命令面

全局参数：`--json`（机器可读输出）、`--quiet`、`--help`。
**退出码**：`0` 成功；`1` 业务失败；`2` 用法错误。

### 1) `certkit gen [hosts...]`
一键生成 TLS 证书与私钥：
```bash
certkit gen                                    # 默认当前目录生成针对 localhost 与 127.0.0.1 的证书
certkit gen dev.local api.test 192.168.1.100  # 自动识别域名与 IP，默认优先由本地 CA 签发
certkit gen --self-signed                      # 强制单机自签名，不用本地 CA
certkit gen --out ./certs                      # 指定输出目录
```

### 2) `certkit ca`
一键初始化本地 Root CA 并自动信任：
```bash
certkit ca                                     # 默认写入 ~/.certkit/ca/ 并自动加入系统受信任库
certkit ca --no-trust                          # 生成 CA 但不自动调用系统信任
certkit ca --out DIR                           # 自定义生成目录
```

### 3) `certkit check [target]`
检查证书文件或远程服务状态：
```bash
certkit check                                  # 默认检查当前目录 ./cert.pem
certkit check ./my-cert.pem
certkit check github.com                       # 自动补全 :443
```

### 4) `certkit convert <in-file> [out-file]`
证书格式互转（支持单参数智能同名推导与全参控制）：
```bash
# 极简模式（单参数自动对切，密码默认 changeit）：
certkit convert cert.pem                       # 自动寻找同目录 key.pem，产出同名 cert.p12
certkit convert cert.pem --all                 # 一键同时生成同名 cert.p12 与 cert.der
certkit convert cert.p12                       # 自动解包为 cert.pem 与 cert-key.pem

# 完整参数模式（全兼容保留）：
certkit convert cert.pem cert.p12              # 显式指定输出目标
certkit convert cert.pem --key ./my.key --out /other/app.pfx --pass "MySecret"
```

### 5) `certkit trust`
系统证书受信任状态管理：
```bash
certkit trust install [ca-cert] [--system]     # 缺省时默认安装 ~/.certkit/ca/ca.crt
certkit trust check [ca-cert]                  # 缺省时默认检查 ~/.certkit/ca/ca.crt
certkit trust uninstall [ca-cert] [--system]
```

### 6) `certkit raw <openssl-args...>`
底层原生 OpenSSL 命令直接透传（100% 逃生门）：
```bash
certkit raw version
certkit raw s_client -connect github.com:443
certkit raw x509 -in cert.pem -noout -text
```
- 直接调用系统 OpenSSL，标准输入输出透明透传，不拦截任何参数。

### 7) `certkit doctor`
环境健康检查与诊断自检：
```bash
certkit doctor [--json]
```
- 检查项：
  1. 操作系统类型与架构；
  2. OpenSSL 可执行文件路径与版本（若缺失，给出可直接复制的单行安装命令）；
  3. 系统受信任证书库状态；
  4. 本地默认 CA 状态与到期情况。
- 退出码：所有必要项就绪返回 `0`；存在阻断性问题返回 `1`。



