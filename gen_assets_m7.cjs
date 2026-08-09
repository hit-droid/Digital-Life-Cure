// gen_assets_m7.cjs — 程序化生成可爱 Q 版动漫像素角色 / 立绘 / 图标 / 音乐
// 零外部依赖：自带 PNG 编码(zlib) 与 WAV 合成。
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

const ROOT = __dirname;
const DRAW = path.join(ROOT, 'app/src/main/res/drawable-nodpi');
const RAW = path.join(ROOT, 'app/src/main/res/raw');
const MIP = path.join(ROOT, 'app/src/main/res');
fs.mkdirSync(DRAW, { recursive: true });
fs.mkdirSync(RAW, { recursive: true });

// ---------- PNG ----------
function makeGrid(w, h) { return { w, h, d: Buffer.alloc(w * h * 4, 0) }; }
function px(g, x, y, c) {
  if (!c || c[0] === undefined) return;
  x = x | 0; y = y | 0;
  if (x < 0 || y < 0 || x >= g.w || y >= g.h) return;
  const i = (y * g.w + x) * 4;
  g.d[i] = c[0]; g.d[i + 1] = c[1]; g.d[i + 2] = c[2];
  g.d[i + 3] = c[3] === undefined ? 255 : c[3];
}
function rect(g, x, y, w, h, c) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) px(g, x + i, y + j, c); }
function ell(g, cx, cy, rx, ry, c) {
  for (let y = Math.floor(cy - ry); y <= cy + ry; y++)
    for (let x = Math.floor(cx - rx); x <= cx + rx; x++) {
      const dx = (x - cx) / rx, dy = (y - cy) / ry;
      if (dx * dx + dy * dy <= 1) px(g, x, y, c);
    }
}
function circ(g, cx, cy, r, c) { ell(g, cx, cy, r, r, c); }
function line(g, x0, y0, x1, y1, c) {
  x0 |= 0; y0 |= 0; x1 |= 0; y1 |= 0;
  const dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
  const sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
  let err = dx - dy;
  for (;;) {
    px(g, x0, y0, c);
    if (x0 === x1 && y0 === y1) break;
    const e2 = 2 * err;
    if (e2 > -dy) { err -= dy; x0 += sx; }
    if (e2 < dx) { err += dx; y0 += sy; }
  }
}
function encodePNG(g) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  function chunk(type, data) {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const t = Buffer.from(type, 'ascii');
    const crc = Buffer.alloc(4);
    const crcTable = [];
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c >>> 0; }
    let crcVal = 0xffffffff;
    for (let i = 0; i < t.length; i++) crcVal = crcTable[(crcVal ^ t[i]) & 0xff] ^ (crcVal >>> 8);
    for (let i = 0; i < data.length; i++) crcVal = crcTable[(crcVal ^ data[i]) & 0xff] ^ (crcVal >>> 8);
    crc.writeUInt32BE((crcVal ^ 0xffffffff) >>> 0, 0);
    return Buffer.concat([len, t, data, crc]);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(g.w, 0); ihdr.writeUInt32BE(g.h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc(g.h * (g.w * 4 + 1));
  for (let y = 0; y < g.h; y++) {
    raw[y * (g.w * 4 + 1)] = 0;
    g.d.copy(raw, y * (g.w * 4 + 1) + 1, y * g.w * 4, (y + 1) * g.w * 4);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}
function savePNG(name, g) { fs.writeFileSync(path.join(DRAW, name), encodePNG(g)); }
function saveMip(name, g) {
  for (const [d, s] of [['mipmap-mdpi', 48], ['mipmap-hdpi', 72], ['mipmap-xhdpi', 96], ['mipmap-xxhdpi', 144], ['mipmap-xxxhdpi', 192]]) {
    const dir = path.join(MIP, d);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, name), encodePNG(g(s)));
  }
}

// ---------- 调色板 ----------
const P = {
  hero: { hair: [255, 150, 200], hairD: [224, 96, 156], skin: [255, 224, 200], skinD: [240, 196, 170],
    eye: [90, 140, 255], eyeW: [255, 255, 255], mouth: [200, 90, 120], blush: [255, 150, 170],
    dress: [255, 111, 174], dressD: [214, 70, 142], shoe: [255, 255, 255], accent: [255, 240, 170], twintail: true, crown: false },
  maiden: { hair: [150, 210, 255], hairD: [90, 160, 224], skin: [255, 230, 210], skinD: [240, 200, 178],
    eye: [176, 96, 255], eyeW: [255, 255, 255], mouth: [200, 90, 120], blush: [255, 160, 190],
    dress: [235, 245, 255], dressD: [180, 205, 240], shoe: [200, 220, 255], accent: [255, 213, 74], twintail: false, crown: true, longHair: true },
  slime: { body: [150, 240, 165], bodyD: [80, 200, 110], eye: [60, 60, 80], eyeW: [255, 255, 255], mouth: [120, 200, 130], blush: [255, 160, 190] },
  bat: { body: [180, 140, 255], bodyD: [130, 95, 210], eye: [255, 240, 120], eyeW: [255, 255, 255], wing: [150, 110, 220] },
  ghost: { body: [225, 240, 255], bodyD: [180, 210, 240], eye: [90, 120, 180], eyeW: [255, 255, 255], mouth: [150, 170, 210] },
  golem: { body: [170, 160, 150], bodyD: [120, 112, 104], eye: [255, 120, 80], eyeW: [255, 220, 180] },
  dragon: { body: [150, 70, 120], bodyD: [100, 40, 85], belly: [240, 180, 210], eye: [255, 230, 90], eyeW: [255, 255, 255], wing: [110, 50, 95], horn: [255, 230, 160],
    hair: [150, 70, 120], hairD: [100, 40, 85], skin: [240, 180, 210], skinD: [200, 150, 180], mouth: [255, 230, 90], blush: [255, 160, 190], accent: [255, 230, 160], twintail: false, crown: false, longHair: false },
};

// 画一张 Q 版少女脸（参数化，可缩放）。cx,cy 中心；s=头半径
function face(g, cx, cy, s, pal) {
  const hair = pal.hair, hairD = pal.hairD, skin = pal.skin;
  // 后发
  ell(g, cx, cy - s * 0.25, s * 1.02, s * 1.05, hair);
  if (pal.longHair) ell(g, cx, cy + s * 0.55, s * 0.8, s * 1.15, hair); // 长发垂下
  // 双马尾
  if (pal.twintail) {
    ell(g, cx - s * 1.0, cy + s * 0.1, s * 0.42, s * 0.95, hair);
    ell(g, cx + s * 1.0, cy + s * 0.1, s * 0.42, s * 0.95, hair);
    ell(g, cx - s * 1.05, cy - s * 0.2, s * 0.3, s * 0.5, hairD);
    ell(g, cx + s * 1.05, cy - s * 0.2, s * 0.3, s * 0.5, hairD);
  }
  // 脸
  ell(g, cx, cy, s * 0.82, s * 0.92, skin);
  // 刘海
  ell(g, cx, cy - s * 0.55, s * 0.95, s * 0.7, hair);
  for (let i = -2; i <= 2; i++) px(g, cx + i * s * 0.18, cy - s * 0.95, hair);
  // 眼睛
  for (const side of [-1, 1]) {
    const ex = cx + side * s * 0.40, ey = cy + s * 0.08;
    ell(g, ex, ey, s * 0.20, s * 0.28, pal.eyeW);
    ell(g, ex, ey + s * 0.03, s * 0.14, s * 0.22, pal.eye);
    px(g, ex - s * 0.06, ey - s * 0.10, [255, 255, 255]);
    px(g, ex - s * 0.02, ey - s * 0.12, [255, 255, 255]);
  }
  // 腮红
  ell(g, cx - s * 0.55, cy + s * 0.28, s * 0.13, s * 0.08, pal.blush);
  ell(g, cx + s * 0.55, cy + s * 0.28, s * 0.13, s * 0.08, pal.blush);
  // 嘴
  px(g, cx, cy + s * 0.42, pal.mouth);
  px(g, cx - 1, cy + s * 0.42, pal.mouth);
  // 皇冠
  if (pal.crown) {
    const gold = pal.accent, gy = cy - s * 0.92;
    for (let i = -2; i <= 2; i++) rect(g, cx + i * s * 0.22 - 1, gy - (i % 2 ? 2 : 4), 2, (i % 2 ? 2 : 4) + 2, gold);
    rect(g, cx - s * 0.5, gy, s, 2, gold);
  }
}

// 全身角色 32x40
function chibi(frame) {
  const g = makeGrid(32, 40);
  const pal = P.hero;
  face(g, 16, 13, 8, pal);
  // 身体（连衣裙）
  for (let y = 22; y <= 36; y++) {
    const t = (y - 22) / 14;
    const half = Math.round(6 + t * 1.5);
    for (let x = 16 - half; x <= 16 + half; x++) {
      const c = (x > 16 + half - 2) ? pal.dressD : pal.dress;
      px(g, x, y, c);
    }
  }
  // 领口
  rect(g, 14, 22, 5, 2, pal.accent);
  // 手臂
  px(g, 9, 26, pal.skin); px(g, 9, 27, pal.dress);
  px(g, 23, 26, pal.skin); px(g, 23, 27, pal.dress);
  if (frame === 'atk') { rect(g, 22, 24, 6, 3, pal.skin); px(g, 28, 24, [255, 240, 120]); px(g, 29, 25, [255, 255, 200]); }
  // 腿
  const l1 = frame === 'walk2' ? 1 : 0, l2 = frame === 'walk1' ? 1 : 0;
  rect(g, 13, 37 - l1, 3, 3 + l1, pal.shoe);
  rect(g, 17, 37 - l2, 3, 3 + l2, pal.shoe);
  return g;
}
// 少女（被囚）全身 32x40
function maidenFull() {
  const g = makeGrid(32, 40);
  const pal = P.maiden;
  face(g, 16, 13, 8, pal);
  for (let y = 22; y <= 36; y++) {
    const t = (y - 22) / 14;
    const half = Math.round(5 + t * 2);
    for (let x = 16 - half; x <= 16 + half; x++) px(g, x, y, (x > 16 + half - 2) ? pal.dressD : pal.dress);
  }
  rect(g, 14, 22, 5, 2, pal.accent);
  px(g, 9, 26, pal.skin); px(g, 23, 26, pal.skin);
  rect(g, 13, 37, 3, 3, pal.shoe); rect(g, 17, 37, 3, 3, pal.shoe);
  return g;
}
// 怪物
function slime() {
  const g = makeGrid(32, 32); const p = P.slime;
  ell(g, 16, 20, 11, 9, p.body);
  rect(g, 5, 20, 22, 9, p.body);
  ell(g, 16, 22, 11, 7, p.bodyD); // 底部阴影
  // 高光
  ell(g, 11, 14, 3, 2, [255, 255, 255, 200]);
  for (const s of [-1, 1]) {
    const ex = 16 + s * 5;
    ell(g, ex, 19, 2.2, 3, p.eyeW); ell(g, ex, 20, 1.4, 2, p.eye);
  }
  px(g, 16, 25, p.mouth); px(g, 15, 25, p.mouth); px(g, 17, 25, p.mouth);
  return g;
}
function bat() {
  const g = makeGrid(32, 32); const p = P.bat;
  // 翅膀
  for (const s of [-1, 1]) {
    const wx = 16 + s * 4;
    for (let i = 0; i < 10; i++) { px(g, wx + s * i, 14 + Math.abs(i - 5) * 0.6, p.wing); px(g, wx + s * i, 15 + Math.abs(i - 5) * 0.6, p.wing); }
  }
  circ(g, 16, 17, 6, p.body);
  for (const s of [-1, 1]) { ell(g, 16 + s * 3, 16, 2, 2.5, p.eyeW); ell(g, 16 + s * 3, 16.5, 1.2, 1.6, p.eye); }
  px(g, 16, 20, p.eye);
  return g;
}
function ghost() {
  const g = makeGrid(32, 32); const p = P.ghost;
  ell(g, 16, 14, 9, 10, p.body);
  rect(g, 7, 14, 18, 10, p.body);
  for (let x = 7; x <= 24; x += 3) { px(g, x, 24, [0, 0, 0, 0]); px(g, x + 1, 24, [0, 0, 0, 0]); }
  for (const s of [-1, 1]) { ell(g, 16 + s * 4, 14, 2.2, 3, p.eye); }
  px(g, 16, 18, p.mouth);
  return g;
}
function golem() {
  const g = makeGrid(32, 32); const p = P.golem;
  rect(g, 8, 8, 16, 16, p.body); rect(g, 8, 8, 16, 16, p.body);
  rect(g, 8, 8, 16, 4, p.bodyD); rect(g, 8, 20, 16, 4, p.bodyD);
  for (const s of [-1, 1]) { rect(g, 11 + (s > 0 ? 6 : 0), 13, 4, 4, p.eyeW); rect(g, 12 + (s > 0 ? 6 : 0), 14, 2, 2, p.eye); }
  return g;
}
function dragon() {
  const g = makeGrid(64, 64); const p = P.dragon;
  // 翅膀
  for (const s of [-1, 1]) { const wx = 32 + s * 10; for (let i = 0; i < 18; i++) { const yy = 18 + Math.abs(i - 9) * 1.1; px(g, wx + s * i, yy, p.wing); px(g, wx + s * i, yy + 1, p.wing); } }
  // 身体
  ell(g, 32, 36, 16, 14, p.body);
  ell(g, 32, 42, 14, 10, p.belly);
  // 头
  ell(g, 32, 22, 11, 9, p.body);
  // 角
  px(g, 24, 13, p.horn); px(g, 23, 12, p.horn); px(g, 40, 13, p.horn); px(g, 41, 12, p.horn);
  // 眼
  for (const s of [-1, 1]) { ell(g, 32 + s * 5, 22, 2.6, 3, p.eyeW); ell(g, 32 + s * 5, 22.5, 1.6, 2, p.eye); }
  // 嘴
  for (let x = 26; x <= 38; x++) px(g, x, 29, p.eye);
  return g;
}
// 立绘 96x128（大头半身）
function portrait(pal) {
  console.log('portrait pal keys:', Object.keys(pal));
  const g = makeGrid(96, 128);
  face(g, 48, 52, 32, pal);
  // 肩与衣
  ell(g, 48, 120, 40, 26, pal.dress);
  ell(g, 48, 118, 40, 22, pal.dressD);
  rect(g, 18, 100, 60, 18, pal.dress);
  rect(g, 18, 100, 60, 4, pal.accent);
  return g;
}
// 图标脸（多密度）
function iconFace(S) {
  const g = makeGrid(S, S);
  const cx = S / 2, cy = S * 0.46, s = S * 0.30;
  // 背景圆
  circ(g, cx, cy, S * 0.46, [120, 200, 255]);
  circ(g, cx, cy, S * 0.46, [150, 215, 255]);
  face(g, cx, cy, s, P.hero);
  // 肩
  ell(g, cx, S * 0.96, S * 0.34, S * 0.22, P.hero.dress);
  return g;
}

// 保存精灵
savePNG('hero_idle.png', chibi('idle'));
savePNG('hero_walk1.png', chibi('walk1'));
savePNG('hero_walk2.png', chibi('walk2'));
savePNG('hero_atk.png', chibi('atk'));
savePNG('maiden.png', maidenFull());
savePNG('enemy_slime.png', slime());
savePNG('enemy_bat.png', bat());
savePNG('enemy_ghost.png', ghost());
savePNG('enemy_golem.png', golem());
savePNG('boss_dragon.png', dragon());
savePNG('portrait_hero.png', portrait(P.hero));
savePNG('portrait_maiden.png', portrait(P.maiden));
savePNG('portrait_dragon.png', portrait(P.dragon));
saveMip('ic_launcher.png', (S) => iconFace(S));

// ---------- 兼容旧 R.drawable 命名 + 道具/图标 ----------
function crystalGen() { const g = makeGrid(32, 32); ell(g, 16, 18, 9, 13, [255, 150, 220]); ell(g, 16, 16, 5, 9, [255, 205, 240]); line(g, 16, 5, 16, 31, [205, 130, 205]); line(g, 8, 14, 24, 22, [205, 130, 205]); line(g, 24, 14, 8, 22, [205, 130, 205]); ell(g, 13, 12, 2, 3, [255, 255, 255, 220]); return g; }
function wraith() { const g = makeGrid(32, 32); ell(g, 16, 15, 9, 10, [180, 140, 255]); rect(g, 7, 15, 18, 10, [180, 140, 255]); for (let x = 7; x <= 24; x += 3) { px(g, x, 25, [0, 0, 0, 0]); px(g, x + 1, 25, [0, 0, 0, 0]); } for (const s of [-1, 1]) { ell(g, 16 + s * 4, 15, 2, 2.5, [255, 255, 255]); ell(g, 16 + s * 4, 15.5, 1.2, 1.6, [90, 60, 160]); } px(g, 16, 20, [90, 60, 160]); return g; }
function ogre() { const g = makeGrid(32, 32); ell(g, 16, 18, 11, 9, [150, 210, 120]); rect(g, 5, 18, 22, 9, [150, 210, 120]); px(g, 6, 9, [120, 180, 90]); px(g, 5, 8, [120, 180, 90]); px(g, 26, 9, [120, 180, 90]); px(g, 27, 8, [120, 180, 90]); circ(g, 16, 16, 7, [185, 232, 155]); for (const s of [-1, 1]) { ell(g, 16 + s * 4, 16, 2, 2.5, [255, 255, 255]); ell(g, 16 + s * 4, 16.5, 1.2, 1.6, [60, 90, 40]); } px(g, 16, 21, [60, 90, 40]); return g; }
function potionGen() { const g = makeGrid(24, 24); rect(g, 10, 3, 4, 4, [210, 210, 230]); ell(g, 12, 14, 5, 7, [120, 200, 255]); ell(g, 12, 13, 5, 7, [150, 220, 255]); px(g, 10, 6, [255, 255, 255]); return g; }
function relicGen() { const g = makeGrid(24, 24); for (let y = 2; y < 22; y++) { let w = Math.abs(12 - y); for (let x = 12 - w; x <= 24 - (12 - w) - 1; x++) px(g, x, y, [255, 210, 90]); } for (let y = 5; y < 19; y++) { let w = Math.max(0, Math.abs(12 - y) - 1); for (let x = 12 - w; x <= 24 - (12 - w) - 1; x++) px(g, x, y, [255, 238, 150]); } return g; }
function chestGen() { const g = makeGrid(24, 24); rect(g, 4, 8, 16, 13, [150, 100, 60]); rect(g, 4, 8, 16, 4, [120, 80, 50]); rect(g, 4, 12, 16, 2, [90, 60, 40]); rect(g, 11, 12, 2, 5, [255, 220, 90]); return g; }
function signGen() { const g = makeGrid(24, 24); rect(g, 11, 8, 2, 15, [120, 90, 60]); rect(g, 4, 3, 16, 9, [205, 175, 125]); rect(g, 4, 3, 16, 2, [165, 135, 95]); return g; }
function bookGen() { const g = makeGrid(24, 24); rect(g, 5, 5, 14, 14, [185, 125, 225]); rect(g, 5, 5, 3, 14, [145, 95, 185]); rect(g, 12, 5, 2, 14, [255, 255, 255]); return g; }
savePNG('hero.png', chibi('idle'));
savePNG('boss.png', dragon());
savePNG('crystal.png', crystalGen());
savePNG('enemy0.png', slime());
savePNG('enemy1.png', bat());
savePNG('enemy2.png', ghost());
savePNG('enemy3.png', golem());
savePNG('enemy4.png', wraith());
savePNG('enemy5.png', ogre());
savePNG('item_potion.png', potionGen());
savePNG('item_relic.png', relicGen());
savePNG('item_chest.png', chestGen());
savePNG('item_sign.png', signGen());
savePNG('item_book.png', bookGen());
console.log('PNG assets generated.');

// ---------- WAV 合成 ----------
function midi(n) { return 440 * Math.pow(2, (n - 69) / 12); }
function writeWAV(file, samples, rate) {
  const data = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) {
    let v = Math.max(-1, Math.min(1, samples[i]));
    data.writeInt16LE((v * 32767) | 0, i * 2);
  }
  const hdr = Buffer.alloc(44);
  hdr.write('RIFF', 0); hdr.writeUInt32LE(36 + data.length, 4); hdr.write('WAVE', 8);
  hdr.write('fmt ', 12); hdr.writeUInt32LE(16, 16); hdr.writeUInt16LE(1, 20); hdr.writeUInt16LE(1, 22);
  hdr.writeUInt32LE(rate, 24); hdr.writeUInt32LE(rate * 2, 28); hdr.writeUInt16LE(2, 32); hdr.writeUInt16LE(16, 34);
  hdr.write('data', 36); hdr.writeUInt32LE(data.length, 40);
  fs.writeFileSync(path.join(RAW, file), Buffer.concat([hdr, data]));
}
function env(i, n, sr) {
  const a = 0.01 * sr, r = 0.05 * sr;
  if (i < a) return i / a;
  if (i > n - r) return Math.max(0, (n - i) / r);
  return 1;
}
function synth(rate, bpm, leadNotes, bassNotes) {
  const spb = 60 / bpm;
  const total = leadNotes.reduce((a, n) => a + n[1], 0) * spb;
  const N = Math.floor(total * rate);
  const out = new Float32Array(N);
  function track(notes, type, gain, oct) {
    let t = 0;
    for (const [m, beats] of notes) {
      const dur = beats * spb, n = Math.floor(dur * rate);
      const f = midi(m + (oct || 0));
      for (let i = 0; i < n; i++) {
        const ph = (i / rate) * f * 2 * Math.PI;
        let s = type === 'sq' ? (Math.sin(ph) > 0 ? 1 : -1) : (2 / Math.PI) * Math.asin(Math.sin(ph));
        s *= gain * env(i, n, rate);
        const idx = Math.floor(t * rate) + i;
        if (idx < N) out[idx] += s;
      }
      t += dur;
    }
  }
  track(leadNotes, 'sq', 0.32, 0);
  track(bassNotes, 'tri', 0.4, -12);
  // 轻微归一
  let mx = 0; for (let i = 0; i < N; i++) mx = Math.max(mx, Math.abs(out[i]));
  if (mx > 0) for (let i = 0; i < N; i++) out[i] /= mx;
  return out;
}
// 可爱循环：C - Am - F - G 进行 + 五声 arpeggio
const lead = [
  [72, 1], [76, 1], [79, 1], [84, 1], [79, 1], [76, 1],
  [72, 1], [76, 1], [81, 1], [84, 1], [81, 1], [76, 1],
  [77, 1], [81, 1], [84, 1], [89, 1], [84, 1], [81, 1],
  [71, 1], [74, 1], [79, 1], [83, 1], [79, 1], [74, 1],
];
const bass = [
  [48, 3], [52, 3], [53, 3], [55, 3], [48, 3], [52, 3], [53, 3], [55, 3],
];
writeWAV('bgm.wav', synth(22050, 120, lead.concat(lead, lead, lead), bass.concat(bass, bass, bass, bass, bass, bass)), 22050);

// 音效
function sfxTone(freq, dur, type, rate) {
  const N = Math.floor(dur * rate);
  const out = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const ph = (i / rate) * freq * 2 * Math.PI;
    let s = type === 'sq' ? (Math.sin(ph) > 0 ? 1 : -1) : Math.sin(ph);
    s *= Math.exp(-i / (N * 0.4));
    out[i] = s * 0.5;
  }
  return out;
}
writeWAV('sfx_hit.wav', sfxTone(330, 0.12, 'sq', 22050), 22050);
writeWAV('sfx_hurt.wav', sfxTone(140, 0.2, 'sin', 22050), 22050);
writeWAV('sfx_pickup.wav', (() => { const a = sfxTone(660, 0.08, 'sq', 22050); const b = sfxTone(990, 0.1, 'sq', 22050); const o = new Float32Array(a.length + b.length); o.set(a); o.set(b, a.length); return o; })(), 22050);
writeWAV('sfx_win.wav', (() => {
  const notes = [[72, .2], [76, .2], [79, .2], [84, .5]]; let arr = [];
  for (const [m, d] of notes) { const s = sfxTone(midi(m), d, 'sq', 22050); arr = arr.concat(Array.from(s)); }
  return Float32Array.from(arr);
})(), 22050);
console.log('WAV assets generated.');
