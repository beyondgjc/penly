// 用 @resvg/resvg-js（预编译原生二进制，无需系统级 cairo/imagemagick）
// 把完整合成图标 yinji-icon-6-composite.svg 渲染为各平台/分辨率 PNG。
// 与 render_icons.py 输出目标一致，作为无 reportlab cairo 后端时的替代渲染管线。
//
// ⚠️ 一次渲染、双写：
//   ① design/icons/export/**  —— 跨平台归档（Android / iOS / 商店 / 小程序）
//   ② app/src/main/res/mipmap-** —— Android 工程实际引用的资源
//   以前这两处靠手工复制同步，正是「双轨缩放不一致」得以长期潜伏的原因，故改为单次渲染双写。
//   注意：AGP 合并资源时会按前缀去重，这里刻意用同前缀（ic_launcher.png）无冲突；
//         产物为全彩位图（非 .webp/.9.png），放 mipmap-* 是正确的密度桶。
const fs = require("fs");
const path = require("path");
const { Resvg } = require("C:/Users/beyondguo/.workbuddy/binaries/node/workspace/node_modules/@resvg/resvg-js");

const HERE = __dirname;
const ROOT = path.resolve(HERE, "..", ".."); // penly 仓库根
const SRC = path.join(HERE, "yinji-icon-6-composite.svg");
const RES = path.join(ROOT, "app", "src", "main", "res");

// [归档子目录, 工程内 res 子目录(可选), 文件名, 边长px]
const TARGETS = [
  ["android/mipmap-mdpi",   "mipmap-mdpi",    "ic_launcher.png", 48],
  ["android/mipmap-mdpi",   "mipmap-mdpi",    "ic_launcher_round.png", 48],
  ["android/mipmap-hdpi",   "mipmap-hdpi",    "ic_launcher.png", 72],
  ["android/mipmap-hdpi",   "mipmap-hdpi",    "ic_launcher_round.png", 72],
  ["android/mipmap-xhdpi",  "mipmap-xhdpi",   "ic_launcher.png", 96],
  ["android/mipmap-xhdpi",  "mipmap-xhdpi",   "ic_launcher_round.png", 96],
  ["android/mipmap-xxhdpi", "mipmap-xxhdpi",  "ic_launcher.png", 144],
  ["android/mipmap-xxhdpi", "mipmap-xxhdpi",  "ic_launcher_round.png", 144],
  ["android/mipmap-xxxhdpi","mipmap-xxxhdpi", "ic_launcher.png", 192],
  ["android/mipmap-xxxhdpi","mipmap-xxxhdpi", "ic_launcher_round.png", 192],
  ["store",       null, "ic_launcher_512.png", 512],
  ["store",       null, "ic_launcher_1024.png", 1024],
  ["miniprogram", null, "icon_144.png", 144],
  ["miniprogram", null, "icon_512.png", 512],
  ["ios",         null, "icon-60@2x.png", 120],
  ["ios",         null, "icon-60@3x.png", 180],
  ["ios",         null, "icon-1024.png", 1024],
];

function main() {
  if (!fs.existsSync(SRC)) throw new Error("源文件缺失: " + SRC);
  const svg = fs.readFileSync(SRC, "utf8");
  let count = 0;
  for (const [sub, resSub, name, size] of TARGETS) {
    const png = new Resvg(svg, { fitTo: { mode: "width", value: size } }).render().asPng();
    const dirs = [path.join(HERE, "export", sub)];
    if (resSub) dirs.push(path.join(RES, resSub));
    for (const dir of dirs) {
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, name), png);
      count++;
    }
    console.log("  " + name + "  (" + size + "px) → " + dirs.length + " 处");
  }
  console.log(`done: ${count} 个文件（归档 export/ + 工程 res/）`);
}

main();
