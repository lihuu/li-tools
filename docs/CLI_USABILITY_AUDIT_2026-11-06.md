# li-tools 人类与 AI Agent 易用性审查

审查日期为 2026-11-06。验证基线为 `main` 的 `5726b79`（`0.4.0`），范围涵盖 certkit、dukit、gpgkit、skillkit、tokenkit，以及共用的命令行约定、技能文档和技能安装入口。下列整改均为建议。

## 总体判断

人需要从帮助和默认输出中知道下一步怎么做，并在删除、覆盖或更改系统信任前看清影响。Agent 需要靠退出码和可解析的结果判断能否继续、是否应重试，以及哪些状态已经改变。当前最影响两者的是成功信号不可靠、输出选项改变实际行为，以及文档示例和可执行命令脱节。

`dukit` 的双入口值得保留。人用 `browse` 看树和磁盘占用，Agent 用 `scan` 和有界的 `top` 取数据。`skillkit` 的批量结果也有可复用的设计。实测同批安装一项成功、一项失败时，命令返回 `1`，JSON 仍逐项给出 `installed` 和 `failed`，已安装项可由 `list --json` 查到。

## certkit

**人类体验**

- **重试会替换现有密钥。** 在同一临时目录重复运行 `certkit --json gen --self-signed --out certs <host>`，第二次成功并替换 `key.pem`。重复运行 `certkit --json ca --no-trust --out ca` 也成功替换了 CA 证书，指纹随之改变。固定文件名便于使用，同时使误用同一目录或直接重试带来覆盖风险。
- **签发输出混淆签发者与系统信任。** 实测以 `--no-trust` 创建临时 CA，再用它签发叶证书，输出仍将签发者标为 `Trusted Root CA`。
- **子命令帮助难以发现。** `certkit gen --help` 返回 `2`，并在 stderr 把 `--help` 报为未知选项。

**Agent 体验**

- 技能文档中的 `certkit gen --json localhost` 返回 `2`。把 `--json` 放在子命令之前可以得到 JSON。文档中的 `--out-dir`、`--key-type`、`convert --to` 和 `--password` 示例也被拒绝。实际生成参数为 `--out`、`--algo`，转换按目标文件扩展名选择格式，口令选项为 `--pass`。
- 成功生成时，JSON 提供证书主题、SAN、有效期和指纹等元数据，但没有文档承诺的 `cert`、`key` 或 `trusted` 字段。文本模式提供产物路径，Agent 模式需要自行推断。
- 源码显示 `ca` 仅在输出普通文本且非静默时尝试安装信任，`--json` 或 `--quiet` 会跳过这一步。普通文本模式下，信任安装返回失败结果后仍可退出 `0`。本次没有执行系统信任库写入。

**整改方向。** 将现有文件保护和输出格式分开处理。覆盖现有产物需要显式授权。清晰区分 CA 创建、叶证书签发和系统信任变更，在结果中分别报告产物路径和执行状态。

[技能文档](../certkit/SKILL.md) · [命令实现](../certkit/src/main/java/io/github/lihuu/certkit/Main.java) · [OpenSSL 调用](../certkit/src/main/java/io/github/lihuu/certkit/Openssl.java)

## dukit

**人类体验**

- 已有的 macOS Native 二进制能在伪终端打开全屏浏览、显示子目录并用 `q` 正常退出。人可以从浏览视图理解层级和空间占用，排查入口清晰。
- `scan --help` 的大小说明仍声称只提供逻辑大小。实测 JSON 同时返回 `size` 和 `diskSize`。帮助应解释两者及 `--apparent-size` 所影响的显示。

**Agent 体验**

- `scan --depth 0` 返回根节点和汇总大小，`top --files --limit 1 --json` 返回有界排行榜。无终端的 `browse` 返回 `2`，并提示改用 `scan`。这些路径在 JAR 和已有 macOS Native 二进制上均抽测通过。
- `scan --text --json` 实测返回文本和 `0`。两个输出选项冲突时，应返回用法错误，让调用方及时发现参数组合有问题。
- `--depth` 控制输出层数，汇总仍覆盖整个子树。部分路径无法读取时，现有 JSON 的 `meta.errors` 可用来判断结果完整性。Agent 示例应保留这两个条件，避免把浅层输出当成浅层遍历，或把部分结果当成完整统计。

[技能文档](../dukit/SKILL.md) · [扫描实现](../dukit/src/main/java/io/github/lihuu/dukit/cli/ScanCommand.java) · [浏览实现](../dukit/src/main/java/io/github/lihuu/dukit/cli/BrowseCommand.java) · [JSON 报告](../dukit/src/main/java/io/github/lihuu/dukit/core/report/JsonReporter.java)

## gpgkit

**人类体验**

- 裸调用把总帮助写到 stderr 并返回 `2`。`gpgkit verify --help` 返回总帮助，缺少子命令自己的参数解释。`gpgkit --version` 被当成未知命令。排查版本差异或在终端里临时查用法时，这些入口不够直接。
- README 对备份后如何分开放置密文和口令、如何恢复交代清楚。这类面向实际任务的说明值得保留。

**Agent 体验**

- 技能中的 `gpgkit gen "Name <email>"`、`gpgkit verify sample.tar sample.tar.asc`、`gpgkit export <email>` 和 `gpgkit raw -- ...` 实测均被拒绝。实际生成命令使用 `--uid`，验签使用单个文件位置参数和可选的 `--sig`，当前分发表中没有 `export` 或 `raw`。
- 技能写默认 Ed25519，实际默认 RSA 4096、两年有效期且无口令。自动化应依据实际保护方式判断是否继续，文档需要明确列出默认值和口令选项。
- 技能对退出码的分类也与入口不符。实际 `1` 表示执行失败，`2` 表示命令行用法错误。更新技能示例后，应直接对打包产物执行生成参数校验和验签示例。

[技能文档](../gpgkit/SKILL.md) · [命令入口](../gpgkit/src/main/java/io/github/lihuu/gpgkit/Main.java) · [默认值与备份说明](../gpgkit/README.md)

## skillkit

**人类体验**

- 网站技能称 `install` 会同时分发到所有支持的 Agent，实际默认只写共享目录 `~/.agents/skills`。使用独立目录的 Agent 可能找不到刚装好的技能。模块 README 已说明用 `--agent` 选择目标，应同步发布入口里的说明。

**Agent 体验**

- `install --dry-run --json` 对两种本地可判断的情况都返回 `planned` 和 `0`。一是个人仓库里没有请求的技能，二是目标目录已存在非托管的同名副本。随后实际执行分别返回 `Skill not found` 和 `Unmanaged target already exists`，退出码均为 `1`。
- 保留 dry-run 的无写入、无网络语义。把本地可查明的冲突、计划执行的动作、需要联网后才能确认的状态分别输出。实际批量执行已有逐项结果，调用方可以根据返回的目标和状态决定只重试哪些项。

[网站技能](../site/skillkit/SKILL.md) · [分发约定](../skillkit/README.md) · [预览及冲突检查](../skillkit/src/main/java/io/github/lihuu/skillkit/core/SkillManager.java) · [批量结果](../skillkit/src/main/java/io/github/lihuu/skillkit/cli/InstallationCommands.java)

## tokenkit

**人类体验**

- `rm --yes` 的帮助说明它用于跳过确认。伪终端实测，在未指定该选项时 `rm` 仍直接删除了合成测试项。当前标志在实现中没有参与执行决策。
- `get --clip` 的帮助承诺复制到剪贴板。源码显示剪贴板失败时，会向 stdout 写出明文并返回 `0`。在有终端记录或 Agent 日志的环境里，失败路径可能暴露本来只想复制的值。本次未执行真实剪贴板写入。
- 技能推荐用 stdin 存储多行 PEM 等内容。合成多行输入的往返抽测显示，前导空行被丢弃，CRLF 被归一化。若该入口用于保真传输，需定义并验证换行行为。

**Agent 体验**

- `tokenkit list --json` 有结果时输出数组。空库或筛选无匹配项时，实测输出 `No tokens found.` 并返回 `0`，破坏了成功路径的 JSON 契约。空结果应返回 `[]`。
- `tokenkit --json list` 返回 `2`，当前 `--json` 是 `list` 的局部选项。项目文档对全局 JSON 的承诺需要收敛到实际支持的命令。
- `rm FIXTURE MISSING` 实测删除了存在项并返回 `0`，缺失项需要查看 stderr。应说明批量返回码的聚合规则，并给可解析的逐项结果。
- `run -- <cmd>` 用合成值验证了子进程注入，测试子进程仅检查变量值并通过退出码回报。这个入口适合 Agent 使用凭据而不把值读进会话。技能示例应优先展示这种用法，将明文 `get`、`env` 及显式传值路径的输出边界讲清楚。

[技能文档](../site/tokenkit/SKILL.md) · [删除实现](../tokenkit/src/main/java/io/github/lihuu/tokenkit/cli/RmCommand.java) · [剪贴板分支](../tokenkit/src/main/java/io/github/lihuu/tokenkit/cli/GetCommand.java) · [列表实现](../tokenkit/src/main/java/io/github/lihuu/tokenkit/cli/ListCommand.java) · [stdin 处理](../tokenkit/src/main/java/io/github/lihuu/tokenkit/cli/SetCommand.java)

## 共用入口与发布文档

- **技能安装会误报成功。** 以本地脚本和仓库里的技能文件运行 `install-skills.sh --target "<带空格的临时目录>"`，指定目录里没有技能，但脚本返回 `0` 并显示全部安装成功。用替身 `curl` 模拟五项下载全部失败时，脚本同样返回 `0` 并显示全部成功。修正路径处理，按项汇总结果，只在全部成功时显示成功提示。 [安装脚本](../site/install-skills.sh)
- **套件级承诺超出了实际行为。** 根 README 和网站的 Agent 索引宣传统一全局参数及 JSON。实际存在位置约束、命令级支持差异和无效选项。根技能文档也只列出三个工具，网站版覆盖五个。给两个入口提供同一份工具索引和受支持的命令约定。 [中文 README](../README_zh.md) · [根技能](../SKILL.md) · [网站技能](../site/SKILL.md) · [网站 Agent 索引](../site/llms.txt)
- **现有 smoke 尚未约束公开示例。** Native smoke 已检查帮助、错误选项和若干核心路径，但明确跳过 `gpgkit --version`，也未运行本次发现有问题的技能示例和安装失败场景。发布验证应从人和 Agent 实际复制的示例中选取最常见的路径。 [Native smoke](../scripts/smoke-native.py)

## 整改顺序与验收

| 顺序 | 整改 | 验收条件 |
| --- | --- | --- |
| 1 | 处理泄露、覆盖和意外删除 | 剪贴板失败返回非零且 stdout 为空。无显式确认的删除遵循帮助描述。重复生成保留原有产物，除非明确授权替换。证书创建和信任安装分别报告结果，输出模式不改变操作。 |
| 2 | 让自动化可判断结果 | 支持 JSON 的命令在成功时总能按文档解析，包括空结果。冲突参数给出用法错误。批量命令和 dry-run 交代每项状态及可验证范围。明确 `run`、`env` 和原生透传的输出及退出码约定。 |
| 3 | 让新用户能按文档跑通 | README 与发布版 `SKILL.md` 的核心示例可直接执行。帮助能定位子命令，版本可查询。安装脚本保留含空格的目标路径，下载失败时报告失败并返回非零。发布验证检查退出码、stdout 格式和预期副作用。 |

## 验证范围

- 五个模块的 JAR 在 macOS 上打包成功，使用重定向 stdin、stdout、stderr 的方式抽测命令。写操作使用临时目录、合成值、隔离的 Vault、CA 路径、GPG 目录及技能安装目录。打包使用 `mvn -o -B -ntp -Daether.syncContext.named.factory=noop package -DskipTests`。
- 伪终端验证了 tokenkit 的删除交互和已有 macOS dukit Native 二进制的浏览、渲染及退出。dukit Native 还抽测了 JSON 扫描、排行榜和无终端提示。
- CA 的信任安装分支及剪贴板失败后输出明文的结论来自源码。系统信任库写入、真实剪贴板、真实远端、Windows 和 Linux 未实测。本次没有运行完整 Maven 测试套件，也没有重新构建完整 Native 矩阵。
