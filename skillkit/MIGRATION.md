# lihuu-skills.sh → skillkit：行为梳理与并行迁移

## 范围与证据

本次交付是 Maven 子模块骨架与脚本分析，**没有实现下述安装、更新或并行执行功能**。本文的 Java 设计是建议默认值，后续可以调整。

2026-10-22 通过现有 `gh` 认证只读获取私有仓库，分析固定在提交 [`ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c`](https://github.com/lihuu/lihuu-skills/tree/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c)：

- [scripts/lihuu-skills.sh](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/scripts/lihuu-skills.sh)：637 行，本文主要分析对象。
- [scripts/install-all.sh](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/scripts/install-all.sh)：批量安装、agent 探测、Codex 补链、ZCode 去重、hook 注册。
- [scripts/install-third-party.sh](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/scripts/install-third-party.sh)：按声明式清单逐 agent、逐仓库安装。
- [scripts/third-party-skills.json](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/scripts/third-party-skills.json)：第三方声明式清单。
- [README.md](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/README.md)：按需安装与作用域说明。

未执行这些外部脚本，未扫描本机 agent 安装目录，未读取本机 `.skill-lock.json`，未验证 npm `skills` CLI 的内部行为。下文明确区分脚本自己的行为与委托能力。

## 主脚本实际做了什么

它是 npm `skills` CLI 的包装器；复制分发、agent 兼容、已安装枚举和复制类更新主要依赖该 CLI，并非脚本自带的完整 skill 管理引擎。

| 能力 / 入口 | 脚本行为 | Java 迁移要点 |
| --- | --- | --- |
| 模式识别，约 46–62 行 | 脚本父目录同时存在 `skills/` 和 `.git/` 则为 dev；否则 prod 使用 `~/.lihuu-skills` | 原生二进制不再依赖脚本位置。提供显式 `--source-dir`；生产源目录沿用旧位置作为兼容候选。Git worktree 的 `.git` 可以是文件，不能只测目录 |
| `resolve_skills_cmd`，106 行 | 找 `skills` → 尝试 `npm install -g skills@latest` 和 mise reshim → `npx skills@latest` | 最终 Java 自己管理 skill，去掉 npm/npx/Python 依赖。过渡包装只能临时使用，不能把多个共享 lockfile 的 CLI 进程直接并发 |
| `install-self`，294 行 | clone 默认源仓库到 `~/.lihuu-skills`，复制脚本到 `~/.local/bin/lihuu-skills` | CLI 自身安装交给 li-tools 分发流程；源仓库初始化由源管理逻辑承担，不再复制脚本 |
| `add <skill>`，341 行 | 默认 `skills add lihuu/lihuu-skills --skill <name> -g -a '*' -y`；单个 skill | Java 支持多个 skill 与显式批量选择；保留默认来源、scope、agent 选择语义 |
| `add --repo` | 切换第三方仓库，安装后登记 JSON 清单，登记为 best effort | 安装结果确认后才登记；身份至少包含来源、skill 路径、scope、实际目标，避免同名误认 |
| `add --symlink/--link` | 本地最多三层搜索目录并验证 `SKILL.md`；删除目标后直接 `ln -s`；第三方源不支持 | 用 NIO 创建软链，重名必须消歧；受管理旧目标才能替换；Windows 能力不足时报错，不静默改变安装模式 |
| `update`，446 行 | prod 先 `git pull --ff-only` 默认源；随后 `skills update -g -y`；支持 `-a` / `--project` | 来源按仓库去重同步，复制安装只更新已登记项；软链跟随源变化，不能把软链误转换成复制安装 |
| `remove <skill>`，489 行 | 只先查全局 `~/.agents/skills/<name>` 软链；否则委托 `skills remove ... -g -y`；全局 lock 无记录时注销第三方清单 | 按实际 scope 和 agent 解析目标；只删选中安装，全部引用消失后再注销声明 |
| `list`，558 行 | 默认委托 `skills list -g`，有参数时原样传递 | Java 枚举受支持根目录，区分托管、未托管、复制、软链、断链、来源未知 |
| `list-available`，579 行 | 委托 `skills add <repo> --list` | 扫描来源快照中的 `SKILL.md`；同名冲突要报告候选路径 |
| `--list` / `--dry-run`，601 行 | 顶层 dry-run，只打印命令；`list-available` 的 `--list` 则是透传给下游的枚举标志 | 建议正式名称为 `--dry-run`，解析为执行计划；不建目录、不 clone/pull、不登记、不隐式安装工具 |

第三方清单形状为 `repos["owner/repo"] = {description, skills: [name, ...]}`。它表达“希望装什么”，不包含某次实际安装的 agent、scope、模式和版本，不能直接代替安装状态。

脚本自己的软链路径计算：全局 `*` → `~/.agents/skills/<skill>`，全局具体 agent → `~/.<agent>/skills/<skill>`；项目级同理，以项目根目录为 base。**该软链分支只创建一个链接，不会遍历所有 agent 补链**。复制分发则完全由 npm CLI 决定，不能把脚本注释当作已经验证过的跨 agent 路径契约。

## 关联脚本带来的额外范围

`install-all.sh` 定义了 Claude Code、Codex、Qwen Code、ZCode、Cursor、Gemini CLI、Hermes 的探测规则。它也有自动探测调用，但当前未指定 `-a` 的安装分支仍传给下游 `-a '*'`；不能据注释声称已经严格限制到探测结果。

它有三类主脚本没有的行为：安装多来源、多 skill；Codex 的 superpowers 补链与 ZCode 重复软链清理；注册 superpowers SessionStart hook。`install-third-party.sh` 则默认只遍历配置数组里的 Claude Code 和 Codex，从 JSON 取来源逐个安装。两套批量入口的来源列表、agent 范围并不统一。

Java 建议将“来源清单”和“本次安装目标”统一为一份计划；hook 属于另一类用户配置修改，首轮文件安装不自动运行 hook 注册脚本。`doctor` 是另一个独立子系统，本次未审读其实现，不作为已覆盖能力。是否纳入后续里程碑应单独确定。

## 已看到的问题，不应照搬

以下是静态阅读结果，未作为真实安装故障复现：

1. `run_skills` 的管道末尾 `|| true`、`run_skills_raw` 的 `|| true` 和入口 `exit 0` 会掩盖失败。第三方安装失败后仍可能登记成“已安装”；输出筛选还会丢失诊断。Java 必须保留退出码和完整任务诊断。
2. JSON 清单使用无锁的 read-modify-write。直接后台并发 `add` 会丢登记；共享 `.skill-lock.json` 的下游并发写入安全性也未经验证。
3. `update` 串行先同步默认来源、再启动一个整体更新，没有任务拆分、并行上限或逐项结果。不能简单同时运行多个 `skills update`。
4. 软链安装先 `rm -rf` 旧目标，后续失败可能损失旧版本；卸载软链只识别全局默认根，且忽略指定 agent 和项目 scope。
5. `find ... | head -1` 将重名目录静默归为第一个候选，不能可靠表示来源身份。
6. `list` 把 `-g|--global` 与 `-a|--agent` 一起当成带值选项，`list -g` 的 `$2` 在 `set -u` 下可能报错；常见缺值参数也缺少统一校验。
7. JSON 注销按 skill 名遍历所有仓库、全局 lock 检查未表达项目/agent 引用关系，容易误删其他范围的声明。
8. 来源 git pull 失败只告警后继续，调用方无法区分旧来源、更新失败和完整成功；并行版本应对相关来源的任务逐项标记失败，不用旧快照宣称更新成功。

## Java 实现方向

建议直接用 Java 实现管理逻辑，外部 Git 作为唯一来源同步工具，通过参数列表调用 `ProcessBuilder`，复用现有认证，不取出或写出 token。暂不引入 JGit、并发框架或 DI。

业务逻辑按实际出现的职责建立在 `core`：发现来源中的 skill、解析 agent 目标、生成安装计划、同步来源、提交文件与记录状态。`app` 显式构造这些对象，`cli` 只处理参数、输出与退出码。简单数据用 records；需要行为时再新增类，不提前建设 registry/factory/interface 层。

Agent ID 与目录不是简单拼接关系。后续以明确的有限映射实现受支持 agent，允许显式覆盖根目录；不能把“本地所有 agent”解释为扫描用户整个 home。新增 agent 逐个核对其真实发现规则，包括 universal root、agent 专有目录和配置覆盖；本次没有读取这些本机目录。脚本出现的七种 agent 是首轮候选，而非本次已验证的支持清单。

需要 JSON 清单与状态读取时，按项目约定再引入 Jackson，并在 Native 上验证窄范围资源/反射元数据。当前骨架没有 JSON 读写需求，因此没有提前添加 Jackson；共享 `JsonWriter` 可用于后续 CLI 结果输出，不能用于 JSON 解析。

### 拟议 CLI（尚未实现）

```sh
skillkit add skill-a skill-b --jobs 4
skillkit add skill-a --repo owner/repo -a codex,claude-code
skillkit add skill-a --symlink --source-dir /path/to/lihuu-skills
skillkit add skill-a --project --dry-run
skillkit update --jobs 4
skillkit update skill-a -a codex
skillkit remove skill-a --project -a codex
skillkit list
skillkit list-available --repo owner/repo
```

建议保留 `add`，提供 `install` 别名；默认全局、默认来源 `lihuu/lihuu-skills`；默认选择受支持且探测到的 agent，显式 `-a` 可精确限定。默认 `--jobs 4`，必须为正整数，`--jobs 1` 是串行路径。批量全部安装需要显式选择，不因仓库可用就自动安装所有 skill。这些均为设计建议，不是新增的当前 CLI 契约。

### 并行边界与执行阶段

```text
解析 scope / agent / 来源 / skill
        ↓
只读生成计划；验证重名与目标冲突；dry-run 到此结束
        ↓
同步来源：不同仓库限流并行，同一仓库只同步一次
        ↓
固定来源版本，扫描 SKILL.md，展开并去重实际安装目标
        ↓
准备内容：不同 skill 限流并行，写各自临时目录
        ↓
发布目标：同一路径串行；不同目标可并行
        ↓
协调者统一提交状态、第三方声明与汇总结果
```

远程来源的 dry-run 只能报告待解析的来源、请求的 skill/agent 和已知冲突，明确标记未获取的信息；不为填满计划而偷偷联网。正式执行在同步来源后再次校验完整计划，再开始任何目标写入。

1. **来源并行**：按规范化仓库身份去重；同一个来源只 clone/fetch 一次。兼容生产源的 `pull --ff-only` 必须持有该仓库锁；有脏改动则失败并保留现场。显式开发来源不自动 pull。一次复制更新固定来源提交，避免不同 skill 读到不同版本；显式本地开发源复制需建立选定目录的内容快照，软链则按约定继续指向工作目录。
2. **skill 并行**：使用 Java 25 虚拟线程承担 I/O 等待，通过有界调度限制同时执行的工作任务；虚拟线程本身不等于限流。若用 semaphore，必须在任务内部获取许可并在 `finally` 释放，不能让协调者持有未回收的许可。网络和磁盘共用一个用户可理解的 `--jobs` 上限即可，先不增加多组调优参数。
3. **目标去重**：多个 agent 可能读同一个 universal root；按实际目标路径去重，同一实体只发布一次。`repo + skill` 是来源身份，目标路径是写入冲突键；不同来源同名落到同一目标时明确拒绝或要求选择，不能让完成顺序决定胜者。
4. **安全替换**：新内容在目标同级临时目录准备并校验。更新受管理安装时先保留旧目录，再发布新目录，失败尝试恢复并报告恢复结果；目录多步替换不宣称事务原子性。软链只替换链接本身，不跟随删除源目录；未托管同名目录默认报冲突。跨文件系统或原子移动不支持时，要有明确失败/降级契约。
5. **跨进程操作与状态单写**：第三方声明与安装状态由一个协调者合并。首版按状态根持有一个跨进程操作锁，从重新读取状态、重新校验目标一直覆盖到目标发布/恢复、对应状态与声明提交完成；多个 CLI 进程串行，单进程内仍按 `--jobs` 并行。不能只在最终写 JSON 时加锁，否则目标发布顺序可能与状态提交顺序相反，恢复还可能覆盖另一进程的成功结果。不同状态根若允许指向同一实际目标，还需按规范化实际目标路径获取共享跨进程目标锁；多个锁按固定路径顺序获取。状态读取、合并到临时文件和原子替换均在锁内。安装成功而状态提交失败应返回失败，并保留可恢复的操作记录；不能将落盘副作用抹去后报告纯成功。
6. **失败隔离**：一个来源失败，只阻断依赖它的任务，其他来源继续；一个 skill 失败，其他无冲突 skill 继续。输出逐项状态及成功/跳过/失败总数，有任一失败退出 1；参数或预检冲突退出 2；不依赖日志关键字猜成功。
7. **中断与资源所有权**：协调者停止接收新任务，通知工作任务并中止所拥有的 Git 子进程及后代，等待退出和临时目录清理确认，最后汇总。不能把 `Future.cancel(true)` 当成文件写入或子进程已经停止。外部进程需超时、回收并持续消费输出以免阻塞。

更新的核心不是“遍历所有来源装一遍”，而是从已记录的安装项生成计划，再按来源批量检查和同步。软链更新只涉及其生产来源同步和链接有效性检查；相同来源的多个软链无需重复 pull。未安装的 skill 不被 `update` 顺带装入。

### 兼容与迁移状态

建议把第三方 JSON 保留为声明式输入，并保留未知字段。独立的 skillkit 安装状态记录来源、skill 相对路径、选定 agent、scope、目标、模式、提交或内容摘要。只有实际成功的目标进入已安装状态，不能先写成功再去执行。

旧 `~/.agents/.skill-lock.json` 需要先用脱敏样例核对版本与语义，再设计只读导入。当前没有证据证明它能完整表达全部 agent/project 安装，不能直接依赖或覆盖它；也不应让 Java 与 npm CLI 并发维护同一份状态。未知来源的现有目录按未托管展示，认领/迁移必须显式进行。仓库清单中的卸载登记需要等所有受管理目标引用都消失，而不是按名称跨仓库删除。

## 分阶段落地及验证

| 阶段 | 实现内容 | 验证重点 |
| --- | --- | --- |
| 0，本次 | Maven 模块、CLI 壳、资源、JPMS、JAR、独立 Native 构建 | 帮助/版本/公共选项/usage 退出码；JAR 与 Native smoke |
| 1 | 本地来源发现、明确的 agent 目录映射、只读 list/list-available/计划 | 临时目录、重名、scope、共享根去重；不读取开发机私有状态 |
| 2 | 本地 copy/symlink 安装和卸载、受管理安装状态 | 保留未托管文件、失败恢复、scope 精确卸载、JSON round-trip |
| 3 | Git 来源与基于安装状态的更新 | 本地临时 Git 仓库测试，同源只同步一次，固定版本，只更新已安装项 |
| 4 | `--jobs` 并行安装/更新、汇总、文件锁、中断 | 受控边界阻塞证明两个任务同时进入；验证上限、共享目标/状态不丢写、部分失败保留成功结果 |
| 5 | 旧状态导入、第三方声明兼容、分发集成 | 脱敏旧清单 fixture；真实目标 agent 人工验收；三平台 Native；再接入根批量发布、包管理器与站点 |

阶段 3 的任务边界按阶段 4 的调度需求设计，避免事后把“整体 update”硬拆成多个不安全进程。并行测试采用 latch/barrier 与有界超时，不靠总耗时阈值猜并行；`--jobs 1` 与并行路径应产生相同最终安装状态。

首轮业务闭环建议优先跑通“明确目标 + 本地来源 + 并行 copy/symlink”，再加远程来源和现有安装的更新。公共帮助壳当前不会提前暴露没有实现的业务命令。
