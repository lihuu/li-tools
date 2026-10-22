# 开发环境：使用最新 GraalVM CE

Native 构建使用 **GraalVM Community Edition（CE）**，Java 源码保持 release 25；
Maven 要求 3.9+。`gpgkit` 需要 GnuPG，`certkit` 需要 OpenSSL。

截至 2026-10-22，本地已验证的最新官方 CE 为：

| 项目 | 版本 |
|---|---|
| GraalVM 发行版 | Community 25 Innovation 4，`25.4.4.1.1` |
| Runtime 完整标识 | `GraalVM CE 25.4.4.1.1+1.1` |
| `native-image --version` 首行 / JDK | `25.0.4.1.1` |

这些编号表示不同层次。不要只看到 `openjdk 25` 就认为编译器版本相同，
也不要把 CE（社区版）和 Innovation（发行系列）理解为互斥选项。

## 查询最新版本

以 [官方 CE 最新发行页](https://github.com/graalvm/graalvm-ce-builds/releases/latest)
为准，不依赖包管理器缓存中的旧版本。已有 GitHub CLI 时可查询：

```sh
gh api repos/graalvm/graalvm-ce-builds/releases/latest \
  --jq '{tag: .tag_name, name: .name, published: .published_at, url: .html_url}'
```

上表是已验证记录，后续的 `latest` 会变化。升级前确认发行说明中的 JDK 版本仍满足
项目要求，再重新运行相关 JVM 和 Native 检查；CI 的工具链选择由工作流单独管理。

## macOS Apple Silicon 安装

从官方发行页下载 `macos-aarch64` 的 JDK 压缩包及对应 `.sha256` 文件。
下面复现本次已验证版本；安装未来版本时先按上面的查询结果更新标签和文件名。

```sh
set -e
GRAALVM_RELEASE=graal-25.4.4.1.1
GRAALVM_ASSET=graalvm-community-jdk-25i4-25.0.4.1.1_macos-aarch64_bin.tar.gz
GRAALVM_BUNDLE=graalvm-community-25.4.4.1.1+1.1
GRAALVM_DOWNLOAD_DIR=$(mktemp -d)

gh release download "$GRAALVM_RELEASE" --repo graalvm/graalvm-ce-builds \
  --pattern "$GRAALVM_ASSET" --pattern "$GRAALVM_ASSET.sha256" \
  --dir "$GRAALVM_DOWNLOAD_DIR"

GRAALVM_SHA256=$(cat "$GRAALVM_DOWNLOAD_DIR/$GRAALVM_ASSET.sha256")
printf '%s  %s\n' "$GRAALVM_SHA256" "$GRAALVM_DOWNLOAD_DIR/$GRAALVM_ASSET" \
  | shasum -a 256 -c -

mkdir -p "$HOME/Library/Java/JavaVirtualMachines"
test ! -e "$HOME/Library/Java/JavaVirtualMachines/$GRAALVM_BUNDLE"
tar -xzf "$GRAALVM_DOWNLOAD_DIR/$GRAALVM_ASSET" \
  -C "$HOME/Library/Java/JavaVirtualMachines"
```

若同名安装已存在，先确认其版本，跳过解压步骤；不要覆盖现有安装。
保留旧 JDK，可用于版本对照或切回旧工具链。

## 选择工具链并验证

macOS 可通过已注册的 JDK 自动选择 Java 25：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"

java --version
native-image --version
mvn --version
command -v java
command -v native-image
```

确认 `native-image` 的 Runtime 行为 `GraalVM CE`，且 Maven 报告的 Java home
与该工具链一致。如果自动选择了其他厂商 JDK，显式把 `JAVA_HOME` 指向要使用的
GraalVM `Contents/Home`，再将它的 `bin` 前置到 PATH。
各工具构建脚本优先使用 PATH 中的 `native-image`，所以仅改变 `JAVA_HOME` 不够。

将选择方式接入自己已有的 shell 环境配置即可；已打开的终端需要重新加载该配置。
IDE 若固定了旧 SDK 路径，需要另行选择新版。

Linux/Windows 从同一发行页选择对应平台包，按发行页各平台的 Instructions 链接
设置 `JAVA_HOME` 和 PATH。本文的安装命令仅在 macOS ARM64 验证。

## 项目检查

在仓库根目录运行：

```sh
mvn clean test
mvn -DskipTests package -pl certkit -am
(cd certkit && ./build-native.sh)
certkit/target/certkit --help
certkit/target/certkit --version
```

也可用同样方式构建 gpgkit/tokenkit。Windows 的二进制带 `.exe` 后缀。
此次环境更新保持现有 `-O3`、独立可执行文件构建方式及项目安全配置；
安全配置继续包含所选构建 JDK 的完整 `java.security` 策略。

**升级不会自动启用分层缓存。** `LayerCreate` / `LayerUse` 属于另行配置的实验性构建方式，
会涉及基础层和运行库管理；本次版本对照使用普通 standalone 构建，
不将先前分层实验的提速数字归因于升级。

本次 certkit 对照发现：新版 standalone 构建更慢、产物更大，功能冒烟通过。
详细数字和限制见[升级测量记录](GRAALVM_CE_UPGRADE_EVALUATION.md)；
使用新版是工具链与能力选择，不应直接宣传为编译提速。
