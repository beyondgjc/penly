// 用 @resvg/resvg-js（预编译原生二进制，无需系统级 cairo/imagemagick）
// 把完整合成图标 penly-icon-6-composite.svg 渲染为各平台/分辨率 PNG。
// 与 render_icons.py 输出目标一致，作为无 reportlab cairo 后端时的替代渲染管线。
const fs = require("fs");
const path = require("path");
const { Resvg } = require("C:/Users/beyondguo/.workbuddy/binaries/node/workspace/node_modules/@resvg/resvg-js");

const HERE = __dirname;
const SRC = path.join(HERE, "penly-icon-6-composite.svg");

// [子目录, 文件名, 边长px]
const TARGETS = [
  ["android/mipmap-mdpi",   "ic_launcher.png", 48],
  ["android/mipmap-mdpi",   "ic_launcher_round.png", 48],
  ["android/mipmap-hdpi",   "ic_launcher.png", 72],
  ["android/mipmap-hdpi",   "ic_launcher_round.png", 72],
  ["android/mipmap-xhdpi",  "ic_launcher.png", 96],
  ["android/mipmap-xhdpi",  "ic_launcher_round.png", 96],
  ["android/mipmap-xxhdpi", "ic_launcher.png", 144],
  ["android/mipmap-xxhdpi", "ic_launcher_round.png", 144],
  ["android/mipmap-xxxhdpi","ic_launcher.png", 192],
  ["android/mipmap-xxxhdpi","ic_launcher_round.png", 192],
  ["store",  "ic_launcher_512.png", 512],
  ["store",  "ic_launcher_1024.png", 1024],
  ["miniprogram", "icon_144.png", 144],
  ["miniprogram", "icon_512.png", 512],
  ["ios", "icon-60@2x.png", 120],
  ["ios", "icon-60@3x.png", 180],
  ["ios", "icon-1024.png", 1024],
];

function main() {
  if (!fs.existsSync(SRC)) throw new Error("源文件缺失: " + SRC);
  const svg = fs.readFileSync(SRC, "utf8");
  for (const [sub, name, size] of TARGETS) {
    const outDir = path.join(HERE, "export", sub);
    fs.mkdirSync(outDir, { recursive: true });
    const out = path.join(outDir, name);
    const resvg = new Resvg(svg, { fitTo: { mode: "width", value: size } });
    const png = resvg.render().asPng();
    fs.writeFileSync(out, png);
    console.log("  " + out + "  (" + size + "px)");
  }
  console.log("done -> " + path.join(HERE, "export"));
}

main();
