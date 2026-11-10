# dukit

> **跨平台磁盘占用分析利器：全屏交互浏览、流式有界排行、结构化 JSON 树——终结“磁盘空间去哪了”的迷茫。**  
> 专为现代开发者与自动化 Agent 设计的磁盘占用分析工具，基于 Java 25 与 GraalVM Native Image 编译，零运行时依赖，三端系统平权。

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/dukit/index_zh.html"><b>在线展示页</b></a> •
  <a href="../README_zh.md"><b>li-tools 主仓库</b></a>
</p>

---

## ⚡ 为什么需要 dukit？

定位磁盘空间与大文件时，现有工具往往难以兼顾开发交互与自动化需求：
- **`ncdu` 的跨平台痛点**：深度绑定 ncurses，Windows 上无法原生直接运行，且默认交互难以快速导出结构化数据供给 Agent 与自动化脚本消费；
- **`du` 的输出与平台分歧**：在 macOS (BSD) 与 Linux (GNU) 上参数不兼容；大量稀疏文件（Sparse Files）与 APFS 写时复制（CoW）导致表观大小与物理磁盘占用相差几个数量级；
- **目录树庞大时内存与输出爆炸**：传统工具在遍历数十万文件时，逐文件构建节点导致 JSON 体量膨胀到上百兆，极难塞进 LLM / Agent 上下文。

`dukit` 将这些问题彻底收敛：
- **纯流式无反射 JSON 树**：树结构**仅包含目录**，普通文件折叠汇总为统计字段，即使扫描整盘也能保持轻量；
- **默认真实磁盘占用**：通过 FFM 读取平台原生 `st_blocks`，与 `du`、Finder 实际占用完全对齐；
- **全屏双缓冲交互 TUI**：跨平台（macOS、Linux、Windows 11 控制台）一致的交互体验，支持上下方向键、Enter 下钻、Backspace 返回、`s` 排序、`t` 展开直属文件预览、`d` 安全移入回收站；
- **流式有界排行 (`top`)**：内存占用仅与 `--limit` 线性绑定，不构建全量树即可毫秒级回答“哪里最大”。

---

## 🚀 30 秒上手指南

### 1. 默认交互手势（终端直接敲，自动全屏）
```bash
# 全屏交互浏览当前目录
dukit

# 全屏交互浏览指定目录
dukit /Applications
```

> **提示**：在管道模式下（例如 `dukit | jq .`），`dukit` 会智能自动降级输出结构化 JSON，绝不卡死。

### 2. 导出精简机器可读 JSON 树 (`scan`)
```bash
# 默认输出完整子树的纯流式 JSON（仅含目录节点）
dukit scan ~/Projects

# 深度截断：仅输出前 2 层子目录，但尺寸始终覆盖完整底层子树（仅 ~30 KB）
dukit scan ~/Projects --depth 2

# 切换为人类可读文本缩进树
dukit scan ~/Projects --text

# 排除指定目录或通配模式
dukit scan . --exclude node_modules --exclude .git
```

### 3. 流式有界排行 (`top`)
```bash
# 找出最大的 20 个目录（内存恒定）
dukit top ~/Projects

# 找出最大的 20 个独立大文件
dukit top ~/Projects --files

# 仅输出至少 1 GB 的大项，并以 JSON 格式输出
dukit top ~/Projects --min-size 1G --json
```

---

## ⌨️ 全屏交互浏览器快捷键 (`browse`)

在终端输入 `dukit` 或 `dukit browse` 即进入全屏浏览器（基于 JLine FFM 双缓冲）：

| 按键 | 说明 |
| :--- | :--- |
| `↑` / `k` | 光标上移一行 |
| `↓` / `j` | 光标下移一行 |
| `Enter` / `→` / `l` | 进入选中的子目录 |
| `Backspace` / `←` | 返回上一级父目录 |
| `Home` / `End` | 快速跳至顶部 / 底部 |
| `PageUp` / `PageDown` | 向上 / 向下翻整页 |
| `s` | 循环切换排序：大小 (Size) → 名称 (Name) → 文件数 (Count) |
| `t` | **展开 / 收起当前目录直属前 10 大文件预览**（暗色显示，便于排查） |
| `d` | **安全移入系统回收站**（弹出全屏确认对话框，按 `y` 移入，绝不永久硬删） |
| `q` / `Ctrl+C` | 退出全屏交互，干净恢复主屏幕 |

---

## 📊 尺寸语义口径说明

| 字段 | 含义 | 说明 |
| :--- | :--- | :--- |
| **`diskSize`** | **实际磁盘占用** | 默认排序与展示口径。通过 FFM 读取 `st_blocks`，与 `du -s` 及系统文件管理器完全一致。Windows 优雅回退为逻辑大小。 |
| **`size`** | **表观/逻辑大小** | 所有文件逻辑长度之和。可通过 `--apparent-size` 在文本树中切换为优先展示。 |

- **硬链接**：同一扫描内相同 inode/fileKey 的内容仅计一次磁盘占用，所有硬链接正常计入文件总数；
- **符号链接**：永不递归深入跟踪，不计入子目录占用。
- **卡死目录与挂载点**：不再应答的目录列表或条目属性读取（云盘文件提供者守护进程无响应、NFS/SMB/WebDAV 网络挂载失联、FUSE 挂载挂起）只要连续 2 秒一条结果都产不出，就按错误跳过，扫描不会因此挂死；整体一条结果都产不出时同样终止。判据是"静默"而不是"总耗时"，所以慢盘上一直在出结果的大目录会完整扫完，不会被误杀。结果与错误在该预算内全部给出；进程本身要等操作系统释放那个被阻塞的调用才能退出（用户态无法取消它），重启挂载或卸载（macOS：`umount -f <挂载点>`，Linux：`umount -l <挂载点>`，Windows：`net use <盘符>: /delete`）可立即释放。

---

## 📦 安装与分发

```bash
# 一行脚本安装 (macOS & Linux)
curl -fsSL https://tools.lihu.me/install.sh | bash -s dukit

# Windows 11 PowerShell 一行脚本
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew (macOS & Linux)
brew install lihuu/tap/dukit

# Scoop (Windows 11)
scoop install dukit
```
