# skillkit

用 Java 管理个人 skills 仓库和本地 agent 安装，不依赖 `skills`、npm、npx 或 Python。远程 Git 来源使用系统 Git 及其已有认证。

个人仓库支持两种方式：

- **本地内容**：放在 `skills/`，可以自己编写，也可以复制第三方目录后维护。工具不会自动拉取或合并它原来的上游。
- **上游订阅**：`skillkit.json` 只保存来源、路径、ref 和版本。安装/更新时获取上游，内容放入本机缓存和 agent 安装目录，不收入个人仓库的 `skills/`。

## 使用

```sh
# 可以选择普通目录，也可以选择已有 Git 仓库
skillkit --repository /path/to/my-skills init
export SKILLKIT_REPOSITORY=/path/to/my-skills

# 自己编写：随后直接编辑 skills/my-skill/SKILL.md
skillkit create my-skill

# 复制收录：成为本地内容，之后与自己写的 skill 一样维护
skillkit import example --source /path/to/other-skills
skillkit import example --source owner/repo --ref main

# 完全跟随上游：仅保存 metadata
skillkit track example --source owner/repo --ref main
skillkit catalog
skillkit list-available --source owner/repo
skillkit find example
skillkit use example

# 安装多个 skill，最多同时准备 4 个任务
skillkit add my-skill example -a codex,claude-code --jobs 4
# 也可直接订阅并安装
skillkit add another --source owner/repo -a codex
# 链接到本地原稿，或链接到上游固定版本的缓存
skillkit add my-skill -a codex --symlink

# 项目级安装
skillkit add example --project --project-dir /path/to/project -a codex
skillkit add example -a codex --dry-run

skillkit list -a codex
skillkit check -a codex
skillkit update -a codex --jobs 4
skillkit update example -a codex
skillkit remove example --project --project-dir /path/to/project -a codex
# 取消订阅保留已安装副本；卸载不删除个人原稿或订阅
skillkit untrack example
```

同名的本地内容和上游订阅不能同时存在。分类目录中的现有 skill 自动发现，不要求先迁移或逐项注册。`init` 不执行 `git init`、commit 或 push，Git 版本操作由用户自行处理。

来源支持本地目录、GitHub `owner/repo`、HTTPS/SSH Git URL，以及用于本地 Git 仓库的 `file://` URL。指定分支/tag/commit 使用 `--ref`；本地普通目录只接受默认 `HEAD`（实际读取当前文件），需要 Git ref 时使用 Git URL。

## 安装目标与状态

`--repository` 优先于 `SKILLKIT_REPOSITORY`；未指定时，从当前目录向上找 `skillkit.json`，否则使用 `~/.skillkit/repository`。它不硬编码到作者的 GitHub 仓库。

默认只选择已探测到的受支持 agent，也可显式 `-a`。内置 Codex、Claude Code、Cursor、Gemini CLI、Qwen Code、Hermes、ZCode、Pi、OpenCode、Antigravity，以及显式 `universal`；其他工具可用 `--target-dir /absolute/skills/path`。`CODEX_HOME` 用于探测；`CLAUDE_CONFIG_DIR`、`HERMES_HOME`、`XDG_CONFIG_HOME` 用于相应目标。未探测到 agent 时要求显式选择，不自动铺到所有目录。

同一个共享目录只安装一次。全局/项目作用域与 agent 目录映射见 [SPEC.md](SPEC.md)。`--project-dir` 选择项目路径，仍需 `--project` 选择项目作用域。

- 仓库声明：`<repository>/skillkit.json`，可提交到 Git；本地目录来源优先记录相对于个人仓库的路径；Windows 跨盘无法表示相对路径时保留绝对路径，跨机器需重新配置。
- 下载和暂存：`~/.cache/skillkit/`，不写入个人内容仓库。Git 来源固定到已解析的版本目录，避免更新一个 skill 时静默改变其他软链。
- 安装状态：每个实际 skills 根目录的 `.skillkit-state.json`，记录来源仓库、安装模式、摘要和版本。不同个人仓库共同使用同一目标时保留各自记录。
- 操作锁：仓库和目标根目录中的 `.skillkit.lock`，`init` 会加入仓库 `.gitignore`。进程内有界并行，跨进程按实际目标锁保护发布与状态写入。

更新只处理选定目标中已经由当前仓库安装的项，不自动安装 catalog 里的其他 skill。复制安装若在目标内被手工修改，更新/卸载会拒绝覆盖；软链只验证链接身份，源内容可正常编辑。未托管的同名目录不会被认领或删除。

## 验证与兼容范围

核心命令已实现。`--jobs` 控制 skill 准备与来源获取的最大并发；同一来源/ref 在一次操作中复用固定 Git 快照，发布和状态提交在目标锁内串行。失败逐项汇总，成功项保留，存在失败退出 1；`--jobs 1` 可串行执行。

`--json` 输出结构化结果，`--quiet` 抑制操作说明，`--no-color`/`NO_COLOR` 遵循公共 CLI 约定。`use` 打印指令，不启动另一个 agent；`find` 搜索个人 catalog，不调用在线 marketplace。

尚未提供：npm 包同步、HTTP 归档安装、所有第三方 agent 的自动探测、旧 npm lockfile 自动认领、原 third-party-skills.json 的自动迁移、hook 注册，以及 Git commit/push 包装命令。已有本地内容可直接使用，旧安装目录保持未托管，不会被自动覆盖。本工具目前未宣称与 npm skills 的全部实验命令兼容。

## 开发

```sh
mvn test -pl skillkit -am
mvn package -pl skillkit -am
java -jar skillkit/target/skillkit.jar --help
./skillkit/build-native.sh
./skillkit/target/skillkit --help
# 开发验收：需要可创建软链，所有数据均为临时合成数据
python3 scripts/smoke-skillkit.py
```

Java 25、Picocli、JetBrains 注解、JUnit 5、`cli-common`、JPMS、Shade JAR 与其他工具保持一致。JSON 解析使用 Jackson Core streaming，不使用对象反射绑定。版本来自根 POM。当前通过模块自己的脚本构建 Native，尚未接入根批量发布、包管理器或站点。

- [当前 CLI 契约](SPEC.md)
- [原脚本迁移对照](MIGRATION.md)
- [最终需求与原仓库分析](../docs/superpowers/specs/2026-10-22-skillkit-repository-design.md)
