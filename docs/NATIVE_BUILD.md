# 原生构建优化等级

`gpgkit/build-native.sh`、`certkit/build-native.sh` 和 `tokenkit/build-native.sh`
使用 Native Image 默认的 O2 优化等级，不再强制指定 `-O3`。
发布工作流调用同一组脚本，因此后续发布构建也使用这个选择。

## 为什么使用默认 O2

本项目优先考虑 CLI 的构建耗时和分发体积。2026-10-22 在 macOS ARM64 上，
使用 GraalVM CE `25.4.4.1.1+1.1` 对同一个 certkit JAR 做了两轮串行对照：

| 配置 | 第一轮耗时 | 第二轮耗时 | 可执行文件大小 |
|---|---:|---:|---:|
| 默认（O2） | 29.09 秒 | 25.93 秒 | 24.08 MB |
| 显式 `-O2` | 26.68 秒 | 26.78 秒 | 24.08 MB |
| 显式 `-O3` | 39.29 秒 | 39.60 秒 | 39.63 MB |

大小采用十进制 MB。两轮顺序分别为默认/O2/O3 和 O3/O2/默认，
使用相同 JAR、安全策略覆盖文件、4 GiB 构建堆上限和 14 个构建线程。
耗时仅包含 Native Image，不包含 Maven 打包；没有使用分层缓存。

按两次观测的中位数，显式 O2 比 O3 减少约 32% 的编译耗时，产物缩小约 39%。
这些数据来自 certkit，不能直接当作另外两个工具或其他平台的加速比例。
尚未测量 O2/O3 对实际业务运行速度的影响。

## 当前 CE 版本的区别

该安装包的 `lib/svm/builder/svm.src.zip` 中，
`com/oracle/svm/hosted/code/CompileQueue.java` 明确区分 O2 和 O3：

- `applyRegularSuiteTuning` 在低于 O3 时，默认移除部分循环展开和循环向量化阶段。
- `reduceInlinerExploration` 在 O2 时减少内联探索，以降低构建耗时和体积。
- `omitPriorityInliningTuning` 让 O3 跳过用于降低内联编译成本的参数调整。

[官方 25.4 优化指南](https://www.graalvm.org/jdk25.4/reference-manual/native-image/optimizations-and-performance/)
目前仍写着 CE 的 O2/O3 相同；这与上述安装版本随附源码及实测不符。
本项目针对当前工具链采用实测结论，升级 GraalVM 后应重新测量。

## 构建与验证

在仓库根目录构建全部工具：

```sh
./build-native.sh
```

根脚本先执行一次 `mvn -q -DskipTests package -pl gpgkit,certkit,tokenkit -am`，
然后顺序调用三个工具脚本的 `--skip-package` 模式。发布工作流使用同一个入口，
无需先向本地仓库 `install` 公共模块，也不再由三个子脚本重复执行 Maven 生命周期。
已在无 target 的独立工作区用真实入口验证：一次 Maven 调用成功生成三个 O2 原生产物。
七个脚本行为测试还覆盖带空格路径、独立构建、缺少 JAR 和失败时停止后续构建，
仅替代 Maven、Native Image 两个外部命令边界。

普通 CI 和发布构建共用 `.github/actions/setup-graalvm/action.yml`，
固定为 GraalVM CE `25.4.4.1.1`，安装后检查完整 Runtime 版本。
发布矩阵按 Linux AMD64、macOS ARM64、Windows AMD64 分成三个任务，
每个任务统一打包一次，再依次构建三个工具并运行 `scripts/smoke-native.py`。
该冒烟验证真实 Native 的 CLI、合成 PEM/DER 证书解析及临时加密 SQLite 读写，
不使用系统钥匙串或真实凭据。

可以在 GitHub Actions 手动对分支触发 `Release Native Binaries`，
只构建、验收和上传产物；只有 `v*` 标签引用才进入发布任务。

只构建一个工具时，直接执行对应脚本，例如 `./certkit/build-native.sh`。
默认路径始终执行 Maven，已有 JAR 也不会自动跳过打包。
`--skip-package` 仅适合调用者刚刚成功准备了对应 JAR 的同一次构建：
缺少 `target/<tool>.jar` 时会失败；调用者负责保证已有 JAR 与源码和构建参数一致。
它不是持久缓存，也不做基于时间戳的缓存有效性判断。

Maven 本身有增量编译，不代表重复调用时每次都会完整运行 javac；
这里减少的是进程启动、reactor 扫描和重复执行资源处理、打包等生命周期的成本。
三个 Native 编译仍分别执行，保留原有安全策略覆盖及各工具所需参数。

2026-10-22，在本机相同源码的两个临时副本上，用正式联网 Maven 命令、已有依赖缓存测得：

| Maven 准备状态 | 三个工具分别打包（三次调用） | 一次 reactor 打包 |
|---|---:|---:|
| 新建 target | 12.33 秒 | 5.81 秒 |
| 已有 target 的增量构建 | 9.42 秒 | 4.64 秒 |

新建 target 先测分别打包，再测 reactor；增量构建反序。每个条件仅测一次，
不含 Native Image，也不代表首次依赖下载或远端 CI 的耗时。

优化等级不负责放宽 TLS 协议或证书信任策略。
构建日志应显示 `optimization level: 2`，验证时应运行生成的原生可执行文件。
`NATIVE_IMAGE_THREADS` 继续用于按需限制构建并发数。

本次等级对照的六个 certkit 产物均通过帮助、版本、用法错误、证书生成与转换、
受信任 TLS 1.2 成功、未受信任证书失败和 TLS 1.0 拒绝检查。

落地时另行验证了修改后的三个构建脚本：全部成功生成 macOS ARM64 原生产物，
日志均显示 O2；现有 131 个 JVM 测试通过。生成的产物通过 certkit 上述证书与 TLS 检查、
gpgkit 空测试密钥环状态查询，以及 tokenkit 临时 SQLite 数据库中的加密读写、
删除和 profile 隔离检查。测试仅使用临时数据和合成主密钥。

这不代表完整的密码学审计，也不代表 Linux、Windows 或全部外部工具路径验收。

## 为什么尚未应用 Native 预编译

此前的 Hello World 实验使用官方 `LayerCreate` / `LayerUse` 预先生成基础层，
复用后重建从约 36.2 秒降至 22.4 秒。该结果来自 O3 和 4 个构建线程，
不能直接代表当前 O2 下三个工具的收益。

[官方分层说明](https://graalvm.github.io/native-build-tools/latest/layered-images.html)
区分构建期 `.nil` 和运行期共享库：最终应用仍需要随附运行库。
该实验的 `.nil` 约 815 MB、运行库合计约 110 MB，基础层首次构建约 96 秒。
这会改变本项目现有单文件分发约定，并需要适配各平台的归档、安装和库查找路径。

单文件分发是硬性要求，因此这类需要随附运行库的分层方案不进入正式优化路线。
以后若继续研究预编译，必须限定为仅构建期使用的缓存，最终产物不增加运行库依赖。
本次 Maven 去重与工具链对齐不引入分层或运行库分发变化。
