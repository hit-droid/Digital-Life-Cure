// 少女地牢 - 像素建模素材生成器 (true pixel-art, 零外部依赖)
// 仅用 Node 内置 zlib + fs。低分辨率网格、整数 fillRect（无抗锯齿）、有限调色板。
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..');
const DRAW = path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi');
const RAW = path.join(ROOT, 'app', 'src', 'main', 'res', 'raw');
fs.mkdirSync(DRAW, { recursive: true });
fs.mkdirSync(RAW, { recursive: true });

// ---------- 极简画布（RGBA 缓冲） ----------
function makeCanvas(w, h) { return { w, h, data: new Uint8Array(w * h * 4) }; }
function hex(c) {
  if (c[0] !== '#') return [0, 0, 0];
  const n = parseInt(c.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function fill(cv, ax, ay, w, h, col) {
  const [r, g, b] = hex(col);
  for (let yy = ay; yy < ay + h; yy++)
    for (let xx = ax; xx < ax + w; xx++) {
      if (xx < 0 || yy < 0 || xx >= cv.w || yy >= cv.h) continue;
      const i = (yy * cv.w + xx) * 4;
      cv.data[i] = r; cv.data[i + 1] = g; cv.data[i + 2] = b; cv.data[i + 3] = 255;
    }
}
const O = '#191325';        // 统一描边
const SK = '#ffd9b8';       // 肤色

// ---------- PNG 编码（真彩+alpha, 无压缩滤波） ----------
const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1); t[n] = c >>> 0; }
  return t;
})();
function crc32(buf) { let c = 0xffffffff; for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function pngChunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function savePng(name, cv) {
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(cv.w, 0); ihdr.writeUInt32BE(cv.h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  // 每行前加 filter 字节 0
  const raw = Buffer.alloc((cv.w * 4 + 1) * cv.h);
  for (let y = 0; y < cv.h; y++) {
    const off = y * (cv.w * 4 + 1);
    raw[off] = 0;
    raw.set(cv.data.subarray(y * cv.w * 4, (y + 1) * cv.w * 4), off + 1);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  const out = Buffer.concat([sig, pngChunk('IHDR', ihdr), pngChunk('IDAT', idat), pngChunk('IEND', Buffer.alloc(0))]);
  fs.writeFileSync(path.join(DRAW, name + '.png'), out);
  console.log('wrote', name + '.png', out.length, 'bytes');
}

// ---------- 少女基础体型 ----------
function maiden(cv, c, acc) {
  fill(cv, 4, 0, 8, 4, c.hair);
  fill(cv, 4, 1, 8, 7, O);
  fill(cv, 5, 2, 6, 5, SK);
  fill(cv, 5, 1, 6, 2, c.hair);
  fill(cv, 5, 2, 1, 2, c.hair);
  fill(cv, 10, 2, 1, 2, c.hair);
  fill(cv, 6, 4, 1, 1, '#2a2030');
  fill(cv, 9, 4, 1, 1, '#2a2030');
  fill(cv, 5, 5, 1, 1, '#f6a6b2');
  fill(cv, 10, 5, 1, 1, '#f6a6b2');
  fill(cv, 3, 7, 10, 10, O);
  fill(cv, 4, 7, 8, 9, c.dress);
  fill(cv, 4, 7, 8, 2, c.dress2);
  fill(cv, 7, 9, 2, 7, c.dress2);
  fill(cv, 3, 8, 1, 5, SK);
  fill(cv, 12, 8, 1, 5, SK);
  fill(cv, 5, 16, 2, 3, SK);
  fill(cv, 9, 16, 2, 3, SK);
  fill(cv, 5, 18, 2, 1, c.shoe);
  fill(cv, 9, 18, 2, 1, c.shoe);
  if (acc) acc(cv, c);
}

// ================= 角色 =================
// 主角：银发剑少女
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#dfe7f5', dress: '#6f8fd6', dress2: '#4f6fb0', shoe: '#3a4a6a' }, (cv) => {
  fill(cv, 13, 5, 1, 9, '#cfd6e6'); fill(cv, 13, 5, 1, 1, '#ffffff');
  fill(cv, 12, 11, 3, 1, '#9aa3b8'); fill(cv, 13, 12, 1, 3, '#7a5a32');
  fill(cv, 4, 7, 8, 1, '#bcd0ff');
}); savePng('hero', cv); })();

// enemy0：红衣近战少女
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#7a4a2a', dress: '#d9534f', dress2: '#a83b3b', shoe: '#5a1f1f' }, (cv) => {
  fill(cv, 3, 9, 1, 5, '#a83b3b');
}); savePng('enemy0', cv); })();

// enemy1：紫袍巫女
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#3a2a5a', dress: '#7b5cc4', dress2: '#5a3f9a', shoe: '#2a1d45' }, (cv) => {
  fill(cv, 3, 2, 10, 1, '#2a1d45'); fill(cv, 6, 1, 4, 1, '#3a2a5a'); fill(cv, 7, 0, 2, 1, '#3a2a5a');
  fill(cv, 13, 4, 1, 11, '#7a5a32'); fill(cv, 12, 3, 3, 2, '#ffd34d');
}); savePng('enemy1', cv); })();

// enemy2：猫娘（绿）
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#5a8f3a', dress: '#8fd06a', dress2: '#5fa84a', shoe: '#3a5a2a' }, (cv) => {
  fill(cv, 4, 0, 2, 2, '#5a8f3a'); fill(cv, 10, 0, 2, 2, '#5a8f3a');
  fill(cv, 4, 1, 1, 1, '#ff9bb0'); fill(cv, 11, 1, 1, 1, '#ff9bb0');
  fill(cv, 13, 13, 1, 4, '#5a8f3a'); fill(cv, 14, 15, 1, 2, '#5a8f3a');
}); savePng('enemy2', cv); })();

// enemy3：金甲精英少女
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#caa14b', dress: '#e8b84b', dress2: '#c79427', shoe: '#7a5a10' }, (cv) => {
  fill(cv, 5, 0, 6, 1, '#ffd34d'); fill(cv, 5, 0, 1, 1, '#ffd34d'); fill(cv, 8, 0, 1, 1, '#ffd34d'); fill(cv, 11, 0, 1, 1, '#ffd34d'); fill(cv, 7, 1, 2, 1, '#ffd34d');
  fill(cv, 2, 8, 2, 2, '#c79427'); fill(cv, 12, 8, 2, 2, '#c79427');
}); savePng('enemy3', cv); })();

// enemy4：守卫（高个，持盾）
(() => { const cv = makeCanvas(16, 22);
  fill(cv, 4, 0, 8, 4, '#3a8f8f');
  fill(cv, 4, 1, 8, 7, O); fill(cv, 5, 2, 6, 5, SK);
  fill(cv, 5, 1, 6, 2, '#3a8f8f'); fill(cv, 6, 4, 1, 1, '#2a2030'); fill(cv, 9, 4, 1, 1, '#2a2030');
  fill(cv, 5, 5, 1, 1, '#f6a6b2'); fill(cv, 10, 5, 1, 1, '#f6a6b2');
  fill(cv, 3, 7, 10, 12, O); fill(cv, 4, 7, 8, 11, '#4fb0b0'); fill(cv, 4, 7, 8, 2, '#2f8a8a'); fill(cv, 7, 9, 2, 9, '#2f8a8a');
  fill(cv, 3, 8, 1, 6, SK); fill(cv, 12, 8, 1, 6, SK);
  fill(cv, 5, 18, 2, 3, SK); fill(cv, 9, 18, 2, 3, SK);
  fill(cv, 5, 20, 2, 1, '#1d5a5a'); fill(cv, 9, 20, 2, 1, '#1d5a5a');
  fill(cv, 1, 9, 3, 7, '#9aa6b5'); fill(cv, 2, 10, 1, 5, '#cfd6e6');
  savePng('enemy4', cv);
})();

// enemy5：弓手（青）
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#2a6a7a', dress: '#5ec8d8', dress2: '#3a9ab0', shoe: '#1d5a6a' }, (cv) => {
  fill(cv, 13, 4, 1, 11, '#8a5a2b'); fill(cv, 12, 5, 1, 9, '#8a5a2b');
  fill(cv, 14, 4, 1, 1, '#dddddd'); fill(cv, 14, 14, 1, 1, '#dddddd'); fill(cv, 14, 5, 1, 9, '#eeeeee');
}); savePng('enemy5', cv); })();

// 被囚少女（救出后）
(() => { const cv = makeCanvas(16, 20); maiden(cv, { hair: '#caa14b', dress: '#eef2ff', dress2: '#c9d2f0', shoe: '#caa46a' }, (cv) => {
  fill(cv, 5, 0, 6, 1, '#ffd34d'); fill(cv, 5, 0, 1, 1, '#ffd34d'); fill(cv, 8, 0, 1, 1, '#ffd34d'); fill(cv, 11, 0, 1, 1, '#ffd34d');
  fill(cv, 4, 9, 8, 1, '#9a7b4a'); fill(cv, 4, 12, 8, 1, '#9a7b4a'); fill(cv, 7, 8, 1, 6, '#9a7b4a');
}); savePng('maiden', cv); })();

// Boss：水晶女王 (28x28)
(() => { const cv = makeCanvas(28, 28); const hair = '#caa14b', dress = '#9b59b6', dress2 = '#6f3f8a', shoe = '#3a1d5a';
  fill(cv, 8, 0, 12, 6, hair);
  fill(cv, 8, 2, 12, 10, O); fill(cv, 9, 4, 10, 7, SK); fill(cv, 9, 3, 10, 3, hair); fill(cv, 9, 4, 1, 3, hair); fill(cv, 18, 4, 1, 3, hair);
  fill(cv, 11, 7, 2, 2, '#2a2030'); fill(cv, 16, 7, 2, 2, '#2a2030'); fill(cv, 10, 9, 2, 1, '#f6a6b2'); fill(cv, 17, 9, 2, 1, '#f6a6b2');
  fill(cv, 9, 0, 10, 2, '#ffd34d'); fill(cv, 9, 0, 2, 2, '#ffd34d'); fill(cv, 13, 0, 2, 2, '#ffd34d'); fill(cv, 17, 0, 2, 2, '#ffd34d');
  fill(cv, 6, 12, 16, 15, O); fill(cv, 7, 12, 14, 14, dress); fill(cv, 7, 12, 14, 3, dress2); fill(cv, 13, 14, 2, 12, dress2);
  fill(cv, 6, 13, 2, 12, SK); fill(cv, 20, 13, 2, 12, SK); fill(cv, 9, 25, 4, 3, SK); fill(cv, 15, 25, 4, 3, SK);
  fill(cv, 9, 27, 4, 1, shoe); fill(cv, 15, 27, 4, 1, shoe);
  fill(cv, 12, 14, 4, 4, '#5fe6ff'); fill(cv, 13, 15, 2, 2, '#bff4ff');
  fill(cv, 23, 8, 2, 18, '#7a5a32'); fill(cv, 22, 6, 4, 3, '#5fe6ff');
  savePng('boss', cv);
})();

// 囚禁水晶 (16x16)
(() => { const cv = makeCanvas(16, 16);
  fill(cv, 5, 1, 6, 2, '#bff4ff'); fill(cv, 4, 3, 8, 9, '#5fe6ff'); fill(cv, 3, 4, 10, 7, '#3fb6e0');
  fill(cv, 5, 5, 4, 5, '#bff4ff'); fill(cv, 6, 6, 2, 3, '#ffffff'); fill(cv, 4, 12, 8, 2, '#2f8fbf');
  savePng('crystal', cv);
})();

// ================= 道具 (16x16) =================
(() => { const cv = makeCanvas(16, 16);
  fill(cv, 6, 1, 4, 2, '#caa46a'); fill(cv, 7, 3, 2, 1, '#caa46a');
  fill(cv, 5, 4, 6, 9, O); fill(cv, 6, 5, 4, 7, '#ff5d7a'); fill(cv, 6, 5, 4, 2, '#ff97a8'); fill(cv, 7, 6, 1, 3, '#ffffff');
  savePng('item_potion', cv);
})();
(() => { const cv = makeCanvas(16, 16);
  fill(cv, 7, 1, 2, 4, '#ffd34d'); fill(cv, 5, 4, 6, 2, '#ffd34d'); fill(cv, 3, 5, 10, 3, '#ffd34d'); fill(cv, 5, 7, 6, 2, '#ffd34d'); fill(cv, 7, 8, 2, 5, '#ffd34d');
  fill(cv, 6, 4, 1, 5, '#fff2b0'); fill(cv, 7, 2, 1, 9, '#fff2b0');
  savePng('item_relic', cv);
})();
(() => { const cv = makeCanvas(16, 16);
  fill(cv, 2, 7, 12, 7, O); fill(cv, 3, 8, 10, 5, '#8a5a2b'); fill(cv, 3, 5, 10, 3, '#b07a3a'); fill(cv, 3, 7, 10, 1, '#5a3a1a');
  fill(cv, 7, 7, 2, 4, '#ffd34d'); fill(cv, 7, 9, 2, 2, '#7a5a10');
  savePng('item_chest', cv);
})();
(() => { const cv = makeCanvas(16, 16);
  fill(cv, 7, 6, 2, 10, '#6a4a2a'); fill(cv, 1, 2, 14, 5, O); fill(cv, 2, 3, 12, 3, '#caa46a'); fill(cv, 3, 4, 10, 1, '#7a5a2a');
  savePng('item_sign', cv);
})();
(() => { const cv = makeCanvas(16, 16);
  fill(cv, 3, 3, 10, 11, O); fill(cv, 4, 4, 8, 9, '#e8dcc0'); fill(cv, 7, 4, 2, 9, '#9a8a6a');
  fill(cv, 5, 5, 1, 1, '#9a8a6a'); fill(cv, 5, 7, 1, 1, '#9a8a6a'); fill(cv, 10, 5, 1, 1, '#9a8a6a'); fill(cv, 10, 7, 1, 1, '#9a8a6a'); fill(cv, 4, 2, 8, 1, '#b06fd0');
  savePng('item_book', cv);
})();

// ================= 地块 (16x16) =================
(() => { const cv = makeCanvas(16, 16);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    let col = ((x + y) % 2 === 0) ? [86, 158, 78] : [78, 146, 70];
    if ((x * 7 + y * 13) % 11 === 0) col = [110, 178, 96];
    if ((x * 5 + y * 3) % 17 === 0) col = [64, 124, 58];
    const i = (y * 16 + x) * 4; cv.data[i] = col[0]; cv.data[i + 1] = col[1]; cv.data[i + 2] = col[2]; cv.data[i + 3] = 255;
  } savePng('tile_grass', cv);
})();
(() => { const cv = makeCanvas(16, 16);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    let base = [120, 122, 132];
    const row = Math.floor(y / 8); const off = (row % 2) * 8;
    const seam = (y % 8 === 0) || (((x + off) % 8) === 0);
    if (seam) base = [80, 82, 92]; else if ((x + y) % 2 === 0) base = [132, 134, 144];
    const i = (y * 16 + x) * 4; cv.data[i] = base[0]; cv.data[i + 1] = base[1]; cv.data[i + 2] = base[2]; cv.data[i + 3] = 255;
  } savePng('tile_stone', cv);
})();

// ================= 音频 =================
function quant(v) { return Math.max(-1, Math.min(1, v)); }
function tone(t, f, a) { return a * Math.sin(2 * Math.PI * f * t); }
function env(t, d, atk, sus) { if (t < atk) return t / atk; if (t > d - atk) return Math.max(0, (d - t) / atk); return sus; }
function writeWav(name, sr, dur, fn) {
  const n = Math.floor(sr * dur);
  const data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const t = i / sr; let v = quant(fn(t, i / n));
    let s = Math.round(v * 32767); if (s > 32767) s = 32767; if (s < -32768) s = -32768;
    data.writeInt16LE(s, i * 2);
  }
  const head = Buffer.alloc(44);
  head.write('RIFF', 0); head.writeUInt32LE(36 + data.length, 4); head.write('WAVE', 8);
  head.write('fmt ', 12); head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22);
  head.writeUInt32LE(sr, 24); head.writeUInt32LE(sr * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34);
  head.write('data', 36); head.writeUInt32LE(data.length, 40);
  fs.writeFileSync(path.join(RAW, name + '.wav'), Buffer.concat([head, data]));
  console.log('wrote', name + '.wav', sr * dur * 2 + 44, 'bytes');
}
writeWav('bgm', 22050, 16, (t) => {
  const bpm = 100, beat = 60 / bpm, bar = beat * 4, tb = t % bar;
  const notes = [220.0, 261.63, 293.66, 329.63, 392.0, 329.63, 293.66, 261.63];
  const ni = Math.floor(tb / (bar / notes.length)) % notes.length, f = notes[ni];
  const lead = tone(t, f, 0.16) * env(tb, bar, 0.04, 0.7);
  const pad = (tone(t, f / 2, 0.07) + tone(t, f / 2 * 1.5, 0.05)) * env(t % (bar * 2), bar * 2, 0.2, 0.85);
  const bass = tone(t, f / 4, 0.12) * env(tb % beat, beat, 0.01, 0.9);
  const hat = (Math.floor(t * 8) % 2 === 0 ? 0.04 * ((Math.floor(t * 8000) % 97) / 97 - 0.5) : 0);
  const kick = (tb % beat < 0.02) ? 0.18 * Math.exp(-tb % beat * 120) : 0;
  return lead + pad + bass + hat + kick;
});
writeWav('sfx_hit', 22050, 0.13, (t) => (tone(t, 180 + 600 * t, 0.4) * Math.exp(-t * 16)));
writeWav('sfx_skill', 22050, 0.25, (t) => (tone(t, 320 + 500 * t, 0.4) * Math.exp(-t * 8) + tone(t, 640, 0.15) * Math.exp(-t * 6)));

console.log('ALL ASSETS GENERATED');
