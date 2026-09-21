# AGENTS.md — 印迹 / penly（Android）项目简报

> 给 AI 会话的项目上下文：新会话先读这里，避免重复考古。
> 原则：**稳定事实写这里，易变进度只留一行快照（带日期）**；约定变更时同步更新本文件。

## 项目是什么

「印迹 / penly」（`com.beyondguo.penly`）：端到端加密的 Android 密码管理器，
双槽位影子保险库架构；与微信小程序端「私密笔记密码箱」通过 `private-vault-backup`
v1 格式互认备份（契约两端一致，不可单方改）。

工程为**双模块**：`:app`（Android 应用）+ `:crypto-core`（纯 JVM 加密内核，可被
将来的「印迹记录」App 复用，详见「加密 SDK」节）。

## 构建与真机

- **宿主 PowerShell 工具固定调 5.1：git/gh/gradle 一律 `& pwsh -NoProfile -Command '...'`
  委托 pwsh 7（WindowsApps 下），输出 `| Out-File -Encoding utf8` 回传**。
- **更稳的写法：写 `.ps1` 文件 + `& pwsh -NoProfile -File xxx.ps1`**，脚本内
  `[System.IO.File]::WriteAllLines($out, $lines, (New-Object System.Text.UTF8Encoding($false)))`
  自行落盘。`pwsh -Command {...} | Out-File` 回传带对齐空格的输出（如 `git status --short`）
  会崩在 `无法处理"Output"流的 XML: "Element"是无效的 XmlNodeType`（2026-09-21 实测）。
- AGP 8.9.1（compileSdk 36 / Gradle 8.11.1）需 Java 17+；系统 `JAVA_HOME` 是 jdk-11 会失败。
  构建前设：`$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'`
  （AS 更新后目录名带 1，Java 21；`gradle.properties` 已固定 `org.gradle.java.home`，CLI 可省）。
- 构建/装机：`./gradlew :app:installDebug`（真机 MI_8）。
- **纯 JVM 加密内核测试（日常首选）**：`./gradlew :crypto-core:test` —— **~10 秒跑完 96 例，
  不需要连设备**。密码学正确性验证的日常入口，优先于 device test。
- release 回归一键：`./gradlew :app:connectedReleaseAndroidTest -PtestBuildType=release`。
- 测试包 R8 两坑：androidTest 只读 `testProguardFiles()`（规则在 `app/proguard-test-rules.pro`）；
  须 `-applymapping` 对齐 app 混淆，否则启动即崩。
- R8 第三坑（2026-09-18 release 回归实测）：AGP 8.9.1 的 R8 对 `kotlin.jvm.internal.Intrinsics`
  做水平类合并+参数重排 → -applymapping 只对齐名字、参数序不齐 → 运行时 NoSuchMethodError。
  已在 `app/proguard-rules.pro` keep 整包（勿删）。
- **验证闭环铁律**：编译 → installDebug → logcat 复现路径无异常 → run-as 看落盘，四步自证。
- 装机签名：先 `adb shell dumpsys package` 看现有签名再选 keystore；debug 包用
  `~/.android/debug.keystore`（androiddebugkey/android）重签可覆盖。**绝不卸载应用**（清数据）。
- release 剥 Log 已落地（proguard-rules.pro `-assumenosideeffects android.util.Log`），
  但源码层仍须注意 debug 泄露——**当前槽位（A/B）绝不写日志**。
- **多模块 Kotlin 插件坑**（2026-09-21 实测）：根 `build.gradle.kts` 必须显式
  `alias(libs.plugins.kotlin.jvm) apply false`，否则 `kotlin.android` 会把 KGP 以
  「版本未知」带上类路径，`crypto-core` 解析同 id 插件时报
  `already on the classpath with an unknown version`（报错原文，症状对照用）。

## 加密 SDK（`:crypto-core`，Phase 0/1 已落地并推送）

- **模块**：`:crypto-core`（纯 JVM，**禁 `android.*`**）+ `:app` 双模块。包名与 app 同属
  `com.beyondguo.penly.crypto`（所以搬迁零 import 改动）。
- **内容**：`Aead`（原 `CryptoV2`，GCM/HKDF/Argon2id 原语）/ `CryptoEngine`（v1 兼容档，
  故意不改名）/ `DoubleEnvelope`（原 `KeystoreEnvelope`，双层信封）/ `KeyWrapper`（平台接缝接口）/
  `KeySession`（原 `SessionManager` 去 Slot 版）/ `FieldCipher`（#8 字段级加密）/
  `Shamir` / `Totp` / `Profile`（#18 参数档位）/ `Container`（#17 自描述容器）。
- **`crypto-core/README.md` = 接入文档**（讲「怎么用」）；工作区《印迹加密SDK_接口清单.md》
  是设计视角（讲「为什么这么切」）。改接口时**两份都要看**。
- **分层纪律（重要）**：**字段级原语收口到 SDK，条目级结构适配留在宿主**。
  `FieldCipher` 只认 `key + AAD + 明文`，收口三件必须每次做对的事（域分离子密钥 / 逐字段
  AAD 钉位 / 完整性异常归一）。**新宿主加密新字段直接用 `FieldCipher`，别抄 `ItemCipher` 的循环**。
- **`-keep class com.beyondguo.penly.crypto.** { *; }` 按包名匹配、跨模块生效**
  （R8 不区分来源模块，release 24/24 实证）。**改包名 = 必须同步 proguard 规则**。
- **`encodeDefaults` 陷阱**：`VaultHeader` 的 `alg`/`dataAlg`/`headerV` 都有默认值 →
  写库头**必须显式 `encodeDefaults = true`**，否则字段不落盘、自描述性丢失
  （与 `BackupCodec` 同根陷阱）。
- **`crypto-android` 是 Phase 3 规划、未触发**（不要当待办）：现在全 app 只有
  `app/crypto/AndroidKeyStoreWrapper.kt` 一个文件引 `android.*` 且属加密域，它留在 app 是
  正确位置（core 定义接口、app 提供实现，同包名同清洁边界）。**触发条件 = 第二个 App
  （印迹记录）真要用 TEE 信封时**。`SessionManager` 不属它——里面有 `Slot`（产品语义）。
- **不做**：加密索引（「感受不做加密检索」已拍板）、媒体大文件、跨 App 密钥共享（两 App
  完全独立、对等，各自主密码/密钥/备份）。

## GitHub 访问（**2026-09-21 起改用 gh token 走 https，SSH 已被沙箱硬拦**）

- **⚠️ `~/.ssh` 已被沙箱硬性拒绝**（`Access to the path '...\.ssh\known_hosts' is denied`，
  **`dangerouslyDisableSandbox: true` 也无效**）→ SSH 私钥读不到 → remote 虽仍是
  `git@github.com:beyondgjc/penly.git`，但 `git push` **必失败**；首次连接还会卡在
  `github.com` 主机指纹确认（代理网关 IP `198.18.0.56`，`known_hosts` 里没有）。
- **可用配方（2026-09-21 两次推送成功实证）**：

  ```powershell
  $tok = (gh auth token)          # gh 已登录 beyondgjc，scopes 含 repo，token 取自 keyring
  $env:GIT_TERMINAL_PROMPT = '0'
  $hdr = "http.https://github.com/.extraheader=AUTHORIZATION: basic " +
         [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$tok"))
  git -c credential.helper= -c $hdr push https://github.com/beyondgjc/penly.git master
  ```

  拉取同理：`git ... fetch https://github.com/beyondgjc/penly.git master:refs/remotes/origin/master`。
  整段写成 `.ps1` 走 `pwsh -NoProfile -File` 执行（见构建节的 pwsh 输出流坑）。
- **https push 后 `git status` 会显示 `ahead N`** —— 那是 remote-tracking 缓存未更新，
  **不是「没推上去」**。用上面的 `fetch ... master:refs/remotes/origin/master` 显式更新引用仲裁。
- **gh CLI 已登录**（keyring 凭据，账号 beyondgjc，scopes 含 repo）：release/PR 直接
  `gh ...`，如 `gh release create v5.0.0 --title "印迹 5.0" --notes-file <file> <apk>#<asset名>`。
  多 shell 传递带空格的 `--title` 会被吃引号（报 zsh 风格 no matches found）→ 同样写 `.ps1` 绕开。
- 跑前设 `[Console]::OutputEncoding=[Text.Encoding]::UTF8`；git/gh 的 stderr 进度被显示为
  NativeCommandError 属噪音，**成败以 `git log`/`git status`/exit code 为准**。
- **gh 中文输出显示乱码 ≠ 数据坏**（gh 的 UTF-8 被终端按 GBK 解码），GitHub 上内容可能
  正确——有疑问用 WebFetch 直读页面仲裁。
- **`GIT_SSH_COMMAND` 里 `$env:TEMP` 拼串会被吃** → 在仓库根生成杂散文件
  `UsersBEYOND~1AppDataLocalTemppenly_known_hosts`。教训：路径拼串要么用 `Join-Path`，
  要么别用这条通路（走上面的 https 方案）。

## 协作流程（硬约束）

- 改完代码**不主动 commit/push**：对齐方案 → 用户真机验证 → 用户说「提交」才动手；
  「提交」= commit + push 一并做完，不用再问推送。
- 改动范围最小化：一次只修指定页面/问题，不顺手扩散、不顺手重构。
- 实施前先给技术方案；用户确认后再动手。实机问题从源码诊断，引用 `文件:行号`，不猜。

## 架构铁律

- **双槽位影子保险库**：真库 + 占位影子落随机槽位 A/B（`data/Slot`），两槽位磁盘同构、
  不可区分——「是否设过应急密码」不可证伪是设计地基，**禁止**任何落盘状态标记、
  事后检测自愈、诊断 UI（链接不变量 I 破与合法影子在磁盘上不可区分）。
- **原子性**：跨 key 写入必须单次 `VaultStore.commitSlots{}`；JSON 编码/加密必须在
  事务外完成；自愈范式 = commit-last 标记（如 `ensureAuxProvisioned` 的 aux 非空判断，保留勿删）。
- 加密：**新一代 = 契约 v2**（AES-256-GCM + AAD + Argon2id，见 `Aead`/`Container`）；
  **存量 = v1**（AES-256-CBC + PKCS5 非 AEAD + PBKDF2-SHA256 10 万次，`CryptoEngine` 兼容档）。
  v1 的 CBC 路径**错密钥 ~1/256 概率 padding 通过 → 表现为乱码而非异常**，兜底不能只靠 catch；
  v2 的 GCM 是 AEAD，篡改必然 `IntegrityException`。
- `decryptItem`/`decryptAccount` 保持「抛」语义；`EditScreen` 预填失败必须 fail-closed
  （空串回写会静默毁数据）。
- **autofill**（`autofill/` 包）：服务 + 解锁浮层（FILL/SAVE 两模式）+ 响应构建。
  保存条目标题用应用显示名（`util/AppNameResolver`，解不出回包名），`appPackage` 存包名；
  两条路径统一「存过才提示」，无匹配 → save-only 锚定。MIUI「后台弹出界面」是
  锁定态保存/浮层的前提（`util/MiuiBgUi` 两步引导）。
- 前后台判定：`PenlyApp` ActivityLifecycleCallbacks started 计数（==1 前台、==0 后台
  15s 锁库）；禁用 MainActivity onStart/onStop；用 Started/Stopped 不用 Paused。
- 品牌绿 `#07C160` 不许动。
- TOTP：写路径必须显式携带存量 `totpDigits/totpPeriod`（`Totp.resolveEditParams`）；
  `otpauth://hotp/` 不能当 TOTP 建档。
- DataStore 委托必须声明在**文件顶层**（类内声明 → 第二次进页面必崩，见 `VaultStore.penlyDataStore`）。

## 跨端备份契约（改备份格式前必读）

- `private-vault-backup` **v1 两端一致，不可单方改**：PBKDF2 10万次/16B salt ·
  AES-256-CBC/PKCS7 · 导入三态 `wiped/restored/reencrypted`；`masterRef`：
  `penly-def-v1` / `wxb-def-v1`（需 `meta.openid`）/ 缺省 = custom 主密码。
- **v1.1 完整性字段**（2026-09-17 起 Android 全对齐）：`accountMac/secretMac/noteMac/totpMac`
  encrypt-then-MAC；子密钥 = `HMAC-SHA256(key=utf8("yinji-record-mac-v1"), msg=字段密钥)`
  ——**实参顺序与常规写法相反**（crypto.js:228 部署语义，照抄勿"修正"）；MAC 输入 = 存储的
  `ivB64 + "." + ctB64` 逐字使用。验签规则：`ct 与 mac 都非空才验`，缺任一宽容跳过；
  改密/导入重加密遇 Mac 不符 → 中止整个操作（fail-closed）。
- 陷阱：`BackupCodec` 的 Json 未开 `encodeDefaults` → 默认值不落盘（隐式契约，
  任一端改 `pwdMode === 'custom'` 判断即崩）；`tools/gen_test_fixtures.mjs` 是 v1.0 旧格式。
- 已知限制：小程序不识别 totp 字段 → 跨端往返 totp 同型丢失（Mac 亦然，无假警报）。
- **契约 v2（`BackupCodecV2`，GCM + Argon2id）只在 Android 侧自持，小程序端仍是 v1**。
  它**不迁进 `:crypto-core`**——跨端契约已定型，动它就是兼容风险。
  v2 的 `KdfParamsV2`（`alg/saltB64/memoryKiB/iterations/parallelism`）与 SDK 的 `KdfSpec`
  **同名同义**（`Container` 就是照它抽的，故意保持同构）——改一处要想着另一处。

## 发布管线

- keystore：仓库外 `release/penly-release.jks`（alias=penly），密码在
  `keystore.properties`；产物归档 `release/vX.Y.Z/`（mapping 勿公开）。
  **`keystore.properties` 与 `/release` 均已在 `.gitignore`**（旧待办已清，勿再提）。
- release 回归（**发布前必跑**）：`./gradlew :app:connectedReleaseAndroidTest -PtestBuildType=release`
  —— 2026-09-20 实测模拟器 + MI_8 双机 **24/24**（1m11s）。
- **跨 APK 混淆对齐钉子全家桶**（2026-09-20 实锤，`app/proguard-rules.pro`，勿删任何一条）：
  release 优化包下 androidTest 从「0 tests 静默崩溃」修到全过，五层问题——
  ①`androidx.test:runner` 的 pom 把 `tracing` 声明为 compileOnly → 测试 APK 缺 `Trace`
  启动即崩（主包 keep tracing + androidTest 显式依赖 1.3.0）；
  ②androidTest R8 的 `--lib` 是主包产物，被改名类被测试独有库按原名引用 → kotlin/kotlinx 全家族 keep；
  ③Compose `$stable` 字段被删 → 须 `-keepclassmembers`（`keepclassmembernames` **不防删除**，实测无效）；
  ④R8 参数收窄（如 `VaultStore.<init>(Context)` → `(PenlyApp)`，suspend Continuation → ContinuationImpl），
  `-applymapping` 只对齐名字对不齐签名 → 测试直连面 data/crypto/security/search + PenlyApp 整包 keep 签名冻结；
  ⑤`gradle.properties` 固定 `org.gradle.java.home` 指向 JBR（AGP 需 17+，系统 JAVA_HOME=11）。

## 进度快照（2026-09-21）

- **v5.0.0 已正式发布**：tag `v5.0.0`（= `45f63a1`）+ GitHub Release「印迹 5.0」（含 APK asset）。
  v2.0.0/v3.0.0/v4.0.0 均已发布。内容：Passkey 保险库（c9d8f5a）+ 遗产交接 + 图标 v3（fa7838a）
  + 契约 v2 地基 + release 回归修复。
- **加密 SDK 抽模块已落地并推送**：`af9fda6`（Phase 0：建 `:crypto-core` + 正名 + Profile/Container）
  → `379d149`（Phase 1：`FieldCipher` + `reEncryptRecord` + `SessionManager` 委托 `KeySession`）
  → `8498d1f`（`crypto-core/README.md` 接入文档）。
- **验证基线**（全绿）：`:crypto-core:test` **96 例**（~10s）· `:app:testDebugUnitTest` · MI_8 debug
  **24/24** · MI_8 release 回归 **24/24**。
- **真正剩余的收口项**（其余 v3.1 九项经代码核实已完成）：
  ① `VaultRepository.kt:769-774` 槽位 logcat 未清；
  ② `SettingsScreen.kt:461/729` 备份文案与双端 default 互认不符。
- **待拍板**：「印迹记录」App 边界（包名/签名策略/形态）· 6.0 主题选型（A 设备网格 vs C 端侧安全大脑）·
  小程序是否继续维护（契约仍 v1，Android 已 v2）· 应用内检查更新。
