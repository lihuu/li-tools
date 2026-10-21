# gpgkit

[English →](README.md)

**gpgkit 把 GPG 密钥的一辈子收进一个命令。** 生成、备份、还原、续期、签名、验签、发布公钥、自检——
每一件事都是一条命令，不需要回忆 `gpg` 那一长串参数。

它解决的是一个很具体的失败：**换一台机器，密钥就丢了，于是干脆不用 GPG。**
所以这里的第一等公民不是"签名"，而是**备份与还原的可验证性**：

- 任何一次 `backup`，都要当场证明这份备份**能被还原**（回环校验）；
- 任何一次 `restore`，都要当场证明还原进来的密钥**能签出可被验证的签名**。

这两条不是选项，也不打印"大概没问题"。做不到就退出码 1，并且不留下半成品。

gpgkit 自己**不实现密码学**。`gpg` 是它的引擎，`openssl` 只在需要生成随机口令时用。
它做的是**编排 + 校验 + 记录**——以及把那些容易忘、忘了就出事的外部命令参数固定下来。

## 安装

需要 JDK 25。两条路，产物都是**一个可执行文件**。

**日常使用的路（推荐）**——GraalVM native 构建，目标机器不用装 JDK：

```shell
./build-native.sh
./target/gpgkit --help
```

跑完你会看到 `./target/gpgkit` 这个可执行文件。`--help` 应该列出全部子命令；
如果它说找不到 `native-image`，说明 GraalVM 没在 `PATH` 里。

**开发调试的路**——JVM 上直接跑，编译快得多：

```shell
mvn -q package
```

`mvn -q package` 产出的 `target/gpgkit.jar` 带 Main-Class，可以直接跑，不必先装 native：

```shell
java -jar target/gpgkit.jar --help
```

开发和改代码时走这条路，**验收时走 native**：只有 native 可执行文件是发布目标。

把 `target/gpgkit` 拷到 `~/.local/bin/`（或任何在 `PATH` 里的目录）就能在任意地方调用：

```shell
cp target/gpgkit ~/.local/bin/gpgkit
```

## 最短上手路径

**做一次备份**——默认无口令密钥也会被加密存起来：

```shell
gpgkit backup --out ~/gpg-backup
```

成功时你会看到：导出与加密的过程、**回环校验通过**的一行、`manifest.json` 的路径，
最后是一段**可以直接照抄的还原命令**。把那段命令连同备份文件一起存好。

**在另一台机器上还原**：

```shell
gpgkit restore ~/gpg-backup/<那个文件>.asc.gpg
```

成功时你会看到：导入的指纹、到期日，以及**临时载荷签-验通过**的一行。
然后它告诉你下一步该做什么（设 `git config user.signingkey`、重新 publish 公钥等）。

这两条命令之间，还有一件**工具帮不了你**的事：**密文和口令必须走两条路。**
放哪儿、为什么，见 [docs/backup-placement.md](docs/backup-placement.md)。

## 全局参数与退出码

所有子命令都接受：

| 参数 | 作用 |
|---|---|
| `--json` | 机器可读输出 |
| `--quiet` | 精简输出 |
| `--help` | 用法 |

退出码是固定约定，脚本和 CI 可以直接依赖：

| 码 | 含义 |
|---|---|
| `0` | 成功 |
| `1` | 失败，原因写在 stderr |
| `2` | 用法错误 |

**绝不打印任何私钥、口令、token。** 只有指纹、日期、校验和、路径会出现在输出里。
所以把 gpgkit 的输出贴进 issue 或聊天记录是安全的。

## 命令

| 命令 | 一句话 |
|---|---|
| [`status`](#gpgkit-status) | 我这台机器上有什么密钥，还能用多久 |
| [`gen`](#gpgkit-gen) | 生成一对新密钥 |
| [`backup`](#gpgkit-backup) | 备份（核心命令） |
| [`restore`](#gpgkit-restore) | 还原 |
| [`extend`](#gpgkit-extend) | 续期 |
| [`sign`](#gpgkit-sign) | 给发布产物签名 |
| [`verify`](#gpgkit-verify) | 验签 |
| [`publish`](#gpgkit-publish) | 推公钥到 keyserver |
| [`doctor`](#gpgkit-doctor) | 一条命令回答"我这套还能不能签、还能不能发" |

### `gpgkit status`

```shell
gpgkit status [--refresh]
```

列出本机密钥：指纹、算法/长度、创建日、**到期日与剩余天数**、用法（SC/E）、是否有私钥。
还会尽力查一下公钥在 keyserver 上是否可见（默认读取本地缓存实现毫秒级响应，加 `--refresh` 强制请求远端）——**查不到不影响退出码**，网络不通不该让一条本地查询失败。

输出末尾有一句话结论，就是它的全部价值：

- `OK`
- `WARN: 90 天内到期`
- `WARN: 没有可用签名密钥`

在一台全新、**一个密钥都没有**的 Mac mini 上，这条命令也必须给出有用的话，而不是一堆空表。

拿到 `WARN: 90 天内到期` 就去看 [docs/key-expiry.md](docs/key-expiry.md)。

### `gpgkit gen`

```shell
gpgkit gen [--uid "名字 <邮箱>"] [--expire 2y] [--algo rsa4096|ed25519]
           [--passphrase-file F | --no-passphrase] [--publish]
```

默认 `rsa4096`、`2y`、**无口令**。`--uid` 不给就从 git config 取。

生成后会打印**指纹全文**（不是短 id）、创建日与到期日，并提示你**下一步做 `backup`**。
这个提示是认真的：**没备份不许走。** 无口令密钥的私钥文件本身就是凭据，丢了就是身份丢了。

`--publish` 会在生成后把公钥推到 keyserver（见 [`publish`](#gpgkit-publish)）。

关于"默认无口令"这个取舍，以及它为什么是有意的，见 [docs/key-expiry.md](docs/key-expiry.md) 里
「无口令这件事」。

### `gpgkit backup`

```shell
gpgkit backup [--key <id|all>] [--out DIR]
              [--passphrase-file F | --generate-passphrase-to F]
              [--dest <spec>]...
```

这是本工具的核心。它按顺序做五件事：

1. **导出** secret（armored）+ public + **ownertrust/trustdb 的相关条目**——不导 trust 状态，
   还原出来的密钥虽然能签，但信任关系全丢了。
2. **加密**：默认 **AES256 对称加密**。口令可以来自 `--passphrase-file F`，
   或者让本工具生成随机口令写进 `F`（`--generate-passphrase-to F`）——
   **随机口令绝不会打印到 stdout**，它只落在你指定的文件里。**没有「不加密」这个选项**：
   备份永远加密（SPEC §2）。理由一旦允许明文，「回环校验」就退化成自己跟自己比，
   等于用一个空洞的检查换来形式的合规；而未加密的私钥副本一旦落盘，就等于把身份复制了一份。
3. **回环校验（不可跳过）**：解密回来，与原始导出**逐字节比对**。
   不一致就失败退出，**并且不写目的地**——宁可什么都没有，也不要留一份你以为是好的备份。
4. **写目的地**：`--dest` 可以给多次。
5. 生成 `manifest.json` 放在**每个目的地旁边**，最后打印一段**可直接照抄的还原命令**。

目的地两种写法：

| 写法 | 落到哪 |
|---|---|
| `file:/abs/path` | 本机绝对路径 |
| `onedrive:<相对路径>` | `~/Library/CloudStorage/OneDrive-Personal/<相对路径>` |

不给 `--dest` 时，默认只写 `file:<out>` 一处，并提示你还有目的地没配。

`manifest.json` 记的是：指纹、uid、算法、创建/到期、**密文的 sha256**、备份时间、
**口令在哪**（这一栏是给人写的备注，**不是口令本身**）、还原命令原文。
半年后你打开这个文件，就知道该去哪儿找口令、该敲哪条命令。

密文和口令分别放哪，以及**为什么必须分开**：[docs/backup-placement.md](docs/backup-placement.md)。

### `gpgkit restore`

```shell
gpgkit restore FILE [--passphrase-file F] [--keyring DIR] [--yes]
```

1. 解密 → `gpg --import`（secret + public），恢复 ownertrust。
2. **验收（不可跳过）**：用还原进来的私钥**签一个临时载荷，再 verify 回来**。
   不通过就退出码 1，并**指出是哪一步断的**——是解密失败、导入失败，还是签不出来。
3. 打印指纹、到期日，以及**下一步该做什么**：设 `git config user.signingkey`、重新 publish 公钥等。

完整换机流程（含传输通道怎么选、每步成功长什么样）：[docs/migrate-to-new-machine.md](docs/migrate-to-new-machine.md)。

### `gpgkit extend`

```shell
gpgkit extend [--key <id>] [--expire 2y] [--publish]
```

续期**只改自签名，不动密钥**——所以**已经发布的签名不受影响**，别人的历史验证结果不会因为这次续期而变红。
到期日临近时的正解就是它，而不是重新生成一对密钥再想办法让大家换掉旧的。

跑完会打印**旧到期日 → 新到期日**，并提醒你：自签名改过了，公钥要**重新 publish**，否则
keyserver 上还挂着旧的那份。

### `gpgkit sign`

```shell
gpgkit sign <file>... [--checksums sha256] [--out DIR] [--key <id>]
```

给发布产物用。每个文件生成一个分离签名 `<file>.asc`。
加上 `--checksums` 会额外产出 `SHA256SUMS` 和 `SHA256SUMS.asc`——后者是对清单本身的签名，
这样别人只需要信任一个文件。

输出会列出**每个产出文件及其字节数**，方便你核对到底签了几份、传了几份。

### `gpgkit verify`

```shell
gpgkit verify <file> [--sig <file.asc>] [--checksums FILE]
```

验分离签名。给了 `--checksums` 就按清单逐条核对校验和。
**任何不一致都会点名是哪个文件**——不给出"有 1 个文件不匹配"这种没法行动的话。

### `gpgkit publish`

```shell
gpgkit publish [--key <id>] [--keyserver hkps://keyserver.ubuntu.com]
```

把公钥推到 keyserver。失败了会给你**可操作的下一步**——换一个 keyserver、检查网络代理——
而不是甩一句报错就完。

### `gpgkit doctor`

```shell
gpgkit doctor
```

一条命令回答"我这套还能不能签、还能不能发"。它检查：

- `gpg` 在哪、版本多少
- 签名密钥在不在、**剩余天数是否 < 90**
- **真签一次**（临时载荷）并 verify 回来——"能签"是证明出来的，不是猜的
- 有备份配置的话：备份文件在不在、**能不能解密**（口令能取到时）
- keyserver 可达性
- Maven Central 用到的 `~/.m2/settings.xml` 里 `<id>central</id>` **是否存在**

最后一项**只报"有/没有"，绝不打印 token**。

输出每条一行 `PASS/WARN/FAIL`，**退出码按有没有 FAIL 决定**——所以它可以原样放进发布前的脚本里。

换机之后、发布之前、以及每次想起 GPG 的时候，跑它。

## 文档

| 文档 | 什么时候看 |
|---|---|
| [docs/backup-placement.md](docs/backup-placement.md) | 密文放哪、口令放哪、为什么必须分开 |
| [docs/migrate-to-new-machine.md](docs/migrate-to-new-machine.md) | 换新机器时，从头到尾 |
| [docs/key-expiry.md](docs/key-expiry.md) | 密钥快到期了，或者已经到期了 |

## 本文说明与待定

**本文依据 SPEC v1 定稿（提交 610749d）撰写；命令面已对照 `./gpgkit --help` 校过。**

以下是撰写时 SPEC 里**没有**明确、因而本文**故意没有替你决定**的地方：

1. **JVM 路径的确切跑法**：`pom.xml` 最终没有配 exec 插件，也没有装第三个依赖。
   实际路径是 `mvn -q package` 加一个带 Main-Class 的 `target/gpgkit.jar`，已写进上面「安装」一节。
2. **`--no-verify` 与 `--no-encrypt` 都已从契约里删掉**（SPEC 定稿 610749d 裁定）：
   回环校验不可跳过，备份永远加密，两个选项都不存在，传了就是用法错误（退出码 2）。
   `gen` 同理**没有 `--yes`**——它从不提问，所以不需要这个开关。
4. **`manifest.json` 的字段名与嵌套形状**：SPEC §2 第 5 步只列了字段含义，没给 JSON 键名。
   本文按含义描述，没有编造键名；`--json` 的 schema 同样待实现后补。
5. **到期那天 Maven Central 的行为**：本文在 [docs/key-expiry.md](docs/key-expiry.md) 里
   描述了"过期后发布会被拒"，这是**外部服务的策略，不在 SPEC 里**，SPEC 只要求 `doctor` 检查
   `~/.m2/settings.xml` 的 `<id>central</id>`。以 Central 自己的规则为准。
6. **`--out DIR` 与 `--keyring DIR` 的默认值**：SPEC 没写缺省路径，本文只写了"不给时"的行为
   （`backup` 默认只写 `file:<out>` 一处），没有替它选目录。
