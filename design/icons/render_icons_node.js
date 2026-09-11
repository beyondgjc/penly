// 用 @resvg/resvg-js（预编译原生二进制，无需系统级 cairo/imagemagick）
// 把图标矢量源渲染为各平台/分辨率 PNG。
//
// ⚠️ 两个源，用途严格分开 —— 混用会出上架事故：
//   yinji-icon-6-composite.svg（圆角 + 四角透明）
//     → 仅用于 Android 传统 mipmap 位图。Android 7 及以下的启动器直接原样显示位图，
//       图标需要自带形状，所以传统位图带圆角是对的。
//   yinji-icon-6-square.svg（四边直角 + 满出血 + 无 alpha）
//     → iOS App Store / 应用商店列表图 / 微信小程序头像。
//       这些平台会【自己加遮罩】（iOS 超椭圆、微信裁圆形），且 App Store Connect
//       明确拒绝含 alpha 通道的图标 —— 自己加圆角会既被拒又露底。
//
// ⚠️ 一次渲染、双写：
//   ① design/icons/export/**       —— 跨平台归档
//   ② app/src/main/res/mipmap-**   —— Android 工程实际引用
//   以前这两处靠手工复制同步，正是「双轨缩放不一致」得以长期潜伏的原因。
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const { Resvg } = require("C:/Users/beyondguo/.workbuddy/binaries/node/workspace/node_modules/@resvg/resvg-js");

const HERE = __dirname;
const ROOT = path.resolve(HERE, "..", ".."); // penly 仓库根
const RES = path.join(ROOT, "app", "src", "main", "res");
const SRC_ROUND = path.join(HERE, "yinji-icon-6-composite.svg");
const SRC_SQUARE = path.join(HERE, "yinji-icon-6-square.svg");

// [归档子目录, 工程内 res 子目录(可选), 文件名, 边长px, 用哪个源, 是否去 alpha]
const TARGETS = [
  // Android 传统位图：自带圆角
  ["android/mipmap-mdpi",   "mipmap-mdpi",    "ic_launcher.png", 48,  "round",  false],
  ["android/mipmap-mdpi",   "mipmap-mdpi",    "ic_launcher_round.png", 48,  "round",  false],
  ["android/mipmap-hdpi",   "mipmap-hdpi",    "ic_launcher.png", 72,  "round",  false],
  ["android/mipmap-hdpi",   "mipmap-hdpi",    "ic_launcher_round.png", 72,  "round",  false],
  ["android/mipmap-xhdpi",  "mipmap-xhdpi",   "ic_launcher.png", 96,  "round",  false],
  ["android/mipmap-xhdpi",  "mipmap-xhdpi",   "ic_launcher_round.png", 96,  "round",  false],
  ["android/mipmap-xxhdpi", "mipmap-xxhdpi",  "ic_launcher.png", 144, "round",  false],
  ["android/mipmap-xxhdpi", "mipmap-xxhdpi",  "ic_launcher_round.png", 144, "round",  false],
  ["android/mipmap-xxxhdpi","mipmap-xxxhdpi", "ic_launcher.png", 192, "round",  false],
  ["android/mipmap-xxxhdpi","mipmap-xxxhdpi", "ic_launcher_round.png", 192, "round",  false],
  // 平台自己加遮罩 → 满出血正方形 + 去 alpha
  ["store",       null, "ic_launcher_512.png",  512,  "square", true],
  ["store",       null, "ic_launcher_1024.png", 1024, "square", true],
  ["miniprogram", null, "icon_144.png",         144,  "square", true],
  ["miniprogram", null, "icon_512.png",         512,  "square", true],
  ["ios",         null, "icon-60@2x.png",       120,  "square", true],
  ["ios",         null, "icon-60@3x.png",       180,  "square", true],
  ["ios",         null, "icon-1024.png",        1024, "square", true],
];

// ---- 去 alpha 用的小工具（App Store Connect 要求图标不得含 alpha 通道）----
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = c ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function encodeRGB(w, h, rgb) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; // 8bit, truecolor RGB（无 alpha）
  const stride = w * 3;
  const raw = Buffer.alloc(h * (stride + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0;
    rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
// 解码（只处理 resvg 会产出的 8bit 非隔行 RGBA）
function decodeRGBA(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not png");
  let off = 8, w = 0, h = 0, ct = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString("ascii", off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === "IHDR") { w = data.readUInt32BE(0); h = data.readUInt32BE(4); ct = data[9]; }
    else if (type === "IDAT") idat.push(data);
    else if (type === "IEND") break;
    off += 12 + len;
  }
  if (ct === 2) return null; // 已是 RGB
  if (ct !== 6) throw new Error("unexpected colorType " + ct);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * 4, out = Buffer.alloc(h * stride);
  const paeth = (a, b, c) => {
    const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  };
  let p = 0;
  for (let y = 0; y < h; y++) {
    const f = raw[p++], line = raw.subarray(p, p + stride); p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= 4 ? cur[x - 4] : 0, b = prev[x], c = x >= 4 ? prev[x - 4] : 0;
      let v = line[x];
      if (f === 1) v += a; else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1; else if (f === 4) v += paeth(a, b, c);
      cur[x] = v & 255;
    }
  }
  return { w, h, data: out };
}
function stripAlpha(png) {
  const img = decodeRGBA(png);
  if (!img) return png;
  const rgb = Buffer.alloc(img.w * img.h * 3);
  for (let i = 0, j = 0; i < img.data.length; i += 4, j += 3) {
    rgb[j] = img.data[i]; rgb[j + 1] = img.data[i + 1]; rgb[j + 2] = img.data[i + 2];
  }
  return encodeRGB(img.w, img.h, rgb);
}

function main() {
  const cache = {
    round: fs.readFileSync(SRC_ROUND, "utf8"),
    square: fs.readFileSync(SRC_SQUARE, "utf8"),
  };
  let count = 0, stripped = 0;
  for (const [sub, resSub, name, size, which, dropAlpha] of TARGETS) {
    let png = new Resvg(cache[which], { fitTo: { mode: "width", value: size } }).render().asPng();
    if (dropAlpha) { png = stripAlpha(png); stripped++; }
    const dirs = [path.join(HERE, "export", sub)];
    if (resSub) dirs.push(path.join(RES, resSub));
    for (const dir of dirs) {
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, name), png);
      count++;
    }
    console.log("  " + name.padEnd(22) + size + "px  " + which + (dropAlpha ? "  → 已去 alpha" : ""));
  }
  console.log(`done: ${count} 个文件（归档 export/ + 工程 res/），其中 ${stripped} 张去掉了 alpha 通道`);
}

main();
