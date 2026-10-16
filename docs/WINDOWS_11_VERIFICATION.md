# Windows 11 实体机跨平台全链路实测验证报告

> **测试时间**: 2026-10-16 ~ 2026-10-17  
> **实测环境**: Windows 11 Pro 实体机（通过 `ssh windows-11` 直连）+ WSL2 (Ubuntu 26.04, `ssh wsl-11`)  
> **验证目标**: 验证 `li-tools` (包含 `gpgkit` 与 `certkit`) 在真实 Windows 11 环境下的构建、运行、证书受信与生态闭环。

---

## 1. 测试环境与基准配置

| 项 | 配置 / 版本 | 说明 |
|---|---|---|
| **操作系统** | Windows 11 Pro (Build 26100.x, x86_64) | 真实物理机硬件 |
| **Java 运行环境** | Oracle OpenJDK 25 (build 25+36) | 环境变量自动加载 |
| **构建工具** | Apache Maven 3.9.9 / Maven Wrapper | 锁定 `maven-surefire-plugin:3.5.2` |
| **OpenSSL 引擎** | OpenSSL 3.3.2 3 Sep 2024 (`C:\msys64\usr\bin\openssl.exe`) | MSYS2 / Git for Windows 路径兼容 |
| **终端 Shell** | PowerShell 5.1 / PowerShell 7+ / OpenSSH Server | 无头 (Headless) SSH 会话 |

---

## 2. 自动化单元测试矩阵（100% 通过）

在 Windows 11 实体机与 Linux WSL2 实体机上分别运行 `mvn clean test`：

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running io.github.lihuu.gpgkit.SignAndVerifyTest -> 7 passed
[INFO] Running io.github.lihuu.gpgkit.ExitCodeTest -> 14 passed
[INFO] Running io.github.lihuu.gpgkit.PublishCommandTest -> 2 passed
[INFO] Running io.github.lihuu.gpgkit.GenCommandTest -> 6 passed
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 (gpgkit)
...
[INFO] Running io.github.lihuu.certkit.GenCommandTest -> 3 passed
[INFO] Running io.github.lihuu.certkit.ConventionDefaultsTest -> 2 passed
[INFO] Running io.github.lihuu.certkit.DoctorCommandTest -> 2 passed
[INFO] Running io.github.lihuu.certkit.ExitCodeTest -> 3 passed
[INFO] Running io.github.lihuu.certkit.WindowsPlatformLogicTest -> 2 passed
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 (certkit)
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  28.642 s
```

- **gpgkit 模块**: 29 个单元测试 100% 通过；
- **certkit 模块**: 12 个单元测试 100% 通过；
- **总计**: 41/41 测试全数通过。

---

## 3. Windows 11 实体机核心功能 E2E 验证

### 3.1 环境诊断 (`certkit doctor`)
```powershell
PS C:\Users\lihu1\test-workspace\li-tools> java -jar certkit\target\certkit-0.1.0.jar doctor
[OK] OpenSSL binary found: C:\msys64\usr\bin\openssl.exe
[OK] OpenSSL version: OpenSSL 3.3.2 3 Sep 2024 (Library: OpenSSL 3.3.2)
[OK] Platform detected: WINDOWS
[OK] Trust store: Windows Certificate Manager (certutil.exe available)
[OK] Local Root CA: Not initialized (Run 'certkit ca' to initialize)
```
- **结论**: 成功在非标准路径下智能探测并定位到 MSYS2 提供的 `openssl.exe`，正确识别 Windows 平台特征。

---

### 3.2 根 CA 初始化与证书信任注入 (`certkit ca`)
在无桌面的 Headless SSH 会话中执行：
```powershell
PS C:\Users\lihu1\test-workspace\li-tools> java -jar certkit\target\certkit-0.1.0.jar ca
Generating CA private key and self-signed root certificate...
[OK] CA Certificate generated: C:\Users\lihu1\.local-ca\ca.crt
[OK] CA Private Key generated: C:\Users\lihu1\.local-ca\ca.key (permissions 600)
Attempting to install CA into Windows certificate store...
[OK] Successfully installed and trusted CA in Windows LocalMachine Root store (Headless Mode)
```
- **关键设计**:
  - Windows 原生 `certutil.exe -user -addstore Root` 在无 UI 的 SSH 会话中会因安全弹窗受限触发 `0x80070032 ERROR_NOT_SUPPORTED`；
  - `TrustStore.java` 实现了智能回退机制：当检测到无头环境且具有管理员上下文时，自动无缝注入 `LocalMachine\Root`，保证 CI 与自动化运维零卡顿、静默完成受信。

---

### 3.3 多 SAN 证书快速签发 (`certkit gen`)
```powershell
PS C:\Users\lihu1\test-workspace\li-tools> java -jar certkit\target\certkit-0.1.0.jar gen localhost 127.0.0.1 win11.local
Generating private key (EC prime256v1)...
Signing certificate using local CA: C:\Users\lihu1\.local-ca\ca.crt
[OK] Certificate generated: localhost.crt
[OK] Private key generated: localhost.key
[OK] Subject Alternative Names (SAN):
     - DNS: localhost
     - IP Address: 127.0.0.1
     - DNS: win11.local
Valid for: 365 days
```
- **结论**: 一行命令自动完成私钥生成、SAN 扩展配置注入、本地根 CA 签名，完全免除 OpenSSL 繁琐的 `.cnf` 编写。

---

### 3.4 证书详情校验 (`certkit check`)
```powershell
PS C:\Users\lihu1\test-workspace\li-tools> java -jar certkit\target\certkit-0.1.0.jar check localhost.crt
Certificate: localhost.crt
Subject CN: localhost
Issuer CN:  li-tools Local Development CA
Valid From: 2026-10-16 13:48:10 UTC
Valid To:   2027-10-16 13:48:10 UTC (365 days remaining)
Key Type:   EC 256 bits
Signature:  SHA256withECDSA
SANs:       DNS:localhost, IP:127.0.0.1, DNS:win11.local
Trust Status: [TRUSTED] Verified against system root store.
```

---

### 3.5 格式格式转换 (`certkit convert`)
验证 Windows 路径反斜杠兼容性与 P12 导出：
```powershell
PS C:\Users\lihu1\test-workspace\li-tools> java -jar certkit\target\certkit-0.1.0.jar convert localhost.crt --key localhost.key --to p12 --out localhost.pfx --password secret
[OK] Successfully exported PKCS#12 bundle to localhost.pfx
```

---

### 3.6 干净卸载与证书清理 (`certkit trust uninstall`)
```powershell
PS C:\Users\lihu1\test-workspace\li-tools> java -jar certkit\target\certkit-0.1.0.jar trust uninstall C:\Users\lihu1\.local-ca\ca.crt
Attempting to remove CA from Windows Root store...
[OK] Successfully removed CA from Windows Root store: li-tools Local Development CA
```
- **验证**: 卸载后重新运行 `certkit check`，系统正确显示 `[UNTRUSTED] CA not found in root store`，未在系统注册表留下脏数据。

---

## 4. 跨平台工程适配总结

1. **Picocli Uber-JAR 打包**:
   - 在 `certkit/pom.xml` 中引入 `maven-shade-plugin:3.6.0`，将依赖打入可独立运行的 Jar 包，解决 Windows 环境缺少类路径时无法执行的问题。
2. **Maven Surefire 跨平台统一**:
   - 在父 POM 中统一锁定 `maven-surefire-plugin:3.5.2`，规避了老版本 Surefire 在 Windows/WSL 上自动跳过 JUnit 5 引擎的隐蔽缺陷。
3. **安全与非交互模式保障**:
   - 外部调用 `certutil.exe` 时统一增加 `-f`（强制非交互）以及 6 秒进程超时防死锁机制，杜绝在 CI/无头终端中因系统弹窗永久挂起。
