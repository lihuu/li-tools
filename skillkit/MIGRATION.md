# 从 lihuu-skills.sh 迁移到 skillkit

## 最终模型

用户最后确认的模型以两种存储方式为准，取代早期自动三方合并建议：

1. 自己写的或复制第三方目录得到的内容，放在个人 `skills/` 下，作为普通本地文件维护。
2. 完全跟随上游的 skill，仅在个人仓库 `skillkit.json` 登记 metadata；安装、更新时从来源取内容。

因此 Java 实现不包含自动 merge 引擎；“把目录合并进我的仓库”就是复制收录。详见[需求与现有结构分析](../docs/superpowers/specs/2026-10-22-skillkit-repository-design.md)。

## 原脚本证据

原仓库分析固定在 [`ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c`](https://github.com/lihuu/lihuu-skills/tree/ecc6f86c1b7bd59d284decdf49dc0fdcc3de5d5c)。只读检查了 `scripts/lihuu-skills.sh`（637 行）、`install-all.sh`、`install-third-party.sh`、第三方 JSON 清单、README、上游同步说明与 Baoyu 移植说明，没有执行外部脚本或修改实际 agent 内容。

原 `skills/` 有 138 份 SKILL.md，既有直接保存的内容，也有第三方改造内容；第三方清单另有 5 个来源和 18 个 skill 名，对应内容未收入该提交的 skills/。Superpowers 流程为“原上游 → 自己 fork → 个人分发仓库 → agent”。这些结构说明个人内容、可选上游和安装结果必须分别管理。

## 行为对照

| 原行为 | Java 实现 |
| --- | --- |
| 自动寻找/安装 npm skills，失败后 npx 降级 | 已移除；Java 直接管理，远程只用系统 Git |
| 默认硬编码 lihuu/lihuu-skills 来源 | 用户指定个人 repository，自动发现本地内容和声明 |
| 原脚本无独立内容收录模型 | `import` 复制完整目录成为本地内容；`track` 仅保存上游声明 |
| `install-self` 复制脚本并 clone 默认来源 | 独立 Native/JAR 分发；`init` 管理用户自己的目录，不复制脚本 |
| `add` 单个 skill 委托 npm | `add/install` 支持多个 skill、目标、copy/symlink、有界并行 |
| `update` 先 git pull 默认源，再整体调用 skills update | 按已有安装生成任务，本地取当前内容，订阅取新来源，同源/ref 只解析一次 |
| `remove` 只先查默认全局软链 | 按指定 agent/scope/目标操作托管安装，保留原稿和订阅 |
| `list` / `list-available` 委托 npm | Java 读取安装状态、个人 catalog 或来源目录 |
| 用 Python 改第三方 JSON，无锁 read-modify-write | Jackson streaming 读取本工具版本化 JSON，仓库锁和目标锁保护提交 |
| 管道末尾 `\|\| true` 吞掉失败退出码 | 每项记录状态，任一失败返回 1，保留成功结果 |
| 软链替换前 `rm -rf` 目标 | 仅允许受管理目标；暂存、备份、状态提交，失败尝试恢复 |
| 手工后台并发可能覆盖共享 lockfile | 有界虚拟线程准备内容；目标锁内串行发布和状态写入 |

原脚本还存在静态可见问题：同名目录取第一个；remove 的 scope/agent 分支不完整；list 把 -g 当作带值参数；按 skill 名跨仓库注销声明；源拉取失败只告警。Java 的名字校验、重名拒绝、显式目标、来源身份和逐项错误避免照搬这些行为。

## 当前落地与边界

已实现个人仓库初始化/创建/收录/订阅、目录发现、catalog/find/use、安装/检查/并行更新/卸载，以及 Git 固定版本缓存。JSON 用 Jackson Core streaming，不引入 npm 或反射对象绑定。CLI 细节以 [SPEC.md](SPEC.md) 为准。

已有分类内容无需逐项登记即可被发现。原 third-party-skills.json 和 npm lockfile 仍是旧格式，不会被自动重写或认领；迁移第三方项可显式 `track <name> --source <repo>`，现有 agent 安装仍视为未托管，避免无意覆盖。保留自己的改造内容时应先复制到个人 skills/，再安装。

本轮尚未覆盖 npm skills 的全量生态扩展：在线 marketplace 搜索、归档 URL 下载、npm dependency 同步、启动其他 agent、全部 agent 自动探测和 hooks。也未接入公共发布/包管理器。已完成的是上述仓库与安装管理闭环，不把它表述为所有第三方/实验命令的完整兼容实现。
