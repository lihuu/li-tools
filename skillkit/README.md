# skillkit

用 Java 管理自己的 skills 仓库，默认安装到公共 `~/.agents/skills`，也可分发到指定 Agent。运行时不依赖外部 `skills`、npm 或 npx；远程仓库通过系统 Git 获取。

## 使用

六个命令：

```bash
# 首次把自己的远端仓库克隆到本地，之后直接 pull
skillkit pull --repo owner/my-skills
skillkit pull

# 安装自己的 skill：从个人仓库的 skills/ 自动发现，包括分类目录
skillkit install my-skill

# 安装别人的 skill：自动记录上游，之后 update 就跟随该来源
skillkit install example --repo owner/repo

# 批量并行安装；默认并行数为 4
skillkit install first second third --jobs 8

# 只安装到指定 Agent，可逗号分隔或重复参数
skillkit install my-skill --agent claude-code,cursor
skillkit install my-skill --agent shared --agent claude-code

# 更新全部已安装的 skill，或指定名称
skillkit update
skillkit update example --jobs 8

skillkit uninstall example
skillkit list

# 首次发布到自己的远端仓库，之后直接 push
skillkit push --repo owner/my-skills
skillkit push
```

安装默认复制内容，`install --symlink` 可改为软链。安装、更新支持 `--jobs` 和 `--dry-run`；卸载支持 `--dry-run`。所有命令支持公共输出选项，详见 [CLI 契约](SPEC.md)。

## 自己的仓库

个人仓库可以是普通目录或 Git 仓库。自己的 skill，以及复制进来自己维护的第三方 skill，都直接放在 `skills/` 下。无需初始化、注册或导入命令；创建与编辑直接操作文件，发布使用 `push` 自动提交并推送；日常编辑和 Git 历史管理继续使用常规文件/Git 操作。

仓库选择优先级：`--repository /path/to/my-skills` → `SKILLKIT_REPOSITORY` → 当前目录向上找到的 `skills/` 或 `skillkit.json` → `~/.skillkit/repository`。

```bash
cd /path/to/my-skills
skillkit install my-skill
# 或从任意位置指定个人仓库
skillkit --repository /path/to/my-skills install my-skill
```

外部安装 `install example --repo owner/repo` 或 `--repo /path/to/other-skills` 自动在个人仓库 `skillkit.json` 保存来源、路径、ref 和版本。上游内容放在本机缓存和 Agent 目录，更新时完全跟随来源。可用 `--ref main` 指定 Git 分支、标签或提交；本地目录仅支持 HEAD。

想保留第三方 skill 的自定义版本，就把完整目录复制到自己的 `skills/`，按自己的 skill 安装和维护。工具不执行自动三方合并，也不覆盖同名原稿。

## 同步个人私有源

`pull --repo owner/my-skills` 首次将整个个人 Git 仓库克隆到选定的本地仓库，默认 `~/.skillkit/repository`，保存 origin 及跟踪分支。目标必须为空；已有个人文件时拒绝覆盖。之后直接 `pull` 拉取当前分支的 origin 跟踪分支，只允许快进；存在本地修改或提交分歧时保留内容并报错，不自动提交、暂存、合并或重置。已有仓库不能通过 `pull --repo` 静默更换 origin。`pull --dry-run` 不写入或联网。

`pull` 同步个人内容与来源声明，`install` 负责分发，`update` 更新已有安装。拉取不会自动安装到 Agent。

`push` 自动提交 `skills/` 和 `skillkit.json`，然后推送当前分支。首次 `push --repo owner/my-skills` 会为普通目录初始化 Git（main 分支），并保存 origin；已有 Git 仓库可直接使用配置好的 origin。`--repo` 显式指定时会保存为后续使用的发布目标。远端仓库需要事先存在，Git 认证及提交身份沿用现有配置。

```bash
skillkit push -m "更新我的 skills"
skillkit push --dry-run

# 另一台机器从你的私有源安装自己的内容或第三方来源项
skillkit install my-skill third-party-skill --repo owner/my-skills
skillkit update
```

自动提交包含新增、修改与删除；其他路径的暂存内容保留原状。没有内容变化时直接推送已有提交。推送失败保留本地提交，可处理远端分歧后重试；不自动合并或强制覆盖远端。

发布的来源记录同样可供其他机器安装和更新。第三方项按声明获取实际内容，个人仓库保留实体内容与上游元数据两种存储方式。跨机器分发的上游应使用可访问的 Git 地址；本地目录引用依赖相同路径布局。

## 全局安装

默认安装仅写入公共 `~/.agents/skills`，供读取这个目录的 Agent 共享，不再批量创建各 Agent 的配置目录。通过 `--agent <name>` 可指定一个或多个目标，支持逗号分隔及重复参数；选择目标后只向这些目录分发，不额外写入公共目录。使用 `--agent shared,claude-code` 可同时分发到公共目录和 Claude Code。共享路径去重，指定的目标目录按需创建，不要求检测到已安装的 Agent。

支持 `shared`、`codex`、`pi`、`zcode`、`claude-code`、`qwen-code`、`cursor`、`gemini-cli`、`hermes`（也接受 `hermes-agent`）、`opencode`、`antigravity`。Codex、Pi、ZCode 的全局路径本身就是公共目录，指定它们不提供 Agent 之间的隔离。公共目录也不保证所有 Agent 都会读取；需要独立目录的 Agent 应显式安装。路径见 [SPEC.md](SPEC.md)，与 [skills CLI 的全局路径映射](https://github.com/vercel-labs/skills/blob/main/src/agents.ts) 对齐。

`update`、`list`、`uninstall` 同样支持 `--agent`；省略时处理所有受支持目录中的既有安装记录，包括旧版分发的副本。更新不会创建未安装的 Agent 目录。

更新只处理当前个人仓库已安装的 skill，不自动分发尚未安装的其他项。卸载保留个人原稿及来源记录。未托管的同名目录和被手工修改的安装副本会受到保护，不会直接覆盖。

默认本地布局：

```text
~/.skillkit/
  repository/           # 长期保存的个人仓库
    skills/             # 自己编写或复制收录的内容
    skillkit.json       # install 自动维护的上游来源、路径、ref 和版本
  cache/                # 可重新获取的下载内容与安装临时目录
    git/                # Git 仓库缓存
    staging/            # 安装准备目录，操作结束清理临时内容
```

`install --repo` 将远程来源克隆到缓存，并在个人仓库统一保存来源记录，不会覆盖个人内容；`pull --repo` 则将你的个人仓库完整克隆到 `repository/`，两者用途不同。后续省略 `install --repo` 即可按记录安装。自己的 `skills/` 通过目录扫描发现，无需重复登记。没有本地内容或来源记录时，仅凭 skill 名称不能下载；裸 `install` 仍需提供名称。

个人声明为 `<repository>/skillkit.json`，可提交 Git；下载缓存为 `~/.skillkit/cache/`。旧版 `~/.cache/skillkit/` 不会自动移动或删除，以保留已安装软链的目标；后续安装和更新使用新缓存。每个目标目录的 `.skillkit-state.json` 保存本机安装状态；仓库及目标的 `.skillkit.lock` 用于并发协调。机器缓存、安装状态和锁不作为个人内容提交。

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
