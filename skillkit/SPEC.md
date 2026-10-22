# skillkit 当前契约

本文件只描述已实现的模块骨架。目标功能见 [MIGRATION.md](MIGRATION.md)，其中的业务命令尚未注册。

| 调用 | 行为 | 退出码 |
| --- | --- | --- |
| `skillkit` | 向 stdout 打印帮助 | 0 |
| `skillkit --help` / `-h` | 向 stdout 打印帮助 | 0 |
| `skillkit --version` / `-V` | 输出 `skillkit <根 POM 版本>` | 0 |
| `skillkit --no-color` | 接受公共选项，裸调用输出无 ANSI 的帮助 | 0 |
| 未知参数或未实现的子命令 | 向 stderr 输出 usage 错误 | 2 |
| 执行失败 | 通过 `CliRunner` 向 stderr 输出错误 | 1 |

公共选项 `--json`、`--quiet`、`--no-color`、`--debug` 来自 `GlobalOptionsMixin`，可继承给后续子命令。当前没有业务结果或进度；`--json` 和 `--quiet` 不改变帮助、版本输出。

帮助和 usage 错误在重定向、`NO_COLOR` 存在（包括空值）和 `--no-color` 时不输出 ANSI。当前骨架不扫描 agent，不访问网络，不修改任何 skill、源仓库、配置或 lockfile。

入口：`io.github.lihuu.skillkit.app.CliApplication`。生产入口与可注入 stdout/stderr、环境、交互状态的测试入口均使用同一 `CliCommand` 和 `CliRunner`。
