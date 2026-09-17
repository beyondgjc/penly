# AGENTS.md — 印迹 / penly（Android）项目简报

> 给 AI 会话的项目上下文：新会话先读这里，避免重复考古。
> 原则：**稳定事实写这里，易变进度只留一行快照（带日期）**；约定变更时同步更新本文件。

## 项目是什么

「印迹 / penly」（`com.beyondguo.penly`）：端到端加密的 Android 密码管理器，
双槽位影子保险库架构；与微信小程序端「私密笔记密码箱」通过 `private-vault-backup`
v1 格式互认备份（契约两端一致，不可单方改）。

## 构建与真机

- AGP 8.7.2 需 Java 17+；系统 `JAVA_HOME` 是 jdk-11 会失败。构建前设：
  `$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'`（AS 更新后目录名带 1，Java 21）。
- 构建/装机：`./gradlew :app:installDebug`（真机 MI_8）。
- release 回归一键：`./gradlew :app:connectedReleaseAndroidTest -PtestBuildType=release`。
- 测试包 R8 两坑：androidTest 只读 `testProguardFiles()`（规则在 `app/proguard-test-rules.pro`）；
  须 `-applymapping` 对齐 app 混淆，否则启动即崩。
- **验证闭环铁律**：编译 → installDebug → logcat 复现路径无异常 → run-as 看落盘，四步自证。
- 装机签名：先 `adb shell dumpsys package` 看现有签名再选 keystore；debug 包用
  `~/.android/debug.keystore`（androiddebugkey/android）重签可覆盖。**绝不卸载应用**（清数据）。
- release 剥 Log 已落地（proguard-rules.pro `-assumenosideeffects android.util.Log`），
  但源码层仍须注意 debug 泄露——**当前槽位（A/B）绝不写日志**。

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
- 加密：AES-256-CBC + PKCS5（非 AEAD）+ PBKDF2-SHA256 10 万次。错密钥 ~1/256 概率
  padding 通过 → 表现为乱码而非异常，兜底不能只靠 catch。
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

## 发布管线

- keystore：仓库外 `release/penly-release.jks`（alias=penly），密码在
  `keystore.properties`（gitignore）；产物归档 `release/vX.Y.Z/`（mapping 勿公开）；
  `release/` 目录 untracked，「加 gitignore」是既有待办。

## 进度快照（2026-09-18）

- v2.0.0 已发布；v3.0.0 正式包已构建归档；v4.0 推进中。
- 已提交：MIUI 两步引导（d2f56da）、autofill 应用名标题+存过才提示（5a9ad2b）、AGENTS.md（bb0f5d8）。
- 双槽位 `commitSlots{}` 原子提交 + encrypt-then-MAC 四元组（含 v1.1 跨端对齐）已实现，
  单测/固定向量/真机 device test 全过（SlotWriteAtomicityDeviceTest 4/4、RecordMacDeviceTest 3/3），
  **待用户真机回归后提交**；待办：C6 槽位日志清理、备份文件级完整性（可选）。
