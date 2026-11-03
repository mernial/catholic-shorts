/* 가사 플레이어 — Suno 노래 + 가사 싱크 + 음량 반응 시각화 + 앨범 표지
 * 모든 그림은 하나의 canvas(1920x1080 또는 1080x1920)에 그린다.
 * 그래서 방송(창 캡처)과 영상 저장(MediaRecorder)이 같은 화면을 쓴다. */
'use strict';

const $ = (s) => document.querySelector(s);
const FONT = '"Noto Sans KR","Apple SD Gothic Neo","Malgun Gothic",sans-serif';
const DEFAULT_ACCENT = { h: 37, s: 66, l: 65 };

const audio = $('#audio');
const canvas = $('#stage');
const ctx = canvas.getContext('2d');

/* ---------- 상태 ---------- */
const S = {
  title: '', artist: '',
  lines: [],            // [{ text, t }]  t = 초 (null이면 아직 싱크 안 됨)
  offset: 0,
  aspect: '16:9',
  viz: 'bars',
  fontScale: 1,
  showTitle: true,
  bg: 'scene',          // 'scene' = 가사 분위기 장면, 'cover' = 앨범 표지 흐리게
  scenes: [],           // [{ t, theme }] 배경 장면이 바뀌는 시각과 분위기 (내 그림이 있으면 그림이 우선)
};
let audioBlob = null, audioName = '';
let coverBlob = null, coverImg = null, coverURL = null, audioURL = null;
let accent = { ...DEFAULT_ACCENT };
let W = 1920, H = 1080;

let syncing = false, syncIdx = 0;
let recorder = null;

/* ---------- 오디오 분석 ---------- */
let actx = null, analyser = null, recDest = null, freq = null, wave = null;
function ensureAudio() {
  if (actx) { if (actx.state === 'suspended') actx.resume(); return; }
  actx = new (window.AudioContext || window.webkitAudioContext)();
  const src = actx.createMediaElementSource(audio);
  analyser = actx.createAnalyser();
  analyser.fftSize = 2048;
  analyser.smoothingTimeConstant = 0.8;
  src.connect(analyser);
  analyser.connect(actx.destination);
  recDest = actx.createMediaStreamDestination();
  src.connect(recDest);
  freq = new Uint8Array(analyser.frequencyBinCount);
  wave = new Uint8Array(analyser.fftSize);
}

const BANDS = 64;
const bandVals = new Float32Array(BANDS);
let bass = 0, level = 0;

function analyse(dt) {
  const k = Math.min(1, dt * 60);
  if (!analyser) {
    for (let i = 0; i < BANDS; i++) bandVals[i] *= 1 - 0.1 * k;
    bass *= 1 - 0.1 * k; level *= 1 - 0.1 * k;
    return;
  }
  analyser.getByteFrequencyData(freq);
  analyser.getByteTimeDomainData(wave);
  const hzPerBin = actx.sampleRate / analyser.fftSize;
  const lo = 40, hi = 16000;
  for (let i = 0; i < BANDS; i++) {
    const f0 = lo * Math.pow(hi / lo, i / BANDS);
    const f1 = lo * Math.pow(hi / lo, (i + 1) / BANDS);
    const b0 = Math.floor(f0 / hzPerBin);
    const b1 = Math.max(b0 + 1, Math.ceil(f1 / hzPerBin));
    let m = 0;
    for (let b = b0; b < b1 && b < freq.length; b++) m = Math.max(m, freq[b]);
    // 높은 음역은 원래 약하게 잡히므로 살짝 키운다
    let v = Math.pow(m / 255, 1.6) * (1 + (i / BANDS) * 0.9);
    v = Math.min(1, v);
    bandVals[i] = v > bandVals[i] ? bandVals[i] + (v - bandVals[i]) * Math.min(1, 0.7 * k)
                                  : bandVals[i] + (v - bandVals[i]) * Math.min(1, 0.12 * k);
  }
  // 저음(30~140Hz) 세기 → 표지가 쿵쿵 뛰는 정도
  let sum = 0, n = 0;
  for (let b = Math.floor(30 / hzPerBin); b <= Math.ceil(140 / hzPerBin); b++) { sum += freq[b]; n++; }
  const nb = Math.pow(sum / n / 255, 2.2);
  bass += (nb - bass) * Math.min(1, (nb > bass ? 0.6 : 0.15) * k);
  let rms = 0;
  for (let i = 0; i < wave.length; i++) { const d = (wave[i] - 128) / 128; rms += d * d; }
  rms = Math.sqrt(rms / wave.length);
  level += (Math.min(1, rms * 3) - level) * Math.min(1, 0.2 * k);
}

/* ---------- 가사 ---------- */
function parseLyrics(text) {
  const out = [];
  const meta = {};
  let offsetMs = 0;
  const tagRe = /\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)\]/g;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const tags = [...line.matchAll(tagRe)];
    if (tags.length) {
      const txt = line.replace(tagRe, '').trim();
      for (const m of tags) out.push({ ...splitSub(txt), t: +m[1] * 60 + parseFloat(m[2].replace(':', '.')) });
      continue;
    }
    const mt = line.match(/^\[(ti|ar|al|offset)\s*:(.*)\]$/i);
    if (mt) { meta[mt[1].toLowerCase()] = mt[2].trim(); continue; }
    if (/^\[[^\]]*\]$/.test(line)) continue;            // [Verse], [Chorus] 등 Suno 구간 표시
    out.push({ ...splitSub(line), t: null });
  }
  if (meta.offset) offsetMs = parseFloat(meta.offset) || 0;
  if (out.length && out.every((l) => l.t != null)) {
    out.sort((a, b) => a.t - b.t);
    if (offsetMs) out.forEach((l) => { l.t = Math.max(0, l.t - offsetMs / 1000); });
  }
  return { lines: out, meta };
}

/* "원문 // 번역" → { text, sub } */
function splitSub(line) {
  const k = line.indexOf(' // ');
  if (k < 0) return { text: line };
  const sub = line.slice(k + 4).trim();
  return sub ? { text: line.slice(0, k).trim(), sub } : { text: line.slice(0, k).trim() };
}
const lineSource = (l) => (l.sub ? `${l.text} // ${l.sub}` : l.text);

function lineStart(i) { const t = S.lines[i].t; return t == null ? null : t + S.offset; }

function currentIndex(now) {
  let cur = -1;
  for (let i = 0; i < S.lines.length; i++) {
    const t = lineStart(i);
    if (t != null && t <= now) cur = i;
  }
  return cur;
}

function lineProgress(i, now) {
  const start = lineStart(i);
  if (start == null) return 0;
  let end = null;
  for (let j = i + 1; j < S.lines.length; j++) { const t = lineStart(j); if (t != null && t > start) { end = t; break; } }
  if (end == null) end = isFinite(audio.duration) ? audio.duration : start + 5;
  // 자동 싱크는 줄이 끝나는 시각도 안다
  const e = S.lines[i].e;
  if (e != null && e + S.offset > start) end = Math.min(end, e + S.offset + 0.15);
  // 간주가 긴 경우엔 글자 수로 대략 부르는 시간을 추정
  const est = 1.2 + 0.32 * [...S.lines[i].text].length;
  const dur = Math.max(0.3, Math.min(end - start, Math.max(est, 2)) - 0.15);
  return Math.max(0, Math.min(1, (now - start) / dur));
}

function toLRC() {
  const fmt = (t) => {
    const m = Math.floor(t / 60), s = t - m * 60;
    return `[${String(m).padStart(2, '0')}:${s.toFixed(2).padStart(5, '0')}]`;
  };
  const head = [];
  if (S.title) head.push(`[ti:${S.title}]`);
  if (S.artist) head.push(`[ar:${S.artist}]`);
  const body = S.lines.map((l, i) => (l.t == null ? lineSource(l) : fmt(Math.max(0, lineStart(i))) + lineSource(l)));
  return head.concat(body).join('\n') + '\n';
}

function autoDistribute() {
  const dur = audio.duration;
  if (!isFinite(dur) || !S.lines.length) { alert('음악 파일과 가사를 먼저 넣어주세요.'); return; }
  const n = S.lines.length;
  let i = 0;
  while (i < n) {
    if (S.lines[i].t != null) { i++; continue; }
    let j = i;
    while (j < n && S.lines[j].t == null) j++;
    const a = i > 0 ? S.lines[i - 1].t : dur * 0.06 - (dur * 0.89) / n;
    const b = j < n ? S.lines[j].t : dur * 0.95;
    const step = (b - a) / (j - i + 1);
    for (let k = i; k < j; k++) S.lines[k].t = Math.max(0, a + step * (k - i + 1));
    i = j;
  }
  renderList(); save();
}

/* ---------- 앨범 표지 ---------- */
function loadCover(blob) {
  coverBlob = blob;
  if (coverURL) URL.revokeObjectURL(coverURL);
  if (!blob) { coverImg = null; accent = { ...DEFAULT_ACCENT }; rebuildBg(); return Promise.resolve(); }
  coverURL = URL.createObjectURL(blob);
  return new Promise((res) => {
    const img = new Image();
    img.onload = () => { coverImg = img; accent = extractAccent(img); rebuildBg(); res(); };
    img.onerror = () => { coverImg = null; rebuildBg(); res(); };
    img.src = coverURL;
  });
}

function extractAccent(img) {
  const N = 32, c = document.createElement('canvas');
  c.width = c.height = N;
  const x = c.getContext('2d');
  x.drawImage(img, 0, 0, N, N);
  const d = x.getImageData(0, 0, N, N).data;
  let r = 0, g = 0, b = 0, wsum = 0;
  for (let i = 0; i < d.length; i += 4) {
    const [h, s, l] = rgbToHsl(d[i], d[i + 1], d[i + 2]);
    const w = Math.pow(s, 2) * (l > 0.15 && l < 0.9 ? 1 : 0.05) + 0.001;
    r += d[i] * w; g += d[i + 1] * w; b += d[i + 2] * w; wsum += w;
  }
  const [h, s] = rgbToHsl(r / wsum, g / wsum, b / wsum);
  return { h: Math.round(h * 360), s: Math.round(Math.max(s, 0.45) * 100), l: 66 };
}

function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  let h = 0, s = 0;
  const l = (max + min) / 2;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h /= 6;
  }
  return [h, s, l];
}

const hsl = (c, a = 1, dl = 0) => `hsla(${c.h},${c.s}%,${Math.max(0, Math.min(100, c.l + dl))}%,${a})`;

/* 배경: 표지를 크게 흐리게 깔아둔 그림을 미리 만들어 둔다 (매 프레임 흐림 처리는 무겁다) */
let bgCanvas = null;
function rebuildBg() {
  bgCanvas = document.createElement('canvas');
  bgCanvas.width = W; bgCanvas.height = H;
  const b = bgCanvas.getContext('2d');
  if (coverImg) {
    if (typeof b.filter === 'string') {
      b.filter = 'blur(60px) saturate(1.3)';
      drawImageCover(b, coverImg, -150, -150, W + 300, H + 300);
      b.filter = 'none';
    } else {
      const sw = Math.max(8, Math.round(W / 40)), sh = Math.max(8, Math.round(H / 40));
      const small = document.createElement('canvas'); small.width = sw; small.height = sh;
      drawImageCover(small.getContext('2d'), coverImg, 0, 0, sw, sh);
      const mid = document.createElement('canvas'); mid.width = sw * 4; mid.height = sh * 4;
      const m = mid.getContext('2d'); m.imageSmoothingQuality = 'high';
      m.drawImage(small, 0, 0, mid.width, mid.height);
      b.imageSmoothingQuality = 'high';
      b.drawImage(mid, 0, 0, W, H);
    }
  } else {
    const g = b.createLinearGradient(0, 0, W, H);
    g.addColorStop(0, hsl(accent, 1, -45));
    g.addColorStop(1, '#0b0a0a');
    b.fillStyle = g; b.fillRect(0, 0, W, H);
  }
}

function drawImageCover(c, img, x, y, w, h) {
  const ir = img.width / img.height, r = w / h;
  let sw = img.width, sh = img.height, sx = 0, sy = 0;
  if (ir > r) { sw = img.height * r; sx = (img.width - sw) / 2; } else { sh = img.width / r; sy = (img.height - sh) / 2; }
  c.drawImage(img, sx, sy, sw, sh, x, y, w, h);
}

function roundRect(c, x, y, w, h, r) {
  c.beginPath();
  c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r);
  c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r);
  c.arcTo(x, y, x + w, y, r);
  c.closePath();
}

/* ---------- 화면 배치 ---------- */
function layout() {
  const noLyrics = S.lines.length === 0;
  const ring = S.viz === 'ring';
  if (S.aspect === '9:16') {
    return {
      cover: { x: 540, y: noLyrics ? 760 : 560, size: ring ? 580 : 720 },
      title: { y: noLyrics ? 1260 : 1010, size: 50, max: 960 },
      lyrics: { cx: 540, cy: 1390, w: 960, h: 520, size: 56 },
      bars: { y: 1780, h: 130, x0: 60, x1: 1020 },
      prog: { y: 1860, x0: 80, x1: 1000 },
    };
  }
  return {
    cover: { x: noLyrics ? 960 : 520, y: 420, size: ring ? 460 : 560 },
    title: { y: 790, size: 44, max: noLyrics ? 1400 : 820 },
    lyrics: { cx: 1400, cy: 420, w: 820, h: 760, size: 52 },
    bars: { y: 985, h: 115, x0: 80, x1: 1840 },
    prog: { y: 1040, x0: 80, x1: 1840 },
  };
}

function setAspect(a) {
  S.aspect = a;
  [W, H] = a === '9:16' ? [1080, 1920] : [1920, 1080];
  canvas.width = W; canvas.height = H;
  lyricCache = null;
  rebuildBg();
}

/* ---------- 가사 줄바꿈 / 배치 캐시 ---------- */
let lyricCache = null, lyricsVersion = 0, scrollY = null;

function wrapText(c, text, maxW) {
  if (!text) return [''];
  const rows = [];
  let row = '';
  const push = (tok) => {
    const test = row + tok;
    if (c.measureText(test).width <= maxW || !row.trim()) { row = test; return; }
    rows.push(row.trim()); row = tok.trimStart();
  };
  for (const tok of text.split(/(\s+)/)) {
    if (c.measureText(tok).width > maxW) {
      for (const ch of tok) push(ch);
    } else push(tok);
  }
  if (row.trim()) rows.push(row.trim());
  return rows.length ? rows : [''];
}

function lyricLayout(L) {
  const size = Math.round(L.size * S.fontScale);
  const key = `${lyricsVersion}|${size}|${L.w}|${W}`;
  if (lyricCache && lyricCache.key === key) return lyricCache;
  ctx.font = `800 ${size}px ${FONT}`;
  const rowH = size * 1.3, gap = size * 0.75;
  const subSize = Math.round(size * 0.52), subH = subSize * 1.4;
  let y = 0;
  const items = S.lines.map((l) => {
    ctx.font = `800 ${size}px ${FONT}`;
    const rows = wrapText(ctx, l.text || '♪  ♪  ♪', L.w);
    const widths = rows.map((r) => ctx.measureText(r).width);
    ctx.font = `500 ${subSize}px ${FONT}`;
    const subRows = l.sub ? wrapText(ctx, l.sub, L.w) : [];
    const subWidths = subRows.map((r) => ctx.measureText(r).width);
    const h = rows.length * rowH + (subRows.length ? size * 0.15 + subRows.length * subH : 0);
    const it = { rows, widths, subRows, subWidths, y, h, c: y + h / 2 };
    y += h + gap;
    return it;
  });
  lyricCache = { key, size, rowH, subSize, subH, items };
  return lyricCache;
}

/* ---------- 파티클 ---------- */
const particles = Array.from({ length: 70 }, () => newParticle(true));
function newParticle(anyY) {
  return { x: Math.random(), y: anyY ? Math.random() : 1.05, r: 1 + Math.random() * 3.5, s: 0.02 + Math.random() * 0.05, a: 0.15 + Math.random() * 0.45, w: Math.random() * Math.PI * 2 };
}

/* ---------- 그리기 ---------- */
let lastT = performance.now(), clock = 0, spin = 0, lastCur = -2;

function frame(nowMs) {
  const dt = Math.min(0.1, (nowMs - lastT) / 1000);
  lastT = nowMs; clock += dt;
  analyse(dt);
  if (!audio.paused) spin += dt * 0.35;
  const now = audio.currentTime || 0;
  const L = layout();

  drawBackground(now);
  drawParticles(dt);
  drawCover(L.cover);
  if (S.showTitle) drawTitle(L.cover, L.title);
  if (S.lines.length) drawLyrics(L.lyrics, now);
  if (S.viz === 'bars') drawBars(L.bars);
  else if (S.viz === 'wave') drawWave(L.bars);
  drawProgress(L.prog, now);

  updateUI(now);
  requestAnimationFrame(frame);
}

/* ---------- 장면 배경 (가사 분위기 / 내 그림) ---------- */
let sceneImgs = [], sceneBlobs = [], scenesVersion = 0, savedScenes = -1;
let bgCur = -1, bgPrev = -1, bgFadeStart = 0;

function blobToImage(blob) {
  return new Promise((res) => {
    if (!blob) { res(null); return; }
    const img = new Image();
    img.onload = () => res(img);
    img.onerror = () => res(null);
    img.src = URL.createObjectURL(blob);
  });
}

async function setSceneImage(i, blob) {
  sceneBlobs[i] = blob;
  sceneImgs[i] = await blobToImage(blob);
  scenesVersion++;
  save();
}

function clearScenes() {
  S.scenes = []; sceneImgs = []; sceneBlobs = [];
  bgCur = bgPrev = -1; scenesVersion++;
}

function sceneAt(now) {
  let idx = -1;
  for (let i = 0; i < S.scenes.length; i++) {
    if (S.scenes[i].t + S.offset <= now) idx = i;
  }
  return Math.max(0, idx);
}

function drawScene(i, alpha, now) {
  if (sceneImgs[i]) drawSceneImg(i, alpha, now);
  else drawTheme(ctx, S.scenes[i].theme, W, H, alpha, clock, bass, level);
}

function drawSceneImg(i, alpha, now) {
  const img = sceneImgs[i];
  if (!img || alpha <= 0) return;
  const lt = Math.max(0, now - (S.scenes[i].t + S.offset));
  // 천천히 다가가며 옆으로 흐르는 움직임 (켄 번스)
  const sc = 1.05 + 0.08 * Math.min(1, lt / 40) + bass * 0.025;
  const dir = i % 2 ? 1 : -1;
  const dx = dir * W * 0.025 * Math.min(1, lt / 40);
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.translate(W / 2 + dx, H / 2);
  ctx.scale(sc, sc);
  drawImageCover(ctx, img, -W / 2, -H / 2, W, H);
  ctx.restore();
}

function drawBackground(now) {
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, W, H);
  if (S.bg === 'scene' && S.scenes.length) {
    const idx = sceneAt(now);
    if (idx !== bgCur) { bgPrev = bgCur; bgCur = idx; bgFadeStart = clock; }
    const f = Math.min(1, (clock - bgFadeStart) / 1.8);
    const ease = f * f * (3 - 2 * f);
    if (bgPrev >= 0 && bgPrev < S.scenes.length && f < 1) drawScene(bgPrev, 1, now);
    drawScene(bgCur, bgPrev >= 0 ? ease : 1, now);
    ctx.fillStyle = `rgba(0,0,0,${(sceneImgs[bgCur] ? 0.4 : 0.15) - level * 0.08})`;
    ctx.fillRect(0, 0, W, H);
    drawVignette();
    return;
  }
  const sc = 1.08 + bass * 0.06 + Math.sin(clock * 0.15) * 0.015;
  ctx.save();
  ctx.translate(W / 2 + Math.sin(clock * 0.07) * 30, H / 2 + Math.cos(clock * 0.09) * 20);
  ctx.scale(sc, sc);
  ctx.drawImage(bgCanvas, -W / 2, -H / 2, W, H);
  ctx.restore();
  ctx.fillStyle = `rgba(0,0,0,${0.5 - level * 0.12})`;
  ctx.fillRect(0, 0, W, H);
  drawVignette();
}

function drawVignette() {
  const vg = ctx.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.3, W / 2, H / 2, Math.max(W, H) * 0.75);
  vg.addColorStop(0, 'rgba(0,0,0,0)');
  vg.addColorStop(1, 'rgba(0,0,0,0.65)');
  ctx.fillStyle = vg;
  ctx.fillRect(0, 0, W, H);
}

function drawParticles(dt) {
  ctx.save();
  for (const p of particles) {
    p.y -= p.s * dt * (1 + bass * 6);
    p.w += dt;
    if (p.y < -0.05) Object.assign(p, newParticle(false));
    const x = (p.x + Math.sin(p.w) * 0.01) * W, y = p.y * H;
    ctx.globalAlpha = p.a * (0.5 + level);
    ctx.fillStyle = hsl(accent, 1, 15);
    ctx.beginPath(); ctx.arc(x, y, p.r * (1 + bass), 0, Math.PI * 2); ctx.fill();
  }
  ctx.restore();
}

function drawCover(C) {
  const pulse = 1 + bass * 0.07;
  const s = C.size * pulse;
  ctx.save();
  // 뒤쪽 빛
  const glow = ctx.createRadialGradient(C.x, C.y, s * 0.2, C.x, C.y, s * 1.1);
  glow.addColorStop(0, hsl(accent, 0.15 + bass * 0.45));
  glow.addColorStop(1, hsl(accent, 0));
  ctx.fillStyle = glow;
  ctx.fillRect(C.x - s * 1.2, C.y - s * 1.2, s * 2.4, s * 2.4);

  if (S.viz === 'ring') {
    drawRing(C, s / 2);
    ctx.translate(C.x, C.y);
    ctx.rotate(spin);
    ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 50;
    ctx.beginPath(); ctx.arc(0, 0, s / 2, 0, Math.PI * 2); ctx.fillStyle = '#111'; ctx.fill();
    ctx.shadowBlur = 0;
    ctx.save();
    ctx.beginPath(); ctx.arc(0, 0, s / 2, 0, Math.PI * 2); ctx.clip();
    paintCoverImage(-s / 2, -s / 2, s);
    // LP판 결
    ctx.strokeStyle = 'rgba(0,0,0,.18)'; ctx.lineWidth = 1.5;
    for (let r = s * 0.2; r < s / 2; r += 9) { ctx.beginPath(); ctx.arc(0, 0, r, 0, Math.PI * 2); ctx.stroke(); }
    ctx.restore();
    ctx.beginPath(); ctx.arc(0, 0, s * 0.045, 0, Math.PI * 2); ctx.fillStyle = '#0d0c0b'; ctx.fill();
    ctx.lineWidth = 3; ctx.strokeStyle = hsl(accent, 0.8); ctx.stroke();
  } else {
    const x = C.x - s / 2, y = C.y - s / 2, r = s * 0.035;
    ctx.shadowColor = 'rgba(0,0,0,.65)'; ctx.shadowBlur = 60; ctx.shadowOffsetY = 20;
    roundRect(ctx, x, y, s, s, r); ctx.fillStyle = '#111'; ctx.fill();
    ctx.shadowColor = 'transparent';
    ctx.save(); roundRect(ctx, x, y, s, s, r); ctx.clip();
    paintCoverImage(x, y, s);
    // 위쪽 반사광
    const sh = ctx.createLinearGradient(x, y, x + s, y + s);
    sh.addColorStop(0, 'rgba(255,255,255,0.12)'); sh.addColorStop(0.45, 'rgba(255,255,255,0)');
    ctx.fillStyle = sh; ctx.fillRect(x, y, s, s);
    ctx.restore();
    ctx.lineWidth = 2; ctx.strokeStyle = 'rgba(255,255,255,0.12)';
    roundRect(ctx, x, y, s, s, r); ctx.stroke();
  }
  ctx.restore();
}

function paintCoverImage(x, y, s) {
  if (coverImg) { drawImageCover(ctx, coverImg, x, y, s, s); return; }
  const g = ctx.createLinearGradient(x, y, x + s, y + s);
  g.addColorStop(0, hsl(accent, 1, -10)); g.addColorStop(1, hsl({ ...accent, h: accent.h + 40 }, 1, -40));
  ctx.fillStyle = g; ctx.fillRect(x, y, s, s);
  ctx.fillStyle = 'rgba(255,255,255,.85)';
  ctx.font = `700 ${Math.round(s * 0.32)}px ${FONT}`;
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText('♪', x + s / 2, y + s / 2);
}

function drawRing(C, radius) {
  const n = BANDS * 2, inner = radius + 14, maxLen = S.aspect === '9:16' ? 95 : 90;
  ctx.save();
  ctx.translate(C.x, C.y);
  ctx.lineCap = 'round';
  ctx.lineWidth = (2 * Math.PI * inner) / n * 0.55;
  for (let i = 0; i < n; i++) {
    const bi = i < BANDS ? i : n - 1 - i;           // 좌우 대칭
    const v = bandVals[bi];
    const a = (i / n) * Math.PI * 2 - Math.PI / 2;
    const len = 4 + v * maxLen;
    ctx.strokeStyle = hsl({ ...accent, h: accent.h + bi * 0.8 }, 0.55 + v * 0.45, 10 * v);
    ctx.beginPath();
    ctx.moveTo(Math.cos(a) * inner, Math.sin(a) * inner);
    ctx.lineTo(Math.cos(a) * (inner + len), Math.sin(a) * (inner + len));
    ctx.stroke();
  }
  ctx.restore();
}

function fitText(text, max) {
  if (ctx.measureText(text).width <= max) return text;
  let s = text;
  while (s.length > 1 && ctx.measureText(s + '…').width > max) s = s.slice(0, -1);
  return s + '…';
}

function drawTitle(C, T) {
  if (!S.title && !S.artist) return;
  ctx.save();
  ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
  ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 16;
  ctx.fillStyle = '#fff';
  ctx.font = `900 ${T.size}px ${FONT}`;
  if (S.title) ctx.fillText(fitText(S.title, T.max), C.x, T.y);
  ctx.fillStyle = 'rgba(255,255,255,.72)';
  ctx.font = `500 ${Math.round(T.size * 0.66)}px ${FONT}`;
  if (S.artist) ctx.fillText(fitText(S.artist, T.max), C.x, T.y + T.size * 1.05);
  ctx.restore();
}

function drawLyrics(L, now) {
  const lay = lyricLayout(L);
  const cur = currentIndex(now);
  const items = lay.items;
  const target = cur >= 0 ? items[cur].c : items[0].c - lay.rowH * 2;
  if (scrollY == null || Math.abs(target - scrollY) > L.h * 3) scrollY = target;
  scrollY += (target - scrollY) * 0.12;

  // 밝은 배경 그림에서도 가사가 읽히도록 뒤에 은은한 그늘을 깐다
  const shade = S.bg === 'scene' && sceneImgs.some(Boolean) ? 0.55 : 0.3;
  ctx.save();
  ctx.translate(L.cx, L.cy);
  ctx.scale(1, L.h / (L.w + 160));
  const sg = ctx.createRadialGradient(0, 0, 0, 0, 0, (L.w + 160) / 2);
  sg.addColorStop(0, `rgba(0,0,0,${shade})`);
  sg.addColorStop(0.6, `rgba(0,0,0,${shade * 0.6})`);
  sg.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = sg;
  ctx.fillRect(-(L.w + 160) / 2, -(L.w + 160) / 2, L.w + 160, L.w + 160);
  ctx.restore();

  ctx.save();
  ctx.beginPath(); ctx.rect(L.cx - L.w / 2 - 60, L.cy - L.h / 2, L.w + 120, L.h); ctx.clip();
  ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  const half = L.h / 2;
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    const top = L.cy + (it.y - scrollY);
    if (top + it.h < L.cy - half - 10 || top > L.cy + half + 10) continue;
    const dist = Math.abs(it.c - scrollY) / half;
    const isCur = i === cur;
    const alpha = isCur ? 1 : Math.max(0, (i < cur ? 0.38 : 0.55) * (1 - Math.pow(dist, 1.6)));
    if (alpha <= 0.01) continue;
    ctx.font = `800 ${lay.size}px ${FONT}`;
    const p = isCur ? lineProgress(i, now) : (i < cur ? 1 : 0);
    const total = it.widths.reduce((a, b) => a + b, 0) || 1;
    let filled = p * total;
    it.rows.forEach((row, r) => {
      const rw = it.widths[r];
      const x = L.cx - rw / 2;
      const y = top + lay.rowH * (r + 0.5);
      ctx.shadowColor = 'rgba(0,0,0,.55)'; ctx.shadowBlur = 12;
      ctx.fillStyle = `rgba(255,255,255,${(isCur ? 0.42 : 1) * alpha})`;
      ctx.fillText(row, x, y);
      if (isCur) {
        const fw = Math.max(0, Math.min(rw, filled));
        filled -= rw;
        if (fw > 0) {
          ctx.save();
          ctx.beginPath(); ctx.rect(x - 4, y - lay.rowH / 2, fw + 4, lay.rowH); ctx.clip();
          ctx.shadowColor = hsl(accent, 0.55 + bass * 0.4, 10); ctx.shadowBlur = 8 + bass * 10;
          ctx.fillStyle = '#fff';
          ctx.fillText(row, x, y);
          ctx.shadowBlur = 0;
          ctx.fillStyle = hsl(accent, 0.3, 22);
          ctx.fillText(row, x, y);
          ctx.restore();
        }
      }
    });
    if (it.subRows.length) {
      ctx.font = `500 ${lay.subSize}px ${FONT}`;
      ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 10;
      ctx.fillStyle = isCur ? `rgba(255,255,255,0.88)` : `rgba(255,255,255,${0.85 * alpha})`;
      const base = top + it.rows.length * lay.rowH + lay.size * 0.15;
      it.subRows.forEach((row, r) => ctx.fillText(row, L.cx - it.subWidths[r] / 2, base + lay.subH * (r + 0.5)));
    }
  }
  ctx.restore();
}

function drawBars(B) {
  const n = BANDS * 2, w = (B.x1 - B.x0) / n;
  ctx.save();
  for (let i = 0; i < n; i++) {
    const bi = i < BANDS ? BANDS - 1 - i : i - BANDS;   // 가운데가 저음, 양쪽으로 고음
    const v = bandVals[bi];
    const h = 3 + v * B.h;
    const x = B.x0 + i * w + w * 0.18;
    const g = ctx.createLinearGradient(0, B.y - h, 0, B.y);
    g.addColorStop(0, hsl(accent, 0.95, 18));
    g.addColorStop(1, hsl(accent, 0.25, -10));
    ctx.fillStyle = g;
    roundRect(ctx, x, B.y - h, w * 0.64, h, Math.min(w * 0.3, h / 2));
    ctx.fill();
  }
  ctx.restore();
}

function drawWave(B) {
  const mid = B.y - B.h / 2, amp = B.h * 0.55;
  ctx.save();
  ctx.lineJoin = 'round';
  ctx.shadowColor = hsl(accent, 0.9); ctx.shadowBlur = 18;
  for (const [lw, a] of [[6, 0.25], [3, 0.95]]) {
    ctx.beginPath();
    const N = 200;
    for (let i = 0; i <= N; i++) {
      const x = B.x0 + (B.x1 - B.x0) * (i / N);
      let v = 0;
      if (wave && analyser) v = (wave[Math.floor((i / N) * (wave.length - 1))] - 128) / 128;
      const edge = Math.sin((i / N) * Math.PI);            // 양 끝은 잦아들게
      const y = mid + v * amp * 1.6 * edge;
      i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
    }
    ctx.lineWidth = lw; ctx.strokeStyle = hsl(accent, a, 12);
    ctx.stroke();
  }
  ctx.restore();
}

const fmtTime = (t) => {
  if (!isFinite(t) || t < 0) t = 0;
  const m = Math.floor(t / 60), s = Math.floor(t % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
};

function drawProgress(P, now) {
  const dur = isFinite(audio.duration) ? audio.duration : 0;
  const p = dur ? Math.min(1, now / dur) : 0;
  ctx.save();
  ctx.fillStyle = 'rgba(255,255,255,.18)';
  roundRect(ctx, P.x0, P.y - 3, P.x1 - P.x0, 6, 3); ctx.fill();
  ctx.fillStyle = hsl(accent, 1, 8);
  if (p > 0) { roundRect(ctx, P.x0, P.y - 3, (P.x1 - P.x0) * p, 6, 3); ctx.fill(); }
  ctx.beginPath(); ctx.arc(P.x0 + (P.x1 - P.x0) * p, P.y, 8, 0, Math.PI * 2); ctx.fillStyle = '#fff'; ctx.fill();
  ctx.font = `500 22px ${FONT}`;
  ctx.fillStyle = 'rgba(255,255,255,.7)';
  ctx.textBaseline = 'bottom';
  ctx.textAlign = 'left'; ctx.fillText(fmtTime(now), P.x0, P.y - 12);
  ctx.textAlign = 'right'; ctx.fillText(fmtTime(dur), P.x1, P.y - 12);
  ctx.restore();
}

/* ---------- 편집 창 ---------- */
const seek = $('#seek');
let seeking = false;

function updateUI(now) {
  const dur = isFinite(audio.duration) ? audio.duration : 0;
  $('#timeLbl').textContent = `${fmtTime(now)} / ${fmtTime(dur)}`;
  if (!seeking && dur) seek.value = Math.round((now / dur) * 1000);
  const cur = currentIndex(now);
  if (cur !== lastCur) {
    lastCur = cur;
    document.querySelectorAll('#lineList li.cur').forEach((el) => el.classList.remove('cur'));
    const li = document.querySelector(`#lineList li[data-i="${cur}"]`);
    if (li) { li.classList.add('cur'); if (!syncing) li.scrollIntoView({ block: 'nearest' }); }
  }
}

function fmtStamp(t) {
  if (t == null) return '--:--.-';
  const m = Math.floor(t / 60), s = t - m * 60;
  return `${m}:${s.toFixed(1).padStart(4, '0')}`;
}

function renderList() {
  lyricsVersion++; lyricCache = null; lastCur = -2;
  const ol = $('#lineList');
  ol.innerHTML = '';
  S.lines.forEach((l, i) => {
    const li = document.createElement('li');
    li.dataset.i = i;
    if (syncing && i === syncIdx) li.classList.add('next');
    li.innerHTML = '<span class="t"></span><span class="txt"></span><button type="button" data-d="-0.1" title="0.1초 앞으로">−</button><button type="button" data-d="0.1" title="0.1초 뒤로">+</button>';
    const t = li.querySelector('.t');
    t.textContent = fmtStamp(l.t);
    if (l.t == null) t.classList.add('none');
    li.querySelector('.txt').textContent = (l.text || '♪ (간주)') + (l.sub ? ` · ${l.sub}` : '');
    ol.appendChild(li);
  });
  if (syncing) { const n = ol.querySelector('li.next'); if (n) n.scrollIntoView({ block: 'center' }); }
}

$('#lineList').addEventListener('click', (e) => {
  const li = e.target.closest('li');
  if (!li) return;
  const i = +li.dataset.i;
  const d = e.target.dataset.d;
  if (d) {
    const l = S.lines[i];
    const nt = Math.max(0, (l.t ?? audio.currentTime - S.offset) + parseFloat(d));
    if (l.e != null) l.e += nt - (l.t ?? nt);
    l.t = nt;
    renderList(); save();
    return;
  }
  if (syncing) {
    syncIdx = i;
    const prev = i > 0 ? lineStart(i - 1) : 0;
    audio.currentTime = Math.max(0, (prev ?? 0) - 0.5);
    renderList();
  } else if (S.lines[i].t != null) {
    audio.currentTime = Math.max(0, lineStart(i) - 0.3);
  }
});

function stamp() {
  if (syncIdx >= S.lines.length) return;
  S.lines[syncIdx].t = Math.max(0, audio.currentTime - S.offset);
  delete S.lines[syncIdx].e;
  syncIdx++;
  if (syncIdx >= S.lines.length) stopSync();
  renderList(); save();
}

function startSync() {
  if (!audio.src) { alert('음악 파일을 먼저 넣어주세요.'); return; }
  if (!S.lines.length) { alert('가사를 먼저 적용해주세요.'); return; }
  syncing = true; syncIdx = 0;
  document.body.classList.add('syncing');
  $('#syncBtn').textContent = '싱크 끝내기';
  audio.currentTime = 0;
  play();
  renderList();
  document.activeElement && document.activeElement.blur();
}
function stopSync() {
  syncing = false;
  document.body.classList.remove('syncing');
  $('#syncBtn').textContent = '싱크 시작';
  renderList();
}

function play() {
  if (!audio.src) return;
  ensureAudio();
  audio.play().catch(() => {});
}
function togglePlay() { audio.paused ? play() : audio.pause(); }

audio.addEventListener('play', () => { $('#playBtn').textContent = '❚❚ 정지'; });
audio.addEventListener('pause', () => { $('#playBtn').textContent = '▶ 재생'; });
audio.addEventListener('loadedmetadata', () => { lyricCache = null; });

/* ---------- 파일 넣기 ---------- */
async function setAudio(blob, name) {
  audioBlob = blob; audioName = name || '';
  if (audioURL) URL.revokeObjectURL(audioURL);
  audioURL = URL.createObjectURL(blob);
  audio.src = audioURL;
  if (!S.title && name) { S.title = name.replace(/\.[^.]+$/, ''); $('#title').value = S.title; }
}

function applyLyricsText(text, fromFile) {
  const { lines, meta } = parseLyrics(text);
  if (!lines.length) { alert('가사에서 줄을 찾지 못했어요.'); return; }
  const hadTimes = S.lines.some((l) => l.t != null) && !lines.some((l) => l.t != null);
  if (hadTimes) {
    // 줄 수가 같으면 기존 싱크를 살린다 (오타만 고친 경우)
    if (lines.length === S.lines.length) lines.forEach((l, i) => { l.t = S.lines[i].t; });
    else if (!confirm('줄 수가 바뀌어서 맞춰둔 싱크가 지워집니다. 계속할까요?')) return;
  }
  S.lines = lines;
  if (meta.ti && !S.title) { S.title = meta.ti; $('#title').value = S.title; }
  if (meta.ar && !S.artist) { S.artist = meta.ar; $('#artist').value = S.artist; }
  if (fromFile) $('#lyricsIn').value = lines.map(lineSource).join('\n');
  scrollY = null;
  renderList(); save();
}

async function handleFile(f) {
  if (!f) return;
  const name = f.name.toLowerCase();
  if (f.type.startsWith('audio/') || /\.(mp3|wav|m4a|ogg|flac|aac)$/.test(name)) {
    if (audioBlob) {
      S.lines = []; S.title = ''; clearScenes();
      $('#lyricsIn').value = ''; $('#title').value = '';
      renderList();
      if (window.renderScenes) renderScenes();
    }
    await setAudio(f, f.name);
    save(true);
    window.dispatchEvent(new Event('audio-added'));
    return;
  }
  else if (f.type.startsWith('image/')) await loadCover(f);
  else if (/\.(lrc|txt)$/.test(name) || f.type.startsWith('text/')) applyLyricsText(await f.text(), true);
  else return;
  save(true);
}

$('#audioFile').addEventListener('change', (e) => handleFile(e.target.files[0]));
$('#coverFile').addEventListener('change', (e) => handleFile(e.target.files[0]));
$('#lrcFile').addEventListener('change', (e) => { handleFile(e.target.files[0]); e.target.value = ''; });
$('#applyLyrics').addEventListener('click', () => applyLyricsText($('#lyricsIn').value, false));
$('#exportLrc').addEventListener('click', () => {
  if (!S.lines.length) return;
  download(new Blob([toLRC()], { type: 'text/plain' }), `${S.title || 'lyrics'}.lrc`);
});

const dropHint = $('#dropHint');
let dragDepth = 0;
window.addEventListener('dragenter', (e) => { e.preventDefault(); dragDepth++; dropHint.hidden = false; });
window.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; dropHint.hidden = true; } });
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', async (e) => {
  e.preventDefault(); dragDepth = 0; dropHint.hidden = true;
  for (const f of e.dataTransfer.files) await handleFile(f);
});

/* ---------- 컨트롤 ---------- */
$('#playBtn').addEventListener('click', togglePlay);
seek.addEventListener('input', () => {
  seeking = true;
  if (isFinite(audio.duration)) audio.currentTime = (seek.value / 1000) * audio.duration;
});
seek.addEventListener('change', () => { seeking = false; });
$('#syncBtn').addEventListener('click', () => (syncing ? stopSync() : startSync()));
$('#autoBtn').addEventListener('click', autoDistribute);
$('#offset').addEventListener('input', (e) => {
  S.offset = parseFloat(e.target.value);
  $('#offsetLbl').textContent = `${S.offset >= 0 ? '+' : ''}${S.offset.toFixed(2)}초 ${S.offset > 0 ? '(가사 늦게)' : S.offset < 0 ? '(가사 빨리)' : ''}`;
  save();
});
$('#title').addEventListener('input', (e) => { S.title = e.target.value; save(); });
$('#artist').addEventListener('input', (e) => { S.artist = e.target.value; save(); });
document.querySelectorAll('input[name=aspect]').forEach((r) => r.addEventListener('change', (e) => { setAspect(e.target.value); save(); }));
$('#viz').addEventListener('change', (e) => { S.viz = e.target.value; save(); });
$('#fontScale').addEventListener('input', (e) => { S.fontScale = parseFloat(e.target.value); save(); });
$('#showTitle').addEventListener('change', (e) => { S.showTitle = e.target.checked; save(); });
$('#bgMode').addEventListener('change', (e) => { S.bg = e.target.value; save(); });

function toggleBroadcast(force) {
  const on = document.body.classList.toggle('broadcast', force);
  $('#broadcastBtn').classList.toggle('on', on);
  poke();
}
function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen().catch(() => {});
}
$('#broadcastBtn').addEventListener('click', () => toggleBroadcast());
$('#fsBtn').addEventListener('click', toggleFullscreen);
canvas.addEventListener('click', () => { if (document.body.classList.contains('broadcast')) togglePlay(); });

let idleTimer = 0;
function poke() {
  document.body.classList.remove('idle');
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => document.body.classList.add('idle'), 2000);
}
window.addEventListener('mousemove', poke);

window.addEventListener('keydown', (e) => {
  const tag = (e.target.tagName || '').toLowerCase();
  if (tag === 'textarea' || (tag === 'input' && !/^(range|radio|checkbox|file)$/.test(e.target.type)) || tag === 'select') return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;
  const k = e.key;
  if (syncing && (k === ' ' || k === 'Enter')) { e.preventDefault(); stamp(); return; }
  if (syncing && k === 'Backspace') {
    e.preventDefault();
    syncIdx = Math.max(0, syncIdx - 1);
    audio.currentTime = Math.max(0, audio.currentTime - 3);
    renderList(); return;
  }
  if (syncing && k === 'Escape') { stopSync(); return; }
  if (k === ' ') { e.preventDefault(); togglePlay(); }
  else if (k === 'h' || k === 'H' || k === 'ㅗ') toggleBroadcast();
  else if (k === 'f' || k === 'F' || k === 'ㄹ') toggleFullscreen();
  else if (k === 'ArrowLeft') audio.currentTime = Math.max(0, audio.currentTime - 5);
  else if (k === 'ArrowRight') audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + 5);
});

/* ---------- 영상 저장 ---------- */
function pickMime() {
  const list = [
    'video/mp4;codecs=avc1.640028,mp4a.40.2',
    'video/mp4;codecs=avc1,opus',
    'video/mp4',
    'video/webm;codecs=vp9,opus',
    'video/webm;codecs=vp8,opus',
    'video/webm',
  ];
  return list.find((m) => window.MediaRecorder && MediaRecorder.isTypeSupported(m));
}

function download(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name.replace(/[\\/:*?"<>|]/g, '_');
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
}

async function startRecording() {
  if (!audio.src) { alert('음악 파일을 먼저 넣어주세요.'); return; }
  const mime = pickMime();
  if (!mime || !canvas.captureStream) { alert('이 브라우저는 영상 저장을 지원하지 않아요. Chrome이나 Edge를 써주세요.'); return; }
  if (syncing) stopSync();
  ensureAudio();
  const stream = canvas.captureStream(30);
  recDest.stream.getAudioTracks().forEach((t) => stream.addTrack(t));
  const chunks = [];
  recorder = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 8_000_000, audioBitsPerSecond: 192_000 });
  recorder.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  recorder.onstop = () => {
    const ext = mime.startsWith('video/mp4') ? 'mp4' : 'webm';
    if (chunks.length && !recorder.cancelled) download(new Blob(chunks, { type: mime.split(';')[0] }), `${S.title || 'song'}.${ext}`);
    recorder = null;
    $('#recBtn').textContent = '영상 파일로 저장';
    $('#recBtn').classList.remove('on');
    $('#recStatus').textContent = '';
  };
  audio.pause();
  audio.currentTime = 0;
  await new Promise((r) => setTimeout(r, 250));     // 첫 화면이 그려질 시간
  recorder.start(1000);
  await audio.play().catch(() => {});
  $('#recBtn').textContent = '■ 녹화 취소';
  $('#recBtn').classList.add('on');
}
function stopRecording(cancel) {
  if (!recorder) return;
  recorder.cancelled = !!cancel;
  if (recorder.state !== 'inactive') recorder.stop();
  audio.pause();
}
$('#recBtn').addEventListener('click', () => (recorder ? stopRecording(true) : startRecording()));
audio.addEventListener('ended', () => { if (recorder) stopRecording(false); });
audio.addEventListener('timeupdate', () => {
  if (recorder && isFinite(audio.duration)) $('#recStatus').textContent = `녹화 중 ${Math.round((audio.currentTime / audio.duration) * 100)}%`;
});

/* ---------- 저장 (IndexedDB, 이 브라우저 안에만) ---------- */
const DB = {
  db: null,
  open() {
    if (this.db) return Promise.resolve(this.db);
    return new Promise((res, rej) => {
      const r = indexedDB.open('lyric-player', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('kv');
      r.onsuccess = () => { this.db = r.result; res(this.db); };
      r.onerror = () => rej(r.error);
    });
  },
  async put(key, val) {
    const db = await this.open();
    return new Promise((res, rej) => { const tx = db.transaction('kv', 'readwrite'); tx.objectStore('kv').put(val, key); tx.oncomplete = res; tx.onerror = () => rej(tx.error); });
  },
  async get(key) {
    const db = await this.open();
    return new Promise((res, rej) => { const r = db.transaction('kv').objectStore('kv').get(key); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  },
  async clear() {
    const db = await this.open();
    return new Promise((res) => { const tx = db.transaction('kv', 'readwrite'); tx.objectStore('kv').clear(); tx.oncomplete = res; });
  },
};

let saveTimer = 0, savedAudio = null, savedCover = null;
function save(now) {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      await DB.put('meta', { ...S, audioName });
      if (audioBlob !== savedAudio) { await DB.put('audio', audioBlob); savedAudio = audioBlob; }
      if (coverBlob !== savedCover) { await DB.put('cover', coverBlob); savedCover = coverBlob; }
      if (scenesVersion !== savedScenes) { const v = scenesVersion; await DB.put('scenes', sceneBlobs.slice()); savedScenes = v; }
    } catch (err) { console.warn('저장 실패', err); }
  }, now ? 0 : 500);
}

async function restore() {
  try {
    const meta = await DB.get('meta');
    if (!meta) return;
    Object.assign(S, meta);
    delete S.audioName;
    if (S.bg === 'ai') S.bg = 'scene';
    S.scenes = (S.scenes || []).map((sc) => ({ t: sc.t || 0, theme: THEMES[sc.theme] ? sc.theme : 'light' }));
    const a = await DB.get('audio');
    if (a) { await setAudio(a, meta.audioName); savedAudio = a; }
    const c = await DB.get('cover');
    if (c) { await loadCover(c); savedCover = c; }
    const sc = await DB.get('scenes');
    if (sc && sc.length) {
      sceneBlobs = sc;
      sceneImgs = await Promise.all(sc.map(blobToImage));
    }
    savedScenes = scenesVersion;
  } catch (err) { console.warn('불러오기 실패', err); }
  $('#title').value = S.title;
  $('#artist').value = S.artist;
  $('#lyricsIn').value = S.lines.map(lineSource).join('\n');
  $('#offset').value = S.offset;
  $('#offset').dispatchEvent(new Event('input'));
  document.querySelector(`input[name=aspect][value="${S.aspect}"]`).checked = true;
  $('#viz').value = S.viz;
  $('#fontScale').value = S.fontScale;
  $('#showTitle').checked = S.showTitle;
  $('#bgMode').value = S.bg;
  setAspect(S.aspect);
  renderList();
  if (window.renderScenes) renderScenes();
}

$('#resetBtn').addEventListener('click', async () => {
  if (!confirm('음악·표지·가사·싱크를 모두 지우고 새로 시작할까요?')) return;
  await DB.clear();
  location.reload();
});

/* ---------- 시작 ---------- */
(async function init() {
  setAspect('16:9');
  await restore();
  // 캔버스용 글꼴이 늦게 오면 가사 폭 계산을 다시 한다
  if (document.fonts) {
    Promise.all(['400', '500', '800', '900'].map((w) => document.fonts.load(`${w} 40px "Noto Sans KR"`, '가나다ABC'))).then(() => { lyricCache = null; }).catch(() => {});
  }
  requestAnimationFrame(frame);
})();
