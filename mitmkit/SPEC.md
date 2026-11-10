# mitmkit CLI 契约规格 (SPEC v1)

> 本文件是 `mitmkit` 的权威行为契约。实现者与文档作者以它为准；接口变更先改这里。

`mitmkit` 是本地 HTTPS 抓包代理：一个 native 单文件、三平台行为一致的 MITM 代理，
专做开发调试——自动生成根 CA、动态签发叶子证书、把每条流量的 JSONL 记录写到 stdout，
让脚本和 agent 能直接消费。第一期只做显式代理模式（regular proxy），
不做 TUN/透明代理（不创建虚拟网卡）、不做 h2/WebSocket 多路复用。

证书的长期管理（生成、检查、转换、系统信任安装）由 `certkit` 负责；
`mitmkit` 只持有抓包所需的临时 CA，信任安装交给 `certkit trust install`。

## 1. 命令接口

```text
mitmkit [run] [--port PORT] [--host HOST] [--insecure] [--trust-local] [--system-proxy]
                [--url URL] [--header NAME:VALUE]... [--cookie NAME=VALUE]... [--body BODY]
                [--response-header NAME:VALUE]... [--response-body BODY] [--match SUBSTR]
                [--rewrite RULE]... [--record-body] [--record-headers] [--jsonl] [--json] [--no-color]
mitmkit ca [--force] [--json]
mitmkit --help
mitmkit --version
```

- 裸调用等价于 `run`，直接起代理，默认 `--port 8080`。
- `run` 是长驻进程：Ctrl+C 正常退出（返回 `0`）；端口被占用等启动失败返回 `1`。
- CA 目录默认 `~/.mitmkit/ca/`（`ca.crt` + `ca.key`），env `MITMKIT_CA_DIR` 覆盖。
  CA 独立于 certkit（不共用 `~/.certkit`），卸载互不影响。
- `ca` 子命令生成/检查 CA，不装系统信任；已存在时默认拒绝（返回 `1` 并提示），`--force` 重建。
- `run` 启动时若 CA 不存在则自动生成，打印提示行到 stderr（信任安装仍由 certkit 负责）。
- `--system-proxy` 在运行期间设置 OS 系统代理（macOS networksetup / Windows 注册表 / Linux gsettings），
  退出时恢复；设置失败不阻止启动。
- `--trust-local` 对回环上游（127.0.0.1、localhost、::1）跳过证书校验，其他上游仍校验。
- `--rewrite RULE` 可重复，声明高级改写规则（见下）。
- 简单改写 flag（整体替换，人工调用首选）：
  - `--url URL` 把请求 URL 整体替换为 URL
  - `--header NAME:VALUE` 请求头，可重复；同名头有则替换、无则追加
  - `--cookie NAME=VALUE` Cookie，可重复；合并进现有 Cookie 头
  - `--body BODY` 把请求体整体替换为 BODY
  - `--response-header NAME:VALUE` 响应头，可重复
  - `--response-body BODY` 把响应体整体替换为 BODY
  - `--match SUBSTR` 只对 URL 包含 SUBSTR 的请求应用上述简单 flag

## 2. 输出

- 人类可读模式（默认）：stdout 一行一条 flow：
  `<id> <method> <url> <status> <durationMs>ms <reqBytes>B/<resBytes>B`
  错误 flow 记为 `<id> <method> <url> ERROR <message>`。
- `--jsonl` 模式：stdout 每行一个 flow 的 JSON 对象，字段：
  `{id, ts, method, url, status, durationMs, reqBytes, resBytes, reqHeaders, resHeaders, reqBody, resBody, error}`
  - `status` 为整数或 `null`（连接/解析失败时）。
  - `--body` 未开启时 `reqBody`/`resBody` 省略（不出现该键）。
  - `--body` 开启时记录请求/响应体，gzip 自动解码显示，单个 body 超 256KB 截断并标注 `[truncated]`。
  - 默认只记元数据（不记 header/body），避免敏感信息落盘到 stdout。
- `--json` 仅用于 `ca` 子命令的结构化输出，不用于 `run`（`run` 用 `--jsonl`）。
- 代理自身的启动/提示/错误信息走 stderr；流量记录走 stdout，互不混杂。

## 3. 代理行为

- 监听 `<host>:<port>`，默认 `127.0.0.1:8080`。`--host 0.0.0.0` 允许局域网设备接入。
- 支持 HTTP（绝对 URI）与 HTTPS（CONNECT 隧道）两条路径。
- CONNECT：先与客户端完成 TLS（用 CA 动态签发的叶子证书，SAN=目标 host），
  再连接上游；上游证书默认校验，`--insecure` 跳过校验（自签上游场景）。
- 伪造证书只协商 `http/1.1` ALPN；客户端请求 h2 时回落到 http/1.1。
- 上游不可达、证书校验失败、客户端中断：记一条 ERROR flow（或仅 stderr 诊断），不崩溃。
- 并发连接用虚拟线程（thread-per-connection），无连接数硬上限。

## 3.1 改写规则

`--rewrite RULE` 用分号分隔的键值对声明一条规则，可重复：

| 键 | 含义 | 示例 |
| --- | --- | --- |
| `method` | 只改写匹配方法的请求（默认 `*`） | `method:POST` |
| `url` | URL 包含匹配串；带 `->` 时同时声明替换 | `url:/v1->/v2` |
| `setUrl` | 把 URL 整体替换 | `setUrl:https://staging.example.com` |
| `reqHeader` | 请求头（有则替换、无则追加） | `reqHeader:X-Debug:1` |
| `resHeader` | 响应头 | `resHeader:X-Mitm:yes` |
| `cookie` | Cookie（合并进现有 Cookie 头） | `cookie:session=abc` |
| `body` | 请求体整体替换 | `body:{"user":"admin"}` |
| `resBody` | 响应体整体替换 | `resBody:{"ok":true}` |

规则按声明顺序应用；`url`/`setUrl` 替换会影响上游 host/port 的解析。

## 4. CA

- `mitmkit ca`：生成 ECDSA P-256 根 CA（CN=`mitmkit local CA`，有效期 10 年），
  写入 CA 目录；`--json` 输出：`{caCert, caKey, subjectCn, sha256Fingerprint, notAfter}`。
- CA 私钥文件权限 0600（POSIX）。
- 信任安装不在 mitmkit 范围：用 `certkit trust install ~/.mitmkit/ca/ca.crt`。

## 5. 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功（含 `--help`/`--version`、代理正常退出） |
| `1` | 业务失败：CA 生成失败、端口占用、上游/证书错误 flow |
| `2` | 用法错误（参数解析失败由 CliRunner 统一处理） |

交换失败时代理向客户端返回 `502 Bad Gateway`，并记录一条 error flow。

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功（含 `--help`/`--version`、代理正常退出） |
| `1` | 业务失败：CA 生成失败、端口占用、上游/证书错误 flow |
| `2` | 用法错误（参数解析失败由 CliRunner 统一处理） |

## 6. 依赖与限制

- `org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on` 1.81（Bouncy Castle Licence，类 Apache-2.0）：
  进程内 X.509 证书生成与动态签发，避免 openssl 子进程进入热路径。
- 不依赖 certkit 模块（工具间互不依赖，沿用 dukit/jsonkit 惯例）。
- 网络库、HTTP/2、WebSocket、透明模式、CA 私钥导出均不在第一期范围。

## 7. 分发

- 模块目录、Maven artifact、Native 文件名和安装目标均为 `mitmkit`；Windows 为 `mitmkit.exe`。
- 支持 macOS ARM64、Linux AMD64、Windows AMD64。
- Shell、PowerShell、Homebrew、Scoop 均安装正式命令 `mitmkit`；缩写由用户自行设置别名。
