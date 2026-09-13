# Autofill（自动填充）设计与调试指南

> v3.0 项目⑤。本文沉淀系统自动填充的完整行为矩阵、平台兼容性边界、
> 关键设计决策（含被否掉的方案）与调试工具箱。改这块代码前先读本文。

## 1. 行为矩阵（最终版）

| 金库状态 | 表单识别结果 | 聚焦输入框时 | 登录提交后 |
|---|---|---|---|
| 已解锁 | 有匹配条目 | 填充卡片（直接填入） | SaveInfo 已注册 → 弹保存 |
| 已解锁 | 无匹配 | **完全静默**（零数据集） | 弹保存（save-only 认领） |
| 已锁定 | 命中匹配索引 | 认证卡片（点按 → 指纹 → 填充命中项） | — |
| 已锁定 | 未命中 + 表单含密码字段 | **完全静默**（无卡片） | 弹保存（save-only 认领） |
| 已锁定 | 未命中 + 无密码字段（搜索框等） | 静默 | 不弹（`onSuccess(null)` 不参与） |

核心代码：`PenlyAutofillService.onFillRequest`、`AutofillResponseBuilder.buildFillResponse/buildSaveAnchorResponse`。

## 2. 关键设计决策

### 2.1 保存的前提是"认领表单"
Android 框架只把 `onSaveRequest` 发给**对表单返回过响应**的服务。
返回 `null`（不认领）= 保存弹窗机制上不可能出现。
系统侧证据：未认领时 `AutofillSession: handleLogContextCommitted(): last response is null`。

### 2.2 save-only response（N6 语义修正 v2 的终态）
用户产品要求：**没存过 → 聚焦阶段完全静默（不许出现任何卡片），
但保存链路必须保留**。实现采用 AOSP 官方 save-only 模式：
`FillResponse` 只含 `SaveInfo`、**零数据集**——表单被认领但无填充浮层，
提交后系统弹「保存到印迹」。

**被否掉的方案（勿回退）**：空值占位数据集（`AutofillValue.forText("")` + 可见卡片
"手动输入后提交即可保存"）。用户否决理由：聚焦时出现卡片 = 打扰；
且误点卡片会把空串填进输入框清掉已输入内容。

### 2.3 锁定态匹配索引的安全边界
`VaultRepository.autofillMatchIndex()` 免解锁直读双槽位原始存储，按 `appPackage`
匹配。明文暴露面仅标题 + 条目 id（设计内明文字段）；账号/密码/TOTP 均密文不可见；
影子诱饵条目 `appPackage` 为空串永不匹配；手动创建条目不参与匹配。

### 2.4 onSaveRequest 锁定路径
金库锁定时无法加密入库 → `onFailure("金库已锁定：请打开印迹解锁后重新提交…")`。
V1 不做"锁内解锁保存"浮层；若用户反馈差再立项。

## 3. 平台兼容性边界（预期管理）

| App 表单类型 | 填充 | 保存 | 说明 |
|---|---|---|---|
| 标准 View（EditText）+ 浏览器/WebView 网页 | ✓ | ✓ | autofill 协议主场景 |
| 自绘输入框 + 系统兼容模式（compat） | ✓（卡片可弹） | **✗** | 见 3.1 |
| Compose（未声明 autofill 语义）/ Flutter | ✗ | ✗ | 系统连请求都不发 |

### 3.1 案例：米哈游登录页（PorteLoginActivity）
米哈游统一账号 SDK 为自绘输入框，系统走兼容模式桥接：
`dumpsys autofill` 请求历史中其请求 `i=1073741826`（含 `0x2=FLAG_COMPATIBILITY_MODE_REQUEST`），
对照标准表单 `i=1073741824`。实测：印迹正常认领（save-only 响应已返回），
用户登录提交后系统**始终不发起 onSaveRequest**——兼容会话的保存判定由兼容层管理，
米哈游的提交动作不触发它。**责任在 App 侧接入方式，所有密码管理器（Bitwarden、
小米密码管家）在该页同样不弹保存**。填充不受影响（卡片 → 指纹 → 填入可正常工作）。

### 3.2 Compose 案例
Compose 的 UI 画在自绘画布上，系统 assist 结构里只有一个不透明节点，
字段零上报 → 系统不发 fill request → 服务收不到任何调用。
compose-ui 1.2（2022）起有实验性 autofill 语义、1.7（2024）正式化
（`Modifier.semantics { contentType = ... }`），但需开发者显式声明，存量 App 基本没接。
此类 App 只能引导用户进印迹手动添加（设置页已内置"自动填充兼容范围"说明）。

## 4. 调试工具箱

### 4.1 探针页（回归测试，仅 debug 构建）
`app/src/debug/.../AutofillProbeActivity.kt`：自驱型标准账密表单，`am start` 拉起后
自动聚焦 → 2s 后填值 → 1s 后 finish 提交，全程打 `PenlySaveProbe` 日志。

```
adb shell am start -n com.beyondguo.penly/.AutofillProbeActivity
adb shell dumpsys autofill | grep showsSaveUi   # true = 保存 UI 已弹出
```
改动 autofill 代码后跑一次即可确认链路死活，无需真机人肉复测。

### 4.2 系统侧 verbose 日志
```
adb shell settings put global autofill_logging_level 2   # 0=off 1=debug 2=verbose
```
注意：`setprop log.tag.*` 对 MIUI 的 autofill 无效，必须用 settings 开关。
关键日志：`AutofillManager: onActivityFinishing(): calling commitLocked()` →
`setSaveUiState(id): true`（保存判定通过）。

### 4.3 dumpsys autofill 关键字段
- `Requests history` 的 `i=` 请求标志：`0x2` 位 = 兼容模式请求（用于判定 App 接入方式）
- `showsSaveUi` / 会话段 `saveInfo=...`
- `No sessions` = 当前无活跃会话

### 4.4 MIUI 坑（已踩实）
- **后台 instrumentation 启动 Activity 被拦截**：AndroidJUnitRunner onStart 后挂死，
  ActivityScenario 无限等 RESUMED——设备测试用探针页 + shell `am start` 代替
- **SmartPower 快速回收 autofill 服务进程**（启动 800ms 即杀），建议加省电白名单
- release 包日志剥离走 `-assumenosideeffects`（debug 包日志完整保留）

## 5. 修复史速查（2026-09-13）

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| N1 | 点认证卡片无反应/崩溃 | AutofillAuthActivity 未在 Manifest 声明 | 补声明（88ce21f） |
| N3 | 无匹配→null→不认领 | buildFillResponse 返回 null | 恒返回带 SaveInfo 响应（5229566） |
| N4 | 纯 SaveInfo 疑不触发保存 | （后证实为误判，真因是测试载体为 compat App） | 占位数据集（52fa6d6，后被 N6v2 替代） |
| N5 | 浮层静默完成无反馈 | 默认模式+空库 <300ms 自动完成 | Toast 反馈（14017f0） |
| N6 | 先弹卡再解锁查匹配，顺序颠倒 | 用户产品纠偏 | 免解锁匹配前置（ae5aac8） |
| N6v2 | 未命中弹占位卡片被否 + 保存不弹 | 占位卡片违反静默要求；真因 compat | save-only 响应（f80320b）+ 探针归因（02f7e83） |

**教训**：验证 autofill 行为必须用标准 View 表单做隔离测试——在 compat/Compose App
上测，会把"App 侧不支持"误判成"平台限制"或"自己实现有 bug"。
