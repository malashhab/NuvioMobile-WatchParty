// Draws the Nuvio WP logo (two screens and three friends) and writes it where it's used.
// Run from the repo root:
//   node scripts/generate_wp_icons.js readme   the GitHub page logo
//   node scripts/generate_wp_icons.js icons    the Android and iPhone home-screen icons
// Images are drawn by Microsoft Edge in headless mode, then resized and saved with Node's zlib.
// Only the home-screen icons change: the start screen and the logos inside the app stay Nuvio's.

const fs = require("fs");
const os = require("os");
const path = require("path");
const zlib = require("zlib");
const { execFileSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const EDGE = [
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
].find((p) => fs.existsSync(p));

// The six icon color versions the app offers.
const PALETTES = {
  original: ["#45D6EB", "#3965D4", "#B250EF"],
  arctic_blue: ["#4DE3FF", "#3185F5", "#4D55E8"],
  emerald: ["#7BF08D", "#22D37C", "#0BBF9A"],
  rose_gold: ["#B75AFF", "#EC70A9", "#FFB37A"],
  copper: ["#C95945", "#DE7B45", "#ECAE62"],
  graphite: ["#F3F5F7", "#AAB2BE", "#687381"],
};
const IOS_SETS = {
  original: "AppIcon",
  arctic_blue: "AppIconArcticBlue",
  emerald: "AppIconEmerald",
  rose_gold: "AppIconRoseGold",
  copper: "AppIconCopper",
  graphite: "AppIconGraphite",
};

// ---------- The drawing, on a 1024 x 1024 canvas ----------

// The logo's shapes. `ink` colors the shapes and `cut` the black outlines between them; the
// one-color Android icon uses white ink and black cuts, then keeps only the white.
function logo(stops, ink = "url(#g)", cut = "#000", white = "#fff") {
  const play = (cx, cy, size, fill) => {
    const w = size * 0.9;
    const pts = `${cx - w * 0.42},${cy - size / 2} ${cx + w * 0.58},${cy} ${cx - w * 0.42},${cy + size / 2}`;
    return `<polygon points="${pts}" fill="${fill}" stroke="${fill}" stroke-width="${size * 0.16}" stroke-linejoin="round"/>`;
  };
  const screen = (x, y, w, h, opacity = 1, outline = 22) => {
    const r = Math.min(w, h) * 0.2;
    return `
      <rect x="${x - outline}" y="${y - outline}" width="${w + outline * 2}" height="${h + outline * 2}" rx="${r + outline}" fill="${cut}"/>
      <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${ink}" opacity="${opacity}"/>`;
  };
  const person = (cx, cy, s, halo = 26) => {
    const body = `M${cx - 108 * s} ${cy + 200 * s} V${cy + 165 * s} Q${cx - 108 * s} ${cy + 82 * s} ${cx} ${cy + 82 * s} Q${cx + 108 * s} ${cy + 82 * s} ${cx + 108 * s} ${cy + 165 * s} V${cy + 200 * s} Z`;
    return `
      <path d="${body}" fill="${cut}" stroke="${cut}" stroke-width="${halo}" stroke-linejoin="round"/>
      <circle cx="${cx}" cy="${cy}" r="${56 * s + halo / 2}" fill="${cut}"/>
      <path d="${body}" fill="${ink}"/>
      <circle cx="${cx}" cy="${cy}" r="${56 * s}" fill="${ink}"/>`;
  };
  return `
    <defs>
      <linearGradient id="g" x1="0.1" y1="0.05" x2="0.9" y2="0.95">
        <stop offset="0" stop-color="${stops[0]}"/><stop offset="0.5" stop-color="${stops[1]}"/><stop offset="1" stop-color="${stops[2]}"/>
      </linearGradient>
    </defs>
    ${screen(170, 130, 480, 330, 0.42, 0)}
    ${screen(300, 240, 560, 380)}
    ${play(585, 430, 150, cut)}
    ${play(585, 430, 62, white)}
    ${person(335, 690, 0.88)}
    ${person(735, 690, 0.88)}
    ${person(535, 665, 1.04)}`;
}

// The drawing's middle and its farthest point from it, for fitting it inside a circle.
const CENTER = [526, 505];
const REACH = 505;

// A complete SVG: `background` black or none, the logo scaled by `scale` around its middle.
function svg(content, { background = null, scale = 1, size = 1024, rounded = 0 } = {}) {
  const k = (scale * size) / 1024;
  const transform = `translate(${size / 2} ${size / 2}) scale(${k}) translate(${-CENTER[0]} ${-CENTER[1]})`;
  const clip = rounded ? `<clipPath id="tile"><rect width="${size}" height="${size}" rx="${size * rounded}"/></clipPath>` : "";
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}">` +
    `<defs>${clip}</defs><g${rounded ? ' clip-path="url(#tile)"' : ""}>` +
    (background ? `<rect width="${size}" height="${size}" fill="${background}"/>` : "") +
    `<g transform="${transform}">${content}</g></g></svg>`;
}

// ---------- Rendering with Edge and handling PNG files ----------

const WORK = fs.mkdtempSync(path.join(os.tmpdir(), "wp-icons-"));
let renders = 0;

function render(svgText, size) {
  const id = renders++;
  const html = path.join(WORK, `r${id}.html`);
  const png = path.join(WORK, `r${id}.png`);
  fs.writeFileSync(html, `<html><body style="margin:0;overflow:hidden;background:transparent">${svgText}</body></html>`);
  execFileSync(EDGE, [
    "--headless", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
    `--user-data-dir=${path.join(WORK, "profile")}`, `--window-size=${size},${size}`,
    `--screenshot=${png}`, "file:///" + html.replace(/\\/g, "/"),
  ], { stdio: "ignore" });
  return readPng(png);
}

function readPng(file) {
  const b = fs.readFileSync(file);
  const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
  const channels = { 6: 4, 2: 3 }[b[25]];
  if (b[24] !== 8 || !channels || b[28] !== 0) throw new Error(`Unsupported PNG: ${file}`);
  const parts = [];
  for (let i = 8; i < b.length;) {
    const len = b.readUInt32BE(i), type = b.toString("ascii", i + 4, i + 8);
    if (type === "IDAT") parts.push(b.subarray(i + 8, i + 8 + len));
    i += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(parts));
  const stride = w * channels;
  const px = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    for (let x = 0; x < stride; x++) {
      const v = raw[y * (stride + 1) + 1 + x];
      const a = x >= channels ? px[y * stride + x - channels] : 0;
      const up = y > 0 ? px[(y - 1) * stride + x] : 0;
      const c = y > 0 && x >= channels ? px[(y - 1) * stride + x - channels] : 0;
      let pred = 0;
      if (f === 1) pred = a;
      else if (f === 2) pred = up;
      else if (f === 3) pred = (a + up) >> 1;
      else if (f === 4) {
        const p = a + up - c, pa = Math.abs(p - a), pb = Math.abs(p - up), pc = Math.abs(p - c);
        pred = pa <= pb && pa <= pc ? a : pb <= pc ? up : c;
      }
      px[y * stride + x] = (v + pred) & 0xff;
    }
  }
  const data = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    data[i * 4] = px[i * channels];
    data[i * 4 + 1] = px[i * channels + 1];
    data[i * 4 + 2] = px[i * channels + 2];
    data[i * 4 + 3] = channels === 4 ? px[i * channels + 3] : 255;
  }
  return { w, h, data };
}

// Smaller copies average each block of source pixels (weighted by opacity), so edges stay smooth.
function downscale(src, size) {
  if (src.w === size) return src;
  const out = { w: size, h: size, data: new Uint8ClampedArray(size * size * 4) };
  const f = src.w / size;
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    let r = 0, g = 0, b = 0, a = 0, n = 0;
    for (let sy = Math.floor(y * f); sy < Math.min(src.h, Math.ceil((y + 1) * f)); sy++) {
      for (let sx = Math.floor(x * f); sx < Math.min(src.w, Math.ceil((x + 1) * f)); sx++) {
        const i = (sy * src.w + sx) * 4, al = src.data[i + 3];
        r += src.data[i] * al; g += src.data[i + 1] * al; b += src.data[i + 2] * al; a += al; n++;
      }
    }
    out.data.set(a ? [r / a, g / a, b / a, a / n] : [0, 0, 0, 0], (y * size + x) * 4);
  }
  return out;
}

// Keeps what's white as an opaque white shape, for Android's one-color themed icon.
function whiteToAlpha(c) {
  for (let i = 0; i < c.w * c.h; i++) {
    const d = c.data, lum = (d[i * 4] + d[i * 4 + 1] + d[i * 4 + 2]) / 3;
    d.set([255, 255, 255, Math.round((lum * d[i * 4 + 3]) / 255)], i * 4);
  }
  return c;
}

const CRC = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function writePng(file, c) {
  const raw = Buffer.alloc((c.w * 4 + 1) * c.h);
  for (let y = 0; y < c.h; y++) Buffer.from(c.data.buffer, y * c.w * 4, c.w * 4).copy(raw, y * (c.w * 4 + 1) + 1);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(c.w, 0); ihdr.writeUInt32BE(c.h, 4); ihdr.set([8, 6, 0, 0, 0], 8);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0)),
  ]));
}
// Android picks a resource by name only, so the old .webp with the same name must go.
function writeAndroid(file, c) {
  const webp = file.replace(/\.png$/, ".webp");
  if (fs.existsSync(webp)) fs.unlinkSync(webp);
  writePng(file, c);
}

// ---------- Outputs ----------

function readme() {
  // A little smaller than the tile, so the rounded corners never touch the drawing.
  fs.writeFileSync(
    path.join(ROOT, ".github/assets/nuvio-wp-logo.svg"),
    svg(logo(PALETTES.original), { background: "#000", scale: 0.92, rounded: 0.225 }),
  );
}

function icons() {
  if (!EDGE) throw new Error("Microsoft Edge is needed to draw the icons.");
  const BIG = 2048;
  const res = path.join(ROOT, "composeApp/src/androidMain/res");
  const assets = path.join(ROOT, "iosApp/iosApp/Assets.xcassets");
  const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
  // Newer Androids cut icons into the launcher's shape; this circle survives every shape.
  const safeScale = (66 / 108 / 2) * 1024 / REACH;

  const mono = whiteToAlpha(render(svg(logo(PALETTES.original, "#fff", "#000", "#fff"), { scale: safeScale, size: BIG }), BIG));
  for (const [name, stops] of Object.entries(PALETTES)) {
    const suffix = name === "original" ? "" : `_${name}`;
    // iPhone rounds the corners itself, so the square tile is full black.
    const square = render(svg(logo(stops), { background: "#000", scale: 0.9, size: BIG }), BIG);
    writePng(path.join(assets, `${IOS_SETS[name]}.appiconset/app-icon-1024.png`), downscale(square, 1024));
    const roundedTile = render(svg(logo(stops), { background: "#000", scale: 0.86, size: BIG, rounded: 0.18 }), BIG);
    const circleTile = render(svg(logo(stops), { background: "#000", scale: 0.8, size: BIG, rounded: 0.5 }), BIG);
    const foreground = render(svg(logo(stops), { scale: safeScale, size: BIG }), BIG);

    for (const [density, scale] of Object.entries(densities)) {
      const dir = path.join(res, `mipmap-${density}`);
      // Older Androids show the whole icon: a rounded square, and a circle for round launchers.
      writeAndroid(path.join(dir, `ic_launcher${suffix}.png`), downscale(roundedTile, 48 * scale));
      if (name === "original") writeAndroid(path.join(dir, "ic_launcher_round.png"), downscale(circleTile, 48 * scale));
      // Newer Androids put this over the black icon background.
      writeAndroid(path.join(dir, `ic_launcher${suffix}_foreground.png`), downscale(foreground, 108 * scale));
      if (name === "original") writeAndroid(path.join(dir, "ic_launcher_monochrome.png"), downscale(mono, 108 * scale));
    }
  }
}

const mode = process.argv[2];
if (mode === "readme") readme();
else if (mode === "icons") icons();
else throw new Error("Say what to draw: readme or icons");
fs.rmSync(WORK, { recursive: true, force: true });
console.log("Done.");
