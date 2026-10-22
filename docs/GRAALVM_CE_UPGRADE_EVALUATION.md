# GraalVM CE 升级：certkit 对照测量

2026-10-22；源码基线 [`755052e`](https://github.com/lihuu/li-tools/commit/755052e7001ae07dc94be2bfb81d07cae800d258)。

**本机已安装并选择最新官方 CE；这次升级没有让 certkit 的普通 Native 构建更快。**
保持 O3、4 GB 构建堆和 standalone 模式，构建中位数从 22.02 秒增加到 40.37 秒，
约慢 83.3%；产物中位大小从 28.95 MB 增加到 39.63 MB，约增加 36.9%。
相关功能检查通过。项目代码、Native 参数、安全配置与 CI 工作流未修改。

## 工具链与安装结果

| 项目 | 升级前 | 升级后 |
|---|---|---|
| GraalVM CE Runtime | `25.0.2+10.1` | `25.4.4.1.1+1.1` |
| JDK / Native Image 首行版本 | `25.0.2` | `25.0.4.1.1` |
| macOS JDK bundle | `graalvm-ce-25.0.2` | `graalvm-community-25.4.4.1.1+1.1` |

新版来自[官方 CE 发行页](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.4.4.1.1)，
压缩包与对应 `.sha256` 校验一致，安装在用户标准 JDK 目录。
现有环境通过 `/usr/libexec/java_home -v 25` 选择新版；Java、Native Image 和 Maven
均已验证指向新版，旧 JDK 保留，shell 配置未修改。
已打开的终端需重新加载其环境配置，固定 SDK 路径的 IDE 需单独调整。
安装与版本选择见[开发环境准备](DEVELOPMENT_ENVIRONMENT.md)。

## 方法

- 本机 macOS 27.2 ARM64，14 个逻辑 CPU。
- 新 JDK 执行全量 JVM 测试，再准备一次 certkit JAR；两个 Native 版本使用完全相同的 JAR。
- 参数保持当前脚本形态：`--no-fallback -H:+ReportExceptionStackTraces -J-Xmx4g`、
  `-J-Djava.security.properties==<项目配置>`、`-O3 -jar <同一JAR>`。
- 未设置 `NATIVE_IMAGE_THREADS`，两版日志均确认 14 线程、O3、`armv8.1-a` 和相同 C 编译器。
- 三轮顺序：旧→新、新→旧、旧→新。每次启动新的 Native Image 进程。
- 计时仅包含 Native Image 进程，排除 Maven、下载、安装和冒烟检查。
- 连续构建时文件缓存已存在，未独占整机；每版本仅 3 次，不是跨平台 CI 基准。
- 同一个安全覆盖文件继续包含各自 JDK 的完整默认策略；随 JDK 变化的默认策略属于升级的一部分，
  不能将“覆盖文件相同”理解为两个版本所有有效安全属性都逐字节相同。

输入 JAR SHA-256：
`934fec8b45c016a40c30f1a7c8c76e05358fc4f53ae016a4173f2ee3029ed257`。

项目安全覆盖文件 SHA-256：
`cbce6e2d464e6927142ab8ec123739947276014b55a6c2406c2bfb3fadaa6936`。

## 结果

| 指标 | CE 25.0.2 | CE 25.4 |
|---|---:|---:|
| 第 1 次 Native 构建（秒） | 29.219 | 39.892 |
| 第 2 次（秒） | 21.313 | 40.368 |
| 第 3 次（秒） | 22.022 | 40.863 |
| 中位数（秒） | 22.022 | 40.368 |
| 产物大小范围（十进制 MB） | 28.953 | 39.634–39.667 |

旧版第一次明显较慢，表格保留原始值。两版计时范围没有重叠；
本轮支持“此输入与配置下新版更慢”的观察，不支持把 83.3% 推广到所有工具或机器。
新版产物大小也有轻微变化；Native 哈希不同，不作逐字节等价声明。

| 构建阶段中位秒数（日志舍入值） | 旧版 | 新版 |
|---|---:|---:|
| 初始化 | 4.7 | 5.7 |
| 可达性分析 | 5.4 | 6.4 |
| 类型系统 | 1.0 | 1.2 |
| 方法解析 | 0.6 | 0.7 |
| 内联 | 0.4 | 0.4 |
| 方法编译 | 5.9 | 20.1 |
| 方法布局 | 1.7 | 3.1 |
| 创建镜像 | 1.4 | 1.4 |

主要时间差出现在方法编译阶段。各阶段独立取中位数且已经舍入，不能要求相加等于进程中位数。
这些日志没有定位到具体编译优化阶段的原因，不能直接归因为某个上游缺陷。

## 验证范围

新 JDK 下全量 JVM：**131 项，0 失败、0 错误、0 跳过**。
cli-common 36、gpgkit 38、certkit 12、tokenkit 45。

六个 Native 产物全部构建成功，并各自通过：

- help/version 与非法参数退出码、无 ANSI 输出。
- 临时自签名证书生成，PEM/DER 读取与指纹一致性。
- PKCS12 转换，另用 keytool 确认包含 PrivateKeyEntry。
- 缺失文件退出码。
- 显式临时信任库下本地 TLS 1.2 成功；不指定该信任库时出现 PKIX 失败。
- 同一临时信任库下 TLS 1.0 以 `protocol_version` 拒绝；另用允许该协议的 Python
  测试客户端确认服务端确实能协商 TLS 1.0，避免把服务端不支持误认为客户端策略生效。

测试均使用合成证书和 127.0.0.1 服务。没有访问真实凭据、改变系统信任库或连接真实服务。
未进行有效弱证书矩阵、完整 PKIX、所有 Native 命令或 Linux/Windows 验收。
PKCS12 检查证明转换可用，未对其算法强度作验收结论。

## 与分层复用的关系

本轮未启用 `LayerCreate` / `LayerUse`，当前产品仍按原脚本构建 standalone 可执行文件。
先前 Hello World 分层实验是在同一个新版上比较全量与复用，观察到约 38.1% 的重建提速；
那不是旧版升级新版的效果，也不能直接抵消本轮 certkit 的版本差异。
若继续在 certkit 研究分层，需要另建独立实验，并计入基础层成本与运行库分发方式。

## 原始材料与复现边界

本机原始目录：`/tmp/li-tools-graalvm-upgrade-20261022/`。
`compare.py`、`results.json`、`phase-medians.json` 和六份构建日志保留精确命令、计时和产物哈希；
`smoke.py`、`smoke-results.json`、`jvm-tests.log`、`jvm-summary.json` 保留验证证据；
`installation.json` 保留默认工具链选择结果。
本 PR 只归档本文数字与方法，不上传二进制、测试私钥、工具链或这些临时文件。

复现时，先从上述源码基线准备一次 JAR，再分别显式调用两版 `bin/native-image`，
使用本节所列相同参数与输入，并轮换顺序。保留原始采样，不以新一轮结果覆盖本文历史数据。
