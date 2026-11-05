# dukit 删除功能设计（Trash / Delete Design）

- **日期**：2026-11-05
- **状态**：草案，待评审（本文件所在 PR 只交付设计，不包含删除实现）
- **关联任务**：Workbench `t_mw33p43oc82af1bf6f91211c`
- **关联模块**：`dukit`
- **评审门槛**：评审通过前不得合并删除相关生产代码；实现完成后，站点与文档中的“只读”表述必须同步修改。

> 本文把所有“删除”都定义为**把项目移入操作系统回收站（Trash / Recycle Bin）**。v1 不提供永久删除，也不会在 Trash 不可用时退化为永久删除。

---

## 1. 结论摘要

| 必须先决策的问题 | 结论 |
| --- | --- |
| 是否走系统回收站 | **是。** v1 只走系统 Trash；不实现 `rm` 式永久删除。 |
| 是否需要二次确认与 dry-run | **是。** 删除只有一个入口：交互界面。全屏 TUI 使用独立确认页，行式浏览器要求再次输入 `delete`；确认之前不产生任何文件系统修改，确认页本身就是 dry-run。 |
| 是否只允许删除扫描树内的路径 | **是。** 只允许删除当前目录的直接子项，且必须来自本次扫描树；不提供路径输入，因此不存在绝对路径、`..`、符号链接或挂载点逃逸的入口。 |

其他已定结论：

- **只删目录**：扫描树只有目录节点，删除因此只作用于目录。要删单个大文件，用系统文件管理器——dukit 不为此在树上增加文件节点。
- **不提供 CLI 删除命令**：删除只在交互界面中发生，没有 `dukit delete` 子命令，也没有 `--yes` / `--dry-run` / `--json` 之类的脚本化删除开关。
- **不删扫描根**：扫描根本身不能成为目标；TUI 只能删当前目录的子项。
- **没有永久删除入口**：包括 `--permanent`、TUI 快捷键和脚本开关。后续若需要，必须单独立项评审。
- **失败即关闭**：Trash 不可用、跨文件系统、网络卷、路径超限、权限不足时，不做任何修改并报告原因，不退回永久删除。
- **空间不会立即释放**：移入 Trash 只是改名/移动；清空 Trash 由操作系统负责。UI 必须明确提示。
- **实现顺序**：先做纯核心与 Linux/macOS，再做 Windows FFM；所有平台都先用 JVM 测试，最后用 native 二进制冒烟。

---

## 2. 背景与现状

`dukit` 目前是只读分析器：

- `scan` 输出目录树（JSON/文本），`top` 输出最大目录或文件，`browse` 提供全屏浏览器，终端不支持时回退到行式浏览器。
- 扫描树只包含目录；文件被折叠进父目录的 `ownFileSize` / `ownFileCount`，不成为节点。
- 最新模型不把 `Path` 放在每个 `DirEntry` 上：`ScanResult.path()` 是扫描根，`DirEntry` 只保存名称和聚合值，`TuiState` 从“根路径 + 导航 trail”重建当前路径。
- 这是刻意的内存优化：节点数与目录数成正比，而不是文件数。
- 现有站点 FAQ 明确写着 v1 只读；删除功能落地后必须改掉这一表述。

删除之所以风险最高，是因为分析器一旦会改文件系统，路径错误、符号链接、挂载点、跨盘复制、部分失败和平台差异都会从“显示错误”升级为“数据丢失”。本设计先建立不变量，再谈 UI。

---

## 3. 范围

### 3.1 v1 范围

- 全屏浏览器：选中目录后按 `d`，确认后移入系统 Trash。
- 行式浏览器：`delete <n>`，确认后移入系统 Trash。
- 三个平台的 Trash 适配器：
  - Linux：freedesktop.org Trash 规范，纯 JDK 实现。
  - macOS：`~/.Trash` 与卷级 `.Trashes/<uid>`，纯 JDK 实现。
  - Windows：`SHFileOperationW` + `FOF_ALLOWUNDO`，通过 FFM 调用 Shell32。
- 删除成功后的内存树更新，不重新扫描整棵树。
- 测试、native 冒烟、文档更新。

### 3.2 明确不做

- 永久删除（`unlink` / `rm -rf` / `DeleteFile`）。
- 恢复、清空 Trash、Trash 浏览、自动清理。
- 多选删除、通配符、正则、从 `top` 结果直接删除。
- 删除扫描根。
- 符号链接本身或链接目标的删除。
- 网络共享（SMB/NFS/AFP/WebDAV）上的删除。
- Windows `\\?\` 长路径与超过 `MAX_PATH` 的路径；后续用 `IFileOperation` 另案处理。
- macOS Finder 的 “Put Back” 保证。
- 不提供 CLI 删除子命令，因此也没有 `--yes`、`--dry-run`、`--json` 等脚本化删除开关。

---

## 4. 决策记录

### D1：系统 Trash，而不是 dukit 自管回收站

**决策**：目标移入系统 Trash。Linux 遵循 freedesktop.org Trash 规范；macOS 使用系统 Trash 目录；Windows 请求 Recycle Bin。

**理由**：

- 用户对“删除”的预期就是可以在系统 Trash 中找回；自管目录会制造第二个回收站，用户找不到，生命周期也要自己维护。
- 系统 Trash 已处理跨用户、卷、元数据和清空策略；自己实现这些是本项目不该承担的复杂度。
- 与 `ncdu` 等工具的“删除后还能从桌面恢复”的直觉一致。

**代价**：

- 删除不是立即释放空间；UI 必须提示“清空 Trash 后才释放”。
- Linux/macOS 的 Trash 目录布局必须严格按规范实现，否则文件管理器和找回工具可能看不到。
- Windows Recycle Bin 在超大文件、禁用回收站策略、网络卷等情况下可能无法回收；见 D9。

**被否决的方案**：

- **永久删除**：风险最高，且没有恢复路径；不能作为 v1 默认。
- **dukit 自管 Trash**：跨文件系统要复制，用户不可见，恢复/清理/配额都要自己做。
- **调用 `trash` / `gio` / PowerShell**：引入运行时依赖，且命令拼接与转义是新的攻击面。
- **`java.awt.Desktop.moveToTrash`**：JDK 25 的 Linux `XDesktopPeer` 只声明 `OPEN`、`MAIL`、`BROWSE`，不支持 `MOVE_TO_TRASH`；引入 `java.desktop` 还会增加 native 体积，并遇到 macOS AWT native image 的已知问题。不能作为跨平台原语。

### D2：二次确认，确认页就是 dry-run

**决策**：

- TUI 中按 `d` 只进入确认页；在用户按 `y` 之前，不创建 Trash 目录、不写 `.trashinfo`、不移动任何东西。
- 确认页必须显示：目标绝对路径、大小、文件数、目录数（TUI 有数据时）、Trash 去向，以及“清空 Trash 前不会释放空间”。
- 行式浏览器要求再次输入完整的 `delete`（不是 `y`），避免回车惯性。

**理由**：

- 删除是唯一不可逆（在清空 Trash 前只是“半可逆”）的操作，预览必须是强制步骤，而不是可选开关。
- 没有脚本入口，就不存在“非交互环境如何确认”的问题；拒绝成为唯一的默认行为。

**代价**：多一次按键/输入；无法把删除写进脚本。

### D3：严格限制在扫描树 / 显式边界内

**决策**：

- **TUI**：目标必须是本次 `ScanResult` 中“当前目录的直接子项”。不提供“输入任意路径删除”的入口。
- 没有路径输入入口：目标只能来自扫描树中当前目录的子项。
- 以下情况一律拒绝：
  - 绝对路径目标、盘符相对路径、UNC 路径；
  - 任何 `..` 组件；
  - 根目录、文件系统挂载点；
  - 目标路径上任何一级符号链接；
  - 目标自身是符号链接；
  - 目标子树中包含另一个挂载点的目录；
  - 目标包含或等于 Trash 根目录；
  - 一个目标包含另一个目标（重叠目标）。
- TUI 的路径来自扫描树，且每次按 `d` 时重新 `lstat` 校验，不信任旧快照。

**理由**：

- 绝大多数误删来自“路径拼错/多一个 `..`/脚本变量为空”。不提供路径输入，这类错误在入口处就不存在。
- 扫描树内删除意味着用户看到的就是将要删的；不需要在确认页重新解释一棵陌生的树。
- 拒绝符号链接和挂载点是对“递归移动目录”最重要的保护：移动一个目录不会跟随其中的符号链接，但目标本身或祖先被替换成链接时必须拒绝。

### D4：删除只在交互界面中提供，且只删目录

**决策**：

- 删除入口只有两个，都在交互界面里：全屏 TUI 的 `d`，行式浏览器的 `delete <n>`。
- 不提供 `dukit delete` 子命令，也没有 `--yes` / `--dry-run` / `--json` 等脚本化删除开关。
- 两个入口都只对目录生效，因为扫描树没有文件节点。
- 文件级删除不作为 v1 目标；若以后要把文件加入树，需单独评估内存和渲染成本。

**理由**：

- 删除的价值来自“看到的就是将要删的”。脱离扫描树的视觉上下文，用命令行参数指定路径，安全收益消失而误删风险保留。
- 脚本化删除最容易踩的坑（变量为空、路径拼错、循环变量失控）恰好是交互确认能挡住的。不提供入口，就不需要为这些场景设计 `--yes`、退出码和部分失败语义。
- 需要脚本化清理的用户可以用系统自带的回收站工具；dukit 不打算成为 `trash-cli` 的替代品。
- 树只有目录节点是 dukit 的核心内存设计；为了删除文件而在树上增加文件节点，会把内存从“目录数”推到“文件数”，与本项目已有优化冲突。

**代价**：无法在脚本里调用删除。这是有意的取舍。

### D5：不实现永久删除

**决策**：v1 没有 `--permanent`，也没有 TUI 永久删除键。

**理由**：

- 永久删除会引入第二套确认、第二套失败模型、第二套跨平台实现（Windows 只读文件/目录、POSIX 权限、部分删除），风险与收益不成比例。
- 需要立即释放空间的用户可以在系统 Trash 中清空；这是操作系统已经提供的、用户熟悉的操作。
- 如果未来确实需要，必须单独设计并评审，至少满足：独立的 `--permanent`、强确认、无 TUI 单键入口、`FileVisitor + NOFOLLOW_LINKS`、逐项错误报告、挂载点/重解析点防护。

### D6：不调用 shell，不拼接命令

**决策**：所有平台适配器都用 JDK API 或明确的 FFM 调用；不执行 `rm`、`osascript`、`gio`、PowerShell、`cmd.exe`。

**理由**：

- 路径中的空格、引号、换行、`$()`、通配符和编码差异会变成命令注入或静默误删。
- 外部命令是否安装、版本差异、桌面会话差异都会让行为不可预测。
- 项目已有 FFM 基础设施（`NativeStatReader`、JLine），Windows Shell 调用可以沿用同一套 native 校验流程。

### D7：失败关闭（fail closed）

**决策**：Trash 不可用、跨设备、网络卷、权限不足、路径超限、目标在扫描后变化时，**不做任何修改**，报告原因；绝不回退到永久删除。

**理由**：删除功能的第一优先级是“没有删掉”优于“删错了/永久删了”。失败后用户仍可以用系统工具处理。

### D8：删除成功后更新内存树，不重扫整棵树

**决策**：成功移入 Trash 后，从 `ScanResult` 的树中移除目标节点，并沿祖先路径扣减聚合值；TUI 的 trail 按名称重建。只有出现“结果不确定”的失败时才把会话标记为 stale。

**理由**：

- 删除一个目录后重扫可能耗时数秒到数十秒；用户会以为界面卡死。
- 目标的 `size`、`fileCount`、`dirCount`、`errorCount` 在扫描时已经存在，按本次扫描的聚合扣减是精确的；硬链接的“首次计数”归属可能让剩余树总量低于新扫描值，见 10.1。
- 失败时不能假装成功；不确定时必须停止继续删除。

### D9：Windows 的已知边界

**决策**：v1 使用 `SHFileOperationW` 请求 Recycle Bin，并设置 `FOF_WANTNUKEWARNING`。不承诺“任何情况下都能回收”。

**已知限制**：

- 文件超过 Recycle Bin 配额、回收站被策略禁用、卷没有回收站时，Windows 可能永久删除而不是回收。`FOF_WANTNUKEWARNING` 要求系统在这种情况下给出警告；dukit 不关闭这个警告。
- 因此 **Windows 上不承诺“完全不出现系统对话框”**：当系统无法回收时，nuke warning 仍可能出现。dukit 是交互式工具，这个警告正是要给人看的；无桌面会话下它可能被系统忽略或以 aborted/未知结果返回。
- 网络共享没有 Recycle Bin；适配器必须拒绝 UNC 路径和 `DRIVE_REMOTE` 卷，不能把“无法回收”变成静默永久删除。
- `SHFileOperationW` 不支持 `\\?\` 前缀，且长路径会失败；v1 在预览阶段拒绝超限路径，并给出明确错误。
- 未来若要“硬保证只进回收站”，需要 `IFileOperation` + `FOFX_RECYCLEONDELETE`/`FOFX_EARLYFAILURE` 的 COM 实现；这是独立工作，不混入 v1。

### D10：不做恢复/清空 Trash

**决策**：dukit 只负责“放进去”，不负责“拿出来”或“倒掉”。

**理由**：恢复需要读取平台元数据、处理重名和原路径冲突；清空需要遍历 Trash 并处理权限，都是新的破坏性操作。交给系统文件管理器。

---

## 5. 安全不变量

以下不变量是实现和测试的验收基线。任何一条不满足，删除入口必须拒绝执行。

| 编号 | 不变量 |
| --- | --- |
| I1 | v1 的删除实现中不存在永久删除路径；Trash 失败不得回退。 |
| I2 | TUI 目标必须是当前目录的直接子项，且在本次 `ScanResult` 的树中；扫描根永远不可删。 |
| I3 | 不存在 CLI 删除入口；目标只能来自扫描树中当前目录的直接子项，不接受任何路径参数。 |
| I4 | 从扫描根到目标的每一级都必须是真实目录，不能是符号链接；Windows 上也不能是 junction/reparse point（JDK 的 `isSymbolicLink()` 对 junction 返回 false，必须用 `isOther()` 或 reparse 属性判断）。 |
| I5 | 目标不能是文件系统根、挂载点，或包含另一个挂载点的目录；Windows 上子树中的 reparse point 必须先验证 Shell 不会跟随，否则拒绝。 |
| I6 | 目标不能等于或包含将要使用的 Trash 目录；否则移动会变成“把目录移进它自己”。 |
| I7 | 目标不能是已知 Trash 根（`~/.Trash`、`.Trashes`、`$XDG_DATA_HOME/Trash`、`.Trash-$uid` 等），也不能位于将要使用的 Trash 目录内部。 |
| I8 | 确认页出现之前不做任何文件系统修改；`n`/Esc/`q`/Ctrl-C 取消后也不留任何 Trash 文件或 info 文件。 |
| I9 | 移动必须在同一文件系统内完成；使用 `ATOMIC_MOVE`，跨设备时以 `AtomicMoveNotSupportedException` 失败，绝不回退为复制后删除。 |
| I10 | 删除成功后树聚合值必须与“移除该节点”一致；结果不确定时不得继续删除，并将会话标记为 stale。 |

### 5.1 威胁与对策

| 威胁 | 对策 | 残余风险 |
| --- | --- | --- |
| 用户输入 `../../etc` | 不存在路径输入；目标只能从扫描树中选择 | 无（没有该入口） |
| 扫描后目标被替换成符号链接 | 每次执行前逐级 `lstat`，`NOFOLLOW_LINKS`；最终项必须是目录/普通文件 | 检查与移动之间存在极短 TOCTOU 窗口；这是本地工具的通用边界，OS 的 `rename` 本身是原子的 |
| 目标祖先目录被替换成符号链接或 Windows junction | POSIX/Windows 都逐级检查；Windows 额外拒绝 `isOther()`/reparse point，不能只信 `isSymbolicLink()` | 同上 |
| 现有扫描器把 Windows junction 当目录遍历 | 删除功能实现前先修复 `NioStatReader.kindOf`/扫描器的 reparse point 处理；删除 guard 再独立拒绝 | 这是现有扫描缺陷，删除功能会放大它；需要回归测试 |
| 删除挂载点或包含挂载点的目录 | 比较目标与父目录的 `FileStore`/`unix:dev`；遍历子树检查嵌套挂载 | 非 POSIX 平台的挂载检测是 best effort，需要 native 测试 |
| Trash 目录是符号链接 | 检查 `NOFOLLOW_LINKS`、所有者、权限；拒绝链接 | 本地攻击者可在检查后替换；交互式工具可接受 |
| 多用户机器上写入别人的 Trash | Linux 使用 `$uid` 子目录并校验所有者/权限；macOS 使用 `.Trashes/$uid` | 需要正确的 uid/权限检查，测试覆盖 |
| 跨文件系统移动导致复制/部分失败 | Linux/macOS 选择同设备 Trash，并用 `ATOMIC_MOVE` 保证跨设备直接失败；Windows 交给同卷 Recycle Bin | Windows 卷内由 Shell 处理 |
| 网络共享被永久删除 | Windows 拒绝 UNC/`DRIVE_REMOTE`；Linux/macOS 按 mountinfo/`FileStore.type()` 的 fstype 拒绝已知网络文件系统 | 目录挂载/未知 fstype 需要平台验证 |
| Windows 超大文件/禁用回收站 | `FOF_WANTNUKEWARNING`；不关闭系统警告；文档明确 | 系统策略仍可能永久删除；v1 不承诺硬保证 |
| 删除目录后界面仍显示旧大小 | 成功即扣减；不确定则 stale 并禁用后续删除 | 外部进程同时修改目录时，树本来就会过期 |
| 硬链接导致“大小已扣减但空间未释放” | 预览和提示使用“表观大小/移入 Trash”；明确空间在清空 Trash 后才释放 | 无法从 `DirEntry` 知道外部硬链接；删除后的树总量也可能低于新扫描值，需要重扫才精确 |

---

## 6. 路径校验

### 6.1 TUI 目标解析

`DirEntry` 不再携带 `Path`，因此目标路径必须由 `ScanResult.path()` + `TuiState` 的 trail + 选中项名称重建：

```text
target = scan.path()
for each trail node from root to current: target = target.resolve(node.name())
target = target.resolve(selected.name())
```

校验顺序：

1. `selected` 在 `entries()` 范围内，目标非空。
2. `target.normalize()` 严格位于 `scan.path()` 之下，且不等于 `scan.path()`。
3. `target.getParent().equals(currentDirectory)`——必须是直接子项，不是更深的后代。
4. 从 `scan.path()` 到 `target` 逐级 `lstat`：
   - 中间项必须是目录；
   - 任何一级 `isSymbolicLink()` 为真则拒绝；
   - Windows 上任何一级 `isOther()` 为真（reparse point/junction）也拒绝，因为 JDK 的 `isSymbolicLink()` 只识别 `IO_REPARSE_TAG_SYMLINK`；
   - 最终项必须是目录（TUI 只删目录）。
5. 目标仍存在，且不是根目录/挂载点。
6. 目标子树不含嵌套挂载点（见 6.4）。
7. `TrashStore.prepare(target)` 成功，且返回的 Trash 路径不在目标内部。

### 6.2 没有 CLI 目标解析

删除不接受路径参数，因此不存在“命令行目标解析”这一层。目标解析只有 6.1 的 TUI 路径：从 `ScanResult` 的树里取当前目录的直接子项，再逐级 `lstat` 校验。

这去掉了一整类攻击面：绝对路径、盘符相对路径、UNC、`..`、通配符、重叠目标，以及“多个目标中部分成功”的语义。

### 6.3 根、挂载点、Trash 根

- 文件系统根：`target.getParent() == null` 或 `target.equals(target.getRoot())` 时拒绝。
- 挂载点：目标与父目录的 `FileStore`/`unix:dev` 不同，说明目标本身是另一个文件系统的挂载点，拒绝。Linux 用 `/proc/self/mountinfo` 同时检查目标自身和子树；Windows 用 `GetVolumePathNameW` 识别文件夹挂载点，分类不明时拒绝。
- Trash 根：`~/.Trash`、`~/.local/share/Trash`、`$XDG_DATA_HOME/Trash`、卷级 `.Trashes`、`.Trash-$uid` 等不能作为目标。
- 目标位于 Trash 内部：拒绝，避免把 Trash 里的项目再次“回收”成重名副本。
- Trash 去向在目标内部：目标包含 Trash 路径时拒绝。典型例子是删除整个 `$HOME`，而 home trash 就在 `$HOME/.local/share/Trash` 里。这种路径不能“移进自己的 Trash”。
- 目标子树中的嵌套挂载点：移动父目录时可能把挂载点卷进去；v1 在 POSIX 上遍历子树，发现不同 `unix:dev`/`FileStore` 就拒绝。Linux 还应解析 `/proc/self/mountinfo` 识别同设备的 bind mount；仅比较设备号不足以发现所有挂载点。Windows 上检查目标自身是否为 reparse point；子树中的 reparse point 必须先做 native 验证（Shell 是否跟随），无法证明不跟随时拒绝整棵目录。
- 网络卷：Linux 用 mountinfo/fstype，macOS 用 `FileStore.type()`/`statfs`；已知网络文件系统在 `prepare` 阶段就抛 `NETWORK_VOLUME`，不会走到“在同卷创建 Trash”的步骤。

### 6.4 挂载点遍历的边界

- 预览扫描本身不跟随符号链接；挂载点检查是独立的目录遍历，比较设备号并解析 Linux `/proc/self/mountinfo`，不读取文件内容。Windows 上同时检查 reparse point/junction；无法证明 Shell 不跟随就拒绝。
- 该遍历在 `plan` 阶段（确认页出现之前）执行一次；对超大目录会增加一次 `O(目录数)` 的遍历，但换来“不会把另一个卷移进 Trash”的保证。
- `execute` 的再次校验重新检查路径、类型、目标自身是否挂载；不重复整棵子树的挂载遍历。确认与移动之间新增嵌套挂载点属于已声明的 TOCTOU 残余；POSIX `rename` 可能以 `EBUSY` 拒绝，Windows Shell 的行为由 native 测试确认。
- 遍历失败（权限不足、目录消失）时拒绝删除，而不是跳过。删除一个无法完整检查的目录不符合 I5。

### 6.5 TOCTOU 说明

JDK NIO 没有提供 `openat`/`unlinkat` 风格的目录句柄 API；本设计不假装消灭 TOCTOU。实际约束是：

- 校验与移动之间只有很短的时间窗口；
- 移动本身由 OS 原子 `rename`/Shell API 完成；
- 目标是本地用户自己的文件；攻击者若已能改写父目录，本机上的风险远大于此窗口；
- 不跟随符号链接、不跟随挂载点，显著降低了“误删到别处”的概率。

如果评审要求更强保证，需要在 POSIX 上用 FFM `renameat2(RENAME_NOREPLACE)`/`openat` 重做整个移动链路；这会把 v1 从“纯 JDK + Windows Shell”扩展到三平台 FFM，建议另案处理。

---

## 7. 用户体验

### 7.1 全屏浏览器

- 底部帮助增加 `d delete`。
- 按 `d`：
  1. 校验当前选中项目；失败则在状态行显示原因，不进入确认页。
  2. 调用 `TrashStore.prepare` 取得 Trash 去向；失败同样只显示原因。
  3. 用确认页替换底部帮助。
- 确认页示例（宽度不足时截断路径，不截断关键动词）：

```text
Delete  /Users/lihu/tmp/build-cache
        3.4 GiB · 12 directories · 1,204 files
        → Trash: /Users/lihu/.Trash/build-cache
y move to Trash   n cancel   space is not freed until Trash is emptied
```

- `y`：执行移动。成功后在状态行显示 `✔ moved to Trash: build-cache`，从树中移除该节点，光标停在原索引位置（列表缩短时 clamp 到末尾）。
- `n` 或 `Esc`：取消，状态行显示 `cancelled`，树不变。
- 确认页激活时，其他导航键忽略；`q`/Ctrl-C（`Key.QUIT`）视为取消，不删除，返回到列表并显示 `cancelled`。这样 raw mode 下始终保留中断手势；取消后再按 `q` 才退出。
- 删除失败：状态行显示 `✖ <原因>`；树不更新。若结果不确定（见 10.4），标记 stale，后续 `d` 直接拒绝并提示重启。
- Trash 不可用：`d` 不进入确认页，状态行显示“此文件系统没有可用的 Trash；未做任何更改”。
- 目录有扫描错误（`errorCount > 0`）：v1 **不进入确认页**。挂载点预检必须能遍历目标树；无法读取的子目录意味着无法证明没有嵌套挂载点，因此 fail closed，状态行说明“目标包含无法读取的条目，未做任何更改”。不要用“移动不需要读取内容”来绕过这一点——安全不变量 I5 要求完整预检。若未来要放宽，必须先证明每个失败项都是非目录，且不掩盖嵌套挂载。

### 7.2 行式浏览器

- 帮助增加 `delete <n>`。
- `delete 3` 打印与 TUI 等价的计划，然后要求输入完整单词 `delete`：

```text
Move to Trash: /Users/lihu/tmp/build-cache
  3.4 GiB · 1,204 files · 12 directories
  Trash: /Users/lihu/.Trash/build-cache
Type 'delete' to confirm:
```

- 只有去除首尾空白后精确等于 `delete` 才执行；`yes`、`y`、空行、EOF 都视为取消。
- 成功后更新树并重新渲染；失败时打印错误，树不变；如果目标已被移走但 receipt 不明确，按 10.4 处理；不确定时标记 stale。
- `delete` 没有参数或编号越界：打印用法，不执行。

### 7.3 没有 CLI 入口

删除命令不存在，所以这一节只说明它为什么不存在：

- 没有 `dukit delete`，没有 `--within`、`--dry-run`、`--yes`、`--json`。
- 没有“非交互环境如何确认”“多个目标部分成功”“退出码怎么定”这些需要单独设计的契约。
- 脚本化清理请用系统自带的回收站工具。

### 7.4 为什么没有 `--dry-run`

确认页就是 dry-run：它显示完整计划，`y` 之前没有任何副作用。再加一个“dry-run 模式”只会制造“按了 `d` 却没有确认页”的歧义。

CLI 入口被整体移除（见 D4），所以也不需要 `--dry-run`、`--yes` 或 `--json`。

---

## 8. 架构

```text
BrowseCommand / CliCommand / Browser / FullScreenBrowser
                         |
                         v
                    DeleteService
                  /       |        \
                 v        v         v
          DeleteGuard  TrashStore  TreeUpdater
                       /    |    \
                      v     v     v
                   Linux  macOS  Windows
```

### 8.1 包与职责

建议新增（名称可在实现时微调，但边界不变）：

```text
core/delete/
  DeleteService     规划、再次校验、执行、结果归类
  DeleteGuard       路径/根/挂载点/Trash 包含关系校验
  DeletePlan        目标、预览值、Trash 去向、dryRun
  DeleteTarget      单个目标的只读预览
  DeleteResult      单个目标的执行结果
  DeleteException   带原因枚举和“结果是否不确定”标记
  TreeUpdater       从 ScanResult 中移除节点并扣减聚合值

core/trash/
  TrashStore        平台 Trash 的系统边界接口
  TrashTarget       prepare 的返回值（路径、去向展示、文件系统路径）
  TrashReceipt      move 的返回值
  TrashException    带 Reason 和 Outcome
  LinuxTrashStore
  MacOsTrashStore
  WindowsTrashStore
  UnsupportedTrashStore
```

- `core/delete` 和 `core/trash` 不依赖 Picocli。
- `TrashStore` 是真实的系统边界，允许在测试中注入假实现；其他项目类不 mock。
- 挂载点、设备号、fstype、卷根通过一个窄的 `VolumeResolver`（或等价注入）读取；测试注入固定表，生产实现读 `/proc/self/mountinfo`/`FileStore`/`statfs`。这是系统边界的测试缝，不是业务抽象。
- `DeleteService` 通过构造器接收 `TrashStore`。平台选择在 `cli`/`app` 装配层完成，核心不读 `os.name`。
- `TreeUpdater` 只接受 `ScanResult` 和绝对目标路径，返回新的 `ScanResult`；它不碰文件系统，纯函数，易测。
- `DeletePlan` 一旦生成，`execute` 仍会再次校验目标；规划结果不是“通行证”。

### 8.2 TrashStore 契约

```java
public interface TrashStore {
    @NotNull TrashTarget prepare(@NotNull Path target) throws TrashException;
    @NotNull TrashReceipt move(@NotNull TrashTarget target) throws TrashException;
}
```

- `prepare`：只读检查，计算去向和名称，供确认页展示；不得创建目录或写文件。
- `move`：再次检查后执行移动；不得回退永久删除。
- `TrashException.Reason` 至少包含：
  `UNSUPPORTED_PLATFORM`、`NO_TRASH`、`NETWORK_VOLUME`、`CROSS_DEVICE`、`PATH_TOO_LONG`、`MOUNT_POINT`、`TARGET_IS_TRASH`、`TARGET_CONTAINS_TRASH`、`NOT_FOUND`、`TARGET_CHANGED`、`PERMISSION_DENIED`、`ABORTED`、`IO_ERROR`。
- `TrashException.Outcome`：`NOT_STARTED` 或 `UNKNOWN`。`UNKNOWN` 用于 Windows Shell 返回非零/aborted 等可能已经发生部分修改的情况；POSIX `rename` 失败通常是 `NOT_STARTED`。

### 8.3 平台选择

在 `CliCommand` 的装配层做显式 switch，不引入注册表/服务加载：

```java
TrashStore store = switch (Platform.current()) {
    case MACOS -> new MacOsTrashStore(environment, home);
    case WINDOWS -> new WindowsTrashStore();
    case LINUX -> new LinuxTrashStore(environment, home);
    case UNKNOWN -> new UnsupportedTrashStore();
};
```

测试通过构造器注入假 `TrashStore` 或临时目录实现；不修改生产代码的分支逻辑。

### 8.4 数据流

TUI：

```text
d
  -> TuiState.selectedPath()
  -> DeleteService.planEntry(scan, currentDir, entry)
       -> DeleteGuard.validate(...)
       -> TrashStore.prepare(target)
  -> ScreenRenderer 确认页
y
  -> DeleteService.execute(plan)
       -> DeleteGuard.validatePath(...)      // 再次检查路径/类型/目标自身挂载；不重复子树遍历
       -> TrashStore.move(target)
       -> 验证目标消失
       -> TreeUpdater.remove(scan, target)
  -> TuiState.replaceResult(updated)
  -> 状态行
```


---


---

## 9. 平台 Trash 实现

### 9.1 Linux：freedesktop.org Trash

参考规范：<https://specifications.freedesktop.org/trash/latest/>。

**Home Trash**

- `$XDG_DATA_HOME/Trash`；`XDG_DATA_HOME` 必须是绝对路径，否则回退 `$HOME/.local/share`。
- 目标与 home trash 在同一设备时，使用 home trash。
- 目录结构：`files/`、`info/`；在 `move` 阶段（确认之后）不存在时创建，权限 `0700`。
- 若 home trash 是符号链接，或不属于当前用户，失败。

**网络卷拒绝**

- 在 Linux 上解析 `/proc/self/mountinfo` 的 fstype；`nfs`、`nfs4`、`cifs`、`smb3`、`smbfs`、`sshfs`、`fuse.sshfs`、`afs`、`9p`、`davfs`、`davfs2`、`webdav` 等网络文件系统直接抛 `NETWORK_VOLUME`，不得在这些文件系统上创建 Trash。
- 如果 mountinfo 不可用，使用 `Files.getFileStore(target).type()` 做同样的已知网络类型过滤；分类为网络就失败。
- 该检查在 home trash 与卷级 trash 选择之前执行：即使 home trash 与网络目标“同设备”，也不把网络文件移进本地 Trash。最终选中的 Trash 目录本身也要做一次 fstype 检查；任何一端是网络文件系统都拒绝。

**其他设备（卷级 Trash）**

- 通过 `unix:dev`（不可用时退回 `FileStore`）判断目标设备。
- 找到目标的挂载点：优先解析 `/proc/self/mountinfo`，取包含目标的、最长的挂载点；不可解析时退回“向上走到设备变化前的最高目录”。
- 若 `$topdir/.Trash` 存在：
  - 必须是目录、不是符号链接、带 sticky bit、对当前用户可写；
  - 使用/创建 `$topdir/.Trash/$uid`（`0700`，校验所有者）。
  - 若 `.Trash` 通过检查但 `$uid` 目录创建失败，按规范回退到 `$topdir/.Trash-$uid`；若 `.Trash` 本身检查失败，不得使用它，直接尝试 `.Trash-$uid`。
- 否则使用/创建 `$topdir/.Trash-$uid`（`0700`，校验所有者）。
- 两者都不可用时：**失败**。不把文件复制到 home trash；跨设备复制会慢、会占用 home 空间，且可能在中途失败。
- 目标等于或包含将要使用的 Trash 目录时，失败。

**info 文件**

文件名：`files/<name>` 与 `info/<name>.trashinfo` 必须同名。名称冲突时依次尝试 `name`、`name.2`、`name.3`，同时检查 `files/` 和 `info/` 两个位置。

创建顺序（规范要求先 info、后移动）：

1. 用 `CREATE_NEW` 原子创建 `info/<name>.trashinfo`；已存在则换下一个名称。
2. 写入：
   ```text
   [Trash Info]
   Path=<percent-encoded original path>
   DeletionDate=YYYY-MM-DDTHH:mm:ss
   ```
3. 在 `files/<name>` 创建一个**与目标同类型的空占位项**（普通文件用 `CREATE_NEW`，目录用 `createDirectory`）。这一步同时完成名称预留：如果名称已存在，删除 info 并换下一个名称。
4. `Files.move(target, placeholder, StandardCopyOption.ATOMIC_MOVE)`。`ATOMIC_MOVE` 在 POSIX 上是 `rename`，会原子替换我们自己创建的空占位项；跨设备时抛 `AtomicMoveNotSupportedException`，不做 copy。
5. 移动失败：删除占位项和刚创建的 info 文件，报告错误；如果失败是名称竞争/占位项被替换，换下一个名称重试；如果是原子移动不支持，直接失败，绝不回退普通 `Files.move`。
6. 成功后占位项已被目标替换，返回 receipt。
7. 崩溃可能留下空占位项或 info 文件；不会损坏原文件，也不会产生半复制文件。v1 不在启动时清理这些残留；这是可接受的临时垃圾。

- `Path=` 编码：

- home trash 使用绝对路径；卷级 trash 使用相对于 `$topdir` 的路径（不包含 `..`）。规范原文对 `$topdir/.Trash/$uid` 的“相对目录”表述有歧义；glib/gvfs 实际按 `$topdir` 相对路径读取，本设计跟随这一事实标准，并把 gvfs/Nautilus 恢复 round-trip 列入未验证项。
- UTF-8 百分号编码；保留 `/` 和 URL unreserved 字符；编码 `%`、控制字符、空格、非 ASCII 字节。
- `DeletionDate` 使用本地时间、秒精度，格式 `yyyy-MM-dd'T'HH:mm:ss`（规范示例不带时区偏移）。
- 必须有 round-trip 测试：空格、`%`、换行、非 ASCII 文件名。

**规范中 SHOULD 但 v1 不做**

- `directorysizes` 缓存：规范是 SHOULD，不是 MUST；v1 不写，避免额外的并发/原子替换逻辑。文件管理器可以自行重建。

### 9.2 macOS：`~/.Trash` 与卷级 `.Trashes/<uid>`

**选择 Trash**

- 先用 `Files.getFileStore(target).type()`（或 `statfs` 的 `f_fstypename`）拒绝 `smbfs`、`nfs`、`afpfs`、`webdav`、`osxfuse` 等网络文件系统，抛 `NETWORK_VOLUME`；不得在 SMB/NFS 卷上创建 `.Trashes`。最终选中的 Trash 目录本身也要做同样的检查；任何一端是网络文件系统都拒绝。
- `$HOME` 必须是绝对路径。
- 目标与 home 在同一设备（`unix:dev`）时，使用 `$HOME/.Trash`；不存在时在 `move` 阶段创建（`0700`），并校验它不是符号链接、属于当前用户。
- 其他卷：向上找到同设备的最高目录作为卷根；使用 `$volume/.Trashes/$uid`。
  - `.Trashes` 必须不是符号链接；存在时校验 sticky bit（若文件系统暴露 POSIX mode），不存在时在 `move` 阶段尝试创建并设置 sticky（`01777`，若平台支持），`$uid` 目录不存在时创建（`0700`），校验所有者与权限；已有 `$uid` 目录不是 `0700` 时拒绝。
  - 卷根不可写、`.Trashes` 不可用、无法设置/校验 sticky、创建失败：**失败**，不退回复制到 home trash。
- 目标等于或包含将要使用的 Trash 目录时，失败。

**移动与名称**

- 名称冲突依次尝试 `name`、`name 2`、`name 3`（Finder 风格，便于用户识别）。
- 与 Linux 相同的两步：先在 Trash 中创建与目标同类型的空占位项预留名称，再用 `Files.move(target, placeholder, StandardCopyOption.ATOMIC_MOVE)` 原子替换占位项。`ATOMIC_MOVE` 跨设备会抛 `AtomicMoveNotSupportedException`，不做 copy。
- 同一设备上的 rename 保留 inode、扩展属性、ACL 和资源分支；不使用 `Files.copy`，因此不会丢失元数据，也不会出现半复制文件。
- 移动前再次确认设备一致，作为早期错误；最终保证仍由 `ATOMIC_MOVE` 提供。
- 失败时删除占位项；名称竞争则尝试下一个名称；`AtomicMoveNotSupportedException` 直接失败，绝不回退普通 `Files.move`。
- 崩溃可能留下空占位项；不会损坏原文件，也不会产生半复制文件。

**已知限制**

- Finder 的 “Put Back” 是 Finder 自身维护的行为，第三方程序移动进 Trash 的项目不保证出现 Put Back；v1 不承诺。项目仍然会出现在 Trash 中，可由用户手动移回。
- 受 TCC/Full Disk Access 保护的目录可能拒绝访问；报错，不做绕过。

### 9.3 Windows：Shell32 / Recycle Bin

参考：

- <https://learn.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-shfileoperationw>
- <https://learn.microsoft.com/en-us/windows/win32/api/shellapi/ns-shellapi-shfileopstructw>
- <https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getdrivetypew>

**前置拒绝**

- UNC 路径（`\\server\share`）。
- 用 `GetVolumePathNameW` 解析目标所在卷，再用 `GetDriveTypeW` 分类：
  - `DRIVE_REMOTE`：网络卷，拒绝。
  - `DRIVE_CDROM`、`DRIVE_UNKNOWN`、`DRIVE_NO_ROOT_DIR`：拒绝。
  - `DRIVE_FIXED`、`DRIVE_REMOVABLE`、`DRIVE_RAMDISK`：允许，但仍受后续 Recycle Bin 配额/策略影响。
  - 分类失败：fail closed。
- 绝对路径长度达到 `MAX_PATH`（260）或带 `\\?\` 前缀：报 `PATH_TOO_LONG`，不使用可能截断的路径。
- 目标为根目录/卷根。

**调用**

- FFM 调用 `shell32!SHFileOperationW`，`FO_DELETE`。
- `SHFILEOPSTRUCTW` 的 64 位布局必须由 `MemoryLayout.structLayout` 明确写出，并用测试锁定字段偏移。x64 与 Windows arm64 都是 LLP64，偏移相同：
  - `hwnd`：pointer，偏移 0
  - `wFunc`：int，偏移 8
  - 4 字节 padding（12–15）
  - `pFrom`：pointer，偏移 16
  - `pTo`：pointer，偏移 24
  - `fFlags`：short，偏移 32
  - 2 字节 padding（34–35）
  - `fAnyOperationsAborted`：int，偏移 36
  - `hNameMappings`：pointer，偏移 40（36 + 4 已满足 8 字节对齐，不能再加 padding）
  - `lpszProgressTitle`：pointer，偏移 48
  - 结构体大小：56 字节
- `pFrom` 是 UTF-16、双 NUL 结尾的绝对路径；`pTo = NULL`；`hwnd = NULL`。
- 标志：
  - `FOF_ALLOWUNDO`：请求移入 Recycle Bin。
  - `FOF_NOCONFIRMATION`：dukit 已经确认过，不再显示确认框。
  - `FOF_SILENT`：不显示进度框。
  - `FOF_NOERRORUI`：错误由 dukit 报告。
  - `FOF_WANTNUKEWARNING`：如果系统准备永久删除而不是回收，要求警告；这个标志不能省。
  - `FOF_NO_CONNECTED_ELEMENTS`：不要因为 HTML 关联文件而删掉目标之外的内容。
- 不使用 `FOF_NO_UI`：MSDN 只把它定义为 `FOF_SILENT | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_NOCONFIRMMKDIR` 的简写；它是否会抑制 nuke warning 未验证，因此不采用这种“看起来更安静”的组合。
- 每次调用只处理一个目标，避免批处理中的部分成功无法与目标对应。
- 返回值：`0` 且 `fAnyOperationsAborted == FALSE` 才算成功；否则映射常见错误码（取消、拒绝访问、路径无效、路径过长、文件太大等），未知码显示十六进制。
- 结果不确定：Shell 返回非零或 aborted 时，`TrashException.Outcome.UNKNOWN`；`DeleteService` 随后验证目标是否仍然存在。

**Native Image / 符号查找**

- Windows 的 `Linker.defaultLookup()` 只覆盖 C 运行库，找不到 `shell32.dll`/`kernel32.dll` 的导出。必须用 `SymbolLookup.libraryLookup("shell32", arena)` 和 `SymbolLookup.libraryLookup("kernel32", arena)`（或先 `System.loadLibrary` + `loaderLookup`），并在同一次操作的 `Arena` 生命周期内使用；不能依赖 `defaultLookup`。
- 现有 `reachability-metadata.json` 已有 `foreign.downcalls`。新增：
  - `SHFileOperationW`：`int(void*)`
  - `GetDriveTypeW`：`int(void*)`（与前者同签名；metadata 是集合，重复声明无害）
  - `GetVolumePathNameW`：`int(void*, void*, int)`
- `--enable-native-access=ALL-UNNAMED` 已在 `build-native.sh`；native 构建必须实际跑一次删除冒烟，而不是只验证启动。
- 所有 FFM 调用放在 TUI 的主执行线程；不跨线程共享 `Arena`。

**Reparse point / junction**

- JDK 25 的 `WindowsFileAttributes.isSymbolicLink()` 只在 reparse tag 为 `IO_REPARSE_TAG_SYMLINK` 时返回 true；junction 的 tag 是 `IO_REPARSE_TAG_MOUNT_POINT`，因此 `isSymbolicLink()` 为 false，而 `isDirectory()` 仍为 true，`isOther()` 为 true。**现有 `NioStatReader.kindOf` 先判断 `isDirectory()`，在 Windows 上会把 junction 当作普通目录继续遍历；这是删除功能的前置缺陷，必须先修复并加回归测试。**
- 删除 guard 的祖先/目标检查必须独立于扫描器：任何一级 `isOther()` 为真都拒绝，不能只检查 `isSymbolicLink()`。
- 目标子树含 reparse point 时，`SHFileOperationW` 的行为需要 native 测试：如果发现它会进入 junction 或删除链接目标，v1 在预览阶段拒绝整棵目录，并记录这一限制。该保守策略也会拒绝 OneDrive 占位目录等 reparse point 目标/祖先；用户可改用 Explorer。
- 该测试属于发布前必须完成的平台验收项，不能用 JVM 测试代替。

### 9.4 未知平台

`UnsupportedTrashStore.prepare` 永远抛 `UNSUPPORTED_PLATFORM`。不猜测、不退化。

---

## 10. 树更新

### 10.1 移除算法

给定 `ScanResult` 和绝对目标路径：

1. 计算 `Path relative = scan.path().relativize(target)`；必须是一段或多段名称，不能为空。
2. 从根开始按名称找到目标节点，同时记录祖先链。找不到、路径为空或路径不在根下时抛出内部异常，不返回半更新的树。
3. 从父节点移除目标；对每个祖先：
   - `size -= target.size`
   - `fileCount -= target.fileCount`
   - `dirCount -= (1 + target.dirCount)`
   - `errorCount -= target.errorCount`
   - `ownFileSize` / `ownFileCount` 不变（目标不是祖先的“直接文件”）
4. 复用未受影响的子树；只复制祖先链上的节点。
5. 从 `ScanResult.errors` 中移除路径位于目标之下的错误项。
6. 返回新的 `ScanResult`；`path` 不变。

这是纯函数，不读文件系统；测试覆盖根、父节点、目标只有一个子项、目标有错误、目标大小/计数为边界值等情况。

**硬链接边界**：扫描时硬链接内容按“首次出现”计一次，`DirEntry` 不记录每个文件的链接归属。删除目标后按目标聚合值扣减，是“相对本次扫描归属”的精确值，不等于重新扫描的结果：如果目标包含首次计数的内容，而 twin 在别处，剩余树会偏低。v1 不重扫；UI 只承诺“移入 Trash / 清空后才释放空间”，不承诺删除后的树总量等于新扫描。需要精确值就重新运行 dukit。

### 10.2 TUI trail 重建

- `TuiState` 增加 `selectedPath()`，返回 `currentPath` 下的选中项目绝对路径；没有选中项时返回空，删除入口拒绝并显示原因。
- 删除成功后调用 `replaceResult(updated)`：
  - 记录旧 trail 的目录名称序列和当前选中索引；
  - 从新根按名称重新找到每一级；
  - 如果当前目录仍存在，恢复 trail；否则回到根；
  - 选中索引保持不变，再 `clamp` 到新列表长度。
- 当前目录本身不会是被删目标（TUI 只删子项），所以 trail 的祖先都存在；防御性处理“不存在则回根”。

### 10.3 行式浏览器

- `Browser.result` 改为可替换的字段；trail 同样按名称重建。
- 成功删除后保留当前目录，选中索引 clamp，重新渲染。
- `currentPath()` 继续由 `result.path()` + trail 计算。

### 10.4 结果不确定与 stale

`DeleteService.execute` 在 `TrashStore.move` 抛错后必须验证：

- 目标已不存在（`Files.exists(target, NOFOLLOW_LINKS) == false`）：按成功处理。Shell 可能已经完成移动但返回了非零，或者用户/系统删除了它；树更新，状态行说明“未收到明确确认，但目标已不存在”。
- 目标仍存在：
  - `Outcome.NOT_STARTED`：树不变，报告原因。
  - `Outcome.UNKNOWN`：树可能已经部分改变；标记 `stale`，禁用后续删除，状态行提示“扫描结果可能已过期，请退出并重新运行 dukit”。
- stale 不阻止继续浏览；只阻止删除。v1 不引入 `r` 重扫，因为重扫需要把扫描参数/excludes 传进浏览器，属于独立的小功能。

---

## 11. 测试与验收

### 11.1 单元测试（JVM）

- **DeleteGuard**
  - 目标等于根、根外、`..` 逃逸、绝对路径、盘符相对路径、UNC：拒绝。
  - 目标不存在、最终项是符号链接、中间项是符号链接：拒绝。
  - 目标是文件、FIFO/socket/设备：拒绝（只有目录可删）。
  - 目标与父目录不同 `FileStore`（模拟挂载点）：拒绝。
  - 目标等于 Trash 根、位于 Trash 内部、包含 Trash：分别拒绝。
  - 目标树含不可读取的子目录：拒绝（无法完成挂载预检）。
  - 目录目标的预览扫描返回任何 `ScanError`：拒绝（I5）。
  - **TreeUpdater**
  - 移除叶子、移除中间节点、移除根的唯一子节点；祖先 `size`/`fileCount`/`dirCount`/`errorCount` 精确扣减。
  - `ownFileSize`/`ownFileCount` 不变。
  - `ScanResult.errors` 过滤正确。
  - 未受影响的子树对象复用（可用引用相等断言，避免无意义深拷贝）。
- **Linux Trash**
  - info 文件格式、`Path=` 百分号编码 round-trip（空格、`%`、换行、非 ASCII）。
  - 名称冲突递增。
  - `CREATE_NEW` 竞争处理。
  - 移动失败时 info 被清理。
  - `DeletionDate` 使用注入的 `Clock`，测试可确定。
- **macOS Trash**
  - 注入临时 `home`；`~/.Trash` 名称冲突。
  - 卷级 `.Trashes/<uid>` 路径选择（通过注入的设备/卷根查找器）。
- **Windows Trash**
  - `SHFILEOPSTRUCTW` 布局偏移/大小测试（0/8/16/24/32/36/40/48，size 56）；在 Windows 上运行。
  - 错误码映射；`fAnyOperationsAborted` 处理。
  - 路径长度/UNC/远程卷拒绝。
- **TUI**
  - `d` 进入确认，`y` 产生删除请求，`n`/Esc/`q`/Ctrl-C 取消。
  - 确认状态下导航键被忽略；中断手势只取消、不删除，取消后再次 `q` 才退出。
  - 删除后 trail/选中项/滚动位置正确。
  - 渲染器确认页宽度、截断、无 ANSI 时纯文本。
- **Line Browser**
  - `delete 3` 计划、输入 `delete` 确认、取消、越界、成功后重渲染。

### 11.2 平台集成测试

- **Linux**：`XDG_DATA_HOME` 指向 `@TempDir`；真实创建 `Trash/files`、`Trash/info`；验证移动、info 内容、冲突、权限、失败清理。不污染真实用户 Trash。
  - 卷级 Trash：通过注入的 `VolumeResolver`（mountinfo/文件系统类型/设备号）测试 `$topdir/.Trash` 的 sticky/owner/symlink 检查、`.Trash-$uid` 回退、topdir 相对 `Path=` 编码，以及 `NETWORK_VOLUME` 拒绝；不要求 CI 真的挂载 NFS。
  - 原子替换：验证占位项存在时 `ATOMIC_MOVE` 成功，跨设备模拟时返回 `AtomicMoveNotSupportedException` 而不是复制。
- **macOS**：`MacOsTrashStore` 注入 `@TempDir` 作为 home；验证 `~/.Trash` 移动。卷级路径用注入的 `VolumeResolver`（卷根/设备/fstype）测试；真实外接卷验证放入人工/发布清单。
- **Windows**：
  - 默认 JVM 测试用假 `TrashStore` 或只读校验；真实 Recycle Bin 测试用环境变量 opt-in，避免每次 `mvn test` 都往开发者回收站放文件。
  - 发布前在 Windows 11 上跑真实文件、目录、只读文件、被占用文件、超长路径、UNC/映射网络盘、junction 的矩阵。
  - 单独跑一次“Recycle Bin 配额不足/被策略禁用/超大文件”的场景，确认 `FOF_WANTNUKEWARNING` 的实际行为（对话框、aborted 或永久删除）；该结果写入发布说明，不能用 JVM 测试替代。

### 11.3 Native 验证

删除涉及 Windows FFM 和 macOS/Linux 的 `unistd` 行为，必须用 native 二进制验证：

- Linux/macOS/Windows：构建 native 后创建临时树，用 PTY 驱动全屏 TUI：
  1. 打开 `dukit <tmp>`，选中目标目录，按 `d`：确认页出现，目标仍存在。
  2. 按 `y`：目标消失，进程正常退出。
  3. 验证 Trash 文件存在（Linux/macOS 可按已知路径检查）；Windows 只验证原路径消失（Recycle Bin 位置由系统管理）。
  4. 清理测试产生的 Trash 文件。
- 行式浏览器回退路径（终端无法进入 raw mode 时）手动验证一次 `delete <n>` 流程。
- PTY 自动化不作为 CI 门槛；发布前在三个平台手动跑一次全屏 `d` 流程，并把结果记录在发布 PR。

### 11.4 未验证项必须明确

- Windows Recycle Bin 在超大文件/禁用回收站策略下的行为。
- Windows junction/reparse point 是否被 Shell 跟随。
- macOS 外接卷 `.Trashes` 权限与 Finder 显示。
- Linux 非标准挂载（bind mount、FUSE、网络文件系统）上的 Trash 可用性。
- Linux 卷级 Trash 的 `Path=` 是否被 gvfs/Nautilus 正确恢复（glib 事实标准 vs 规范歧义）。
- Finder Put Back 不承诺。

这些不能靠 JVM 测试或“看起来正确”的代码推断；发布说明必须列出实际验证过的平台与场景。

---

## 12. 实现计划（评审通过后）

0. **前置缺陷修复**
   - 修复 Windows 扫描器把 junction/reparse point 当作目录遍历的问题（`NioStatReader.kindOf` 的顺序/属性判断），补 Windows 回归测试；这是删除功能的前置条件，独立于删除提交。
   - 该修复必须早于 TUI 的任何树遍历，否则 junction 会在 guard 拒绝之前被扫到边界之外。
1. **核心纯逻辑**
   - `DeleteGuard`、`DeletePlan`、`DeleteTarget`、`DeleteResult`、`TreeUpdater`。
   - 先写失败测试：根/越界/符号链接/junction/挂载点/重叠目标/树扣减。
2. **Linux Trash**
   - `LinuxTrashStore`、info 编码、原子创建、冲突、失败清理。
   - 用临时 `XDG_DATA_HOME` 做集成测试。
3. **macOS Trash**
   - `MacOsTrashStore`、home/volume 选择、名称冲突。
   - 注入临时 home 的测试。
4. **Windows Trash**
   - FFM `SHFileOperationW`、`GetVolumePathNameW`、`GetDriveTypeW`；struct 布局与 native 元数据。
   - Windows JVM 测试 + opt-in 真实回收站测试。
5. **TUI**
   - `Key` 增加 `DELETE`/`CONFIRM`/`CANCEL`；`KeyParser` 映射 `d`/`y`/`n`，并把独立的 `Esc`（无后续序列字节）映射为 `CANCEL`。
   - `ScreenRenderer` 确认页/状态行/stale。
   - `TuiState.selectedPath()` / `replaceResult()`。
   - 确认态导航键忽略；`q`/Ctrl-C 在确认态取消，取消后再 `q` 才退出。
6. **行式浏览器**
   - `delete <n>`、确认、树替换、帮助文本。
7. **Native 与发布**
   - `scripts/smoke-native.py` 增加 dry-run + 实际删除 + 原路径消失断言。
   - 三个平台 native 构建与手动 TUI 验证。
8. **文档**
   - 更新 `site/dukit/SKILL.md`、`site/dukit/index.html`、`index_zh.html` 中“只读”的 FAQ。
   - 在 README 的 dukit 说明中加入“交互式移入 Trash”。
   - SemVer：功能新增，minor bump；由发布 PR 统一处理。

提交节奏：沿用项目约定，行为变更先提交失败测试，再提交最小实现；缺陷修复保持 test/fix 两个 commit。本次设计 PR 不包含生产代码。

---

## 13. 备选方案汇总

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| 系统 Trash | **采用** | 用户可恢复、符合预期、平台负责清空 |
| 永久删除 | v1 不采用 | 无恢复路径，风险最高 |
| dukit 自管 Trash | 不采用 | 用户不可见，生命周期/跨卷/恢复都要自建 |
| 外部 `trash`/`gio`/PowerShell | 不采用 | 运行时依赖、转义/版本/桌面会话差异 |
| `Desktop.moveToTrash` | 不采用 | Linux 不支持；AWT native image 在 macOS 有已知问题 |
| macOS `NSFileManager.trashItem` via FFM | v1 不采用 | 纯 JDK 的 `~/.Trash`/`.Trashes` 已满足系统 Trash 目录；ObjC FFM 增加 native 风险，且 Put Back 仍不保证 |
| Windows `IFileOperation` | 后续 | 更强的“只回收/失败”语义，但 COM+FFM 复杂度高；v1 用 `SHFileOperationW` 并记录限制 |
| CLI 删除子命令 | **不采用** | 删除的价值依赖扫描树的视觉上下文；脚本化删除最容易因变量为空/路径拼错误删，而这正是交互确认能挡住的 |
| TUI 增加文件节点 | 后续 | 会把树内存从目录数推到文件数，与现有扫描优化冲突 |

---

## 14. 评审清单

请评审人逐项确认或提出修改：

- [ ] **D1** 同意 v1 只走系统 Trash，不提供永久删除。
- [ ] **D2** 同意删除只有交互入口：TUI 确认页、行式浏览器输入 `delete`，确认前无副作用。
- [ ] **D3** 同意只删扫描树中当前目录的直接子项，不提供任何路径输入。
- [ ] **D4** 同意删除只在交互界面提供、只删目录，不提供 CLI 删除子命令。
- [ ] **D5** 同意 Windows v1 使用 `SHFileOperationW` 并接受 D9 的已知限制；还是要求先做 `IFileOperation`？
- [ ] **D6** 同意 macOS v1 用纯 JDK 的 `~/.Trash`/`.Trashes`，不引入 ObjC FFM。
- [ ] **D7** 同意失败关闭、绝不回退永久删除。
- [ ] **D8** 同意删除后只更新内存树，不自动重扫；不确定结果标记 stale。
- [ ] **D9** 同意 v1 不做恢复/清空 Trash。
- [ ] **D10** 同意实现顺序：先修复 Windows junction 扫描缺陷，再核心 -> Linux -> macOS -> Windows -> TUI -> native/文档。
- [ ] 确认 Windows junction/reparse point 的前置修复作为独立缺陷提交，并保留回归测试。
- [ ] 确认 Linux/macOS 的网络 fstype 拒绝，以及 Windows nuke warning 的实际行为，属于可接受的 v1 边界。
- [ ] 确认发布前必须完成三平台 native 冒烟和手动 TUI 验证。
- [ ] 确认站点/SKILL 的“只读”表述在实现 PR 中同步修改。

**评审结论**：`待评审` / `通过` / `需修改`

**评审人**：____________________  
**日期**：____________________  
**备注**：________________________________________________________________

---

## 15. 参考资料

- freedesktop.org Trash Specification 1.0：<https://specifications.freedesktop.org/trash/latest/>
- Apple `FileManager.trashItem(at:resultingItemURL:)`：<https://developer.apple.com/documentation/foundation/filemanager/trashitem(at:resultingitemurl:)>
- Microsoft `SHFileOperationW`：<https://learn.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-shfileoperationw>
- Microsoft `SHFILEOPSTRUCTW`：<https://learn.microsoft.com/en-us/windows/win32/api/shellapi/ns-shellapi-shfileopstructw>
- Microsoft `GetDriveTypeW`：<https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getdrivetypew>
- GraalVM Native Image FFM：<https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/>
- JDK 25 `XDesktopPeer`（Linux 不支持 `MOVE_TO_TRASH`）：<https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.desktop/unix/classes/sun/awt/X11/XDesktopPeer.java>
- GraalVM macOS AWT native image 已知问题：<https://github.com/oracle/graal/issues/13272>
