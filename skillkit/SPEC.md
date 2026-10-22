# skillkit CLI 契约

## 个人仓库

`--repository <path>` 为继承选项；否则读取 `SKILLKIT_REPOSITORY`，再从当前目录向上查找 `skillkit.json`，最后默认 `~/.skillkit/repository`。

| 命令 | 行为 |
| --- | --- |
| `init` | 初始化个人目录和版本 1 JSON 声明；不初始化 Git 或覆盖已有声明 |
| `create <name>` | 创建可编辑的 `skills/<name>/SKILL.md` |
| `import <names...> --source <source> [--ref HEAD]` | 复制完整目录到个人 skills/，不保留活动上游关系 |
| `track <names...> --source <source> [--ref HEAD]` | 仅登记上游 metadata，不向个人 skills/ 写入内容 |
| `untrack <name>` | 取消订阅，不自动卸载已安装副本 |
| `catalog` | 枚举本地内容和订阅 |
| `list-available [--source <source>] [--ref HEAD]` | 枚举个人 catalog 或指定来源 |
| `find [query]` | 按名称查找个人 catalog |
| `use <name>` | 输出 SKILL.md 内容，不安装，不启动 agent |

`--repo` 是 `--source` 别名。GitHub owner/repo、普通 Git URL 和本地目录受支持；Git ref 可选择分支/tag/commit。本地普通目录是活动文件来源，非默认 ref 会报 usage 错误。Git 来源通过系统 Git 获取到用户缓存中的固定版本快照。

所有名字必须匹配 `[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}`。来源的重复目录名必须消歧后再使用；本地内容与订阅重名时拒绝解析。源文件须为普通文件/目录，源内软链暂不支持，避免复制到源范围之外。

`import/track --dry-run` 只报告请求与已知冲突，不写目录、声明或缓存，不下载远程内容；未获取来源的可用性不会假装已经验证。

仓库 `skillkit.json` 的结构为 `{"version":1,"upstreams":[{"name":"example","source":"owner/repo","path":"skills/example","ref":"main","revision":"<resolved revision>"}]}`。本地内容自动从 skills/ 发现，无需在 JSON 再登记。订阅指向本地目录时 revision 为内容摘要，Git 来源为实际提交。成功安装/更新后记录已解析版本，不把失败项写成成功。

## 安装与卸载

| 命令 | 行为 |
| --- | --- |
| `add/install <names...>` | 安装个人 catalog 项 |
| `add <names...> --source <source>` | 订阅该来源后安装；来源登记和安装是两个步骤 |
| `update [names...]` | 更新当前仓库在选定目标中的已有安装，保留安装模式 |
| `remove/rm/uninstall <names...>` | 删除当前仓库托管的安装，不删个人内容或订阅 |
| `list/ls` | 枚举当前仓库在选定目标中的安装 |
| `check` | 检查已安装项是否缺失、被修改或落后于来源；不修改声明和安装，可刷新来源缓存 |

共同目标参数：`-a/--agent`（重复或逗号分隔）、`--project` 或 `-g/--global`（互斥，默认全局）、`--project-dir`、`--target-dir`。`--target-dir` 与显式 agent 互斥。无 agent 参数时只使用探测到的受支持 agent；没有目标时报 usage 错误。

| Agent | 项目 skills 根 | 全局 skills 根 |
| --- | --- | --- |
| Codex / Pi / ZCode / universal | `.agents/skills` | `~/.agents/skills` |
| Claude Code | `.claude/skills` | `${CLAUDE_CONFIG_DIR:-~/.claude}/skills` |
| Qwen Code（qwen-code/qwen） | `.qwen/skills` | `~/.qwen/skills` |
| Cursor | `.agents/skills` | `~/.cursor/skills` |
| Gemini CLI | `.agents/skills` | `~/.gemini/skills` |
| Hermes Agent | `.hermes/skills` | `${HERMES_HOME:-~/.hermes}/skills` |
| OpenCode | `.agents/skills` | `${XDG_CONFIG_HOME:-~/.config}/opencode/skills` |
| Antigravity | `.agents/skills` | `~/.gemini/antigravity/skills` |

`add --symlink/--link` 链接到本地原稿或固定 Git 版本缓存；默认复制。更新保留每个目标原有 copy/symlink 模式。Windows 无创建软链能力时返回执行失败，不静默改成复制。

`add/update --jobs <N>` 默认为 4，必须为正整数；有界虚拟线程并行准备不同 skill，同源/ref 复用一次解析。目标发布、恢复和对应状态提交在跨进程目标锁内进行。同一个实际目标根去重；同名未托管内容或其他仓库所有的目标不会被覆盖。

`add/update/remove --dry-run` 不改变声明、缓存、目录或安装。更新仅选择现有安装；不把新 catalog 项顺带安装。部分失败保留成功结果并返回 1，JSON 中包含各项状态。本地复制安装被手工修改时更新/卸载拒绝覆盖；删除软链不跟随删除源。

## 输出与执行

裸调用和 `-h/--help` 输出帮助，`-V/--version` 输出 `skillkit <根 POM 版本>`。成功返回 0，参数错误返回 2，执行或逐项失败返回 1。结果在 stdout，普通错误在 stderr；JSON 批量操作的失败信息随逐项结构输出。

公共选项来自 `GlobalOptionsMixin`：`--json`、`--quiet`、`--no-color`、`--debug`。帮助和版本始终保持相应格式；没有 ANSI 的重定向输出、presence-based NO_COLOR、usage 错误遵循统一约定。

仓库锁保护个人声明，目标锁保护文件替换与 `.skillkit-state.json`。目录替换包含多步操作，不宣称崩溃原子事务；正常文件或状态写入失败会尝试恢复旧目标。若操作系统阻止恢复，报告执行失败并保留备份供恢复。进程被强制杀死的自动恢复不在当前契约内。
