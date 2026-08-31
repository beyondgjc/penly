# 印迹（印迹）· Figma 设计稿 / 设计系统规范

> 适用版本：印迹 Android v1.0.0（代码态为「v2 扁平风」）
> 用途：作为 Figma 设计源文件蓝图，支撑后续迭代与 Android / iOS / HarmonyOS / 微信小程序 多端对齐
> 数据来源：全部尺寸、配色、字号取自 `android/app/.../ui/theme/*` 与 7 个 Screen 源码，非主观设定

---

## 0. Figma 文件结构建议（Page 划分）

| Page | 内容 |
|---|---|
| 🎨 Tokens | 颜色 / 字号 / 间距 / 圆角 / 投影 变量（由 `yinji-design-tokens.json` 导入） |
| 🧩 Components | 按钮、输入框、卡片、列表行、字母头像、顶栏、FAB、搜索框、Chip、开关、对话框、空状态 |
| 📱 Screens | 引导 / 锁屏 / 列表 / 详情 / 编辑 / 我的 / 改密（各 1 个 Frame，含主要状态） |
| 🔁 Multi-Platform | 跨端差异说明与共用 Token 对照（见第 5 节） |

**画板基准**：Android 设计用 **360 × 800 @1x**（mdpi 基线），导出 3x 为 1080 × 2400。与 iOS 对齐时复用同一套 Token，仅画板改为 **390 × 844**。

---

## 1. 设计 Token

### 1.1 颜色（Color）

#### 品牌 / 语义色
| Token | Hex | 用途（代码出处） |
|---|---|---|
| `color.brand` (PenGreen) | `#07C160` | 主色：主按钮、图标、链接、强调 |
| `color.brand.dark` (PenGreenDark) | `#06B154` | 主色按压态 / 字母头像文字色 |
| `color.brand.soft` (PenGreenSoft) | `#E6F9EF` | 主色浅底：字母头像背景、primaryContainer |
| `color.danger` (PenDanger) | `#FF5B5B` | 错误、删除、重置、危险操作 |
| `color.danger.soft` (PenDangerSoft) | `#FFEDED` | 删除按钮底色 |
| `color.warn` (PenWarn) | `#FF9500` | 警示：默认保护模式提示、未设主密码 |
| `color.info` (PenInfo) | `#576B95` | 次级信息色：详情页分类标签文字 |

#### 中性 / 背景 / 文本灰阶
| Token | Hex | 用途 |
|---|---|---|
| `color.bg` (PenBg) | `#FFFFFF` | 页面背景 |
| `color.bg.soft` (PenBgSoft) | `#F2F3F5` | 浅灰底：输入框背景、设置卡片底、分区底 |
| `color.surface` (PenCard) | `#FFFFFF` | 卡片 / 浮层表面 |
| `color.text.1` (PenText1) | `#1A1D24` | 标题、主文本 |
| `color.text.2` (PenText2) | `#2B2F36` | 正文 |
| `color.text.3` (PenText3) | `#8A909C` | 次级文本 / 占位符 / 说明 / 组标题 |
| `color.text.4` (PenText4) | `#B6BCC6` | 描边 / 禁用 / 空状态图标 |
| `color.line` (PenLine) | `#111827` @ **6%** 透明度 | 分隔线（1dp） |

> 分隔线在 Figma 中记为：`#111827` + Opacity 6%（源码 `0x0F111827`，alpha=0x0F≈6%）。

### 1.2 字号（Typography）

字体族：**系统无衬线**（Android Roboto + 中文回退；iOS SF Pro / PingFang；小程序 system-ui）。西文 Roboto，中文 PingFang SC / 思源黑体。

| Token | 字号 | 字重 | 颜色 | 对应 M3 角色 / 出处 |
|---|---|---|---|---|
| `text.display` | 28sp | Bold(700) | text.1 | 列表/详情页大标题（硬编码 28sp） |
| `text.title-lg` | 22sp | Bold(700) | text.1 | M3 titleLarge |
| `text.title-md` | 17sp | SemiBold(600) | text.1 | M3 titleMedium（编辑/详情标题） |
| `text.body-lg` | 16sp | Regular(400) | text.2 | M3 bodyLarge |
| `text.body-md` | 14sp | Regular(400) | text.2 | M3 bodyMedium |
| `text.body-sm` | 12sp | Regular(400) | text.3 | M3 bodySmall / labelMedium |
| `text.group` | 13sp | Regular(400) | text.3 | 列表分组头（硬编码 13sp） |

行高：标题 1.25×，正文 1.5×。

### 1.3 间距（Spacing，4dp 基准）

| Token | dp | 典型使用 |
|---|---|---|
| `space.1` | 4 | 图标与文字间隙 |
| `space.2` | 8 | 顶栏内边距纵 / 小组间隙 |
| `space.3` | 12 | 顶栏横内边距 / 区块上下 |
| `space.4` | 16 | 设置页横内边距 / SettingRow 纵内边距 |
| `space.5` | 20 | **主屏横内边距（列表/详情/编辑统一 20）** |
| `space.6` | 24 | 大段间距 |
| `space.7` | 28 | 锁屏/引导外边距 |
| `space.8` | 36 | FAB 底部偏移 / 锁屏末端 |
| `space.10` | 40 | 编辑页底部留白 |
| `space.14` | 56+ | 列表底部留白（110dp，含 FAB 避让） |

### 1.4 圆角（Radius）

| Token | dp | 用途 |
|---|---|---|
| `radius.full` | 999 | 搜索框（圆形） |
| `radius.avatar` | size × 1/3 | 字母头像（44→~15，52→~17，56→~19） |
| `radius.btn` | 20 | 主/次/危险按钮（M3 默认） |
| `radius.card` | 12 | 卡片 / 设置卡（M3 默认） |
| `radius.delete` | 14 | 编辑页「删除记录」按钮（硬编码） |

### 1.5 投影 / 层级（Elevation）

**扁平风为主**：组件无投影或仅 1dp 极淡投影。背景区隔靠 `bg.soft` 而非阴影。
- `SettingCard` elevation = 0
- 普通 `Card` 默认 1dp（可忽略，视觉接近无）
- 对话框：`AlertDialog` 默认浮层（无需自绘阴影）

### 1.6 图标

- 图标库：Material Symbols / Material Icons（与 Compose `Icons` 一致）
- 图标尺寸：导航/操作 24dp；空状态 64dp；锁屏盾牌 76dp、引导盾牌 84dp；字母头像内文字 = 头像尺寸 × 0.42
- 图标色：默认 `text.3`；主操作/品牌图标 `brand`；危险 `danger`

---

## 2. 组件库（Components）

### 2.1 按钮 Button
- **主按钮 Primary**：底 `brand`，文字白；高 **52dp**，圆角 `radius.btn`，全宽
- **次按钮 Outlined**：描边 `brand`，文字 `brand`；高 52dp（引导页「设置主密码」）
- **危险按钮**：底 `danger.soft`，文字 `danger`；高 50dp（编辑页「删除记录」，圆角 14）
- **文字按钮 TextButton**：无底，文字 `brand`/`danger`；用于对话框、链接
- 状态：default / disabled（opacity 0.6）/ pressed（主色→`brand.dark`）

### 2.2 输入框 Input
- **OutlinedTextField（密码/主密码）**：label 上浮；圆角 `radius.btn`；默认 M3 描边
- **搜索框**：全宽、底 `bg.soft`、无边框（`transparent`）、**圆形**；前置搜索图标 `text.3`，placeholder `text.3`
- **FlatInputRow（编辑页扁平行）**：左标签（`text.1`，Medium），右输入（文字 `text.1`、**右对齐** `TextAlign.End`），行高内边距 16dp 纵 / 20dp 横；占位词垫在右端

### 2.3 卡片 Card
- **SettingCard**：底 `bg.soft`，elevation 0，圆角 `radius.card`，内部 `SettingRow` 纵向堆叠
- **通用 Card**：底 `surface`，用于引导/改密信息卡

### 2.4 列表行 Row
- **VaultRow（印迹列表项）**：横内边距 20 / 纵 10；左 `MonogramAvatar(44)` + 间距 14 + 右侧（标题 `body-lg` Medium `text.1` 单行省略；副标题账号 `body-sm` `text.3`）
- **ValueRow（详情信息行）**：纵内边距 18；左标签 `body-lg` Medium `text.1`，右值 `body-lg`（空值显 `text.3`）；点击整行复制
- **SettingRow（我的列表项）**：横内边距 16 / 纵 14；左标题 `body-lg`（危险态 `danger`）+ 副标题 `body-sm` `text.3`，右侧 trailing（Switch/文字）

### 2.5 字母头像 MonogramAvatar
- 圆角方形（radius = size/3），底 `brand.soft`，文字取首字符大写、色 `brand.dark`、字号 size×0.42
- 常用尺寸：40（默认）/ 44（列表）/ 52（编辑）/ 56（详情）

### 2.6 顶部栏 Top Bar
- **列表页自定义栏**：无系统 TopAppBar；左侧大标题「印迹」28sp Bold，右侧 `Settings` 图标（`text.3`）→ 我的
- **标准 TopAppBar（我的/改密）**：左返回箭头，居中标题（「我的」/「设置主密码」/「修改主密码」），底 `bg`，无阴影
- **编辑页自定义栏**：左 `Close` 图标，居中标题「新增/编辑记录」17sp SemiBold，右 `Check` 图标（`brand`，busy 时变灰）

### 2.7 浮动按钮 FAB
- 右下角：`padding` end 8 / bottom 36；默认 M3 FAB（含 `+` 图标），仅列表页显示，跳转新增

### 2.8 筛选 Chip / 开关 / 对话框 / 空状态
- **FilterChip**：分类筛选（全部 / 各 category），选中态 `brand`
- **Switch**：指纹解锁开关（设置页）
- **AlertDialog / ConfirmDialog**：标题 + 正文 + 确定(brand/danger) + 取消；用于重置/删除/导入确认、加密说明、导出成功
- **空状态**：居中图标 64dp `text.4` + 文字 `text.3`（「暂无记录，点击右下角添加」/「无匹配记录」）

---

## 3. 逐屏规范（Screens）

> 以下每屏 Frame = 360 × 800，横向统一内边距见 Token。

### 3.1 引导 Onboarding（未初始化首屏）
- 居中布局，外边距 28
- 盾牌图标 84dp `brand` → 标题「印迹」22sp Bold → 副标题「端到端加密 · 明文不出本机」12sp `text.3`
- 信息卡（Card，内边距 16）：标题「两种开始方式」17sp + 说明 12sp `text.2`
- 主按钮「快速开始（默认保护）」52dp 全宽；次按钮「设置主密码」52dp
- 展开「设置主密码」态：两个密码 OutlinedTextField（主密码/确认，≥8 位）+ 错误 12sp `danger` + 主按钮「创建并进入」+ 文字按钮「返回」

### 3.2 锁屏 Lock
- 居中，外边距 28
- 锁图标 76dp `brand` → 标题「印迹」22sp Bold → 间距 28
- **默认模式**：提示「默认保护模式 · 未设主密码」12sp `warn` → 主按钮「轻触进入」52dp
- **主密码模式**：OutlinedTextField「主密码」（圆点掩码）→ 主按钮「解锁」52dp
- 指纹可用时：`FilledTonalButton`「指纹解锁」52dp（图标+文字）
- 错误 12sp `danger`；底部文字按钮「忘记主密码？重置印迹」`danger`

### 3.3 列表 List（主页）
- 顶栏：上下 8 间距，左大标题「印迹」28sp Bold + 右 `Settings` 图标
- 搜索框（圆形，bg.soft，无边框），横内边距 20
- 分类 FilterChip 横向滚动（间距 8，内边距 20）
- 分组列表：组头 13sp `text.3`（横内边距 20，纵 8）；每项 `VaultRow`
- 底部留白 110dp（避让 FAB）
- 空态：居中图标 + 文字
- FAB 右下（end 8 / bottom 36）→ 新增

### 3.4 详情 Detail
- 自定义顶栏：左 `ArrowBack`、右 `Edit`
- 大标题「密码详情」28sp Bold
- 头像 56 + 名称 17sp SemiBold + 「上次修改：…」12sp `text.3`；分类标签 12sp `brand`
- `ValueRow` 账号（点击复制）/ 密码（默认 `••••••••••`，点击揭示并复制）/ 备注（block）
- 行间 `HorizontalLine`（1dp `line`）

### 3.5 编辑 Edit（新增/编辑共用）
- 顶栏：左 `Close`、中「新增/编辑记录」17sp SemiBold、右 `Check`(`brand`)
- 头像 52 + 内联名称输入（placeholder「名称」，title-md）
- `FlatInputRow` ×3：账号 / 密码 / 分类（默认「默认」），分隔线 1dp
- 备注 block：标签「备注」+ 多行输入（placeholder「补充说明…」，minLines 3）
- 编辑态底部：危险按钮「删除记录」50dp（底 `danger.soft` 文字 `danger`，圆角 14）

### 3.6 我的 Settings
- 标准 TopAppBar「我的」+ 返回
- 状态卡（SettingCard）：钥匙图标 `brand` + 标题（默认/主密码保护）+ 副标题（默认态 `warn`）；默认态含主按钮「设置主密码（推荐）」
- SectionTitle「安全」→ SettingCard：立即锁定 / 指纹解锁(Switch)
- SectionTitle「数据」→ SettingCard：修改主密码 / 导出数据 / 导入数据 / 重置印迹(danger)
- SectionTitle「关于」→ SettingCard：加密说明 / 版本「1.0.0」
- 底部居中声明「印迹 · 端到端加密，密钥不出本机」12sp `text.3`

### 3.7 改密 ChangePwd（set / change）
- 标准 TopAppBar（「设置主密码」/「修改主密码」）+ 返回
- 信息卡（Card，内边距 14，文字 `info`）
- set 模式：新主密码 + 确认；change 模式：旧 + 新 + 确认（均 OutlinedTextField，≥8 位）
- 错误 12sp `danger`；主按钮「设置/确认修改」52dp

---

## 4. 交互与状态要点（供还原）
- 主密码错误 → 锁屏提示「主密码错误」；指纹失效 → 「本地凭证已失效，请手动解锁」
- 详情密码：点行即揭示明文 + 复制（无独立眼睛开关，与参考一致）
- 列表点击整行进入详情；编辑保存 Toast「已保存」
- 删除 / 重置 / 导入均为二次确认对话框（danger）
- 仅浅色主题（深色模式为后续版本）

---

## 5. 多端对齐映射（Multi-Platform Parity）

印迹 Android 的 Token **已与微信小程序 `app.wxss` 设计变量对齐**（见 `Color.kt` 注释）。跨端原则：**共用同一套 Hex Token，仅布局容器因平台规范而异**。

| 维度 | Android (印迹) | 微信小程序 | iOS | HarmonyOS |
|---|---|---|---|---|
| 主色 / 文本灰阶 | 本表 Token | 同（已对齐） | 复用同 Hex | 复用同 Hex |
| 导航容器 | 列表页「我的」图标入口（无底部 Tab） | 微信 TabBar（外壳独有） | 底部 Tab / 我的入口均可 | 底部 Tab（参考 MIUI） |
| 顶栏 | 列表自定义 / 标准 TopAppBar | 微信导航栏 | 大标题导航栏 | ArkUI 导航栏 |
| 列表项 | VaultRow（头像+标题+副标题） | 同结构 | 同结构 | 同结构 |
| 加密说明文案 | 见 Settings「加密说明」 | 同 | 同 | 同 |

> ⚠️ 已知漂移风险：鸿蒙方案曾出现「残留底部导航栏」，多端还原时需确保移动端（Android/iOS）**不出现多余底部 Tab**，小程序 Tab 仅为其外壳。

---

## 6. 交付与下一步
1. 用 `yinji-design-tokens.json` 在 Figma 通过 **Tokens Studio / 变量导入** 生成 Color / Number / String 变量。
2. 按第 2 节在 🧩 Components 页搭组件（建议用 Figma 变体 Variants 表达按钮/输入状态）。
3. 按第 3 节在 📱 Screens 页逐屏还原（可先锁屏 + 列表 + 详情 + 编辑 4 个主流程）。
4. 多端评审时对照第 5 节映射表，确认 Token 一致、无明显布局漂移。
5. 后续迭代：新增屏幕/组件 → 先补 Token → 再建组件 → 再入屏，保持「设计系统优先」。

> 注：本规范与 `android/` 源码 1:1 对应，代码改动后需同步此处（建议将本文件与 `yinji-design-tokens.json` 纳入仓库 `design/` 目录共同版本管理）。
