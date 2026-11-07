# jsonkit

> **一条命令完成 JSON 格式化、高亮和查询。**  
> 基于 Java 25 与 GraalVM Native Image 构建，零运行时依赖，macOS、Linux、Windows 11 共用同一个原生二进制。

<p align="center">
  <a href="README.md"><b>English</b></a> •
  <a href="README_zh.md"><b>简体中文</b></a> •
  <a href="https://tools.lihu.me/jsonkit/"><b>落地页</b></a> •
  <a href="../README_zh.md"><b>li-tools 套件</b></a>
</p>

---

## ⚡ 为什么需要 jsonkit？

打开 JSON 不应该先背一串工具组合：

- `cat file.json | jq .` 依赖 jq，Windows 上经常需要额外安装。
- `python -m json.tool` 会重写数字，也不能按路径取值。
- 为了取一个字段临时写脚本，比问题本身还慢。

`jsonkit` 让人和 Agent 使用同一个起手式：

```bash
jsonkit response.json
```

默认用两空格缩进格式化，终端支持 ANSI 时自动高亮。下一步要取某个路径的值，只需加 `-p`，仍然是同一条命令。没有子命令，不需要切换模式，也不用第二个工具。

## 🚀 快速开始

```bash
# 格式化文件；只有 stdout 是终端时才显示颜色
jsonkit response.json

# 从管道读取
curl -s https://api.example.com/users | jsonkit

# 取单个值
jsonkit response.json -p user.name

# 取多个值
jsonkit -p 'items[*].id' response.json

# 使用标准 JSONPath 条件过滤
jsonkit -p "items[?@.status == 'failed'].id" response.json
```

正式命令是 `jsonkit`。更短的 `jk` 由用户按需在自己的 Shell 中设置别名：

```bash
alias jk=jsonkit
```

## 📦 安装

```bash
# Shell 安装器（macOS 与 Linux）
curl -fsSL https://tools.lihu.me/install.sh | bash -s jsonkit

# PowerShell（Windows 11）
irm https://tools.lihu.me/install.ps1 | iex

# Homebrew（macOS 与 Linux）
brew install lihuu/tap/jsonkit

# Scoop（Windows）
scoop install jsonkit
```

确认安装：

```bash
jsonkit --version
```

## 📖 用法

```text
jsonkit [FILE|-] [-p PATH | --path PATH] [--no-color] [--debug]
jsonkit --help
jsonkit --version
```

| 调用 | 行为 |
| --- | --- |
| `jsonkit response.json` | 缩进并显示整份 JSON |
| `jsonkit response.json -p user.name` | 显示命中的单个 JSON 值 |
| `jsonkit -p '$.items[0].id' response.json` | 通过完整 JSONPath 显示第一个 ID |
| `other-tool --json \| jsonkit -p 'items[*].id'` | 从 stdin 读取并输出匹配值数组 |
| `jsonkit -p "items[?@.status == 'failed'].id" response.json` | 条件过滤并输出匹配的 ID 数组 |
| `jsonkit` | stdin 为管道或重定向文件时读取 stdin；stdin 接交互终端时显示帮助 |
| `jsonkit -` | 显式从 stdin 读取，包括从终端输入直到 EOF |

### 单值与数组

只由根节点、字段名和数组下标组成的路径是**单值路径**，直接输出选中的 JSON 值：

```bash
$ jsonkit -p user.name response.json
"Alice"

$ jsonkit -p user.missing response.json
jsonkit: path "$.user.missing" matched no value   # 退出码 1

$ jsonkit -p items response.json
[
  {"id": 1},
  {"id": 2}
]
```

含通配符、切片、选择器列表、递归后代或条件过滤的路径输出**匹配值数组**，即使只命中一个值也一样：

```bash
$ jsonkit -p 'items[*].id' response.json
[
  1,
  2
]

$ jsonkit -p 'items[*].missing' response.json
[]                                                # 退出码 0
```

### 简写路径

CLI 只在入口为三种常用写法补根节点：

| 你写的路径 | 交给引擎的路径 |
| --- | --- |
| `user.name` | `$.user.name` |
| `.user.name` | `$.user.name` |
| `[0].id` | `$[0].id` |
| `['a.b']` | `$['a.b']` |
| `items[?@.status == 'failed'].id` | `$.items[?@.status == 'failed'].id` |

补根节点之后完全遵循 [RFC 9535](https://www.rfc-editor.org/rfc/rfc9535.html)：字段名、数组下标、通配符、切片、选择器列表、递归后代、条件过滤，以及标准 `length()`、`count()`、`match()`、`search()`、`value()` 函数。查询引擎为 [Treetrail](https://github.com/treetrail/treetrail) 1.0.0（Apache-2.0），通过 [JSONPath 一致性测试集](https://github.com/jsonpath-standard/jsonpath-compliance-test-suite) 的全部 715 个用例。

## 🧭 引擎选型

查询引擎锁定为 **Treetrail `jsonpath-core` 1.0.0**：

- 许可：Apache-2.0。
- 运行时依赖：仅 `org.jspecify:jspecify` 1.0.1（注解）。
- 一致性：通过 JSONPath 一致性测试集 commit `20ebdcc`（2026-10-13）的全部 715 个用例，包含 RFC 9535 的五个标准函数。
- Native Image：纯 Java，无反射与资源元数据；仅登记 FFM `isatty` downcall。macOS ARM64、GraalVM CE 25.5.5.0.3、默认 O2 下，原始原生二进制为 15,051,816 字节（14.35 MiB）。
- 上限：访问 10,000,000 个节点、返回 1,000,000 个结果节点、嵌套 1,000 层；超过任一上限返回 `1`。

查询路径使用同一份 Java 值树：对象为 `LinkedHashMap`，数组为 `ArrayList`，数字为以 `BigDecimal` 为后端的 `JsonNumber`。

## 🎨 输出契约

- 两空格缩进、LF 换行、末尾一个 LF。
- 空对象和空数组输出 `{}` 和 `[]`。
- 保持对象成员原始顺序。
- 保留数字字面量：`123456789012345678901234567890` 和 `1.2300` 不会被舍入或改写。
- 接受带或不带 BOM 的 UTF-8 输入。
- 查看时显示重复对象键；查询含重复键的输入返回退出码 `1`。
- 诊断包含文件名（或 `<stdin>`）以及行列位置。
- 是否高亮只取决于 **stdout** 是否为终端。`--no-color`、设置 `NO_COLOR` 或重定向 stdout 都输出无 ANSI 的 UTF-8 JSON。
- stdin 和 stdout 的终端状态分别判断：终端可以读取管道输入并保留高亮；stdout 被重定向时即使 stdin 是终端也不输出颜色。

## 🚦 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功输出、显示帮助或版本；多值查询无匹配也输出 `[]` |
| `1` | 读取失败、JSON 不合法、查询输入含重复键、单值路径无匹配、资源上限 |
| `2` | CLI 用法错误或 JSONPath 表达式无效 |

## 🛡️ 资源上限

查询超过上限时返回退出码 `1` 并给出诊断，而不是耗尽进程：

- 最多访问 10,000,000 个节点
- 最多返回 1,000,000 个结果节点
- 文档嵌套深度最多 1,000 层

JSON 解析同样使用 1,000 层嵌套上限。

## 🔧 从源码构建

需要 Java 25 与 GraalVM Native Image。

```bash
# 在仓库根目录
mvn -pl jsonkit -am test
./jsonkit/build-native.sh
jsonkit/target/jsonkit --version
```

原生可执行文件位于 `jsonkit/target/jsonkit`（Windows 为 `jsonkit.exe`）。FFM `isatty` downcall 已登记在 `META-INF/native-image/io.github.lihuu/jsonkit/reachability-metadata.json`；Native 构建同时传入 `--enable-native-access=ALL-UNNAMED`。

## 🤖 Agent 常用配方

```bash
# 先看未知响应的整体结构，避免一次倒入全部内容
curl -s "$API" | jsonkit -p '$'

# 取出一个字段供 Shell 变量使用
TOKEN=$(curl -s "$API" | jsonkit -p access_token)

# 提取失败任务的 ID，交给下一步处理
curl -s "$API" | jsonkit -p "jobs[?@.status == 'failed'].id" > failed.json

# 提交前统一格式化
jsonkit package.json > package.pretty.json && mv package.pretty.json package.json
```
