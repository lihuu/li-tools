# tokenkit — 规格 v1（契约）

> 这个文件是 **tokenkit 的核心接口契约**。实现者、自动化测试和文档作者均以它为准；任何接口修改必须先修改此契约。
> 目的：提供一个**现代、跨平台（macOS / Linux / Windows 11）、内嵌式加密存储、终端秒级注入**的通用 Token 管理工具，彻底消灭环境变量明文裸奔与跨平台配置割裂。

---

## 0. 为什么存在

现代开发者日常需要管理几十个各类凭据（OpenAI/Anthropic API Key、GitHub PAT、AWS AK/SK、私有服务 Token）。但在实践中往往面临两大极端困境：
1. **明文裸奔与高泄露风险**：开发者为了图方便，直接将明文 Token 硬编码写在 `~/.zshrc`、`~/.bashrc` 或项目 `.env` 文件中。一旦发生代码误提交、屏幕共享、终端录屏或排查日志，密钥瞬间公开展览。
2. **重型安全工具心智负担高**：许多密码管理器要求每次开启终端或调用时弹窗输入长主密码或触控指纹。这对于开发者“每秒钟可能新建一个终端 Tab / 窗口”的日常高频场景而言，使用体验完全不可接受。
3. **平台割裂**：Mac 下有 Keychain、Windows 下没有开箱即用的对等极简 CLI，团队与个人跨设备配置极度繁琐。

`tokenkit` 坚持**“易用性第一、安全不添乱”**的设计原则：
- **磁盘上永远强加密**：内嵌标准 SQLite，敏感数据全部经过 AES-256-GCM 认证加密；
- **终端内秒级静默注入**：无感透明对接系统原生凭据能力（macOS Keychain / Windows DPAPI），终端启动时毫秒级加载环境变量，零弹窗、零打扰；
- **三端原生平权**：macOS、Linux 与 Windows 11 下拥有完全一致的命令动词与心智模型。

---

## 1. 形态与技术栈

- **模块位置**：`li-tools/tokenkit`
- **基础脚手架规范**：全面继承 [lihuu/java-cli](https://github.com/lihuu/java-cli) 作为标准工程模板，遵循其架构分层与代码整洁纪律。
- **语言与构建**：**Java 25 + Maven + GraalVM Native Image**，最终输出为**零运行时依赖的单一独立原生可执行文件**（`target/tokenkit`）。
- **工程分层标准（严格对齐 java-cli）**：
  - `io.github.lihuu.tokenkit.app`：负责 CLI 入口装配、`ApplicationInfo` 版本动态注入与显式依赖构造（零业务代码）。
  - `io.github.lihuu.tokenkit.cli`：负责 Picocli 注解解析、参数路由、`OutputStyle` 语义色彩输出与退出码映射。
  - `io.github.lihuu.tokenkit.core`：负责纯业务领域（数据存储、加解密管道、密钥管理），**绝对不依赖 Picocli 或 CLI 层**。
- **代码纪律**：
  - 显式声明所有局部类型，**禁止使用 `var`**（包括测试）。
  - 全面采用纯构造器显式注入（Constructor Injection），严禁引入 Spring/Guice 等 DI 框架或运行时反射扫描。
  - 公共方法入参及返回值显式标注 `@NotNull` / `@Nullable`（来自 `org.jetbrains:annotations`）。
- **核心依赖**：
  - CLI 框架：`info.picocli:picocli` (配合 `picocli-codegen` 在编译期生成 Native 元数据)
  - 存储引擎：内嵌式 `org.xerial:sqlite-jdbc`（引擎全打包内嵌，目标机器**无需预装 SQLite**）
  - 密码学引擎：纯 JDK 25 原生 `javax.crypto` (AES-256-GCM)，无任何外部 C/OpenSSL 动态链接依赖。
- **调用原则**：支持交互式友好终端展示，全量命令均支持 `--json`、静默模式与标准 Unix 管道流输入。

---

## 2. 核心约定 (Convention over Configuration)

1. **默认存储路径**：
   - **macOS / Linux**：`~/.config/tokenkit/tokens.db`（优先遵循 XDG 规范）
   - **Windows 11**：`%APPDATA%\tokenkit\tokens.db`
   - 环境变量 `TOKENKIT_DB_PATH` 可强制覆盖上述路径。
2. **Profile（环境分组）约定**：
   - 底层数据模型中 `profile` 为必填项，默认值始终为 `'default'`。
   - 所有命令缺省 `--profile` 参数时，自动静默作用于 `'default'` 环境，用户无需感知分组概念。
3. **透明机器静默加密 (Zero-Friction Encryption)**：
   - 每一个 Token 在写入 SQLite 前，均由 256 位 Master Key 进行 AES-256-GCM 加密，并带有独立 12 字节随机 Nonce 与 16 字节认证标签。
   - **Master Key 获取阶梯策略**：
     - **优先级 1 (CI/容器逃生门)**：若检测到环境变量 `TOKENKIT_MASTER_KEY`，直接使用该 256 位十六进制密钥。
     - **优先级 2 (macOS)**：通过系统 Keychain (`security` 服务: `li-tools.tokenkit`, 账号: `master-key`) 存取，由系统在用户登录会话内静默授权，零弹窗。
     - **优先级 3 (Windows 11)**：通过 Windows 原生 DPAPI (`CryptProtectData`) 加密 Master Key 并持久化在 `%APPDATA%\tokenkit\.master.key`，强绑定当前 Windows 登录会话，零交互。
     - **优先级 4 (Linux)**：优先通过 D-Bus Secret Service 存取；无桌面环境下安全降级为 `~/.config/tokenkit/.master.key` 并强制设置 Linux 权限 `0600`。
4. **命名即环境变量名**：
   - Token 名称（如 `OPENAI_API_KEY`）默认即为导出到 Shell 时的环境变量名称。

---

## 3. SQLite 数据契约 (Database Schema)

数据库初始化时，务必原子化创建以下 3 张表及对应索引：

```sql
-- 1. Vault 元数据表 (版本控制与主密钥金丝雀验证)
CREATE TABLE IF NOT EXISTS vault_metadata (
    key         TEXT PRIMARY KEY,              -- 配置键 (如 schema_version, canary_nonce, canary_ciphertext)
    value       TEXT NOT NULL                  -- 配置值
);

-- 2. Profile 环境表 (环境配置与激活状态)
CREATE TABLE IF NOT EXISTS profiles (
    name        TEXT PRIMARY KEY,              -- 环境名称 (如 default, work, personal)
    description TEXT,                          -- 环境说明
    is_active   INTEGER NOT NULL DEFAULT 0,    -- 是否为当前全局激活环境 (1=是, 0=否)
    created_at  INTEGER NOT NULL               -- 创建时间戳 (Unix ms)
);

-- 默认内置初始化 'default' 环境
INSERT OR IGNORE INTO profiles (name, description, is_active, created_at)
VALUES ('default', 'Default system profile', 1, unixepoch('subsec') * 1000);

-- 3. Token 核心数据表 (通用 Token 存储模型)
CREATE TABLE IF NOT EXISTS tokens (
    id          TEXT PRIMARY KEY,              -- 紧凑唯一 UUID
    profile     TEXT NOT NULL DEFAULT 'default',-- 所属 Profile
    name        TEXT NOT NULL,                 -- Token 标识符 (也是环境变量名)
    kind        TEXT NOT NULL DEFAULT 'generic',-- 类型: generic | api_key | oauth2 | pem | composite
    
    -- 加密载荷字段 (AES-256-GCM)
    nonce       BLOB NOT NULL,                 -- 12 字节强随机 IV / Nonce
    ciphertext  BLOB NOT NULL,                 -- 密文载荷 (密文数据 + 16 字节 GCM Auth Tag)
    
    -- 检索与元数据
    description TEXT,                          -- 备注信息
    tags        TEXT,                          -- 逗号分隔检索标签 (如 "llm,work")
    expires_at  INTEGER,                       -- 过期时间戳 (可选, Unix ms)
    created_at  INTEGER NOT NULL,              -- 创建时间戳 (Unix ms)
    updated_at  INTEGER NOT NULL,              -- 最后更新时间戳 (Unix ms)
    
    CONSTRAINT uq_profile_name UNIQUE (profile, name),
    CONSTRAINT fk_tokens_profile FOREIGN KEY (profile) REFERENCES profiles(name) ON DELETE CASCADE
);

-- 4. 复合索引 (保障高频 Shell 注入在 2ms 内完成)
CREATE INDEX IF NOT EXISTS idx_tokens_profile_lookup ON tokens(profile, name);
CREATE INDEX IF NOT EXISTS idx_tokens_tags ON tokens(tags);
```

---

## 4. 命令面规范 (CLI Surface)

### 全局选项 (Global Flags)
- `--profile <name>`：指定操作的目标 Profile（缺省时为 `default`）。
- `--json`：以 JSON 格式输出机器可读数据。
- `--quiet` / `-q`：静默模式，仅输出关键结果或完全不输出信息文本。
- `--help` / `-h`：输出帮助手册。
- `--version` / `-V`：输出版本信息。

### 统一退出码 (Exit Codes)
- `0`：执行成功。
- `1`：业务逻辑失败（如 Token 不存在、主密钥解密失败、数据完整性校验失败）。
- `2`：命令行用法错误（参数缺失、未知子命令或选项无效）。

---

### 子命令规范

#### 1) `tokenkit set <name> [value]`
添加或更新一个 Token：
```bash
# 1. 显式设值
tokenkit set OPENAI_API_KEY sk-proj-123456

# 2. 交互式隐藏输入 (防终端历史回溯)
tokenkit set GITHUB_TOKEN
# 提示: Enter token value for 'GITHUB_TOKEN': [输入被遮蔽]

# 3. 管道流导入 (支持多行 PEM、私钥或长 Token)
cat my_private.pem | tokenkit set SERVER_SSH_KEY

# 4. 指定所属分组与元数据
tokenkit set AWS_ACCESS_KEY_ID AKIAIOSFODNN7EXAMPLE --profile work --tag cloud,infra --desc "公司生产账号"
```
- **行为**：若同 Profile 下已存在同名 Token，执行原子覆盖更新，并刷新 `updated_at`；若不存在则插入新记录。

#### 2) `tokenkit get <name>`
获取指定 Token 的解密值：
```bash
# 直接打印明文到 stdout (结尾不带换行，方便管道透传)
tokenkit get OPENAI_API_KEY

# 注入系统剪贴板 (不向屏幕输出任何明文，防窥屏)
tokenkit get GITHUB_TOKEN --clip

# 获取指定环境下的 Token
tokenkit get GITHUB_TOKEN --profile work
```
- **选项**：`--clip`（写入操作系统原生剪贴板，支持 macOS pbcopy、Windows clip、Linux xclip/wl-copy）。

#### 3) `tokenkit env` (核心杀手级功能)
导出当前 Profile 下所有 Token 为 Shell 环境变量定义：
```bash
# 默认输出（根据当前 OS 智能自适应）
tokenkit env

# 显式指定 Shell 语法
tokenkit env --shell zsh    # 输出 export KEY="VAL"
tokenkit env --shell bash   # 输出 export KEY="VAL"
tokenkit env --shell pwsh   # 输出 $env:KEY = "VAL"
tokenkit env --shell fish   # 输出 set -gx KEY "VAL"

# 指定 Profile 导出
tokenkit env --profile work
```
- **性能契约**：冷启动到全量导出时间必须控制在 **10ms 以内**，确保加入 `.zshrc` 或 PowerShell `$PROFILE` 毫无卡顿。
- **转义安全性**：对 Token 值中的引号、换行符和特殊字符进行严密的 Shell 转义，杜绝命令注入。

#### 4) `tokenkit run [--profile <name>] -- <command...>`
将当前 Profile 的 Token 作为环境变量注入子进程并执行，**不污染当前终端上下文**：
```bash
# 仅对单次运行注入环境变量
tokenkit run -- npm run dev
tokenkit run --profile work -- git push origin main
tokenkit run -- aws s3 ls
```
- **行为**：解密当前 Profile 下所有 Token，合并至当前进程环境副本，使用系统 `ProcessBuilder` 启动子进程；主进程等待并完全透传子进程的标准输入、输出与退出码。

#### 5) `tokenkit list`
列表展示已登记的 Token 元数据：
```bash
tokenkit list
tokenkit list --profile all         # 查看所有 Profile
tokenkit list --tag work            # 按标签过滤
```
- **输出格式**：表格形式输出 `PROFILE`, `NAME`, `KIND`, `MASKED_VALUE`, `TAGS`, `UPDATED_AT`。
- **脱敏规则**：所有 Token 值默认自动打码（如 `sk-pr••••a8B3`），永远不在列表中展示完整明文。若指定 `--json` 则输出结构化元数据（密文值默认省略，除非显式指定 `--reveal`）。

#### 6) `tokenkit rm <name>`
删除一个或多个 Token：
```bash
tokenkit rm OPENAI_API_KEY
tokenkit rm GITHUB_TOKEN --profile work
tokenkit rm OPENAI_API_KEY GITHUB_TOKEN -y   # 免二次确认批量删除
```

#### 7) `tokenkit profile <subcommand>`
管理环境分组：
```bash
tokenkit profile list               # 列出所有 profile 及当前激活项
tokenkit profile create <name>      # 创建新环境
tokenkit profile use <name>         # 切换全局激活环境 (切换后后续命令默认使用该环境)
tokenkit profile rm <name>          # 删除环境 (同时级联删除该环境下的全部 Token)
```

#### 8) `tokenkit backup <subcommand>`
本地安全快照备份与还原（保障本机数据安全、防手抖误删）：
```bash
# 1. 创建本地备份（默认自动以时间戳生成文件名，保存至数据目录下 backups/ 目录，免输路径）
tokenkit backup create
# 输出：✔ 成功创建本地数据库备份: ~/.config/tokenkit/backups/tokenkit-backup-20261018-013000.db

# 显式指定输出文件
tokenkit backup create --out /path/to/my-backup.db

# 2. 列出本地历史备份
tokenkit backup list

# 3. 从备份还原（还原前自动保存就地快照，防意外覆写）
tokenkit backup restore /path/to/my-backup.db
```
- **定位**：本地备份文件保留原始机器 Master Key 密文状态，适用于本机系统重装前备份或误操作回滚；若需迁移至不同机器或跨系统，请使用 `export / import`。

#### 9) `tokenkit export [options]` (跨机/跨平台安全导出)
将 Token 解密并封装为可移植中转包（或明文 JSON），用于跨操作系统（macOS / Windows 11 / Linux）或新老机器迁移：
```bash
# 1. 极简导出：无需任何参数，默认导出当前 profile，自动生成时间戳文件名，交互提示输入中转密码
tokenkit export
# 终端输出高亮提示：
# ✔ 成功导出 8 个 Token (加密迁移包)!
#   文件位置: ./tokenkit-export-20261018-013000.tkbundle

# 2. 导出全部环境
tokenkit export --all

# 3. 指定输出路径与导出指定 Profile
tokenkit export --profile work --out ./work-tokens.tkbundle

# 4. 导出明文 JSON（显式声明 --unencrypted，供人工核查或第三方系统接入）
tokenkit export --unencrypted
# 输出: ./tokenkit-export-20261018-013000.json
```
- **默认文件名约定**：
  - 加密包：`./tokenkit-export-YYYYMMDD-HHmmss.tkbundle`
  - 明文包：`./tokenkit-export-YYYYMMDD-HHmmss.json`
- **加密机制**：使用源机器 Master Key 解密选定 Token，提示用户输入一次性中转口令（或 `--passphrase`），通过 PBKDF2-HMAC-SHA256 派生传输密钥后以 AES-256-GCM 封装为 `.tkbundle`；若带 `--unencrypted` 则输出格式化明文 JSON。

#### 10) `tokenkit import <file> [options]` (跨机/跨平台安全导入)
在目标机器/系统上导入迁移包，解密后使用目标机器的本地 Master Key 重新加密入库：
```bash
# 1. 极简导入：默认冲突跳过 (--skip)，输入中转密码即完成
tokenkit import tokenkit-export-20261018-013000.tkbundle

# 2. 覆盖模式：若目标存在同名 Token 则强制覆盖
tokenkit import tokenkit-export-20261018-013000.tkbundle --overwrite
```
- **冲突策略**：
  - **默认策略为 `--skip`**：若当前库中已存在同名 Token，保留本地原值，安全跳过；
  - **`--overwrite` / `-f`**：强制使用导入文件中的 Token 覆盖本地同名记录。
- **彩色详细结果反馈（标准输出格式）**：
  导入完成后，必须输出清晰美观的彩色摘要，区分新增、跳过与覆盖明细：
  ```text
  ✔ 导入完成！(共处理 7 个 Token)
    • 新增导入: 5 个 (OPENAI_API_KEY, ANTHROPIC_API_KEY, AWS_KEY, ...)
    • 冲突跳过: 2 个 [已存在] (GITHUB_TOKEN, DB_PASSWORD)
    • 覆盖更新: 0 个
  ```

---

## 5. CLI 彩色终端输出规范 (Color & Terminal UX)

为提供现代极客手感并减少心智负担，所有控制台输出默认附带符合直觉的 ANSI 颜色高亮（遵循 `NO_COLOR` 环境变量或全局 `--no-color` 开关）：
- **成功状态与新增记录**：亮绿 (`\u001B[32;1m`)，前缀 `✔`
- **警告与冲突跳过**：明黄 (`\u001B[33;1m`)，前缀 `⚠`
- **高亮关键信息（文件名、路径、Token 标识名）**：青色 (`\u001B[36;1m`)
- **错误与异常提示**：亮红 (`\u001B[31;1m`)，前缀 `✖`
- **脱敏遮罩、时间戳与次要说明**：弱化灰色 (`\u001B[90m`)
- **命令语法与示例**：粗体白色 (`\u001B[1m`)

---

## 6. 跨平台安全性与异常防护

1. **认证加密防篡改 (AEAD)**：
   - 必须使用 `AES/GCM/NoPadding`。
   - GCM 认证标签（16 字节）确保一旦 SQLite 文件被外部恶意篡改，解密时必定抛出 `AEADBadTagException`，严禁容忍任何损坏数据。
2. **内存敏感信息擦除**：
   - 密钥处理中尽可能使用 `char[]` 或 `byte[]`，在完成加解密后显式使用 `Arrays.fill(..., (byte)0)` 覆写清空内存，防止内存 Core Dump 泄露。
3. **金丝雀自检机制 (Canary Verification)**：
   - `vault_metadata` 表中持久化一条由 Master Key 加密已知固定内容（如 `"tokenkit-canary-v1"`）的密文。
   - 启动时预先校验金丝雀；若解密失败，直接提示主密钥无效（如系统凭据被重置、或跨机拷贝了 db 文件），阻止后续无效操作。
4. **并发与文件锁**：
   - SQLite 默认启用 WAL（Write-Ahead Logging）模式，确保多终端 Tab 同时读写（例如多个终端同时打开执行 `tokenkit env`）互不阻塞。
