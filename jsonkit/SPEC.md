# jsonkit CLI 契约规格 (SPEC v1)

> 本文件是 `jsonkit` 的权威行为契约。完整设计动机与验收表见
> [JSONkit 设计规格](../docs/superpowers/specs/2026-11-06-jsonkit-design.md)。

## 1. 命令接口

```text
jsonkit [FILE|-] [-p PATH | --path PATH] [--no-color] [--debug]
jsonkit --help
jsonkit --version
```

- 文件和查询是独立参数，查询可放在文件前后；`--` 结束选项解析。
- 只读本地文件或 stdin；结果写 stdout，诊断写 stderr。
- 有 `--path` 且无文件时读取 stdin。stdin 是交互终端时返回用法错误，避免误等输入。
- 无文件、无路径且 stdin 是交互终端时显示帮助并返回 `0`。
- `-` 显式读取 stdin，包括从终端输入直到 EOF。
- stdout 是否高亮只取决于 stdout 终端状态；`--no-color` 或存在 `NO_COLOR` 时禁用 ANSI。

## 2. 输入与输出

- 每次读取一份 UTF-8 JSON 文档，接受 UTF-8 BOM；拒绝空输入、截断、第二个根值和尾部非空白内容。
- 格式化使用两空格缩进、LF 换行、末尾一个 LF；空对象和空数组输出 `{}` 和 `[]`。
- 保持对象成员顺序和数字字面量；不把大整数或高精度小数重写成近似值。
- 查看允许重复对象键；查询遇到重复键返回 `1`。
- 查询在输入校验和求值完成后才写结果；查看可流式输出，因此迟到的错误可能留下已写前缀。

### 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功输出 JSON，或显示帮助、版本；多值查询无匹配也输出 `[]` |
| `1` | 文件读取失败、JSON 不合法、查询输入有重复键、单值路径无匹配、资源上限 |
| `2` | CLI 用法错误或 JSONPath 表达式无效 |

## 3. 查询语义

首版使用 RFC 9535 语义，支持字段名、数组下标、通配符、切片、选择器列表、递归后代、条件过滤和标准过滤函数。

简写只在 CLI 入口补根节点：

| 输入路径 | 交给引擎的路径 |
| --- | --- |
| `user.name` | `$.user.name` |
| `.user.name` | `$.user.name` |
| `[0].id` | `$[0].id` |
| `['a.b']` | `$['a.b']` |
| `items[?@.status == 'failed'].id` | `$.items[?@.status == 'failed'].id` |

输出形状由解析后的表达式决定：

- 只含根、字段名、下标的单值路径输出选中的 JSON 值；缺失返回 `1`。选中的值是数组时直接输出该数组。
- 含通配符、切片、多个选择器、后代查询或过滤的路径输出匹配值数组；无匹配输出 `[]`，单个 `null` 输出 `[null]`。
- 对象成员按输入顺序遍历，保证同一输入在各平台得到一致顺序。

## 4. 依赖与限制

- `jackson-core` 读取 token、流式写出并构建有序值树；`io.github.treetrail:jsonpath-core:1.0.0`（Apache-2.0）执行 RFC 9535 查询。
- 查询上限：10,000,000 个访问节点、1,000,000 个结果节点、嵌套深度 1,000。超过上限返回 `1`。
- JSON 解析嵌套深度上限为 1,000；超过上限返回 `1`。

## 5. 分发

- 模块目录、Maven artifact、Native 文件名和安装目标均为 `jsonkit`；Windows 为 `jsonkit.exe`。
- 支持 macOS ARM64、Linux AMD64、Windows AMD64。
- Shell、PowerShell、Homebrew、Scoop 均安装正式命令 `jsonkit`；`jk` 由用户自行设置别名。
