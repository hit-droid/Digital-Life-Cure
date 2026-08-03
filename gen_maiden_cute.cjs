// gen_maiden_cute.cjs v2 — 重绘可爱少女系列，带黑描边、清晰头身比、大眼高光
// 输出：主角(idle/walk1/walk2/atk) + 曦(maiden) + 村民NPC x2 + 立绘 x2 + 启动图标
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');
const ROOT = __dirname;
const DRAW = path.join(ROOT, 'app/src/main/res/drawable-nodpi');
const MIP = path.join(ROOT, 'app/src/main/res');
fs.mkdirSync(DRAW, { recursive: true });

function makeGrid(w, h) { return { w, h, d: Buffer.alloc(w * h * 4, 0) }; }
function px(g, x, y, c) { if (!c) return; x = x | 0; y = y | 0; if (x < 0 || y < 0 || x >= g.w || y >= g.h) return; const i = (y * g.w + x) * 4; const a = c[3] === undefined ? 255 : c[3]; if (a === 255) { g.d[i] = c[0]; g.d[i + 1] = c[1]; g.d[i + 2] = c[2]; g.d[i + 3] = 255; } else { const src = a / 255, inv = 1 - src; g.d[i] = c[0] * src + g.d[i] * inv; g.d[i + 1] = c[1] * src + g.d[i + 1] * inv; g.d[i + 2] = c[2] * src + g.d[i + 2] * inv; g.d[i + 3] = Math.max(g.d[i + 3], a); } }
function rect(g, x, y, w, h, c) { for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) px(g, x + i, y + j, c); }
function ell(g, cx, cy, rx, ry, c) { for (let y = Math.floor(cy - ry); y <= cy + ry; y++) for (let x = Math.floor(cx - rx); x <= cx + rx; x++) { const dx = (x - cx) / rx, dy = (y - cy) / ry; if (dx * dx + dy * dy <= 1) px(g, x, y, c); } }
function circ(g, cx, cy, r, c) { ell(g, cx, cy, r, r, c); }
// 给非透明轮廓加 1px 深色描边
function outline(g, col) {
  const snap = Buffer.from(g.d);
  const N = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
  for (let y = 0; y < g.h; y++) for (let x = 0; x < g.w; x++) {
    const i = (y * g.w + x) * 4;
    if (snap[i + 3] !== 0) continue;
    let near = false;
    for (const [dx, dy] of N) { const nx = x + dx, ny = y + dy; if (nx < 0 || ny < 0 || nx >= g.w || ny >= g.h) continue; if (snap[(ny * g.w + nx) * 4 + 3] > 40) { near = true; break; } }
    if (near) { g.d[i] = col[0]; g.d[i + 1] = col[1]; g.d[i + 2] = col[2]; g.d[i + 3] = 255; }
  }
}
function encodePNG(g) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  function chunk(type, data) { const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0); const t = Buffer.from(type, 'ascii'); const crc = Buffer.alloc(4); const crcTable = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c >>> 0; } let crcVal = 0xffffffff; for (let i = 0; i < t.length; i++) crcVal = crcTable[(crcVal ^ t[i]) & 0xff] ^ (crcVal >>> 8); for (let i = 0; i < data.length; i++) crcVal = crcTable[(crcVal ^ data[i]) & 0xff] ^ (crcVal >>> 8); crc.writeUInt32BE((crcVal ^ 0xffffffff) >>> 0, 0); return Buffer.concat([len, t, data, crc]); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(g.w, 0); ihdr.writeUInt32BE(g.h, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const raw = Buffer.alloc(g.h * (g.w * 4 + 1)); for (let y = 0; y < g.h; y++) { raw[y * (g.w * 4 + 1)] = 0; g.d.copy(raw, y * (g.w * 4 + 1) + 1, y * g.w * 4, (y + 1) * g.w * 4); }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}
function savePNG(name, g) { fs.writeFileSync(path.join(DRAW, name), encodePNG(g)); }
function saveMip(name, gen) { for (const [d, s] of [['mipmap-mdpi', 48], ['mipmap-hdpi', 72], ['mipmap-xhdpi', 96], ['mipmap-xxhdpi', 144], ['mipmap-xxxhdpi', 192]]) { const dir = path.join(MIP, d); fs.mkdirSync(dir, { recursive: true }); fs.writeFileSync(path.join(dir, name), encodePNG(gen(s))); } }

const OUT = [46, 32, 58]; // 描边色（深紫黑）

// 通用可爱角色（32x40 全身 chibi）
// pal: hair, hairD, hairHi, skin, skinD, eye, dress, dressD, shoe, accent, ribbon
// opt: twintail, longHair, crown, sidebun, eyesClosed, arm(atk)
function drawChar(pal, opt) {
  opt = opt || {};
  const g = makeGrid(32, 40);
  const cx = 16, hy = 13, s = 8.5;
  // 后发/长发（先画，垫在头后）
  if (opt.longHair) { ell(g, cx, hy + 8, s * 1.15, s * 1.7, pal.hair); ell(g, cx - 5, hy + 10, 3, 6, pal.hairD); ell(g, cx + 5, hy + 10, 3, 6, pal.hairD); }
  ell(g, cx, hy - 1, s * 1.18, s * 1.15, pal.hair); // 后发轮廓
  // 双马尾
  if (opt.twintail) {
    ell(g, cx - 9, hy + 3, 3.2, 6.5, pal.hair); ell(g, cx + 9, hy + 3, 3.2, 6.5, pal.hair);
    ell(g, cx - 9, hy + 6, 2.2, 3, pal.hairD); ell(g, cx + 9, hy + 6, 2.2, 3, pal.hairD);
    for (const sx of [-9, 9]) { rect(g, cx + sx - 3, hy - 4, 6, 3, pal.ribbon); px(g, cx + sx, hy - 3, pal.accent); }
  }
  // 脸
  ell(g, cx, hy, s, s * 1.05, pal.skin);
  // 刘海
  ell(g, cx, hy - 4, s * 1.05, s * 0.7, pal.hair);
  ell(g, cx - 4, hy - 2, 3, 3.5, pal.hair); ell(g, cx + 4, hy - 2, 3, 3.5, pal.hair);
  rect(g, cx - 1, hy - 6, 2, 2, pal.hair);
  // 发色高光
  ell(g, cx - 2, hy - 5, 2.5, 1.2, pal.hairHi);
  // 眼睛（大 + 高光）
  for (const side of [-1, 1]) {
    const ex = cx + side * 3.6, ey = hy + 1.5;
    if (opt.eyesClosed) { for (let i = -2; i <= 2; i++) px(g, ex + i, ey, OUT); px(g, ex + side * 2, ey - 1, OUT); continue; }
    ell(g, ex, ey, 2.3, 3.0, [255, 255, 255]);      // 眼白
    ell(g, ex, ey + 0.4, 1.7, 2.4, pal.eye);         // 虹膜
    ell(g, ex, ey + 1.0, 1.0, 1.4, [30, 20, 45]);    // 瞳孔
    px(g, ex - 1, ey - 1, [255, 255, 255]);          // 高光
    px(g, ex - 0.5, ey - 1.5, [255, 255, 255]);
    px(g, ex + 1, ey + 1, [255, 255, 255, 180]);
    // 上睫毛
    for (let i = -2; i <= 2; i++) px(g, ex + i, ey - 3, OUT);
    px(g, ex + side * 3, ey - 2, OUT);
  }
  // 腮红
  ell(g, cx - 5, hy + 3.5, 1.8, 1.1, [255, 150, 175, 160]);
  ell(g, cx + 5, hy + 3.5, 1.8, 1.1, [255, 150, 175, 160]);
  // 嘴
  px(g, cx - 1, hy + 5, pal.mouth); px(g, cx, hy + 5.5, pal.mouth); px(g, cx + 1, hy + 5, pal.mouth);
  // 皇冠
  if (opt.crown) { const gy = hy - 9; for (let i = -2; i <= 2; i++) { const hh = (i % 2 ? 2 : 4); rect(g, cx + i * 2 - 1, gy - hh, 2, hh, pal.accent); px(g, cx + i * 2, gy - hh, [255, 255, 200]); } rect(g, cx - 5, gy, 10, 2, pal.accent); }
  // 身体裙子（梯形）
  for (let y = 22; y <= 37; y++) { const t = (y - 22) / 15; const half = Math.round(4 + t * 5); for (let x = cx - half; x <= cx + half; x++) px(g, x, y, (x > cx + half - 2 || x < cx - half + 1) ? pal.dressD : pal.dress); }
  rect(g, cx - 4, 22, 8, 2, pal.accent); // 领口/腰带
  ell(g, cx, 30, 6, 3, pal.dressD, 0);   // 裙摆阴影
  // 手臂
  const armY = opt.arm === 'atk' ? 24 : 26;
  px(g, cx - 6, armY, pal.skin); px(g, cx - 6, armY + 1, pal.dress);
  if (opt.arm === 'atk') { rect(g, cx + 6, 23, 4, 2, pal.skin); px(g, cx + 10, 23, [255, 240, 120]); px(g, cx + 11, 24, [255, 255, 200]); }
  else { px(g, cx + 6, armY, pal.skin); px(g, cx + 6, armY + 1, pal.dress); }
  // 腿/鞋（行走微抬）
  const l1 = opt.walk === 1 ? 1 : 0, l2 = opt.walk === 2 ? 1 : 0;
  rect(g, cx - 4, 37 - l1, 3, 3 + l1, pal.shoe); rect(g, cx + 1, 37 - l2, 3, 3 + l2, pal.shoe);
  outline(g, OUT);
  return g;
}

const P = {
  hero: { hair: [255, 150, 200], hairD: [225, 96, 156], hairHi: [255, 205, 230], skin: [255, 226, 204], skinD: [244, 198, 174], eye: [90, 140, 255], mouth: [206, 84, 120], dress: [255, 118, 180], dressD: [214, 74, 142], shoe: [250, 250, 255], accent: [255, 236, 150], ribbon: [110, 215, 255] },
  maiden: { hair: [150, 208, 255], hairD: [92, 160, 222], hairHi: [214, 240, 255], skin: [255, 232, 214], skinD: [242, 202, 182], eye: [175, 100, 255], mouth: [206, 90, 128], dress: [244, 250, 255], dressD: [190, 214, 244], shoe: [200, 222, 255], accent: [255, 216, 90], ribbon: [255, 160, 200] },
  villagerA: { hair: [150, 110, 78], hairD: [110, 78, 52], hairHi: [190, 150, 110], skin: [252, 220, 192], skinD: [230, 194, 166], eye: [96, 72, 52], mouth: [180, 96, 96], dress: [126, 156, 96], dressD: [92, 120, 70], shoe: [110, 86, 60], accent: [220, 200, 140], ribbon: [200, 160, 110] },
  villagerB: { hair: [96, 84, 130], hairD: [66, 58, 96], hairHi: [150, 138, 190], skin: [255, 228, 206], skinD: [236, 202, 180], eye: [120, 96, 60], mouth: [198, 100, 120], dress: [150, 120, 180], dressD: [112, 88, 140], shoe: [90, 74, 120], accent: [230, 210, 240], ribbon: [220, 180, 230] },
};

function portrait(pal, opt) {
  opt = opt || {};
  const g = makeGrid(96, 128);
  const cx = 48, hy = 46, s = 30;
  if (opt.longHair) { ell(g, cx, hy + 34, s * 1.2, s * 2.0, pal.hair); }
  ell(g, cx, hy - 2, s * 1.2, s * 1.15, pal.hair);
  if (opt.twintail) { ell(g, cx - 34, hy + 12, 12, 26, pal.hair); ell(g, cx + 34, hy + 12, 12, 26, pal.hair); for (const sx of [-34, 34]) { rect(g, cx + sx - 10, hy - 14, 20, 10, pal.ribbon); } }
  ell(g, cx, hy, s, s * 1.05, pal.skin);
  ell(g, cx, hy - 14, s * 1.05, s * 0.68, pal.hair);
  ell(g, cx - 14, hy - 6, 10, 12, pal.hair); ell(g, cx + 14, hy - 6, 10, 12, pal.hair);
  ell(g, cx - 8, hy - 16, 8, 4, pal.hairHi);
  for (const side of [-1, 1]) {
    const ex = cx + side * 13, ey = hy + 5;
    ell(g, ex, ey, 8, 11, [255, 255, 255]);
    ell(g, ex, ey + 2, 6, 9, pal.eye);
    ell(g, ex, ey + 4, 3.5, 5, [30, 20, 45]);
    ell(g, ex - 3, ey - 3, 2.6, 3, [255, 255, 255]);
    px(g, ex + 3, ey + 4, [255, 255, 255, 180]);
    for (let i = -8; i <= 8; i++) px(g, ex + i, ey - 11, OUT);
  }
  ell(g, cx - 18, hy + 12, 6, 4, [255, 150, 175, 160]); ell(g, cx + 18, hy + 12, 6, 4, [255, 150, 175, 160]);
  rect(g, cx - 3, hy + 18, 2, 3, pal.mouth); ell(g, cx, hy + 20, 3, 1.5, pal.mouth);
  if (opt.crown) { const gy = hy - 34; for (let i = -2; i <= 2; i++) { const hh = (i % 2 ? 6 : 12); rect(g, cx + i * 8 - 3, gy - hh, 6, hh, pal.accent); } rect(g, cx - 18, gy, 36, 5, pal.accent); }
  // 肩/衣
  ell(g, cx, 122, 42, 26, pal.dress); rect(g, 14, 104, 68, 20, pal.dress); rect(g, 14, 104, 68, 5, pal.accent);
  outline(g, OUT);
  return g;
}
function iconFace(S) {
  const g = makeGrid(S, S);
  const cx = S / 2, cy = S * 0.5, s = S * 0.34;
  circ(g, cx, cy, S * 0.47, [130, 205, 255]);
  circ(g, cx, cy, S * 0.44, [170, 222, 255]);
  const pal = P.hero;
  ell(g, cx, cy - s * 0.15, s * 1.1, s * 1.05, pal.hair);
  ell(g, cx - s, cy, s * 0.4, s * 0.8, pal.hair); ell(g, cx + s, cy, s * 0.4, s * 0.8, pal.hair);
  ell(g, cx, cy + s * 0.05, s * 0.9, s * 0.95, pal.skin);
  ell(g, cx, cy - s * 0.45, s * 0.95, s * 0.6, pal.hair);
  for (const side of [-1, 1]) { const ex = cx + side * s * 0.42, ey = cy + s * 0.2; ell(g, ex, ey, s * 0.24, s * 0.32, [255, 255, 255]); ell(g, ex, ey + s * 0.05, s * 0.17, s * 0.25, pal.eye); px(g, ex - s * 0.08, ey - s * 0.1, [255, 255, 255]); }
  ell(g, cx - s * 0.5, cy + s * 0.4, s * 0.16, s * 0.1, [255, 150, 175, 170]); ell(g, cx + s * 0.5, cy + s * 0.4, s * 0.16, s * 0.1, [255, 150, 175, 170]);
  return g;
}

savePNG('hero.png', drawChar(P.hero, { twintail: true }));
savePNG('hero_idle.png', drawChar(P.hero, { twintail: true }));
savePNG('hero_walk1.png', drawChar(P.hero, { twintail: true, walk: 1 }));
savePNG('hero_walk2.png', drawChar(P.hero, { twintail: true, walk: 2 }));
savePNG('hero_atk.png', drawChar(P.hero, { twintail: true, arm: 'atk' }));
savePNG('maiden.png', drawChar(P.maiden, { longHair: true, crown: true }));
savePNG('npc_villager.png', drawChar(P.villagerA, {}));
savePNG('npc_villager2.png', drawChar(P.villagerB, { twintail: true }));
savePNG('portrait_hero.png', portrait(P.hero, { twintail: true }));
savePNG('portrait_maiden.png', portrait(P.maiden, { longHair: true, crown: true }));
saveMip('ic_launcher.png', (S) => iconFace(S));
saveMip('ic_launcher_round.png', (S) => iconFace(S));
console.log('v2 cute sprites regenerated with outlines.');
