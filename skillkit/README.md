# skillkit

用 Java 管理自己的 skills 仓库，并全局安装到所有受支持 Agent。运行时不依赖外部 `skills`、npm 或 npx；远程仓库通过系统 Git 获取。

## 使用

只有四个命令：

```bash
# 安装自己的 skill：从个人仓库的 skills/ 自动发现，包括分类目录
skillkit install my-skill

# 安装别人的 skill：自动记录上游，之后 update 就跟随该来源
skillkit install example --repo owner/repo

# 批量并行安装；默认并行数为 4
skillkit install first second third --jobs 8

# 更新全部已安装的 skill，或指定名称
skillkit update
skillkit update example --jobs 8

skillkit uninstall example
skillkit list
```

安装默认复制内容，`install --symlink` 可改为软链。安装、更新支持 `--jobs` 和 `--dry-run`；卸载支持 `--dry-run`。所有命令支持公共输出选项，详见 [CLI 契约](SPEC.md)。

## 自己的仓库

个人仓库可以是普通目录或 Git 仓库。自己的 skill，以及复制进来自己维护的第三方 skill，都直接放在 `skills/` 下。无需初始化、注册或导入命令；创建与编辑直接操作文件，Git 提交、拉取与推送继续用 Git。

仓库选择优先级：`--repository /path/to/my-skills` → `SKILLKIT_REPOSITORY` → 当前目录向上找到的 `skills/` 或 `skillkit.json` → `~/.skillkit/repository`。

```bash
cd /path/to/my-skills
skillkit install my-skill
# 或从任意位置指定个人仓库
skillkit --repository /path/to/my-skills install my-skill
```

外部安装 `install example --repo owner/repo` 或 `--repo /path/to/other-skills` 自动在个人仓库 `skillkit.json` 保存来源、路径、ref 和版本。上游内容放在本机缓存和 Agent 目录，更新时完全跟随来源。可用 `--ref main` 指定 Git 分支、标签或提交；本地目录仅支持 HEAD。

想保留第三方 skill 的自定义版本，就把完整目录复制到自己的 `skills/`，按自己的 skill 安装和维护。工具不执行自动三方合并，也不覆盖同名原稿。

## 全局安装

每次安装都写入所有内置目标，无需选择 Agent 或作用域。共享路径只写一次；未存在的目标目录会创建。目标对应 Codex、Pi、ZCode、Claude Code、Qwen Code、Cursor、Gemini CLI、Hermes、OpenCode、Antigravity，具体路径见 [SPEC.md](SPEC.md)。

更新只处理当前个人仓库已安装的 skill，不自动分发尚未安装的其他项。卸载保留个人原稿及来源记录。未托管的同名目录和被手工修改的安装副本会受到保护，不会直接覆盖。

个人声明为 `<repository>/skillkit.json`，可提交 Git；下载缓存为 `~/.cache/skillkit/`。每个目标目录的 `.skillkit-state.json` 保存本机安装状态；仓库及目标的 `.skillkit.lock` 用于并发协调。机器缓存、安装状态和锁不作为个人内容提交。

## 构建与验证

```bash
mvn test -pl skillkit -am
mvn package -pl skillkit -am
java -jar skillkit/target/skillkit.jar --help
./skillkit/build-native.sh
./skillkit/target/skillkit --help
python3 scripts/smoke-skillkit.py
```

最后一条使用临时仓库、临时 Git 来源和隔离的用户目录验证 JAR 与 Native，不修改实际 Agent 安装。

- [脚本迁移分析](MIGRATION.md)
- [需求与原仓库结构分析](../docs/superpowers/specs/2026-10-22-skillkit-repository-design.md)
