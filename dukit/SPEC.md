# dukit CLI 契约规格 (SPEC v1)

> 本文件是 `dukit` 的**权威规格契约**。实现者、文档作者与 AI Agent 均以此为准；任何命令行为、选项或输出格式的调整，必须首先在此契约中定义。
> 目的：提供一个受 ncdu 启发的跨平台、零依赖磁盘占用分析利器，单次遍历目录树，统一解决“全局结构掌握 (JSON)”、“最大占用定位 (top)”与“交互式直观排查 (browse)”三大诉求，并在 macOS、Linux 与 Windows 11 上提供完全一致的原生体验。

---

## 0. 设计理念与背景

传统磁盘分析工具在现代跨平台开发与自动化运维中存在诸多痛点：
1. **`du` 与 `ncdu` 跨平台割裂**：`ncdu` 依赖 ncurses，在 Windows 上难以原生运行或依赖 WSL/Cygwin；原生 `du` 在 macOS (BSD) 与 Linux (GNU) 之间的参数差异巨大（如 `--apparent-size`、`-b`）。
2. **表观大小与实际占用混淆**：在现代文件系统（macOS APFS、Linux ext4/btrfs、Windows NTFS）上，稀疏文件（Sparse File）与写时复制克隆（Copy-on-Write / CoW）极为普遍。一个 512 GB 表观大小的 Docker 镜像在磁盘上可能仅占用 477 MB，传统工具常导致误报。
3. **节点数随文件爆炸**：在包含数十万文件的目录树（如 `/Applications`、`node_modules`）中，逐文件生成树节点会导致数百兆 JSON 与海量内存开销。
4. **CI/Agent 自动化不友好**：传统工具多偏重终端全屏交互，缺乏开箱即用、无反射、结构高度精简的流式 JSON 树输出。

`dukit` 采用 **Java 25 + GraalVM Native Image** 构建，零运行时依赖，单文件原生二进制毫秒级启动，严格贯彻 **Triple-Platform Parity（三端系统平权）**。

---

## 1. 架构定位与模块边界

- **所属模块**：`li-tools/dukit`
- **基础依赖**：仅依赖 `cli-common`（提供 `CliRunner`、`GlobalOptionsMixin`、退出码、流式 JSON 与终端着色）；全屏终端交互基于 JLine 的 FFM（Foreign Function & Memory API）原生终端驱动，零 JNI、零动态链接库。
- **构建目标**：单一独立可执行原生二进制（macOS `dukit`、Linux `dukit`、Windows `dukit.exe`）。
- **进程模式**：纯本地扫描与只读分析，唯一写操作仅为交互式浏览器中的“移入系统回收站（Trash）”。

---

## 2. 核心指标与尺寸语义 (Size Semantics)

`dukit` 同时测量并报告两种尺寸指标，以消除任何歧义：

| 指标字段 | 概念口径 | 默认行为 | 技术实现与平台表现 |
| :--- | :--- | :--- | :--- |
| **`diskSize`** | **实际磁盘占用 (Occupied Space)** | **所有视图默认优先使用** | 对应 `st_blocks × 512`（或 1024 依平台标准转换）。通过 FFM 零开销读取平台 `struct stat`。在 macOS (APFS) 与 Linux (ext4) 上与 `du -s`、Finder、GNOME 磁盘工具完全对齐。Windows 暂不暴露原生块计数，优雅回退至 `size`。 |
| **`size`** | **表观大小 (Apparent / Logical Size)** | 可选指标 (`--apparent-size`) | 逻辑字节数之和（等价于 `du --apparent-size`）。JSON 树中两种指标并存；文本树与排行可通过 `--apparent-size` 强制切换。 |

### 核心计算规则
1. **硬链接去重 (Hardlink Deduplication)**：同一扫描任务内，具有相同 `fileKey`（inode + device）的内容仅计算一次占用（首个遍历到的生效）；所有硬链接均正常计入文件总数 (`files`)。Windows 在 JDK 25 下不暴露 `fileKey`，按独立文件计算并如实记录。
2. **符号链接 (Symlinks)**：符号链接本身永不递归跟随，不计入目录大小，不计入文件总数。若用户指定的**扫描根路径**本身是符号链接，启动前首先解析其实际目标目录。
3. **目录 Inode 本身**：不额外增加目录元数据本身的块大小，仅累计目录下实际直属文件与子目录的贡献。
4. **权限错误与不可读路径**：不可读目录不中断全局扫描；错误路径以警告流记入 stderr，并增加扫描元数据中的 `errors` 计数。

---

## 3. 命令行接口与行为契约

### 3.1 退出码规范

遵循 `cli-common` 统一契约：
- **`0` (Success)**：扫描完成并正常输出，包括出现部分路径权限拒绝的容错扫描。
- **`1` (Execution Failure)**：目标路径不存在、不是目录或不可访问等业务执行失败。
- **`2` (Usage Error)**：非法参数、无效选项，或非交互终端环境误调用必须交互的命令。

### 3.2 默认手势 (Default Gesture)

运行 `dukit` 裸命令：
- **交互终端 (TTY)**：无参数时默认全屏交互浏览当前工作目录；携带 `<path>` 时全屏浏览指定目录。
- **管道/重定向 (Piped / Non-TTY)**：例如 `dukit | jq .`，自动降级为非阻塞输出全量目录树 JSON，杜绝全屏挂死或报错。

---

### 3.3 子命令一览

| 命令 | 行为定位 | 默认输出 | 关键参数 |
| :--- | :--- | :--- | :--- |
| **`dukit [path]`** | 快速入口（默认手势） | 交互终端进入全屏 `browse`；管道进入 `scan` JSON | 继承全局参数 |
| **`dukit scan [path]`** | 全量目录树扫描 | **默认纯流式 JSON** | `--depth <n>`、`--apparent-size`、`--exclude <glob>`、`--text` |
| **`dukit top [path]`** | 有界流式排行榜 | **默认人类可读排行榜** | `-n/--limit <n>`、`--files`、`--min-size <size>`、`--json`、`--exclude <glob>` |
| **`dukit browse [path]`** | 现代化全屏交互浏览器 | **全屏双缓冲 TUI** | `--exclude <glob>` |

---

### 3.4 `dukit scan [path]` 规格细节

一次性遍历整棵目录树，输出层次化聚合结果。

1. **Directories-Only 树结构精简**：
   - 树节点**仅包含目录**。常规普通文件不作为独立节点，而是折叠计入直属父目录的 `ownFileSize` 与 `ownFileCount` 字段。
   - 66 万文件规模的目录树（如 `/Applications`）由传统 66 万节点骤降至 8.7 万目录节点，JSON 传输体量从 63 MB 压减至 11 MB。
2. **`--depth <n>` 深度截断**：
   - 控制输出层级。**无论 `--depth` 设置为何值，所有节点的尺寸与统计均始终包含完整子树的真实聚合**。
   - 例如 `--depth 1` 仅输出首层直属子目录节点，但大小为完整占用；JSON 仅几十 KB，极速适配 Agent 上下文。
   - 默认值 `-1`（全量深度）；`0` 表示仅输出根目录聚合。
3. **`--text` 人类可读文本树**：
   - 禁用 JSON，输出格式化树状图（带百分比、大小与结构连线）。
4. **`--apparent-size`**：
   - 文本树切换为逻辑大小。

#### JSON 输出 Schema (scan)
```json
{
  "meta": {
    "tool": "dukit",
    "version": "0.4.0",
    "path": "/Users/example/Projects",
    "diskSize": 19756847104,
    "size": 21474836480,
    "files": 45892,
    "directories": 3120,
    "errors": 0,
    "generatedAt": "2026-11-06T00:30:00Z"
  },
  "tree": {
    "name": "Projects",
    "diskSize": 19756847104,
    "size": 21474836480,
    "files": 45892,
    "directories": 3120,
    "errors": 0,
    "ownFileSize": 32768,
    "ownFileCount": 4,
    "children": [
      {
        "name": "service-backend",
        "diskSize": 15234567890,
        "size": 16106127360,
        "files": 38100,
        "directories": 2400,
        "errors": 0,
        "ownFileSize": 4096,
        "ownFileCount": 1,
        "children": []
      }
    ]
  }
}
```

---

### 3.5 `dukit top [path]` 规格细节

专为“哪里最占空间”设计的极速查询指令。

1. **流式有界双堆计算 (Bounded Streaming Heaps)**：
   - 不构建内存完整目录树，内部维护容量为 `limit` 的固定小顶堆。
   - 内存开销仅与 `--limit` 线性相关，扫描百 GB / 数 TB 磁盘内存恒定（~100MB 级别）。
2. **过滤与排序**：
   - `--limit/-n <n>`：返回前 N 项，默认 `20`。
   - `--files`：默认对“目录”进行排行；指定 `--files` 切换为对“单个文件”进行排行。
   - `--min-size <size>`：只保留不低于该阈值的条目，支持单位：`B`、`K/KiB`、`M/MiB`、`G/GiB`、`T/TiB`。
   - **查询根路径不入榜**：用户指定的扫描根目录是查询范围本身，不计入排行结果。
3. **`--json` 输出格式**：
```json
{
  "meta": {
    "tool": "dukit",
    "version": "0.4.0",
    "path": "/Users/example/Projects",
    "mode": "directories",
    "limit": 20,
    "count": 2
  },
  "entries": [
    {
      "path": "/Users/example/Projects/service-backend/target",
      "diskSize": 12582912000,
      "size": 13421772800,
      "files": 12400,
      "directories": 850
    }
  ]
}
```

---

### 3.6 `dukit browse [path]` 规格细节

现代化终端全屏交互浏览器，基于 JLine FFM 原生驱动进入备用屏幕（Alternate Screen Buffer）。

#### 交互按键矩阵
| 按键操作 | 功能说明 | 限制条件 |
| :--- | :--- | :--- |
| **`↑` / `k`** | 光标向上移动一行 | 首行自动停住，不循环折返 |
| **`↓` / `j`** | 光标向下移动一行 | 末行自动停住，不循环折返 |
| **`Enter` / `→` / `l`** | 进入选中的子目录 | 仅对目录有效；对文件预览行无动作 |
| **`Backspace` / `←`** | 返回上一级父目录 | 已在扫描根目录时无动作 |
| **`Home` / `End`** | 快速跳转至列表顶部 / 底部 | - |
| **`PageUp` / `PageDown`** | 向上 / 向下翻动整页 | 依终端视口高度自适应 |
| **`s`** | 循环切换排序维度：`大小 (Size) → 名称 (Name) → 文件数 (Count)` | 切换后保持当前选中项可见 |
| **`t`** | **展开 / 收起当前目录直属最大文件预览 (Top Files)** | 仅展示直属前 10 大文件，暗色显示，不可进入 |
| **`d`** | **安全移入系统回收站 (Move to Trash)** | 打开确认对话框；严防误删，只移入回收站 |
| **`q` / `Ctrl+C`** | 干净退出全屏模式，恢复终端主屏幕 | - |

#### 安全回收站策略 (Delete to Trash)
1. **仅允许交互触发**：绝不暴露 CLI 删除子命令（无 `dukit rm`，无 `--yes`，无 `--dry-run`）。只有当用户在交互式树形界面中看清路径全貌时，才允许发起清理。
2. **多重安全防护门禁 (DeleteGuard)**：
   - 严禁删除扫描根目录。
   - 严禁删除位于扫描树外部的路径。
   - 严禁删除符号链接本身或挂载点目录。
   - 严禁删除回收站目录自身或其内部文件。
3. **确认对话框交互**：
   - 按 `d` 弹出全屏居中确认模态：明确列出完整路径、占用空间、包含子目录与文件总数、目标回收站路径。
   - 严格按键确认：按 `y` 执行移动，按 `n` 或 `Esc` 取消并返回浏览。
4. **各平台回收站对接**：
   - **macOS**：用户主卷移入 `~/.Trash`；外部挂载卷移入 `/Volumes/<vol>/.Trashes/<uid>`。
   - **Linux**：严格遵循 freedesktop.org 规范，写入 `.trashinfo` 元数据与 `files/` 目录；拒绝跨设备伪移动。
   - **Windows**：调用系统安全回收站（Recycle Bin）；若 Windows 回收站被管理员禁用或空间已满，系统 Shell 警告后由系统执行。
5. **动态原子更新 (TreeUpdater)**：
   - 删除成功后，`dukit` 动态自底向上扣除父路径尺寸差额，即时刷新界面，无需耗时重扫整棵目录树。

---

## 4. 并行扫描引擎与性能契约

1. **ForkJoinPool 自适应并行**：
   - 扫描基于 `ForkJoinPool` 驱动。
   - **自适应阈值 (Adaptive Fork Threshold)**：目录所含直属子项超过预设分叉阈值时才派发新任务至工作线程；小目录树直接在当前线程内联完成。50 个文件的小目录 10ms 即可完成，规避线程调度开销。
   - 实测 59 GB、66 万文件的重型目录树：`dukit` 仅需 **3.2 秒**，效率超越 `ncdu`（8.5 秒）。
2. **线程安全共享状态**：
   - 采用并发安全集合与原子计数器处理全局累计、Top-N 堆和错误队列，确保输出结果确定且一致。
