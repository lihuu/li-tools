# gpgkit — 规格 v1（契约）

> 这个文件是**接口契约**。实现者和文档作者都以它为准；任何要改接口的地方，先改这里。
> 目的：把 GPG 的一生（生成 / 备份 / 还原 / 续期 / 签名 / 验签 / 发布公钥 / 自检）收进**一个命令**，
> 让"换一台机器就丢密钥"这件事消失。

## 0. 为什么存在

以前不用 GPG，就是因为它太散：在一台机器上建好一对密钥，换一台机器就可能把它丢掉，
所以我一直没真正用起来。这个工具要把 GPG 的一生收进一个命令里，让我能把它统一管起来。

所以本工具的第一等公民**不是**"签名"，是**备份与还原的可验证性**：任何一次备份，都必须当场证明它**能被还原**（回环校验），任何一次还原，都必须当场证明它**能签出可被验证的签名**。

## 1. 形态

- 仓库：`~/git/gpgkit`（独立 git 仓库；GitHub 远端可选，暂不建）。
- 实现：**Java 25 + GraalVM native image**，目标产物是**一个可执行文件**（`./target/gpgkit`），
  拿到就能用，不要求目标机器装 JDK。
- **依赖尽量为零**（native image 最怕反射与 ServiceLoader）：
  只用 JDK 自带 API；JSON 输出**手写**一个最小序列化器，不引 Jackson。
- 它**不重新实现密码学**：`gpg` 是本工具的引擎，`openssl` 只在需要随机口令时用。
  本工具做的是**编排 + 校验 + 记录**（以及把 gpg 那些容易忘的参数固定下来）。
- 外部命令一律**显式参数、非交互**（`--batch --yes --pinentry-mode loopback` 等），
  避免"跑起来弹一个 pinentry 然后卡住直到超时"。

## 2. 命令面（这是契约；实现者不许加参数不写进这里）

全局：`--json`（机器可读输出）、`--quiet`、`--help`。
**退出码**：`0` 成功；`1` 失败（原因写 stderr）；`2` 用法错误。

**环境变量**（只有这两个；要加第三个先写进这里，别偷偷加）：
- `GPGKIT_BACKUP_DIR` —— 覆盖备份目录缺省值。存在的理由：`doctor` 按规格不带参数，却要能回答"备份在不在"。
- `GPGKIT_PASSPHRASE_FILE` —— 覆盖口令文件缺省值。理由：**口令不走命令行**（会进 shell history）。
  缺省路径 = `~/.gpgkit/backup-passphrase.txt`，**并且必须落在 `Defaults` 里** —— 否则 `doctor` 那条"备份能不能解密"的检查永远够不着它。

**`manifest.json` 的键名**（`backup` 写，`restore`/`doctor` 读；`--json` 输出沿用同一套键）：
`fingerprint`、`userIds`、`algorithm`、`created`、`expires`、`cipher`、`sha256`（密文用）、
`backedUpAt`、`passphraseStorage`（人写的备注：口令**存在哪**，**不是口令本身**）、`restoreCommand`、`destinations`（这份备份的每个副本落在哪）。

### `gpgkit status`
```
gpgkit status [--refresh]
```
列出本机密钥：指纹、算法/长度、创建日、**到期日与剩余天数**、用法（SC/E）、是否有私钥、
公钥在 keyserver 上是否可见（默认读取本地缓存以保证毫秒级响应，`--refresh` 强制请求远端；尽力而为，失败不影响退出码 0）。
末尾给**一句话结论**：`OK` / `WARN: 90 天内到期` / `WARN: 没有可用签名密钥`。

### `gpgkit gen`
```
gpgkit gen [--uid "名字 <邮箱>"] [--expire 2y] [--algo rsa4096|ed25519]
           [--passphrase-file F | --no-passphrase] [--publish]
```
- **没有 `--yes`**：`gen` 从不提问，所以不需要它。
- 默认：`rsa4096`、`2y`、**无口令**（为了无头签名能跑，见 §4 的口径），`--uid` 缺省时从 git config 取。
- 生成后必须打印：**指纹全文**（不是短 id）、创建/到期日，并提示下一步该做 `backup`（**没备份不许走**）。
- `--publish`：生成后把公钥推到 keyserver（见 `publish`）。

### `gpgkit backup`  ← 本工具的核心
```
gpgkit backup [--key <id|all>] [--out DIR] [--passphrase-file F | --generate-passphrase-to F]
              [--dest <spec>]...
```
缺省值：`--out` = `~/.gpgkit/backups`；`--key` = 那把唯一的可用签名密钥（不止一把时报错并要求指名，别猜）。
**没有 `--no-verify`**：回环校验不是选项，是这个命令存在的理由（§3.2）。

1. 导出：secret（armored）+ public + **ownertrust/trustdb 的相关条目**（还原时 trust 状态要能复原）。
2. 加密：**默认 AES256 对称加密**，口令来自 `--passphrase-file`，或用 `--generate-passphrase-to F`
   由本工具生成随机口令**写进 F**（**绝不打印到 stdout**）。
   **没有「不加密」这个选项** —— 备份永远加密。理由：一旦允许明文，「回环校验」就退化成自己跟自己比，等于用一个空洞的检查换来形式的合规；而未加密的私钥副本一旦落盘，就等于把身份复制了一份。
3. **回环校验（不可跳过）**：解密回来与原始导出**逐字节比对**；不一致 → 失败退出，不写目的地。
4. 写目的地：`--dest` 可重复，形如 `file:/abs/path`、`onedrive:<相对路径>`（后者落到
   `~/Library/CloudStorage/OneDrive-Personal/<相对路径>`）。默认目的地 = `file:<out>` 一处 + 打印未配置的提示。
5. 生成 `manifest.json` **放在每个目的地旁边**，内容：指纹、uid、算法、创建/到期、
   **密文的 sha256**、备份时间、**口令在哪（人写的备注字段，不是口令本身）**、还原命令原文。
6. 结束时打印一段**可直接照抄的还原命令**。

### `gpgkit restore <backup.asc.gpg>`
```
gpgkit restore FILE [--passphrase-file F] [--keyring DIR] [--yes]
```
1. 解密 → `gpg --import`（secret + public），恢复 ownertrust。
2. **验收（不可跳过）**：用还原进来的私钥**签一个临时载荷并 verify 回来**；失败 → 退出码 1 并指出是哪一步断的。
3. 打印：指纹、到期日、以及"下一步该做什么"（设 `git config user.signingkey`、重新 publish 公钥等）。

### `gpgkit extend`
```
gpgkit extend [--key <id>] [--expire 2y] [--publish]
```
续期（只改自签名，**不动密钥**，已发布的签名不受影响）。到期日临近时的正解。
打印**旧到期日 → 新到期日**，并提醒：改了之后公钥要重新 publish。

### `gpgkit sign`
```
gpgkit sign <file>... [--checksums sha256] [--out DIR] [--key <id>]
```
给发布产物用：每个文件生成 `<file>.asc`（分离签名）；`--checksums` 额外产出
`SHA256SUMS` 与 `SHA256SUMS.asc`（后者是对清单本身的签名）。
输出必须列出**每个产出文件及其字节数**。

### `gpgkit verify`
```
gpgkit verify <file> [--sig <file.asc>] [--checksums FILE]
```
验分离签名；给了 `--checksums` 就按清单逐条校验和比对。**任何不一致都要点名是哪个文件。**

### `gpgkit publish`
```
gpgkit publish [--key <id>] [--keyserver hkps://keyserver.ubuntu.com]
```
推公钥到 keyserver。失败要给出**可操作的下一步**（换 keyserver、检查网络代理），不是干报错。

### `gpgkit doctor`
一条命令回答"我这套还能不能签、还能不能发"：
- `gpg` 在哪、版本多少
- 签名密钥在不在、**剩余天数是否 < 90**
- **真签一次**（临时载荷）并 verify —— 证明"能签"不是猜的
- 有备份配置的话：备份文件在不在、**能不能解密**（口令可取得时）
- keyserver 可达性
- Maven Central 用到的 `~/.m2/settings.xml` 里 `<id>central</id>` **是否存在**（只报"有/没有"，**绝不打印 token**）
输出每条一行 `PASS/WARN/FAIL`，退出码按**有没有 FAIL** 决定。

## 3. 硬约束（实现者必须遵守）

1. **绝不打印任何私钥、口令、token。** 只有指纹、日期、校验和、路径可以打印。
2. **任何"备份"不许在没有回环校验的情况下成功退出。**
3. **任何"还原"不许在没有"签一次并验回来"的情况下成功退出。**
4. 写文件前先做全部可失败的检查（先验证、后落盘），避免留下半成品。
5. 一切对 `gpg` 的调用都要**非交互**；检测到会挂住的调用方式算 bug。
6. 目标机器可能**没有任何 GPG 密钥**（全新 Mac mini）—— `status`/`doctor` 必须在那种机器上也能给出有用的话。

## 4. 无口令密钥这个取舍（写下来，别让后人重新纠结）

本项目选择**无口令**（`%no-protection`）密钥作为默认。理由是发布流水线要无头签名：
`maven-gpg-plugin` 配 `--pinentry-mode loopback`，没有口令就不会弹 pinentry。
代价：**私钥文件本身就是凭据**（丢了或泄露等于身份丢了）。
本工具的立场：**不因为难用就取消口令**，而是让"无口令"这件事**有备份、有记录、有到期日**兜着 ——
所以 `backup` 默认加口令加密存起来，`status`/`doctor` 盯着到期日。
`gen --passphrase-file` 支持带口令的密钥；但带口令会让无头签名需要 `passphraseEnvName`，用之前想清楚。

## 5. 交付物

- `pom.xml`（JDK 25、零依赖、exec 可直接跑）
- `src/main/java/...` 上述命令的实现
- `build-native.sh`（GraalVM native 构建：`--no-fallback`、`-O3`，每个 flag 为什么在那写在注释里）
- `README.md`（怎么用，一步一步）+ `docs/` 下的 runbook：
  - `migrate-to-new-machine.md`（换机：备份 → 新机 clone → restore → doctor）
  - `key-expiry.md`（到期前怎么办）
  - `backup-placement.md`（密文放哪、口令放哪、为什么两者要分开）
- 测试：至少覆盖 ① 回环校验能发现坏密文 ② restore 的签-验验收路径 ③ 退出码约定

## 6. 风格

**注释解释"为什么"**，不解释"做了什么"；
踩过的坑（例如 pinentry 挂住、native image 的反射限制）写进注释里，让下一个人不用再踩。
