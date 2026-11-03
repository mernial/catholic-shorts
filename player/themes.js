/* 가사 분위기 배경 — 가사에 나오는 낱말로 장면을 고르고, 캔버스에 직접 그린다 (인터넷·AI 필요 없음). */
'use strict';

const THEMES = {
  light:   { name: '빛',          top: '#4a3416', bottom: '#120c05', keys: ['빛', '햇살', '햇빛', '아침', '태양', '해가', '영광', '광채', '새벽', '밝', '환한', '비추', '빛나', 'light', 'shine', 'sun', 'glory', 'morning'] },
  stars:   { name: '별밤',        top: '#0b1430', bottom: '#020308', keys: ['별빛', '별이', '별들', '별을', '별처럼', '밤', '어둠', '달빛', '달님', '잠들', '고요', '꿈', '은하', 'star', 'night', 'moon', 'dark', 'dream'] },
  water:   { name: '물결',        top: '#0d3550', bottom: '#03101a', keys: ['바다', '물결', '물가', '물이', '물을', '강물', '강가', '파도', '샘물', '세례', '호수', '흐르', '생명수', 'sea', 'water', 'river', 'wave', 'ocean'] },
  rain:    { name: '비',          top: '#2a3340', bottom: '#0a0d12', keys: ['비가', '빗', '눈물', '울어', '울며', '울고', '슬픔', '아픔', '외로', '상처', '흐느', 'rain', 'tear', 'cry', 'sorrow'] },
  snow:    { name: '눈',          top: '#33435a', bottom: '#0d121a', keys: ['겨울', '눈송이', '하얀', '하얗', '눈이 내', '성탄', '크리스마스', '구유', '마구간', '탄생', '태어나', 'snow', 'winter', 'christmas', 'white'] },
  petals:  { name: '꽃잎',        top: '#4a2036', bottom: '#14070e', keys: ['꽃', '봄', '향기', '장미', '피어', '정원', '설레', '들판', '꽃잎', 'flower', 'spring', 'rose', 'bloom'] },
  embers:  { name: '불꽃',        top: '#4a1a08', bottom: '#120502', keys: ['불꽃', '불이', '불을', '불길', '촛불', '등불', '성령', '뜨거', '타오르', '열정', 'fire', 'flame', 'candle', 'spirit', 'burn'] },
  sky:     { name: '하늘',        top: '#1d4d7a', bottom: '#0a1a2a', keys: ['하늘', '구름', '바람', '날개', '새처럼', '날아', '자유', '높이', '천국', 'sky', 'cloud', 'wind', 'wing', 'fly', 'heaven'] },
  cross:   { name: '십자가 빛',   top: '#2a1630', bottom: '#0a050c', keys: ['십자가', '희생', '고난', '가시', '골고타', '부활', '구원', '속죄', '못 박', 'cross', 'sacrifice', 'resurrection', 'salvation'] },
};
const THEME_ORDER = ['light', 'sky', 'stars', 'petals', 'water', 'cross', 'embers', 'snow', 'rain'];

/* 글 한 덩어리에 가장 어울리는 장면. 아무 낱말도 없으면 null */
function pickTheme(text) {
  const t = ` ${String(text).toLowerCase()} `;
  let best = null, bestScore = 0;
  for (const k of THEME_ORDER) {
    let score = 0;
    for (const key of THEMES[k].keys) {
      let from = 0, idx;
      while ((idx = t.indexOf(key, from)) >= 0) { score += key.length >= 2 ? 1 : 0.6; from = idx + key.length; }
    }
    if (score > bestScore) { bestScore = score; best = k; }
  }
  return best;
}

/* 가사 줄을 n개 장면으로 나누고 장면마다 분위기를 고른다 */
function planThemeScenes(lines, n, mergeSame, dur) {
  const timed = lines.filter((l) => l.t != null);
  if (!timed.length) {
    // 가사가 없는 연주곡: 곡 길이를 고르게 나눠 차분한 장면을 차례로
    const calm = ['light', 'sky', 'petals', 'water', 'stars', 'cross'];
    const total = isFinite(dur) && dur > 0 ? dur : 180;
    n = Math.max(1, n);
    return Array.from({ length: n }, (_, k) => ({ t: +(total * k / n).toFixed(2), theme: calm[k % calm.length] }));
  }
  n = Math.max(1, Math.min(n, timed.length));
  // 가사 사이가 길게 빈 곳(간주)을 우선 경계로 쓰고, 나머지는 줄 수로 고르게 나눈다
  const cuts = new Set();
  const gaps = timed.slice(1).map((l, i) => ({ i: i + 1, gap: l.t - (timed[i].e ?? timed[i].t) })).sort((a, b) => b.gap - a.gap);
  for (const g of gaps) { if (cuts.size >= n - 1 || g.gap < 6) break; cuts.add(g.i); }
  for (let k = 1; cuts.size < n - 1 && k < n; k++) cuts.add(Math.round((timed.length * k) / n));
  const starts = [0, ...[...cuts].filter((c) => c > 0 && c < timed.length).sort((a, b) => a - b)];
  const uniq = [...new Set(starts)];
  const scenes = [];
  let prev = null, cycle = 0;
  uniq.forEach((s, k) => {
    const end = k + 1 < uniq.length ? uniq[k + 1] : timed.length;
    const text = timed.slice(s, end).map((l) => `${l.text} ${l.sub || ''}`).join(' ');
    let theme = pickTheme(text);
    if (!theme) {
      // 낱말로 못 고르면 앞 장면과 다른 차분한 장면을 돌아가며 쓴다
      const calm = ['light', 'sky', 'stars', 'cross'];
      theme = calm[cycle++ % calm.length];
      if (theme === prev) theme = calm[cycle++ % calm.length];
    }
    // 그림 없이 같은 분위기가 이어지면 굳이 바꾸지 않는다
    if (!(mergeSame && theme === prev)) scenes.push({ t: k === 0 ? 0 : timed[s].t, theme });
    prev = theme;
  });
  return scenes;
}

/* ---------- 그리기 ---------- */
function seeded(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}
const POOL = {};
function pool(key, n, make) {
  if (!POOL[key]) { const r = seeded(key.length * 7919 + n); POOL[key] = Array.from({ length: n }, () => make(r)); }
  return POOL[key];
}

function drawTheme(c, key, W, H, a, time, bass, level) {
  const th = THEMES[key] || THEMES.light;
  c.save();
  c.globalAlpha = a;
  const g = c.createLinearGradient(0, 0, 0, H);
  g.addColorStop(0, th.top); g.addColorStop(1, th.bottom);
  c.fillStyle = g; c.fillRect(0, 0, W, H);
  const S = Math.max(W, H);
  const fx = FX[key] || FX.light;
  fx(c, W, H, S, a, time, bass, level);
  c.restore();
}

const FX = {
  light(c, W, H, S, a, t, bass) {
    c.globalCompositeOperation = 'lighter';
    const ox = W * 0.72, oy = -H * 0.12;
    for (let i = 0; i < 9; i++) {
      const ang = Math.PI * 0.32 + i * 0.075 + Math.sin(t * 0.13 + i) * 0.025;
      const len = S * 1.5, wid = 0.022 + (i % 3) * 0.012;
      c.globalAlpha = a * (0.06 + 0.04 * Math.sin(t * 0.4 + i * 1.7) + bass * 0.08);
      c.fillStyle = '#ffd98a';
      c.beginPath();
      c.moveTo(ox, oy);
      c.lineTo(ox + Math.cos(ang - wid) * len, oy + Math.sin(ang - wid) * len);
      c.lineTo(ox + Math.cos(ang + wid) * len, oy + Math.sin(ang + wid) * len);
      c.closePath(); c.fill();
    }
    const glow = c.createRadialGradient(ox, 0, 0, ox, 0, S * 0.6);
    glow.addColorStop(0, `rgba(255,220,150,${0.35 + bass * 0.2})`); glow.addColorStop(1, 'rgba(255,220,150,0)');
    c.globalAlpha = a; c.fillStyle = glow; c.fillRect(0, 0, W, H);
    motes(c, W, H, a, t, '#ffe7b0', 'lightmotes');
  },

  stars(c, W, H, S, a, t, bass) {
    const stars = pool('stars', 260, (r) => ({ x: r(), y: r() * 0.85, s: 0.6 + r() * 2.2, p: r() * 6.28, f: 0.5 + r() * 2 }));
    c.fillStyle = '#fff';
    for (const s of stars) {
      c.globalAlpha = a * (0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * s.f + s.p))) * (0.6 + bass * 0.6);
      c.beginPath(); c.arc(s.x * W, s.y * H, s.s, 0, 6.283); c.fill();
    }
    // 달은 왼쪽 위 구석에: 오른쪽은 가사, 가운데는 표지 자리
    const mx = W * 0.065, my = H * 0.1, mr = S * 0.04;
    const mg = c.createRadialGradient(mx, my, mr, mx, my, mr * 7);
    mg.addColorStop(0, 'rgba(200,215,255,0.35)'); mg.addColorStop(1, 'rgba(200,215,255,0)');
    c.globalAlpha = a; c.fillStyle = mg; c.fillRect(0, 0, W, H);
    c.fillStyle = '#eef2ff'; c.beginPath(); c.arc(mx, my, mr, 0, 6.283); c.fill();
    // 가끔 별똥별
    const cyc = (t % 9) / 9;
    if (cyc < 0.12) {
      const k = cyc / 0.12, sx = W * (0.15 + 0.3 * ((Math.floor(t / 9) * 0.37) % 1)), sy = H * 0.1;
      const x = sx + k * W * 0.25, y = sy + k * H * 0.18;
      const lg = c.createLinearGradient(x, y, x - W * 0.08, y - H * 0.06);
      lg.addColorStop(0, `rgba(255,255,255,${0.9 * (1 - k)})`); lg.addColorStop(1, 'rgba(255,255,255,0)');
      c.strokeStyle = lg; c.lineWidth = 2.5;
      c.beginPath(); c.moveTo(x, y); c.lineTo(x - W * 0.08, y - H * 0.06); c.stroke();
    }
  },

  water(c, W, H, S, a, t, bass) {
    const sh = c.createRadialGradient(W * 0.5, H * 0.25, 0, W * 0.5, H * 0.25, S * 0.6);
    sh.addColorStop(0, 'rgba(120,200,255,0.18)'); sh.addColorStop(1, 'rgba(120,200,255,0)');
    c.fillStyle = sh; c.fillRect(0, 0, W, H);
    const layers = [['#1b6a8f', 0.55, 0.5], ['#145778', 0.65, 0.65], ['#0d4260', 0.75, 0.8], ['#082c42', 0.86, 1]];
    layers.forEach(([col, base, sp], i) => {
      c.globalAlpha = a * 0.85;
      c.fillStyle = col;
      c.beginPath(); c.moveTo(0, H);
      for (let x = 0; x <= W; x += 20) {
        const y = H * base + Math.sin(x / W * 6.283 * (1.3 + i * 0.4) + t * sp) * H * (0.02 + bass * 0.015) + Math.sin(x / W * 17 + t * sp * 1.7) * H * 0.006;
        c.lineTo(x, y);
      }
      c.lineTo(W, H); c.closePath(); c.fill();
    });
    c.globalCompositeOperation = 'lighter';
    const sp = pool('glints', 70, (r) => ({ x: r(), y: 0.55 + r() * 0.4, p: r() * 6.28 }));
    c.fillStyle = '#bfe8ff';
    for (const s of sp) {
      c.globalAlpha = a * Math.max(0, Math.sin(t * 1.5 + s.p)) * 0.5;
      c.fillRect(((s.x + t * 0.004) % 1) * W, s.y * H, 14, 2);
    }
  },

  rain(c, W, H, S, a, t) {
    const drops = pool('rain', 240, (r) => ({ x: r(), y: r(), l: 0.02 + r() * 0.04, v: 0.6 + r() * 0.6, o: 0.15 + r() * 0.35 }));
    c.strokeStyle = '#b8c8dc'; c.lineWidth = 1.5;
    for (const d of drops) {
      const y = ((d.y + t * d.v) % 1.1) - 0.05, x = (d.x + y * 0.08) % 1;
      c.globalAlpha = a * d.o;
      c.beginPath(); c.moveTo(x * W, y * H); c.lineTo((x - d.l * 0.15) * W, (y - d.l) * H); c.stroke();
    }
    const fog = c.createLinearGradient(0, H * 0.6, 0, H);
    fog.addColorStop(0, 'rgba(150,170,190,0)'); fog.addColorStop(1, 'rgba(150,170,190,0.18)');
    c.globalAlpha = a; c.fillStyle = fog; c.fillRect(0, 0, W, H);
  },

  snow(c, W, H, S, a, t) {
    const flakes = pool('snow', 180, (r) => ({ x: r(), y: r(), s: 1 + r() * 4, v: 0.02 + r() * 0.05, p: r() * 6.28 }));
    c.fillStyle = '#fff';
    for (const f of flakes) {
      const y = (f.y + t * f.v) % 1.05, x = f.x + Math.sin(t * 0.6 + f.p) * 0.015;
      c.globalAlpha = a * (0.4 + f.s / 8);
      c.beginPath(); c.arc(x * W, y * H, f.s, 0, 6.283); c.fill();
    }
    const gl = c.createRadialGradient(W * 0.5, H * 1.1, 0, W * 0.5, H * 1.1, S * 0.7);
    gl.addColorStop(0, 'rgba(255,230,190,0.18)'); gl.addColorStop(1, 'rgba(255,230,190,0)');
    c.globalAlpha = a; c.fillStyle = gl; c.fillRect(0, 0, W, H);
  },

  petals(c, W, H, S, a, t, bass) {
    const gl = c.createRadialGradient(W * 0.3, H * 0.2, 0, W * 0.3, H * 0.2, S * 0.7);
    gl.addColorStop(0, 'rgba(255,190,210,0.2)'); gl.addColorStop(1, 'rgba(255,190,210,0)');
    c.fillStyle = gl; c.fillRect(0, 0, W, H);
    const ps = pool('petals', 70, (r) => ({ x: r(), y: r(), s: 6 + r() * 10, v: 0.03 + r() * 0.04, p: r() * 6.28, rs: 0.5 + r() * 1.5, h: 330 + r() * 30 }));
    for (const p of ps) {
      const y = (p.y + t * p.v * (1 + bass)) % 1.08 - 0.04, x = (p.x + Math.sin(t * 0.5 + p.p) * 0.03 + t * 0.006) % 1;
      c.save();
      c.translate(x * W, y * H); c.rotate(t * p.rs + p.p); c.scale(1, 0.55 + 0.45 * Math.sin(t * p.rs * 1.3 + p.p));
      c.globalAlpha = a * 0.75;
      c.fillStyle = `hsl(${p.h},75%,82%)`;
      c.beginPath(); c.ellipse(0, 0, p.s, p.s * 0.6, 0, 0, 6.283); c.fill();
      c.restore();
    }
  },

  embers(c, W, H, S, a, t, bass) {
    const gl = c.createRadialGradient(W * 0.5, H * 1.05, 0, W * 0.5, H * 1.05, S * 0.75);
    gl.addColorStop(0, `rgba(255,120,40,${0.4 + bass * 0.3})`); gl.addColorStop(1, 'rgba(255,120,40,0)');
    c.fillStyle = gl; c.fillRect(0, 0, W, H);
    c.globalCompositeOperation = 'lighter';
    const es = pool('embers', 130, (r) => ({ x: r(), y: r(), s: 1 + r() * 3, v: 0.04 + r() * 0.08, p: r() * 6.28 }));
    for (const e of es) {
      const y = 1.05 - ((e.y + t * e.v * (1 + bass * 2)) % 1.1), x = e.x + Math.sin(t * 1.2 + e.p) * 0.02;
      c.globalAlpha = a * (0.4 + 0.6 * Math.abs(Math.sin(t * 3 + e.p))) * Math.min(1, y * 1.5);
      c.fillStyle = e.s > 2.5 ? '#ffd27a' : '#ff8a3a';
      c.beginPath(); c.arc(x * W, y * H, e.s, 0, 6.283); c.fill();
    }
  },

  sky(c, W, H, S, a, t) {
    const sun = c.createRadialGradient(W * 0.2, H * 0.15, 0, W * 0.2, H * 0.15, S * 0.5);
    sun.addColorStop(0, 'rgba(255,240,210,0.35)'); sun.addColorStop(1, 'rgba(255,240,210,0)');
    c.fillStyle = sun; c.fillRect(0, 0, W, H);
    const cl = pool('clouds', 9, (r) => ({ x: r(), y: 0.08 + r() * 0.7, s: 0.12 + r() * 0.16, v: 0.004 + r() * 0.008, o: 0.12 + r() * 0.16 }));
    for (const k of cl) {
      const x = ((k.x + t * k.v) % 1.4) - 0.2;
      for (let j = 0; j < 4; j++) {
        const cx = (x + j * k.s * 0.35) * W, cy = k.y * H + Math.sin(j * 1.7) * k.s * H * 0.12, r = k.s * S * (0.28 + (j % 2) * 0.1);
        const g = c.createRadialGradient(cx, cy, 0, cx, cy, r);
        g.addColorStop(0, `rgba(255,255,255,${k.o})`); g.addColorStop(1, 'rgba(255,255,255,0)');
        c.globalAlpha = a; c.fillStyle = g; c.fillRect(cx - r, cy - r, r * 2, r * 2);
      }
    }
    // 멀리 나는 새
    c.strokeStyle = 'rgba(20,30,45,0.5)'; c.lineWidth = 2;
    for (let i = 0; i < 3; i++) {
      const bx = ((t * 0.02 + i * 0.13) % 1.2 - 0.1) * W, by = H * (0.3 + i * 0.05) + Math.sin(t + i) * 8, w = 12 + Math.sin(t * 6 + i) * 4;
      c.globalAlpha = a * 0.6;
      c.beginPath(); c.moveTo(bx - 14, by - w * 0.4); c.quadraticCurveTo(bx - 6, by - 2, bx, by); c.quadraticCurveTo(bx + 6, by - 2, bx + 14, by - w * 0.4); c.stroke();
    }
  },

  cross(c, W, H, S, a, t, bass) {
    const cx = W * 0.5, cy = H * 0.42, u = Math.min(W, H);
    const halo = c.createRadialGradient(cx, cy, 0, cx, cy, S * 0.55);
    halo.addColorStop(0, `rgba(255,225,170,${0.3 + bass * 0.2 + 0.05 * Math.sin(t * 0.8)})`);
    halo.addColorStop(1, 'rgba(255,225,170,0)');
    c.fillStyle = halo; c.fillRect(0, 0, W, H);
    c.globalCompositeOperation = 'lighter';
    c.shadowColor = 'rgba(255,220,160,0.9)'; c.shadowBlur = 60;
    c.fillStyle = `rgba(255,236,200,${0.22 + 0.06 * Math.sin(t * 0.8)})`;
    const bw = u * 0.035, vh = u * 0.62, hw = u * 0.36;
    c.fillRect(cx - bw / 2, cy - vh * 0.38, bw, vh);
    c.fillRect(cx - hw / 2, cy - vh * 0.38 + vh * 0.22, hw, bw);
    c.shadowBlur = 0;
    motes(c, W, H, a, t, '#ffe7c0', 'crossmotes');
  },
};

function motes(c, W, H, a, t, color, key) {
  const ms = pool(key, 60, (r) => ({ x: r(), y: r(), s: 1 + r() * 2.5, p: r() * 6.28, v: 0.005 + r() * 0.01 }));
  c.fillStyle = color;
  for (const m of ms) {
    const x = (m.x + Math.sin(t * 0.2 + m.p) * 0.02) * W, y = ((m.y - t * m.v + 1) % 1) * H;
    c.globalAlpha = a * (0.25 + 0.35 * Math.sin(t + m.p) ** 2);
    c.beginPath(); c.arc(x, y, m.s, 0, 6.283); c.fill();
  }
}
