# 从 lihuu-skills.sh 迁移到 skillkit

## 最终模型

用户最后确认的模型以两种存储方式为准，取代早期自动三方合并建议：

1. 自己写的或复制第三方目录得到的内容，放在个人 `skills/` 下，作为普通本地文件维护。
2. 完全跟随上游的 skill，仅在个人仓库 `skillkit.json` 登记 metadata；安装、更新时从来源取内容；push 将内容和声明发布到个人远端。

因此 Java 实现不包含自动 merge 引擎；“把目录合并进我的仓库”就是复制收录。详见[需求与现有结构分析](../docs/superpowers/specs/2026-10-22-skillkit-repository-design.md)。

## 原脚本证据

原仓库分析固定在 [`ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c`](https://github.com/lihuu/lihuu-skills/tree/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c)。只读检查了 `scripts/lihuu-skills.sh`（637 行）、`install-all.sh`、`install-third-party.sh`、第三方 JSON 清单、README、上游同步说明与 Baoyu 移植说明，没有执行外部脚本或修改实际 agent 内容。

原 `skills/` 有 138 份 SKILL.md，既有直接保存的内容，也有第三方改造内容；第三方清单另有 5 个来源和 18 个 skill 名，对应内容未收入该提交的 skills/。Superpowers 流程为“原上游 → 自己 fork → 个人分发仓库 → agent”。这些结构说明个人内容、可选上游和安装结果必须分别管理。

## 行为对照

| 原行为 | Java 实现 |
| --- | --- |
| 自动寻找/安装 npm skills，失败后 npx 降级 | 已移除；Java 直接管理，远程只用系统 Git |
| 默认硬编码 lihuu/lihuu-skills 来源 | 用户指定个人 repository，自动发现本地内容和声明 |
| 原脚本无独立内容模型 | 个人 skills/ 直接维护原稿；外部 install 自动保存上游元数据 |
| `install-self` 复制脚本并 clone 默认来源 | 独立 Native/JAR 分发，直接使用已有个人目录 |
| `add` 单个 skill 委托 npm | 统一 `install` 支持多个 skill、copy/symlink、有界并行，默认安装到公共目录，可选多个 Agent |
| `update` 先 git pull 默认源，再整体调用 skills update | 按已有安装生成任务，本地取当前内容，订阅取新来源，同源/ref 只解析一次 |
| `remove` 只先查默认全局软链 | 统一 `uninstall` 从全部内置全局目标卸载，保留原稿与来源记录 |
| `list` / `list-available` 委托 npm | `list` 读取当前个人仓库的全局安装状态 |
| 用 Python 改第三方 JSON，无锁 read-modify-write | Jackson streaming 读取本工具版本化 JSON，仓库锁和目标锁保护提交 |
| 管道末尾 `\|\| true` 吞掉失败退出码 | 每项记录状态，任一失败返回 1，保留成功结果 |
| 软链替换前 `rm -rf` 目标 | 仅允许受管理目标；暂存、备份、状态提交，失败尝试恢复 |
| 手工后台并发可能覆盖共享 lockfile | 有界虚拟线程准备内容；目标锁内串行发布和状态写入 |

原脚本还存在静态可见问题：同名目录取第一个；remove 的 scope/agent 分支不完整；list 把 -g 当作带值参数；按 skill 名跨仓库注销声明；源拉取失败只告警。Java 的名字校验、重名拒绝、共享目标去重、来源身份和逐项错误避免照搬这些行为。

## 当前落地与边界

CLI 按用户最后反馈收敛为六个命令：`install`、`update`、`uninstall`、`list`、`push`、`pull`。自己的内容直接放在个人 skills/；别人的 skill 通过 `install <name> --repo <source>` 安装并自动保存来源。`pull` 首次克隆个人仓库并负责后续快进拉取。默认安装到公共 ~/.agents/skills，`--agent` 可选多个目标。没有 import、track、untrack 等额外管理命令，也不提供项目作用域选择。CLI 细节以 [SPEC.md](SPEC.md) 为准。

已有分类内容无需逐项登记即可发现。原 third-party-skills.json 和 npm lockfile 不会被自动重写；外部项用 install 安装。现有 Agent 安装仍视为未托管，避免覆盖。保留个人改造内容时，将目录复制到个人 skills/ 后按自己的 skill 安装。

本轮覆盖目录/Git 来源、来源元数据、所有内置全局目标、并行安装更新和卸载，不实现在线市场、归档下载、npm 依赖同步或启动其他 Agent。
