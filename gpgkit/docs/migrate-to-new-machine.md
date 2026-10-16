# 换机 runbook

**场景**：老机器上有 GPG 密钥，要在一台新机器（比如一台全新的 Mac mini）上把它恢复出来，
并且确认它**真的还能用**。

这篇是照抄用的。每一步都写了**成功长什么样**——因为换机最大的坑不是命令敲错，
是**你以为成功了**：文件在那儿、`gpg --list-keys` 也有输出，但签名悄悄签不出来。

**总览**：

```text
老机器：backup（含回环校验）
   ↓
传输：密文走一条路，口令走另一条路（不可同路）
   ↓
新机器：拿到 gpgkit 可执行文件
   ↓
新机器：restore（含签-验验收）
   ↓
新机器：doctor
   ↓
新机器：git config user.signingkey
```

---

## 0. 老机器：先确认现状

```shell
gpgkit status
```

**成功长什么样**：列出你的密钥，每条的**指纹、算法/长度、创建日、到期日与剩余天数、
用法（SC/E）、有没有私钥**，末尾一句话结论。拿到 `OK` 就可以往下走。

**先记下指纹全文**。后面每一步都要核对它，所以现在就抄下来（指纹是可以随便贴的，
它不是秘密）。如果结论是 `WARN: 90 天内到期`，**先去做
[key-expiry.md](key-expiry.md) 里的续期**——刚换完机器就发现签不了名，是最不该有的顺序。

如果这里是 `WARN: 没有可用签名密钥`，说明老机器上本来就没密钥，那这篇 runbook 不适用：
直接在新机器上 `gpgkit gen`。

## 1. 老机器：做备份

```shell
gpgkit backup --out ~/gpg-backup \
              --dest onedrive:gpgkit \
              --generate-passphrase-to ~/gpg-backup-passphrase.txt
```

`--out` 写一份到本地，`--dest` 再写一份到别处——上面用 OneDrive 当例子。`--dest` 可以重复，
多一个目的地多一层保险；密文该放到什么样的地方，见 [backup-placement.md](backup-placement.md)。
口令由 gpgkit 生成并**写进你指定的文件**——它**不会**打印到屏幕上，所以你**不会**在终端里
看到口令本身。

**成功长什么样**：

1. 导出 secret / public / ownertrust 的过程；
2. **回环校验通过**的一行——解密回来跟原始导出逐字节一致。这一行是全文的重点；
3. `manifest.json` 的路径；
4. **一段可以直接照抄的还原命令**（末尾）。

**失败了呢？** 如果回环校验没过，gpgkit **退出码 1 并且不写目的地**——这是有意的，
宁可什么都没有，也不要留一份你以为是好的备份。这时 `~/gpg-backup` 里**不该有**密文文件。
先别重试，去看 stderr 里说的是哪一步断的（导出、加密还是解密比对）。

**如果 `--dest` 写出去的那一步没有落到位**：先确认那个同步客户端（例子里是 OneDrive）正常同步；
如果目的地是**云端保险箱**（例如 OneDrive 的私人保管箱），那是人工步骤，
见 [backup-placement.md](backup-placement.md)。

## 2. 传输：两条路，绝不同路

这一步没有命令，只有纪律。**密文和口令走两个不同的通道。** 具体挑哪两个通道由你定，
规则只有一条：两者不能是同一条。下面拿一对现成的组合当例子——**密文进云端保险箱，
口令进密码管理器**；换别的组合就把名字换掉，规则不变，挑法见
[backup-placement.md](backup-placement.md)。

| | 例子里走的通道 |
|---|---|
| **密文** `.asc.gpg` + `manifest.json` | 云端保险箱（例：OneDrive 的私人保管箱；新机器登录同一个账号即可见） |
| **口令** | 密码管理器（例：Apple 的「密码」App；新机器登录同一个 Apple 账号即可见） |

**为什么不能同路**：密文单独泄露不致命，它还需要口令才能变成私钥。一旦口令和密文走了同一条通道——
同一个云盘、同一封邮件、同一个 U 盘——那条通道被攻破一次，两半就同时到手了，
前面所有的加密都白做。详见 [backup-placement.md](backup-placement.md)。

**不要**做的事：把口令文件一起拷进 `~/gpg-backup/` 再整个传过去；
把口令写进 `manifest.json`；用同一条邮件把两样发给自己。这三件事会一次性抹掉整条防线。

**成功长什么样**：新机器上**先**能看到密文（从你放密文的那条通道下来），
**再**能拿到口令（从你存口令的那条通道里查到那一条记录）。两个来源不同、互不依赖。
如果其中任何一个是靠另一个才拿到的，说明你走成一条路了。

## 3. 新机器：拿到 gpgkit

新机器上**不需要有 JDK**——native 可执行文件拿到就能用。两种拿法：

**从源码构建**（新机器上有 GraalVM 时）：

```shell
git clone <仓库地址> ~/git/gpgkit
cd ~/git/gpgkit
./build-native.sh
cp target/gpgkit ~/.local/bin/gpgkit
```

**直接从老机器拷**（更快，但要确认架构一致）：

```shell
# 老机器
scp target/gpgkit <新机器>:~/.local/bin/gpgkit
```

**成功长什么样**：

```shell
gpgkit --help
```

能列出全部子命令，说明二进制本身是好的。

**顺手确认新机器上的 gpg**：

```shell
gpg --version
```

gpgkit 自己不做密码学，`gpg` 是它的引擎——**引擎不在，gpgkit 什么都干不了**。
输出里应该能看到版本号。如果是 `command not found`，先装 `gpg`（Homebrew: `brew install gnupg`），
再往下走。

## 4. 新机器：还原

```shell
gpgkit restore ~/gpg-backup/<那个文件>.asc.gpg --passphrase-file <口令文件>
```

最稳的写法是**直接抄老机器 `backup` 末尾打印的那段还原命令**——那是给你照抄用的，
路径和参数都是对的。

**成功长什么样**，三样都必须在：

1. 导入 secret + public 的结果；
2. **临时载荷签-验通过**的一行。这是全文最该看的一行：它证明的不是"文件导进来了"，
   而是"**这把我真的能用它签出可被验证的签名**";
3. 指纹、到期日，以及**下一步该做什么**的提示。

**失败了呢？** gpgkit 会**指出是哪一步断的**：

| 断在哪 | 说明什么 | 先查什么 |
|---|---|---|
| 解密 | 口令不对，或密文损坏 | 口令那一条是不是复制多了空格/换行；密文的 sha256 对不对得上 `manifest.json` |
| 导入 | 密文是对的，但 gpg 不认 | `gpg --version` 是不是太低；`--keyring DIR` 是否指对了位置 |
| 签-验 | 私钥进来了但签不出/验不回 | 这是最该警惕的一种，别跳过——**没有这一步的还原不算还原** |

核对指纹：新机器 `gpgkit status` 打出来的指纹，必须跟第 0 步抄下来的**逐字符一致**。
不一致说明还原的不是同一把密钥，或者同时存在多把。

## 5. 新机器：自检

```shell
gpgkit doctor
```

**成功长什么样**：每条一行 `PASS/WARN/FAIL`，覆盖 `gpg` 的位置与版本、签名密钥在不在、
剩余天数是否 < 90、**真签一次并验回来**、备份文件在不在且能不能解密、keyserver 可达性、
`~/.m2/settings.xml` 里的 `<id>central</id>` 是否存在。

**退出码按有没有 FAIL 决定**——有 `FAIL` 就是非 0，所以这一步可以直接放进脚本。

怎么读：

- `FAIL` → 必须先解决，别往下走。
- `WARN` → 不挡事，但要知道是什么。最常见的是 keyserver 不可达（网络代理），
  以及剩余天数接近 90——后者去看 [key-expiry.md](key-expiry.md)。
- 关于 Maven Central 那一行：它**只报"有/没有"，绝不打印 token**，所以输出可以放心贴。

## 6. 新机器：让 git 用它签名

```shell
git config --global user.signingkey <指纹全文>
```

**成功长什么样**：`git config --global user.signingkey` 回读出来就是那串指纹。

然后做一件真实的事来验证，别停在"配置看起来对"：

```shell
git commit --allow-empty -S -m "verify new machine signing"
git log --show-signature -1
```

**成功长什么样**：`git log --show-signature` 里出现 `Good signature` 和你的密钥。
如果它说 `gpg: skipped ... No secret key`，说明 git 找的不是你还原的那把密钥——
回去核对 `user.signingkey` 的指纹。这一步通过了，换机才算真的完成。

## 7. 收尾

- `~/gpg-backup-passphrase.txt` 这类明文口令文件，**存进你的密码管理器之后就删掉**
  （例子里是 Apple 的「密码」App，见 [backup-placement.md](backup-placement.md)）。
- 老机器上的密钥**先别删**。新机器 `doctor` 全绿、并且成功签过至少一次名之后，
  再考虑处理老机器。
- 把 **`manifest.json` 里「口令在哪」那一栏**更新一下——它存在的意义就是让半年后的你
  知道去哪找口令。

---

**本文依据 SPEC v1（提交 b67e81e）撰写；工具实现完成后应对照 `./gpgkit --help` 再校一遍。**
