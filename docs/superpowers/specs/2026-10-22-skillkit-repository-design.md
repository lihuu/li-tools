# skillkit：个人 skills 仓库与安装管理（当前需求）

状态：以用户最新简化要求为准。公开命令只有 install、update、uninstall、list、push；install 统一安装自己的或别人的 skill，默认全局分发到所有内置 Agent 目标。无需 import、track、untrack、初始化或 Agent 选择步骤。存储保持本地内容与上游元数据两种方式。

## 用户已明确的目标

- 用 Java 实现 `skills` 工具的能力，运行时不再依赖外部 `skills`、npm 或 npx 命令。
- 支持并行安装和更新。
- 工具管理用户自己的 skills 集合，集合可以位于一个本地目录，也可以是一个 Git 仓库。
- 集合中实际保存自己编写的 skills，以及从远程获取的第三方 skills；这不是只保存来源引用的目录。
- 第三方 skill 可配置是否关联上游，以及完全跟随上游还是与个人仓库中的内容合并。
- 自己的仓库可以作为 skills 来源，和其他上游仓库一样参与安装和管理。
- 原来的 `lihuu/lihuu-skills` 既是内容仓库，也是安装管理脚本载体；Java 模块需要覆盖这两方面需求，不能只替代安装包装器。

## 三种对象与数据流

1. **上游来源**：第三方 Git 仓库、本地目录，以及提供发现能力的索引。索引定位内容，内容来源另行记录，不能把 npm `skills` 命令本身当成内容仓库。
2. **个人 skills 仓库**：用户可编辑、收藏、组织和版本管理的内容集合，不是可以随时清空的下载缓存。Git 仓库与普通目录均可直接使用，Git 功能只适用于前者。
3. **agent 安装目标**：全局被 agent 实际发现的目录，是个人仓库或上游某个版本的分发结果。

主路径为“上游来源 → 个人仓库 → agent 安装目录”；自己写的 skill 从个人仓库直接进入安装路径。直接从第三方来源安装也应保留，但不等同于已收藏进个人仓库。

下载缓存、个人仓库和安装目录各自具有不同的生命周期。卸载 agent 中的 skill 不应删除个人仓库中的原稿；清理缓存不应损失收藏或自有内容。

## 内容与来源关系

个人仓库至少需要区分自有 skill 和第三方 skill。第三方项应记录来源仓库、来源内路径、选定 ref 和上次同步的实际版本；本地非 Git 来源使用内容摘要等身份信息。

需要区分“希望安装哪些内容”的声明与“本机实际安装到哪里”的状态：

- 仓库中可共享的信息：skills 内容、来源关系、固定版本，以及可复现安装所需的声明。
- 本机状态：展开后的绝对路径、实际 agent 目标、安装模式和安装成功版本。不能把开发机绝对路径作为跨机器的通用声明。
- 临时状态：下载缓存、锁和暂存目录，不作为个人内容提交。

个人仓库有两种存储方式：

| 方式 | 仓库保存内容 | 更新规则 |
| --- | --- | --- |
| 本地内容 | `skills/` 中的完整 skill 目录：自己编写或从第三方复制收录 | 用户直接编辑或再次复制；不自动联系原上游、不做三方合并 |
| 上游订阅 | `skillkit.json` 记录名称、来源、来源内路径、ref 与已解析版本 | 安装/更新时获取上游内容，完全跟随配置的上游；下载内容只在本机缓存及安装目标中 |

用户说的“合并目录”是复制收录，不是 Git merge。用户自行复制的目录按本地内容管理；install --repo 自动记录上游元数据。两种方式不可对同一个 skill 名重复声明，不能自动覆盖本地原稿。

普通目录与 Git 仓库均可作为个人集合；Git 只是集合本身的版本管理方式。上游可以是 Git URL、GitHub owner/repo 或本地目录。不为每个 skill 建 fork，不建设自动冲突合并引擎。

## 更新语义

本地 skill 的安装更新取个人目录当前内容，上游订阅的安装更新获取其上游新版本。更新仅处理已选择/已安装项，未安装内容不会自动分发。复制收录与自己的 skill 使用同一更新路径。

用户新增确认：push 自动提交 skills/ 与 skillkit.json，并推送个人远端；其他 Git 历史管理由用户显式操作。库中提交可移植的上游元数据，本机缓存、锁和安装状态不作为个人内容提交。

## 能力范围

最终公开 CLI 收敛为：

- `install <names...>` 安装个人目录内容；`--repo <source>` 安装外部来源并自动保存元数据。
- `update [names...]` 并行更新已有安装。
- `uninstall <names...>` 全局卸载，保留原稿和来源记录。
- `list` 显示安装状态。
- `push` 自动提交个人内容和来源记录并发布远端，供其他机器作为私有源安装。

所有操作面向全部内置全局目录，不增加 Agent、项目作用域选择。目录创建、复制和 Git 版本管理继续使用常规文件/Git 操作。保留 copy/symlink、批量并行、来源版本记录与受管理目标保护；不扩展在线市场、npm 同步或启动其他 Agent。

## 并行与数据一致性约束

沿用并补充 [迁移分析](../../../skillkit/MIGRATION.md) 的并行边界：

- 不同来源限流并行获取，同一来源在一次操作中只获取一次。
- 来源内容确定之后，不同 skill 的准备和安装可并行。
- 写入个人仓库也需要冲突键与跨进程操作锁，不能只保护 agent 安装目录。
- 上游版本记录只有在获取/安装成功后更新；本地内容不会被订阅更新覆盖。
- 不同 agent 共用同一发现根时只写一次；同一路径不能由不同任务竞争覆盖。
- 状态、声明和引用关系统一合并写入，任何任务失败均需准确汇总。
- 保留原有内容与未托管文件；中断须等待实际任务和子进程停止，不能把取消 Future 当成清理已完成。

Git 操作仍复用现有认证，不取出、复制或写出凭据。真实操作用户的内容仓库、上游或 agent 目录之前，先通过临时目录和临时 Git 仓库的可重复测试。

## 现有仓库结构的核对证据

只读检查固定提交 `ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c`，没有执行外部脚本，也没有访问它们提到的本机私有目录：

- [skills/](https://github.com/lihuu/lihuu-skills/tree/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/skills) 中有 138 份 `SKILL.md`，均为普通 Git 文件，分布在直接子目录与分类目录中。它们是内容集合，不能仅凭目录名断言每一项的作者或上游。
- [baoyu-article-illustrator/PORT_NOTES.md](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/skills/creative/baoyu-article-illustrator/PORT_NOTES.md) 明确记录从 `JimLiu/baoyu-skills` v1.57.0 收录：部分 styles/palettes 原样保留，`SKILL.md` 等文件做 Hermes 适配，更新时部分可覆盖、部分须合并。它证明个人仓库已经包含第三方实际内容及自定义修改。
- [upstream-sync/SKILL.md](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/skills/software-development/upstream-sync/SKILL.md) 描述 `obra/superpowers → lihuu/superpowers fork → lihuu/lihuu-skills/skills/superpowers → agent`，现有流程使用选择性 cherry-pick 保留定制，再复制到个人分发仓库。工具应能表达这个来源链，无需强制为全部 skill 引入额外 fork。
- [scripts/third-party-skills.json](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/scripts/third-party-skills.json) 记录 5 个来源、18 个 skill 名。这 18 个名字未对应到该提交 `skills/` 中同名 `SKILL.md`，关联安装脚本直接从外部仓库装到 agent。它们是现有声明式外部安装路径，迁移时应提供将内容收录到个人仓库的能力，不能误称已被内容仓库收录。
- [install-skill/SKILL.md](https://github.com/lihuu/lihuu-skills/blob/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c/skills/skill-management/install-skill/SKILL.md) 将本仓库安装、第三方直接安装和源目录开发区分开；新工具继续保留安装职责，同时把内容收录和上游维护纳入同一套管理模型。

## 最终澄清

用户最后明确：所谓“合并上游”是把目录复制进统一 skills 目录，按本地内容维护；完全跟随上游则只需在仓库保存 metadata。实现以两种存储方式为准，早期关于自动三方合并、上游基线合并和冲突工作流的建议已撤回。

最后一次 CLI 澄清：统一用 install，来源元数据由安装自动维护；早期设计中的 import/track/untrack、搜索/创建等独立子命令及项目作用域已移除。
