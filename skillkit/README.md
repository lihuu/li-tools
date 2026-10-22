# skillkit

管理本地 agent skills 的 Java CLI。目标是将 `lihuu/lihuu-skills` 的安装、更新、卸载和清单管理迁移到 Java，并支持受控的并行安装和更新。

产品目标同时包括管理用户自己的 skills 仓库（Git 仓库或本地目录）、维护其上游来源，并从个人仓库分发给 agent，见[需求草案](../docs/superpowers/specs/2026-10-22-skillkit-repository-design.md)。这些能力尚未实现。

**当前状态：模块骨架。** 已接入 Maven reactor、`cli-common`、JPMS、可执行 JAR 和独立 Native 构建；当前仅提供帮助、版本和公共选项。安装、更新、卸载、agent 探测和并行调度尚未实现。

## 开发

从仓库根目录运行：

```sh
mvn test -pl skillkit -am
mvn package -pl skillkit -am
java -jar skillkit/target/skillkit.jar --help
java -jar skillkit/target/skillkit.jar --version
./skillkit/build-native.sh
./skillkit/target/skillkit --help
```

与其他工具相同，使用 Java 25、Picocli、JetBrains 注解和 JUnit 5；统一依赖 `cli-common` 的 `CliRunner` 与 `GlobalOptionsMixin`。版本来自根 POM，经 Maven 过滤进入 `application.properties`。没有新增业务依赖。

```text
skillkit/
├── pom.xml
├── build-native.sh
├── SPEC.md
├── MIGRATION.md
└── src/
    ├── main/
    │   ├── java/
    │   │   ├── module-info.java
    │   │   └── io/github/lihuu/skillkit/
    │   │       ├── app/        # 入口、显式 wiring、应用元信息
    │   │       └── cli/        # Picocli 命令与输出
    │   └── resources/         # Maven 版本过滤与 Native 资源声明
    └── test/java/             # 真实 CLI wiring 测试
```

业务代码按实现需要再建立 `core` 包，不预建空包或抽象。完整功能落地之前，根 Native 批量发布入口、release assets、包管理器和安装站点继续保持现有三款工具；本模块通过自己的脚本构建和验证。

- [当前 CLI 契约](SPEC.md)
- [脚本行为梳理与 Java 并行迁移方案](MIGRATION.md)
