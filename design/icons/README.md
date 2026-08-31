# 私密密码箱 Penly · 应用图标方案

> 6 款矢量图标，统一套用品牌绿 `#07C160` 与 v2 扁平风，圆角 224/1024（≈22%）。
> 矢量源文件为 SVG，可无损缩放至任意分辨率（含 App Store 1024px、Android xxxhdpi 192px、Favicon 等）。

## 方案一览

| 编号 | 文件 | 视觉 | 适用调性 |
|---|---|---|---|
| 1 | `penly-icon-1-shield.svg` | 绿底 + 白盾牌 + 绿钥匙孔 | 经典安全语义，强识别（**推荐首选**） |
| 2 | `penly-icon-2-lock.svg` | 白底 + 绿智能锁 | 干净轻盈，贴近系统原生风格 |
| 3 | `penly-icon-3-vault.svg` | 绿底 + 白保险箱 | 强调「本地自托管保险箱」 |
| 4 | `penly-icon-4-key.svg` | 浅绿底 `#E6F9EF` + 绿钥匙 | 柔和差异化，显轻快 |
| 5 | `penly-icon-5-hexagon.svg` | 绿六边形 + 白钥匙孔 | 几何现代感，科技/加密调性 |
| 6 | `penly-icon-6-fingerprint.svg` | 绿底 + 白指纹 | 呼应 App 的生物识别解锁能力 |

## 预览
浏览器打开 `preview.html` 可横向对比 6 款。

## 落地建议
- **Android 12+ 自适应图标**：当前为合成预览图；正式接入时按官方规范拆分为 **背景层（foreground/background）**，背景用纯 `#07C160`、前景用白色字形，交由系统遮罩（圆形/方圆形/squircle）裁切，保证多机型一致。
- **iOS**：直接用合成 PNG（1024px 圆角由系统处理，源文件保留直角圆角 224 即可）。
- **小程序**：微信对图标有圆角与尺寸要求，按平台导出对应 PNG。
- 选定方案后，我可补充：① 各分辨率 PNG 导出脚本；② Android `mipmap-anydpi`/`ic_launcher` 自适应图标资源；③ 应用商店不同尺寸的变体（含圆角/留白微调）。

> 设计来源：与 `penly-figma-design-system.md` 的 Color Token 完全一致（brand `#07C160`、brand.soft `#E6F9EF`）。
