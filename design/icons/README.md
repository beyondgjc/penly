# 印迹 · 应用图标资产

> 品牌绿 `#07C160` · 扁平风 · 矢量源为 SVG，可无损缩放到任意分辨率
> （App Store 1024px、Android xxxhdpi 192px、Favicon 等）

## ✅ 已采用方案：指纹 v2

**2026-09-11 改版**。v1 指纹在桌面真实尺寸下不可辨，且 Android 与 iOS 两端口径不一致，故重绘。

### v1 → v2 改了什么

| 项 | v1 | v2 | 原因 |
|---|---|---|---|
| 描边宽度 | `30`（画布 2.9%） | **`76`（7.4%）** | 48px 下 1.4px → 3.6px，小尺寸才看得见 |
| 脊线间距 | 24–40 不等 | **统一 74**（7.2%） | 间距不均会让小尺寸的线条糊成一坨 |
| 字形重心 | 偏下 32px（3.1%） | **居中** | v1 视觉重量左倾 |
| 脊线条数 | 4 条（含 1 条孤立大弧） | **3 条**（2 弧 + 1 指芯） | v1 的 `M512 352 a200 200…` 与其余三条不成体系，末端凭空断掉 |
| 字形占位 | 合成图 30.5% / 自适应前景 52% | **两端口径统一 64%** | 见下 |

### 字形占位口径（关键，勿单改一侧）

统一为 **字形外接盒 = 可见区域的 64%**：

- **合成图标**（iOS / 商店 / 小程序 / 旧版 Android）：字形 = 画布 **655.36px**（64%）
- **Android 自适应**（108dp 画布 / 72dp 可见区 / 66dp 安全区）：字形 = **46.08dp**（可见区 64%）
  - 换算：`p_108 = 54 + 0.0681657 × (p_1024 − 512/510)`
  - 已实测落在 66dp 安全区内（对角半径 32.11dp < 33dp），圆 / 方圆 / 方形遮罩均不裁切

> ⚠️ **不要单独改其中一条链路。** 合成源与 `app/src/main/res/drawable/ic_launcher_foreground.xml`
> 必须同时按上式重算，否则又会退回 v1 那种「Android 上饱满、iOS 上缩小」的双轨不一致。

## 文件与产物

| 文件 | 作用 |
|---|---|
| `yinji-icon-6-fingerprint.svg` | 合成图标源（1024 画布，含底色圆角方）——**唯一真源** |
| `yinji-icon-6-composite.svg` | 与上者内容一致；`render_icons_node.js` 的渲染入口 |
| `render_icons_node.js` | 一次渲染、**双写**：`export/**`（跨平台归档）+ `app/src/main/res/mipmap-**`（工程实际引用） |
| `yinji-icon-1..5-*.svg` | 早期备选方案，仅留档，未接入 |
| `preview.html` | 浏览器打开可横向对比 6 款方案 |
| `export/` | Android / iOS / 商店 / 小程序全套位图 |

### 生成的位图（`node render_icons_node.js`）

| 平台 | 尺寸 |
|---|---|
| Android mipmap | mdpi 48 · hdpi 72 · xhdpi 96 · xxhdpi 144 · xxxhdpi 192（`ic_launcher` + `ic_launcher_round`） |
| 应用商店 | 512 · 1024 |
| 小程序 | 144 · 512 |
| iOS | 60@2x 120 · 60@3x 180 · 1024 |

## Android 侧接入状态

- `mipmap-anydpi/ic_launcher.xml` + `ic_launcher_round.xml`：**自适应图标（已接入）**
  - `<background>` → `drawable/ic_launcher_background.xml`（108dp 全出血纯 `#07C160`，不加圆角）
  - `<foreground>` → `drawable/ic_launcher_foreground.xml`（108dp 视口，字形 46.08dp）
  - `<monochrome>` → 复用前景层（Android 13+ 主题图标取 alpha 通道）
- `mipmap-{mdpi..xxxhdpi}/ic_launcher{,_round}.png`：旧版 Android（<8）兜底位图

> 注：`ic_launcher_round.png` 目前渲染的是圆角方形（沿用历史行为）。现代 Android 由系统遮罩负责圆形裁切，
> 只有旧版圆角启动器才会用到这张图；若要严格圆形可另出一版。

## 落地建议

- **改字形**：只改 `yinji-icon-6-fingerprint.svg` → 跑 `render_icons_node.js` →
  按 §字形占位口径 重算 `ic_launcher_foreground.xml`（生成脚本见工作区 `印迹图标改版/src/build_android_assets.cjs`）。
- **iOS**：直接用合成 PNG，1024px 的圆角由系统处理，源文件保留 224 直角圆角即可。
- **小程序**：微信对图标有圆角与尺寸要求，按平台导出对应 PNG。
- **验证闭环**：重出位图 → `./gradlew :app:installDebug` → 桌面 + 设置「应用信息」+ 最近任务三处确认 →
  圆形与方圆遮罩各看一次。

> 设计来源：与 `yinji-figma-design-system.md` 的 Color Token 一致（brand `#07C160`、brand.soft `#E6F9EF`）。
