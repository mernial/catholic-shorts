/* 자동 만들기 — 무료, 이 컴퓨터 안에서만.
 *  1) 음성인식 AI(Whisper)를 브라우저 안에서 돌려 노래를 받아 적고 시각을 얻는다.
 *     모델은 처음 한 번 Hugging Face에서 내려받아 브라우저에 보관된다. 노래 파일은 밖으로 나가지 않는다.
 *  2) 붙여넣은 가사가 있으면 받아쓴 글자와 맞대어(정렬) 그 가사에 시각을 붙인다.
 *  3) 가사 낱말로 배경 장면(빛·별밤·물결…)을 고른다. 내 그림을 넣으면 그림이 장면이 된다. */
'use strict';

(() => {
  const OPT_LS = 'lp-auto-opts';
  const el = (id) => document.getElementById(id);
  try { localStorage.removeItem('lp-openai-key'); } catch (e) { /* 예전 버전의 키 지우기 */ }

  const MODELS = {
    small: 'onnx-community/whisper-small',
    base: 'onnx-community/whisper-base',
  };
  const DEFAULTS = { auto: true, model: 'small', lang: 'korean', scenes: 0 };
  const opts = { ...DEFAULTS };
  try {
    const saved = JSON.parse(localStorage.getItem(OPT_LS) || '{}');
    for (const k of Object.keys(DEFAULTS)) if (k in saved) opts[k] = saved[k];
  } catch (e) { /* 저장된 설정 없음 */ }
  if (!MODELS[opts.model]) opts.model = 'small';
  const saveOpts = () => { try { localStorage.setItem(OPT_LS, JSON.stringify(opts)); } catch (e) { /* 무시 */ } };

  const bind = (id, prop, kind) => {
    const input = el(id);
    if (kind === 'check') input.checked = !!opts[prop]; else input.value = String(opts[prop]);
    input.addEventListener('change', () => {
      opts[prop] = kind === 'check' ? input.checked : kind === 'num' ? parseInt(input.value, 10) : input.value;
      saveOpts();
    });
  };
  bind('autoOnAdd', 'auto', 'check');
  bind('asrModel', 'model');
  bind('lang', 'lang');
  bind('sceneCount', 'scenes', 'num');

  let busy = false;
  function status(msg, kind) {
    const s = el('autoStatus');
    s.textContent = msg;
    s.className = 'status' + (kind ? ' ' + kind : '');
  }
  function bar(p) {
    const b = el('autoBar');
    b.hidden = p == null;
    if (p != null) b.value = p;
  }

  /* ---------- 음성인식 일꾼 (화면이 멈추지 않게 따로 돌린다) ---------- */
  const WORKER_SRC = `
const VERSIONS = ['3.7.0', '3.6.3', '3.5.1'];
let T = null, asr = null, loadedKey = '';
async function lib() {
  if (T) return T;
  let last;
  for (const v of VERSIONS) {
    try { T = await import('https://cdn.jsdelivr.net/npm/@huggingface/transformers@' + v); return T; }
    catch (e) { last = e; }
  }
  throw new Error('LIB:' + (last && last.message));
}
self.onmessage = async (e) => {
  const m = e.data;
  try {
    const { pipeline, env } = await lib();
    env.allowLocalModels = false;
    let device = 'wasm';
    try { if (self.navigator.gpu && await self.navigator.gpu.requestAdapter()) device = 'webgpu'; } catch (_) {}
    const key = m.model + '|' + device;
    if (!asr || loadedKey !== key) {
      self.postMessage({ type: 'loading', device });
      const files = {};
      asr = await pipeline('automatic-speech-recognition', m.model, {
        device,
        dtype: device === 'webgpu' ? { encoder_model: 'fp32', decoder_model_merged: 'q4' } : 'q8',
        progress_callback: (p) => {
          if (p.status === 'progress' && p.total) {
            files[p.file] = [p.loaded, p.total];
            let a = 0, b = 0;
            for (const k in files) { a += files[k][0]; b += files[k][1]; }
            self.postMessage({ type: 'download', loaded: a, total: b });
          }
        },
      });
      loadedKey = key;
    }
    self.postMessage({ type: 'running', device });
    const out = await asr(m.audio, {
      language: m.lang || null,
      task: 'transcribe',
      chunk_length_s: 30,
      stride_length_s: 5,
      return_timestamps: true,
    });
    self.postMessage({ type: 'done', chunks: out.chunks || [], text: out.text || '' });
  } catch (err) {
    self.postMessage({ type: 'error', message: String((err && err.message) || err) });
  }
};`;

  let worker = null;
  function getWorker() {
    if (!worker) worker = new Worker(URL.createObjectURL(new Blob([WORKER_SRC], { type: 'text/javascript' })), { type: 'module' });
    return worker;
  }

  function recognize(audio16k, onMsg) {
    return new Promise((resolve, reject) => {
      const w = getWorker();
      w.onmessage = (e) => {
        const m = e.data;
        if (m.type === 'done') resolve(m.chunks);
        else if (m.type === 'error') reject(new Error(m.message));
        else onMsg(m);
      };
      w.onerror = (e) => reject(new Error(e.message || '음성인식 일꾼 오류'));
      w.postMessage({ model: MODELS[opts.model], lang: opts.lang === 'auto' ? null : opts.lang, audio: audio16k }, [audio16k.buffer]);
    });
  }

  async function decode16k(blob) {
    const ac = new (window.AudioContext || window.webkitAudioContext)();
    const dec = await ac.decodeAudioData(await blob.arrayBuffer());
    ac.close();
    const rate = 16000;
    const off = new OfflineAudioContext(1, Math.ceil(dec.duration * rate), rate);
    const src = off.createBufferSource();
    src.buffer = dec; src.connect(off.destination); src.start();
    return (await off.startRendering()).getChannelData(0).slice();
  }

  /* ---------- 받아쓴 결과 다듬기 ---------- */
  // 반주만 나오는 곳에서 음성인식이 지어내는 흔한 문장들
  const JUNK = /(시청해\s*주셔서|구독|좋아요|알림\s*설정|자막|MBC|KBS|SBS|뉴스|thank(s)? for watching|subscribe|♪|\[음악\]|\(음악\))/i;
  const NO_LETTERS = /^[^\p{L}\p{N}]*$/u;

  function cleanChunks(chunks, dur) {
    const out = [];
    for (const c of chunks) {
      const text = String(c.text || '').replace(/\s+/g, ' ').trim();
      let [s, e] = c.timestamp || [];
      if (!text || s == null || JUNK.test(text) || NO_LETTERS.test(text)) continue;
      if (e == null || e < s) e = Math.min(dur || s + 4, s + 4);
      // 같은 문장이 연달아 세 번 넘게 나오면 지어낸 것으로 본다
      const same = out.slice(-3).filter((o) => o.text === text).length;
      if (same >= 3) continue;
      out.push({ text, s, e });
    }
    return out;
  }

  // 긴 문장은 가사 한 줄 길이로 고르게 자른다 (시각은 글자 수로 나눈다)
  function chunksToLines(chunks) {
    const lines = [];
    for (const c of chunks) {
      const words = c.text.split(' ');
      const total = c.text.replace(/ /g, '').length || 1;
      const target = total / Math.ceil(total / 16);
      let cur = [], curLen = 0, done = 0;
      const flush = () => {
        if (!cur.length) return;
        const len = cur.join('').length;
        const s = c.s + ((c.e - c.s) * done) / total;
        done += len;
        const e = c.s + ((c.e - c.s) * done) / total;
        lines.push({ text: cur.join(' '), t: +s.toFixed(2), e: +e.toFixed(2) });
        cur = []; curLen = 0;
      };
      for (const w of words) {
        if (curLen && curLen + w.length > target * 1.15) flush();
        cur.push(w); curLen += w.length;
      }
      flush();
    }
    return lines;
  }

  /* 붙여넣은 가사 ↔ 받아쓴 글자 맞대기 (글자 단위 정렬). 한글은 초성·중성이 같으면 반쯤 맞은 것으로 친다. */
  function jamo(ch) {
    const c = ch.charCodeAt(0) - 0xac00;
    if (c < 0 || c > 11171) return null;
    return [Math.floor(c / 588), Math.floor((c % 588) / 28)];
  }
  function sim(a, b) {
    if (a === b) return 2;
    const x = jamo(a), y = jamo(b);
    if (x && y) {
      if (x[0] === y[0] && x[1] === y[1]) return 1;
      if (x[1] === y[1] || x[0] === y[0]) return 0;
    }
    return -1;
  }
  const keep = (ch) => /[\p{L}\p{N}]/u.test(ch);

  function alignLyrics(refLines, chunks) {
    // 받아쓴 쪽: 글자마다 대략의 시각
    const hyp = [];
    for (const c of chunks) {
      const chars = [...c.text].filter(keep);
      chars.forEach((ch, i) => hyp.push({ ch: ch.toLowerCase(), t: c.s + ((c.e - c.s) * i) / Math.max(1, chars.length) }));
    }
    // 가사 쪽: 글자마다 몇 번째 줄인지
    const ref = [];
    refLines.forEach((l, li) => { for (const ch of l.text) if (keep(ch)) ref.push({ ch: ch.toLowerCase(), li }); });
    const n = ref.length, m = hyp.length;
    if (!n || !m) return null;
    const GAP = -1;
    const W1 = m + 1;
    const score = new Int32Array((n + 1) * (m + 1));
    const move = new Uint8Array((n + 1) * (m + 1)); // 0 대각, 1 가사만, 2 받아쓰기만
    for (let i = 1; i <= n; i++) { score[i * W1] = i * GAP; move[i * W1] = 1; }
    for (let j = 1; j <= m; j++) { score[j] = 0; move[j] = 2; }   // 앞쪽 지어낸 글자는 벌점 없이 건너뛴다
    for (let i = 1; i <= n; i++) {
      for (let j = 1; j <= m; j++) {
        const d = score[(i - 1) * W1 + j - 1] + sim(ref[i - 1].ch, hyp[j - 1].ch);
        const u = score[(i - 1) * W1 + j] + GAP;
        const l = score[i * W1 + j - 1] + GAP;
        if (d >= u && d >= l) { score[i * W1 + j] = d; move[i * W1 + j] = 0; }
        else if (u >= l) { score[i * W1 + j] = u; move[i * W1 + j] = 1; }
        else { score[i * W1 + j] = l; move[i * W1 + j] = 2; }
      }
    }
    // 뒤쪽 지어낸 글자도 벌점 없이: 마지막 행에서 가장 좋은 칸부터 거슬러 간다
    let bj = m;
    for (let j = 0; j <= m; j++) if (score[n * W1 + j] > score[n * W1 + bj]) bj = j;
    const firstT = new Array(refLines.length).fill(null), lastT = new Array(refLines.length).fill(null);
    let i = n, j = bj;
    while (i > 0 && j > 0) {
      const mv = move[i * W1 + j];
      if (mv === 0) {
        const r = ref[i - 1], h = hyp[j - 1];
        if (sim(r.ch, h.ch) > 0) {
          firstT[r.li] = h.t;
          if (lastT[r.li] == null) lastT[r.li] = h.t;
        }
        i--; j--;
      } else if (mv === 1) i--; else j--;
    }
    // 못 맞춘 줄은 앞뒤 줄 사이로 채운다
    const dur = isFinite(audio.duration) ? audio.duration : (hyp[hyp.length - 1].t + 5);
    const out = refLines.map((l, k) => ({ text: l.text, t: firstT[k], e: lastT[k] }));
    for (let k = 0; k < out.length; k++) {
      if (out[k].t != null) continue;
      const a = k - 1;                                   // 시각이 있는 앞 줄 (없으면 -1)
      let b = k; while (b < out.length && out[b].t == null) b++;
      const tb = b < out.length ? out[b].t : dur;
      const ta = a >= 0 ? out[a].t : Math.max(0, tb - 3 * (b - a));
      for (let q = a + 1; q < b; q++) out[q].t = ta + ((tb - ta) * (q - a)) / (b - a);
      k = b;
    }
    let prev = -1;
    for (const l of out) {
      if (l.t <= prev) l.t = prev + 0.05;
      prev = l.t;
      l.t = +l.t.toFixed(2);
      l.e = l.e != null && l.e > l.t ? +(l.e + 0.25).toFixed(2) : undefined;
      if (l.e === undefined) delete l.e;
    }
    return out;
  }

  /* ---------- 장면 ---------- */
  function sceneCountFor(nImages) {
    if (nImages) return nImages;
    if (opts.scenes > 0) return opts.scenes;
    const dur = isFinite(audio.duration) ? audio.duration : 180;
    return Math.max(3, Math.min(8, Math.round(dur / 35)));
  }

  function replanScenes() {
    const imgs = sceneBlobs.filter(Boolean);
    const scenes = planThemeScenes(S.lines, sceneCountFor(imgs.length), !imgs.length);
    clearScenes();
    S.scenes = scenes;
    return imgs;
  }

  async function attachImages(blobs) {
    for (let i = 0; i < S.scenes.length; i++) await setSceneImage(i, blobs[i] || null);
  }

  /* ---------- 전체 흐름 ---------- */
  function setBusy(b) {
    busy = b;
    el('autoBtn2').disabled = b;
    document.body.classList.toggle('auto-busy', b);
  }

  class Plain extends Error {}   // 사람에게 그대로 보여줄 안내

  function friendly(err) {
    if (err instanceof Plain) return err.message;
    const msg = err.message || String(err);
    if (/^LIB:/.test(msg) || /Failed to fetch|NetworkError|load failed/i.test(msg)) return '음성인식 AI를 내려받지 못했어요. 인터넷 연결을 확인하고 다시 해주세요. (처음 한 번은 인터넷이 필요해요)';
    if (/memory|allocation|OOM/i.test(msg)) return '컴퓨터 메모리가 모자라요. 다른 탭을 닫거나, 고급 설정에서 정확도를 「빠름」으로 바꿔 다시 해주세요.';
    return `자동 만들기 중 문제가 생겼어요: ${msg}`;
  }

  async function run() {
    if (busy) return;
    if (!audioBlob) { status('음악 파일을 먼저 넣어주세요.', 'err'); return; }
    setBusy(true);
    const t0 = performance.now();
    let tick = 0;
    try {
      status('1/3 음악을 읽는 중…'); bar(null);
      const pcm = await decode16k(audioBlob);
      const dur = pcm.length / 16000;
      const chunks = await recognize(pcm, (m) => {
        if (m.type === 'loading') status(`2/3 음성인식 AI 준비 중… (처음 한 번은 내려받느라 몇 분 걸려요)`);
        else if (m.type === 'download') {
          const p = m.loaded / m.total;
          bar(p);
          status(`2/3 음성인식 AI 내려받는 중… ${Math.round(p * 100)}% (${(m.total / 1048576).toFixed(0)}MB, 처음 한 번만)`);
        } else if (m.type === 'running') {
          bar(null);
          const how = m.device === 'webgpu' ? '그래픽카드로' : 'CPU로 (조금 느려요)';
          const startRun = performance.now();
          clearInterval(tick);
          tick = setInterval(() => {
            const s = Math.round((performance.now() - startRun) / 1000);
            status(`2/3 노래를 듣고 가사를 받아 적는 중… ${how} ${s}초 지남 · 3분짜리 노래면 1~5분 걸려요`);
          }, 1000);
        }
      });
      clearInterval(tick);
      const clean = cleanChunks(chunks, dur);
      if (!clean.length) throw new Plain('노래에서 가사를 알아듣지 못했어요. 「3. 가사」에 가사를 붙여넣고 다시 해보세요.');

      status('3/3 박자를 맞추고 배경 장면을 고르는 중…');
      const refText = $('#lyricsIn').value.trim();
      const ref = refText ? parseLyrics(refText).lines.filter((l) => l.text) : [];
      let lines = null;
      if (ref.length) lines = alignLyrics(ref, clean);
      const usedRef = !!lines;
      if (!lines) lines = chunksToLines(clean);

      if (syncing) stopSync();
      S.lines = lines;
      $('#lyricsIn').value = lines.map((l) => l.text).join('\n');
      scrollY = null;
      const imgs = replanScenes();
      await attachImages(imgs);
      S.bg = 'scene'; $('#bgMode').value = 'scene';
      renderList(); renderScenes(); save();
      const sec = Math.round((performance.now() - t0) / 1000);
      status(usedRef
        ? `완료! (${sec}초) 붙여넣은 가사에 박자를 맞췄어요. ▶ 재생해서 확인해보세요.`
        : `완료! (${sec}초) 받아 적은 가사는 틀린 글자가 있을 수 있어요. 「3. 가사」에서 고치거나, Suno 가사를 붙여넣고 다시 누르면 더 정확해요.`, 'ok');
    } catch (e) {
      console.error(e);
      status(friendly(e), 'err');
    } finally {
      clearInterval(tick);
      bar(null);
      setBusy(false);
    }
  }

  /* ---------- 장면 목록 ---------- */
  const thumbURLs = [];
  const fmt = (t) => `${Math.floor(t / 60)}:${String(Math.floor(t % 60)).padStart(2, '0')}`;
  window.renderScenes = function renderScenes() {
    const box = el('sceneList');
    thumbURLs.splice(0).forEach((u) => URL.revokeObjectURL(u));
    box.innerHTML = '';
    S.scenes.forEach((sc, i) => {
      const item = document.createElement('div');
      item.className = 'scene';
      let thumb;
      if (sceneBlobs[i]) {
        thumb = document.createElement('img');
        const u = URL.createObjectURL(sceneBlobs[i]); thumbURLs.push(u);
        thumb.src = u; thumb.alt = `${i + 1}번 장면 그림`;
      } else {
        thumb = document.createElement('canvas');
        thumb.width = 240; thumb.height = 160;
        drawTheme(thumb.getContext('2d'), sc.theme, 240, 160, 1, 3, 0.2, 0.3);
        thumb.className = 'ph';
      }
      thumb.title = '누르면 이 장면으로 이동';
      thumb.addEventListener('click', () => { audio.currentTime = Math.max(0, sc.t + S.offset); });
      const cap = document.createElement('div');
      cap.className = 'cap';
      const tm = document.createElement('span');
      tm.textContent = fmt(Math.max(0, sc.t));
      const sel = document.createElement('select');
      sel.setAttribute('aria-label', `${i + 1}번 장면 분위기`);
      for (const k of THEME_ORDER) { const o = document.createElement('option'); o.value = k; o.textContent = THEMES[k].name; sel.appendChild(o); }
      sel.value = sc.theme;
      sel.disabled = !!sceneBlobs[i];
      sel.addEventListener('change', () => { sc.theme = sel.value; save(); renderScenes(); });
      cap.append(tm, sel);
      item.append(thumb, cap);
      box.appendChild(item);
    });
    el('clearImgs').hidden = !sceneBlobs.some(Boolean);
  };

  el('autoBtn2').addEventListener('click', run);
  el('replanBtn').addEventListener('click', async () => {
    if (!S.lines.some((l) => l.t != null)) { status('박자가 맞춰진 가사가 있어야 장면을 나눌 수 있어요.', 'err'); return; }
    const imgs = replanScenes();
    await attachImages(imgs);
    renderScenes(); save();
    status('가사에 맞춰 장면을 다시 골랐어요.', 'ok');
  });
  el('myImgs').addEventListener('change', async (e) => {
    const files = [...e.target.files].filter((f) => f.type.startsWith('image/'));
    e.target.value = '';
    if (!files.length) return;
    files.sort((a, b) => a.name.localeCompare(b.name, 'ko', { numeric: true }));
    clearScenes();
    S.scenes = S.lines.some((l) => l.t != null)
      ? planThemeScenes(S.lines, files.length)
      : files.map((_, i) => ({ t: (isFinite(audio.duration) ? audio.duration : 180) * (i / files.length), theme: 'light' }));
    await attachImages(files);
    S.bg = 'scene'; $('#bgMode').value = 'scene';
    renderScenes(); save();
    status(`그림 ${files.length}장을 노래 흐름에 맞춰 배치했어요. (파일 이름 순서대로)`, 'ok');
  });
  el('clearImgs').addEventListener('click', async () => {
    for (let i = 0; i < S.scenes.length; i++) await setSceneImage(i, null);
    renderScenes(); save();
  });

  // 음악 파일을 넣으면 바로 시작
  window.addEventListener('audio-added', () => { if (opts.auto) run(); });
})();
