# 新增工具接入清单

这份清单适用于向 li-tools 添加独立安装的命令。根 POM 注册后，工具就会进入 Maven reactor。Native 构建、发布、二进制安装器和 Skill 安装器各自有工具名单，按以下步骤逐处核对。

## 1. 定命令和模块

- [ ] 在规格里确定正式命令名、常用调用、输入来源、stdout 和 stderr 契约以及退出码。模块目录、Maven artifact、Native 文件名和安装目标使用同一名称。Windows 的可执行文件加 `.exe`。
- [ ] 在[根 POM](../pom.xml)的 `<modules>` 注册工具。沿用 Java 25、Maven、Picocli 和 `cli-common`。参考 [skillkit POM](../skillkit/pom.xml)配置可运行 JAR、Maven 过滤的版本资源和 Picocli Native 元数据生成。
- [ ] 在 `app` 显式装配入口，在 `cli` 处理参数、输出和退出码。复用公共 Runner 和适用的选项。检查 `--help`、`--version`、无色重定向，以及工具自己的常用人机调用。其余约定见[项目规范](../AGENTS.md)。

## 2. 定依赖和作用域

- [ ] 按实际用途声明依赖。共用基础是 `cli-common` 和 Picocli。JetBrains Annotations 使用 `provided`，JUnit 使用 `test`，`picocli-codegen` 用于构建期。
- [ ] 只在使用库的模块声明它。跨模块共用的库在根 POM 的 `dependencyManagement` 统一版本，各模块自己决定作用域。仅单一工具使用的库把版本放在该模块。用 `mvn -pl <tool> -am package dependency:tree` 核对实际解析到的传递依赖与作用域。
- [ ] 新增运行依赖时，记录用途、版本、许可、传递依赖和 Native 元数据需求。用仓库锁定的 GraalVM 运行实际使用的功能，并记录 Native 可执行文件和发布归档体积。有替代方案时，在同平台、同工具链和构建参数下比较体积。

## 3. 接入构建、冒烟和打包

- [ ] 新增 `<tool>/build-native.sh`，支持独立构建和 `--skip-package`。把模块加入[根构建脚本](../build-native.sh)的 Maven 打包列表及 Native 构建循环，并更新[构建脚本测试](../scripts/tests/test_native_build.py)。
- [ ] 在 [Native 冒烟脚本](../scripts/smoke-native.py)加入正式入口和核心用例。日常用 `mvn test -pl <tool> -am`，集成验证用 `mvn clean test --batch-mode` 和 `python3 -m unittest discover -s scripts/tests -v`。
- [ ] 更新 [Release 工作流](../.github/workflows/release.yml)的二进制暂存和上传列表、[归档及校验和脚本](../scripts/package-release-archives.py)，以及 [Homebrew 和 Scoop 生成脚本](../scripts/sync-package-managers.py)及对应测试。按发布矩阵验证 macOS ARM64、Linux AMD64 和 Windows AMD64 的真实二进制。

## 4. 接入文档、安装和 Skill

- [ ] 补齐模块使用说明、根 [README](../README.md) 和[中文 README](../README_zh.md)，以及官网工具入口。安装示例明确平台和单工具安装方式。
- [ ] 给 [Shell](../site/install.sh) 和 [PowerShell](../site/install.ps1) 二进制安装器加入单工具目标，并核对默认批量安装列表。
- [ ] 提供独立的 `<tool>/SKILL.md` 及官网的 `site/<tool>/SKILL.md`。Skill 写明何时使用、如何安装该工具、怎样确认 `<tool> --version`，并给出常用的可复制命令。同步[套件 Skill](../SKILL.md)、[官网套件 Skill](../site/SKILL.md)、[Shell Skill 安装器](../site/install-skills.sh)、[PowerShell Skill 安装器](../site/install-skills.ps1)和[安装测试](../scripts/tests/test_install_skills.py)。
- [ ] 首次发布时，先确认最新 GitHub Release 已包含三平台的产物、归档和校验和，再上线公开站点的下载入口和默认批量安装名单。当前站点从 main 的 `site/` 部署，可将这些公开安装改动排在首次发布之后。待 Homebrew 和 Scoop 同步后，逐个验证支持的平台和渠道能安装，并在 PATH 上直接执行正式命令和核心用例。
