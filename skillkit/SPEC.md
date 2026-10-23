# skillkit CLI 契约

## 六个命令

| 命令 | 行为 | 专属参数 |
| --- | --- | --- |
| `install <names...>` | 从个人仓库或指定外部来源，安装到公共目录或指定 Agent | `--repo <source>`、`--ref <ref>`（默认 HEAD）、`--symlink`、`--jobs <n>`（默认 4）、`--dry-run`、`--agent/-a <name>` |
| `update [names...]` | 更新已安装项；省略名称时更新当前个人仓库的全部已安装项 | `--jobs <n>`（默认 4）、`--dry-run`、`--agent/-a <name>` |
| `uninstall <names...>` | 卸载已安装副本，保留个人原稿与来源记录 | `--dry-run`、`--agent/-a <name>` |
| `list` | 列出当前个人仓库管理的全局安装状态 | `--agent/-a <name>` |
| `push` | 自动提交个人内容和来源记录，并发布当前分支 | `--repo <source>`、`-m/--message <text>`、`--dry-run` |
| `pull` | 首次克隆个人仓库，之后从 origin 快进拉取 | `--repo <source>`（首次必填）、`--dry-run` |

只有一个安装入口 `install`；不提供导入、订阅管理、初始化或项目作用域子命令。Agent 选择通过参数完成，支持重复及逗号分隔。`--jobs` 必须为正整数。所有命令继承 `--repository <path>` 及 cli-common 的 `--json`、`--quiet`、`--no-color`、`--debug`。

仓库选择：显式 `--repository` → `SKILLKIT_REPOSITORY` → 当前目录向上发现 `skillkit.json` 或 `skills/` → `~/.skillkit/repository`。相对路径以当前工作目录为基准。

远程来源的 Git 缓存、版本快照与安装准备目录位于 `~/.skillkit/cache/`，不随个人仓库发布。旧缓存不自动移动或删除，保留既有软链目标。

## 内容与来源

本地内容从个人仓库 `skills/` 自动发现。外部来源优先搜索其 `skills/`，没有时搜索来源根目录。支持分类子目录，名称取包含 `SKILL.md` 的目录名。同名候选拒绝歧义；名称仅允许字母、数字、点、下划线、横线，首字符须为字母或数字，长度不超过 128。

`install <names...> --repo <source>` 支持 GitHub `owner/repo`、Git URL 和本地目录。Git 内容固定到实际提交的缓存快照。来源和 ref 在一次操作内共用解析结果，包括失败结果；不同 skill 的解析/准备按 jobs 有界并行，目标发布与状态写入由锁协调。

外部安装成功后自动在 `skillkit.json` 保存元数据：

```json
{"version":1,"upstreams":[{"name":"example","source":"owner/repo","path":"skills/example","ref":"main","revision":"<resolved revision>"}]}
```

本地内容无需 JSON 登记；第三方内容若已复制进个人 `skills/` 则按本地内容维护。相同名字不能同时指向本地内容与外部来源，也不能静默更换为另一来源。本地目录来源尽可能使用相对个人仓库的路径，Git 记录实际提交，本地内容记录摘要。失败项不记作成功版本。

## 私有源发布

`pull --repo <source>` 首次将完整个人仓库克隆到本地仓库，只接受空目录（允许工具自己的 `.skillkit.lock`），保存 origin 和跟踪分支。先克隆到临时目录，成功后在仓库锁内发布；克隆失败不留下部分个人内容。来源可为 owner/repo、Git URL 或本地 Git 仓库。远端包含保留文件 `.skillkit.lock` 时拒绝发布。

已有仓库的 `pull` 要求位于 Git 工作区根目录，当前分支跟踪 origin，工作区及暂存区无本地修改（忽略工具自身根目录锁）。执行 fetch 后只允许快进，不自动提交、暂存、重置或合并分歧，也不允许远端新增路径覆盖本地 ignored 文件。显式 `--repo` 不能静默替换现有 origin。`pull` 不分发到 Agent；拉取后按需 install/update。`--dry-run` 不写入或联网。

`push` 提交的路径限定为 `skills/` 与 `skillkit.json`，包含新增、修改和删除。使用按路径提交保留其他路径的暂存内容；没有受管理内容变化时不产生空提交。

首次对普通目录使用 `push --repo <source>` 会初始化 main 分支，配置 origin 并推送；已有 Git 工作区使用当前分支及 origin。显式 `--repo` 保存为 origin 的获取和发布目标，替换先前的多个 pushurl。来源支持 owner/repo、Git URL、本地 bare 仓库；远端必须已经存在。身份、认证、签名和 hooks 由现有 Git 配置提供，工具不管理凭据。

发布在仓库操作锁内读取提交范围并完成提交和推送。只接受个人 Git 工作区根目录；不接受 detached HEAD。推送使用当前分支的显式 refspec，正常拒绝非快进，不执行 pull、merge、force，不创建标签或远端仓库。失败返回 1 并保留本地提交供重试；`--dry-run` 不初始化 Git、不改配置或暂存区、不提交或联网推送。

分发源内的 `skillkit.json` 也参与解析。安装这类项时本地来源仍指向分发源，路径记录为 `skillkit.json`；更新重新解析分发声明及实际上游，版本记录为最终解析的内容版本。分发源将同名实体内容改为来源声明时，消费者会重新按名称解析。实体内容和同名来源记录冲突时拒绝；循环来源返回明确错误；来源元数据必须为普通文件。

## 全局目标

安装默认只写公共 `~/.agents/skills`，不创建其他 Agent 的配置目录。`--agent` 显式选择后只写所选目录，支持多个值；共享路径去重。指定的目标目录按需创建，不依赖 Agent 是否已经安装。未知 Agent 返回参数错误。

`update`、`list`、`uninstall` 省略 `--agent` 时处理所有受支持路径中的既有安装；指定时仅处理所选路径。更新不向未安装的目录新增分发，兼容旧版已经安装到专用目录的副本。公共目录供支持它的 Agent 读取，不保证覆盖所有 Agent；共享目录的选择不提供消费者隔离。

| Agent 参数 | skills 根目录 |
| --- | --- |
| `shared`、`codex`、`pi`、`zcode` | `~/.agents/skills` |
| `claude-code` | `${CLAUDE_CONFIG_DIR:-~/.claude}/skills` |
| `qwen-code` | `~/.qwen/skills` |
| `cursor` | `~/.cursor/skills` |
| `gemini-cli` | `~/.gemini/skills` |
| `hermes`、`hermes-agent` | `${HERMES_HOME:-~/.hermes}/skills` |
| `opencode` | `${XDG_CONFIG_HOME:-~/.config}/opencode/skills` |
| `antigravity` | `~/.gemini/antigravity/skills` |

默认复制；`--symlink` 链接本地原稿或固定版本的缓存快照。更新保留已有安装模式。每个目标的 `.skillkit-state.json` 记录仓库身份、模式、摘要、链接与版本，不认领未托管目录；不同个人仓库共享目标时保留彼此状态。被修改的复制安装拒绝覆盖和卸载，删除软链不跟随删除源。

## 输出与一致性

裸调用、`-h/--help` 显示帮助，根命令 `-V/--version` 显示 Maven 过滤后的版本。成功返回 0，参数错误返回 2，执行或逐项失败返回 1。结果写 stdout，普通失败写 stderr，JSON 批处理失败在逐项结果中返回。重定向、usage 错误和 presence-based `NO_COLOR` 不输出 ANSI。

`--dry-run` 不写声明、安装或缓存，也不拉取远程来源；只报告已知请求及冲突，不声称已验证未获取的远程内容。`list` 不获取来源或创建目录。

仓库锁保护个人声明，目标锁保护替换和状态写入。单项失败保留其余成功结果。正常发布失败会尝试恢复旧目标；多步目录替换不宣称崩溃原子性，强制终止后的自动恢复不在本轮范围内。
