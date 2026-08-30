# 私密密码箱 · HarmonyOS 端实现方案

> 版本：v1.0 · 2026-08-30
> 关联文档：《Android端实现方案.md》v2.0（实施版）、《技术方案_私密密码箱.md》（小程序 v1.2）、《overview.md》
> 产品范围与小程序 v1.1 / Android 端一致：**只做密码箱**，无记事模块
> 本方案为 Android v2.0 实施版的鸿蒙移植设计：**加密契约、备份格式、交互与文案同构**，底层逐项换为 HarmonyOS NEXT 原生实现

---

## 0. 总体定位

- **不是兼容层**：HarmonyOS NEXT 不支持 APK，鸿蒙版为纯 ArkTS 重写，与 Android 端互不依赖、互不影响。
- **跨端契约不变**：`private-vault-backup` v1 备份文件三端（小程序 / Android / 鸿蒙）互认；加密契约任何一侧不得单方变更。
- **default 模式跨端互认升级**：鸿蒙沿用 Android 同一内置默认主密码常量与 `penly-def-v1` masterRef，使 **default 备份在 Android ↔ 鸿蒙之间双向直解**（小程序仍 ❌，见 §3 互认矩阵与 §12 联动建议）。
- **纯本地不连云**：无网络权限，数据迁移仅靠备份文件。

## 1. 技术选型与工程基线

| 项 | 值 |
|---|---|
| 系统 / API | HarmonyOS NEXT 5.0+，compileSdkVersion 5.0.x(API 12)（PBKDF2 Kdf、Asset Store Kit 均 API 12+，无更低版本包袱） |
| 语言 / UI | ArkTS + 声明式 ArkUI，Stage 模型 |
| 开发工具 | DevEco Studio 5.x |
| bundleName | `com.beyondguo.penly`（与 Android 一致） |
| 应用名 | 「私密密码箱」（与小程序 v1.2 / Android 对齐） |
| 工程位置 | 仓库根目录 `harmony/`（与 Android `app/` 平级，独立 hvigor 工程） |
| 权限 | 仅 `ohos.permission.PRIVACY_WINDOW`（normal 级，防截屏用）；**无网络权限**。另见 §7 剪贴板说明 |

## 2. 加密契约（逐字继承 Android §1，实现换 cryptoFramework）

| 项 | 值 |
|---|---|
| 密钥派生 | PBKDF2-HMAC-SHA256，100,000 次迭代，salt 16 字节随机，输出 32 字节 |
| 对称加密 | AES-256-CBC（PKCS7），每字段独立 16 字节随机 IV |
| 编码 | Base64 标准字母表（含 padding） |
| 校验串 | 加密 `KNOWN_PLAINTEXT = "PRIVATE_VAULT_VERIFY_TOKEN_v1"`，解锁 = 本地解密比对 |
| 字段 | `title`/`category` 明文索引；`account`/`secret`/`note` 密文 `{xEnc, xIv}` |
| 主密码来源 | 小程序 default：`wxb-def-v1::<openid>`；**Android/鸿蒙 default：`penly-def-v1::PenlyFixedDefaultMaster`（同一常量）** |

API 映射（全部异步化，Repository 签名与 Android 对齐）：

```ts
// 派生：密码按 UTF-8 显式编码，与 JS 端 utf8Encode / JVM PBEKeySpec 行为一致
const kdf = await cryptoFramework.createKdf('PBKDF2|SHA256');
const key = await kdf.generateSecret({
  password: { data: utf8(master) }, salt: { data: saltBytes },
  iterations: 100_000, keySize: 32,
} as cryptoFramework.PBKDF2Spec);

// AES-256-CBC/PKCS7，每字段独立随机 IV
const cipher = await cryptoFramework.createCipher('AES256|CBC|PKCS7');
const symKey = await cryptoFramework.createSymKeyGenerator('AES256').convertKey({ data: keyBytes });
await cipher.init(CryptoMode.ENCRYPT_MODE, symKey, { data: ivBytes } as IvParamsSpec);
const ct = await cipher.doFinal({ data: utf8(plain) });

// 随机数（salt/IV/id 用）：cryptoFramework.createRandom().generateRandomBlob(n)
// Base64：util.Base64Helper（标准字母表带 padding，与两端一致）
```

实现要点（契约敏感）：

- 常量直接搬 Android `CryptoEngine`（`KNOWN_PLAINTEXT`、迭代次数、长度、内置默认主密码）；`genId()` 同算法（`a_` + 16 hex + base36 时间戳）。
- 校验串解密**任何异常一律视为密码错误**（含 padding 错误）。
- 时间戳用 `number`（double，毫秒级 2^53 内精确）。
- cryptoFramework 无同步 API，`CryptoEngine`/`VaultRepository` 方法 async 化；明文与密钥仍仅驻内存（`SessionManager` 单例）。
- ArkTS 无反射/序列化框架：手写 `JsonCodec` 逐字段解析 + 默认值 + 忽略未知字段（等价 `ignoreUnknownKeys=true`）；导出 null 字段不写入（等价 `explicitNulls=false`）。

## 3. 备份格式与互认矩阵

`private-vault-backup` v1 格式逐字段对齐（含唯一扩展 `crypto.masterRef`），编解码行为同 Android `BackupCodec`：拒绝未知 `format`、拒绝过新 `version`、忽略未知字段、不写入 null。

互认矩阵（鸿蒙列为本方案承诺，实施后以三端实机回归为准）：

| 备份来源 \ 导入端 | 小程序 | Android | 鸿蒙 |
|---|---|---|---|
| 小程序 custom 导出 | ✅ | ✅（输主密码） | ✅（输主密码） |
| 小程序 default 导出 | ✅（openid 派生） | ✅（重加密为本地默认密钥） | ✅（同 Android 重加密路径） |
| Android custom 导出 | ✅ | ✅ | ✅（输主密码） |
| Android default 导出 | ❌ | ✅ | ✅（同常量直解，原样落地） |
| 鸿蒙 custom 导出 | ✅ | ✅（输主密码） | ✅ |
| 鸿蒙 default 导出 | ❌ | ✅（同常量直解） | ✅ |

结论不变：**custom 模式导出是通用交换格式**；鸿蒙引入后，default 备份在 Android ↔ 鸿蒙之间互通（互认矩阵新增第 4、5 行两格）。

## 4. 默认主密码模式（继承 Android §3，产品语义不变）

- 冷启动无 meta → 一次性轻引导页：「快速开始」（内置默认密码）/「设置主密码」；选择后不再出现。
- `pwdMode = default`：锁屏「轻触进入」一键解锁；设置页橙色弱提示 + 「设置主密码（推荐）」。
- 升级（只升不降）：旧密钥自动取内置常量 → 全量重加密 → 新 salt/校验串 → `pwdMode → custom`。
- default 允许导出，弹窗明示限制。**文案微调**：因 Android/鸿蒙同常量，提示从「仅本应用可再解锁」同步为「未设主密码的备份仅本应用可解锁（Android / 鸿蒙版）」，三端措辞对齐。

## 5. 生物识别（Asset Store Kit，语义对齐 Android §4）

Android 实现：Keystore「每次使用需生物验证」AES-GCM 密钥加密主密码副本 → BiometricPrompt(CryptoObject)。
鸿蒙等价能力由 **Asset Store Kit**（`@ohos.security.asset`）原生提供，语义相同、实现更简（无需自拼 iv:ct 记录，由系统管密文与认证）：

- **开启（default 与 custom 都提供，同 Android 实施定稿）**：custom 需先输一次主密码 + 生物验证；随后 `asset.set({ ALIAS:'penly_master_copy', TYPE:TYPE_SECRET, SECRET:master, REQUIRE_USER_AUTH:true, AUTH_TYPE:AUTH_TYPE_BIOMETRIC, CHALLENGE_TYPE:CHALLENGE_FROM_USER })`。
- **取用**：`userAuth.initContext()` 取 challenge → `asset.get` 触发系统生物验证 → 解出主密码 → 走正常 `unlock(master)` 零知识校验流程（不绕过校验串）。
- **冷启动自动拉起**：锁屏出现且已开启指纹时，页面 `onShown` 后自动发起认证（每锁定周期只自动拉起一次），免点击——对齐 Android 行为。MIUI CryptoObject「冷启感叹号」属 ROM 栈差异，鸿蒙无此机制，**是否出现类似过渡态列为真机验证项**。
- 可用性检测 `userAuth.getAvailableStatus(BIOMETRIC, ATL2)`；未录入时开关仍显示并引导去系统设置（文案同 Android）；Asset/系统异常翻译为中文提示。
- 改主密码 / 关闭开关 / 重置 → `asset.remove` 清除副本。

**平台差异（需知悉）**：Asset Kit 无 Android `setInvalidatedByBiometricEnrollment(true)` 的「录入新生物特征即旧副本失效」语义。缓解：改密/重置流程必清 asset（已覆盖）+「关于」页说明；鸿蒙后续若补充该语义再跟进。

## 6. 界面与交互（逐页对齐 Android v2 扁平风，**无底部导航栏**）

视觉 token 同 Android `Color.kt`（微信绿 `#07C160` 扁平白底、`#F2F3F5` 输入底、`#FF5B5B` 危险、四级文本灰阶）；浅色单主题；系统字体（HarmonyOS Sans）；沉浸式状态栏透明，各页自行系统栏避让。

| 页面 | 交互规格（同 Android v2） | ArkUI 实现要点 |
|---|---|---|
| 列表 | 大标题「密码箱」+ **右上角齿轮进「我的」** + 胶囊搜索（标题/分类/账号，账号解锁后解密参与搜索）+ 分类筛选 chips + 首字符分组 + 字母头像行；FAB 右下角（左上偏移避让） | `Navigation` 首页；`TextInput` 圆角胶囊；`List + ListItemGroup`（分组吸顶）；头像 = 圆角方块 `PenGreenSoft` 底 + `PenGreenDark` 首字符（同 `MonogramAvatar`）；FAB 用 Stack 叠加圆形主色按钮 |
| 详情 | 大标题「密码详情」；头像+名称+上次修改时间+分类；标签左、值右扁平行；**点账号行复制；点密码行复制并揭示**（默认掩码，无「显示」字样）；备注独立段；右上角铅笔进编辑 | 行点击处理复制/揭示；`Text` 掩码用等宽圆点串 |
| 编辑/新增 | 顶栏 ✕（关闭）/ 居中标题 / ✓（保存）；头像+名称内联；字段顺序 **账号→密码→分类→备注**；密码**明文直输**（无显隐开关）；编辑态底部红色删除（带确认） | `TextInput` 组合；Android Compose 的光标对齐 hack（Compose 1.6 已知问题）鸿蒙无此问题，直接左对齐内联即可 |
| 设置 | 左上角返回；立即锁定、指纹开关、修改主密码、导出/导入/粘贴导入、重置、加密说明、版本；各项文案逐字同 Android | `NavDestination`；开关 `Toggle`；确认弹窗 `AlertDialog` |
| 锁屏（两态） | default =「轻触进入」+ 指纹；custom = 主密码输入 + 指纹；冷启自动拉指纹（§5） | 根状态机覆盖层，不在导航栈内 |
| 引导页 | 快速开始 / 设置主密码，选择后不再出现 | 同上 |
| 通用 | 复制标记敏感内容 60 秒自动清空（鸿蒙无「不进预览」标记能力，见 §7）；Toast = `promptAction.showToast` | — |

根状态机不变：Loading → Onboarding（未初始化）→ Lock（未解锁）→ Ready（列表为主页的导航树：detail / edit / changepwd / settings 四个 NavDestination，无 Tab 结构）。

## 7. 数据与安全行为（逐项对齐 Android §6，平台差异标注）

- **存储**：`@ohos.data.preferences`，文件 `penly_vault`，key 同 Android（`vault_meta` / `vault_items`），值为密文 JSON；只存密文，会话密钥仅内存。跨设备迁移靠备份文件，不依赖本地存储格式互通。条目规模（几十~几百）下无写放大问题，不引入 RDB。
- **自动锁**：退后台 15 秒后锁定——`EntryAbility.onBackground` → `setTimeout(lock, 15_000)`，`onForeground` 取消。跳系统 picker（导出/导入）同样触发 onBackground，15 秒正好覆盖往返。
- **防截屏**：release 包 `setWindowPrivacyMode(true)`（需 `ohos.permission.PRIVACY_WINDOW`，normal 级），debug 包放开供 `hdc` 截图自动化——对应 Android FLAG_SECURE 策略与踩坑 2。防截屏同样会挡 `snapshot_display`，自动化技巧见 §9。
- **系统备份**：不实现 BackupExtensionAbility，系统备份/迁移不带应用数据（对齐 Android `allowBackup=false`）。
- **剪贴板**：复制行为同 Android（复制 + Toast + 60 秒后自动清空；清空前比对剪贴板内容仍为本条才清，避免误清用户后续复制）。**平台差异**：鸿蒙无 Android 13+ 的「敏感内容不进预览」标记 API，接受该差异。
- **导出**：鸿蒙无 MediaStore 等价的应用直写公共「下载」目录 API（Android 弃用 SAF 的原因即 MIUI 杀进程，属 ROM 踩坑，鸿蒙无此先例）→ 采用 `DocumentViewPicker.save`（用户选位置写 JSON，文件名 `私密密码箱备份_YYYYMMDD.json`），成功后弹窗展示保存路径需确认。跳系统 picker 由 15s 自动锁覆盖。
- **导入**：`DocumentViewPicker.select`（限 .json）+ **「粘贴导入」兜底**。粘贴导入建议改为**弹窗内多行输入框由用户粘贴**（系统输入框粘贴不需要应用读剪贴板权限，零权限且一步到位）；备选方案为申请 `ohos.permission.READ_PASTEBOARD`（user_grant，首次弹授权窗）程序直读剪贴板，与 Android 实现完全一致。**默认按零权限方案做**，见 §12 决策点 4。
- **改主密码**：旧密钥解密 → 新密钥全量重加密 → 新 salt/校验串；旧指纹副本自动清除（Asset 同步移除）。

## 8. 模块结构映射

| 鸿蒙（`harmony/entry/src/main/ets/`） | 对应 Android | 对应小程序 |
|---|---|---|
| `crypto/CryptoEngine.ets` + `SessionManager.ets` | `crypto/CryptoEngine` + `SessionManager` | `utils/crypto.js` |
| `data/VaultStore.ets` + `VaultRepository.ets` | `data/VaultStore` + `VaultRepository` | `services/localStore.js` + `vault.js` |
| `backup/JsonCodec.ets` + `BackupCodec.ets` | `backup/BackupCodec`（kotlinx-serialization） | `services/export.js` |
| `bio/BioManager.ets`（Asset Kit + userAuth） | `bio/BioManager`（Keystore + BiometricPrompt） | `utils/auth.js` |
| `ui/AppRoot.ets` + `ui/pages/*` + `ui/components/Common.ets` | `ui/AppRoot` + `ui/screens/*` + `components/Common` | `pages/vault/*`、`pages/mine`、`lock-gate`、`secret-field` |
| `util/UiUtils.ets`（剪贴板/时间/相对时间） | `util/UiUtils` | — |
| `ui/theme/Theme.ets` | `ui/theme/Color.kt` 等 | `app.wxss` 设计变量 |

其余工程件：`entryability/EntryAbility.ets`（生命周期/锁定/防截屏）、`model/VaultModels.ets`（字段名与备份格式逐字对齐）、`AppScope/app.json5`、`entry/src/main/module.json5`、`entry/src/ohosTest/`（测试）。

## 9. 自动化与验证技巧（对应 Android §8 踩坑记录的鸿蒙预判）

1. **防截屏挡 hdc 截图**：与 Android 同理，仅 release 启用隐私模式；debug 包可 `hdc shell snapshot_display -f /data/local/tmp/x.jpeg` + `hdc file recv` 取证。
2. **UI 自动化**：`hdc shell uitest uiInput click/swipe/input` 注入；控件树 `uitest dumpLayout`（对应 uiautomator dump）。
3. **应用控制**：`hdc shell aa start -b com.beyondguo.penly -a EntryAbility` 冷启；`hdc shell aa force-stop` 复现冷启；`hdc shell bm clean -n com.beyondguo.penly` 复位数据（对应 `pm clear`）。
4. **保持亮屏**：`hdc shell power-shell setmode 602`（常亮）/锁屏后需唤醒，对应 `svc power stayon usb`。
5. **MIUI 杀进程类问题**：鸿蒙系统 picker 为独立系统进程，无 Android SAF 被杀先例；如真机出现后台被回收，降级方案为「先落沙箱 + 系统分享面板另存」，列为验证项。
6. 锁屏/熄屏下 snapshot 同样可能拿到 0 字节 → 先唤醒再截。

## 10. 测试与验收

1. **hypium 单元测试**：对照 Android 9 项全量复刻，fixtures 复用 `tools/gen_test_fixtures.mjs` 产物（拷入 `entry/src/ohosTest` 资源）：PBKDF2 RFC 向量 + Node 10 万次向量、小程序 custom 备份解锁解密（中文/emoji）、错误密码拒绝、小程序 default 备份重加密迁移、Android default 导出导入回环、改主密码流、格式边界。**通过即证明三端互认。**
2. **业务流测试**：init/unlock/CRUD/changePwd/reset/export/import 全链路（同 Android Repository 语义）。
3. **真机走查**（hdc 自动化 + 截图取证，归档 `harmony/.smoke/`）：冷启引导、快速开始、录入保存、详情解密/掩码/复制揭示、搜索（含账号解密搜索）分类分组、立即锁定、轻触进入、升级主密码（重加密数据完好）、切后台自动锁、导出落盘、冷启自动指纹弹窗、设置页滚底。
4. **三端互认验收**：鸿蒙 ⇄ Android 实机互导（custom + default 双路径），鸿蒙 ⇄ 小程序（custom 路径 + 小程序 default 重加密路径）。

## 11. 里程碑

| 阶段 | 内容 | 产出 |
|---|---|---|
| M1 | 工程骨架：工程件、主题、根状态机 + 导航、空页面，DevEco 可编译运行 | 可安装空壳 |
| M2 | CryptoEngine + SessionManager + hypium 向量测试 | 三端互认证明 |
| M3 | JsonCodec / BackupCodec / VaultStore / VaultRepository + 业务测试 | 数据层完备 |
| M4 | 七个页面全量接通（列表搜索/筛选/分组、详情复制揭示、编辑、锁屏两态、设置） | 功能可用 |
| M5 | 导入导出（DocumentViewPicker + 粘贴导入） | 迁移通路打通 |
| M6 | Asset Kit 生物识别（含冷启自动拉起）+ 15s 自动锁 + 防截屏 | 安全特性齐备 |
| M7 | 冒烟截图 + 三端实机互认验收 | 交付 |

M1–M3 代码可离线编写并单测覆盖；M4–M7 需 DevEco Studio + 模拟器/真机。

## 12. 决策点（待拍板，均已给推荐项）

1. **default 模式主密码**：沿用 Android 同一常量与 `penly-def-v1`（**推荐**，Android/鸿蒙 default 备份双向直解，导入侧零改动）——否则互不相认且 Android 侧需加导入分支，不推荐。
2. **工程位置**：仓库根目录 `harmony/`（**推荐**，同仓同演进，契约变更三端同步）——还是独立仓库？
3. **视觉基线**：1:1 复刻 Android v2 扁平风（**推荐**，同构降本）——还是改用 HarmonyOS 设计规范重做？
4. **「粘贴导入」实现**：弹窗内文本框由用户粘贴，零权限（**推荐**）——还是申请 `READ_PASTEBOARD` 直读剪贴板（与 Android 实现一致，多一次授权弹窗）？
5. **导出方式**：`DocumentViewPicker.save`（**推荐**，鸿蒙无 MediaStore 等价直写 API）——还是「沙箱落盘 + 系统分享面板」？

## 13. 对三端联动的建议（对齐 Android §12）

1. **格式演进纪律**：`private-vault-backup` 只做增量扩展；三端任一发布前跑同一份互导矩阵（§3 的 6 用例）作回归，fixtures 共用 `tools/gen_test_fixtures.mjs`。
2. **小程序 default 互认**（可选）：小程序按 Android §12.1 增加 `penly-def-v1` 常量后，§3 矩阵左下两个 ❌ 即可闭合，三端 default 全互通。
3. **文案同步**：default 模式安全提示、导出限制提示三端措辞一致（含 §4 的「Android / 鸿蒙版」微调）。

## 14. 未决项（对齐 Android §13，可三端一起排期）

- 鸿蒙应用图标（按 HarmonyOS 分层图标规范生成，Android 端同为待办）。
- 密码生成器（三端一起排期）。
- 深色模式（v2 视觉仅浅色）。
- 列表右侧字母快速索引（条目多时的增强）。

## 15. 附：发现的一个 Android 侧不一致（本方案实施外，建议顺手清理）

仓库 `app/src/main/java/com/beyondguo/penly/ui/AppRoot.kt` 仍渲染底部 `NavigationBar`（密码箱/我的双 Tab）+ FAB 的 Tab 逻辑，而 Android 方案 v2.0 与 `ListScreen`（右上角齿轮进「我的」）已是「无底部导航栏」形态。属 v2 视觉改造的残留代码，建议删除该 bottomBar 分支，避免与文档口径漂移（鸿蒙侧按文档实现，不受影响）。
