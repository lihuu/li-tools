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

在仓库根目录依次运行：

```sh
./gpgkit/build-native.sh
./certkit/build-native.sh
./tokenkit/build-native.sh
```

脚本仍按各工具原有设置执行 Maven 打包、安全策略覆盖和 Native Image 构建。
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
