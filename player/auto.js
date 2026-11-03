/* 자동 만들기 — OpenAI(ChatGPT) API로
 *  1) 노래를 듣고 가사를 받아 적고 (whisper-1, 단어마다 시각 포함)
 *  2) ChatGPT가 가사를 줄로 정리하고 배경 장면을 나누고
 *  3) 장면마다 가사 내용에 맞는 그림을 그린다.
 * API 키는 이 브라우저(localStorage)에만 저장되고, 요청은 브라우저에서 OpenAI로 바로 간다. */
'use strict';

(() => {
  const API = 'https://api.openai.com/v1/';
  const KEY_LS = 'lp-openai-key';
  const OPT_LS = 'lp-auto-opts';
  const el = (id) => document.getElementById(id);

  const DEFAULTS = {
    auto: true,
    images: true,
    count: 5,
    style: '따뜻한 빛이 스며드는 서정적인 유화풍, 경건하고 평화로운 분위기',
    textModel: 'gpt-5-mini',
    imageModel: 'gpt-image-1',
    lang: 'ko',
  };
  let opts = { ...DEFAULTS };
  try { Object.assign(opts, JSON.parse(localStorage.getItem(OPT_LS) || '{}')); } catch (e) { /* 저장된 설정 없음 */ }
  const saveOpts = () => { try { localStorage.setItem(OPT_LS, JSON.stringify(opts)); } catch (e) { /* 무시 */ } };

  let busy = false;
  const getKey = () => { try { return (localStorage.getItem(KEY_LS) || '').trim(); } catch (e) { return ''; } };

  /* ---------- 설정 칸 연결 ---------- */
  const keyIn = el('apiKey');
  keyIn.value = getKey();
  keyIn.addEventListener('change', () => {
    const v = keyIn.value.trim();
    if (v === getKey()) return;
    try { localStorage.setItem(KEY_LS, v); } catch (e) { /* 무시 */ }
    if (!busy) status(v ? '키를 저장했어요.' : '');
  });
  const bind = (id, prop, kind) => {
    const input = el(id);
    if (kind === 'check') input.checked = !!opts[prop]; else input.value = opts[prop];
    input.addEventListener('change', () => {
      opts[prop] = kind === 'check' ? input.checked : kind === 'num' ? parseInt(input.value, 10) : input.value.trim();
      saveOpts();
    });
  };
  bind('autoOnAdd', 'auto', 'check');
  bind('makeImages', 'images', 'check');
  bind('sceneCount', 'count', 'num');
  bind('imgStyle', 'style');
  bind('textModel', 'textModel');
  bind('imageModel', 'imageModel');
  bind('lang', 'lang');

  function status(msg, kind) {
    const s = el('autoStatus');
    s.textContent = msg;
    s.className = 'status' + (kind ? ' ' + kind : '');
  }

  /* ---------- OpenAI 호출 ---------- */
  function explain(statusCode, err) {
    const msg = (err && err.message) || '';
    const code = (err && err.code) || '';
    if (statusCode === 401) return 'API 키가 맞지 않아요. 키를 다시 복사해서 넣어주세요.';
    if (code === 'insufficient_quota' || /quota|billing/i.test(msg)) return 'OpenAI 계정에 충전된 금액이 없어요. platform.openai.com → Billing에서 충전해주세요.';
    if (statusCode === 429) return '요청이 너무 많아요. 1분쯤 뒤에 다시 해주세요.';
    if (/verif/i.test(msg)) return '그림 모델을 쓰려면 OpenAI 조직 인증이 필요해요 (platform.openai.com → Settings → Organization → Verify). 또는 고급 설정에서 그림 모델을 dall-e-3으로 바꿔보세요.';
    if (/safety|content_policy|moderation/i.test(msg + code)) return '그림 내용이 OpenAI 정책에 걸렸어요. 그림 분위기 문구를 바꿔서 다시 해주세요.';
    if (/model/i.test(msg) && /exist|not found|access/i.test(msg)) return `모델 이름을 쓸 수 없어요: ${msg}`;
    return `OpenAI 오류 (${statusCode}): ${msg || '알 수 없음'}`;
  }

  async function call(path, body, isForm) {
    const key = getKey();
    if (!key) throw new Error('OpenAI API 키를 먼저 넣어주세요.');
    let r;
    try {
      r = await fetch(API + path, {
        method: 'POST',
        headers: isForm ? { Authorization: `Bearer ${key}` } : { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
        body: isForm ? body : JSON.stringify(body),
      });
    } catch (e) {
      throw new Error('OpenAI에 연결하지 못했어요. 인터넷 연결을 확인해주세요.');
    }
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(explain(r.status, j.error));
    return j;
  }

  /* ---------- 1) 받아쓰기 ---------- */
  const EXT = { 'audio/mpeg': 'mp3', 'audio/mp3': 'mp3', 'audio/wav': 'wav', 'audio/x-wav': 'wav', 'audio/wave': 'wav', 'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/ogg': 'ogg', 'audio/webm': 'webm', 'audio/flac': 'flac' };

  async function uploadable() {
    let blob = audioBlob, name = audioName || '';
    if (!/\.(mp3|mp4|mpeg|mpga|m4a|wav|webm|ogg|flac)$/i.test(name)) name = `song.${EXT[blob.type] || 'mp3'}`;
    if (blob.size > 24 * 1024 * 1024) {           // OpenAI 한도 25MB → 16kHz 모노 WAV로 줄인다
      blob = await toWav16k(blob);
      name = 'song.wav';
    }
    return { blob, name };
  }

  async function toWav16k(blob) {
    const ac = new (window.AudioContext || window.webkitAudioContext)();
    const dec = await ac.decodeAudioData(await blob.arrayBuffer());
    ac.close();
    const rate = 16000;
    const off = new OfflineAudioContext(1, Math.ceil(dec.duration * rate), rate);
    const src = off.createBufferSource();
    src.buffer = dec; src.connect(off.destination); src.start();
    const data = (await off.startRendering()).getChannelData(0);
    const buf = new ArrayBuffer(44 + data.length * 2), v = new DataView(buf);
    const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
    str(0, 'RIFF'); v.setUint32(4, 36 + data.length * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    str(36, 'data'); v.setUint32(40, data.length * 2, true);
    for (let i = 0; i < data.length; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, data[i])) * 0x7fff, true);
    return new Blob([buf], { type: 'audio/wav' });
  }

  async function transcribe(ref) {
    const { blob, name } = await uploadable();
    const fd = new FormData();
    fd.append('file', blob, name);
    fd.append('model', 'whisper-1');
    fd.append('response_format', 'verbose_json');
    fd.append('timestamp_granularities[]', 'word');
    fd.append('timestamp_granularities[]', 'segment');
    if (opts.lang && opts.lang !== 'auto') fd.append('language', opts.lang);
    // 제목·가사를 힌트로 주면 더 잘 알아듣는다 (앞부분만)
    const hint = [S.title, ref].filter(Boolean).join('\n').slice(0, 600);
    if (hint) fd.append('prompt', hint);
    const j = await call('audio/transcriptions', fd, true);
    let words = (j.words || []).map((w) => ({ w: String(w.word).trim(), s: +w.start, e: +w.end })).filter((w) => w.w && isFinite(w.s));
    if (!words.length && j.segments) {
      // 단어 시각이 없으면 문장 시각을 글자 수로 나눠 쓴다
      for (const seg of j.segments) {
        const toks = String(seg.text).trim().split(/\s+/).filter(Boolean);
        const total = toks.reduce((a, t) => a + t.length, 0) || 1;
        let acc = 0;
        for (const t of toks) {
          const s = seg.start + ((seg.end - seg.start) * acc) / total;
          acc += t.length;
          words.push({ w: t, s, e: seg.start + ((seg.end - seg.start) * acc) / total });
        }
      }
    }
    return words;
  }

  /* ---------- 2) ChatGPT로 줄 나누기 + 장면 나누기 ---------- */
  const SYSTEM = `You turn a speech-recognition transcript of a SONG into karaoke lyric lines, and plan background images.

INPUT: a numbered list of recognized words with start times in seconds. The song is sung over music, so there are mishearings. In instrumental parts the recognizer often hallucinates phrases such as "시청해주셔서 감사합니다", "구독과 좋아요", "자막", "MBC 뉴스", "Thank you for watching" — ignore those words (do not put them in any line).

TASK 1 — lines:
- Group consecutive words into lyric lines the way a lyrics sheet would show them (one musical phrase each, usually 2–8 seconds, at most about 22 Korean characters).
- Each line has start_word and end_word: inclusive indices into the word list. Lines are in order and do not overlap.
- text: the lyric line in the song's own language. Fix obvious mishearings so it reads naturally and fits the song's meaning, but never invent lines that were not sung.
- No section labels like [Chorus].
- If REFERENCE LYRICS are given, they are the exact correct lyrics. Use their wording and line breaks, in sung order, mapping each line to the words where it is sung. If a part (e.g. the chorus) is sung more times than it is written, output it every time it is sung. Skip reference lines that were clearly not sung.

TASK 2 — scenes (exactly the requested number; none if the number is 0):
- Split the song into scenes that follow the story and mood of the lyrics. start_line is an index into YOUR lines; the first scene starts at 0, and they increase.
- prompt: a vivid ENGLISH prompt for an image generator that visualizes what those lyrics are about (place, light, symbols, season, mood), in the requested art style. Keep one consistent style across all scenes so they feel like one music video. Wide cinematic composition with calm space for lyrics. No text, letters, logos or captions. No close-up realistic faces.`;

  const SCHEMA = {
    type: 'object', additionalProperties: false, required: ['lines', 'scenes'],
    properties: {
      lines: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['start_word', 'end_word', 'text'],
        properties: { start_word: { type: 'integer' }, end_word: { type: 'integer' }, text: { type: 'string' } } } },
      scenes: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['start_line', 'prompt'],
        properties: { start_line: { type: 'integer' }, prompt: { type: 'string' } } } },
    },
  };

  async function chatJSON(system, user, schema) {
    const j = await call('chat/completions', {
      model: opts.textModel || DEFAULTS.textModel,
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
      response_format: { type: 'json_schema', json_schema: { name: 'result', strict: true, schema } },
    });
    const msg = j.choices && j.choices[0] && j.choices[0].message;
    if (!msg || msg.refusal) throw new Error('ChatGPT가 답을 주지 않았어요. 다시 해주세요.');
    try { return JSON.parse(msg.content); } catch (e) { throw new Error('ChatGPT 답을 읽지 못했어요. 다시 해주세요.'); }
  }

  async function arrange(words, ref, n) {
    const list = words.map((w, i) => `${i}\t${w.s.toFixed(2)}\t${w.w}`).join('\n');
    const user = [
      `Song title: ${S.title || '(unknown)'}`,
      `Art style for images: ${opts.style || DEFAULTS.style}`,
      `Number of scenes: ${n}`,
      ref ? `REFERENCE LYRICS:\n${ref}` : 'REFERENCE LYRICS: (none)',
      `WORDS (index<TAB>start<TAB>word):\n${list}`,
    ].join('\n\n');
    const out = await chatJSON(SYSTEM, user, SCHEMA);
    const last = words.length - 1;
    let prevT = -1;
    const lines = [];
    for (const l of out.lines || []) {
      const a = Math.max(0, Math.min(last, l.start_word | 0));
      const b = Math.max(a, Math.min(last, l.end_word | 0));
      const text = String(l.text || '').trim();
      if (!text) continue;
      let t = words[a].s;
      if (t < prevT) t = prevT + 0.05;                  // 순서가 꼬이지 않게
      prevT = t;
      lines.push({ text, t: +t.toFixed(2), e: +Math.max(t + 0.3, words[b].e).toFixed(2) });
    }
    if (!lines.length) throw new Error('가사 줄을 만들지 못했어요. 다시 해주세요.');
    const scenes = toScenes(out.scenes, lines);
    return { lines, scenes };
  }

  function toScenes(raw, lines) {
    const scenes = [];
    let prev = -1;
    for (const sc of raw || []) {
      const li = Math.max(0, Math.min(lines.length - 1, sc.start_line | 0));
      if (li <= prev && scenes.length) continue;
      prev = li;
      scenes.push({ t: scenes.length ? lines[li].t : 0, prompt: String(sc.prompt || '').trim() });
    }
    return scenes.filter((s) => s.prompt);
  }

  // 가사는 이미 있고 그림 장면만 새로 나눌 때
  async function planScenes(n) {
    const list = S.lines.map((l, i) => `${i}\t${l.t == null ? '?' : l.t.toFixed(1)}\t${l.text}`).join('\n');
    const user = [
      `Song title: ${S.title || '(unknown)'}`,
      `Art style for images: ${opts.style || DEFAULTS.style}`,
      `Number of scenes: ${n}`,
      `The lyric lines are already final. Return them unchanged in "lines" with start_word = end_word = their index, and plan the scenes.`,
      `LINES (index<TAB>start<TAB>text):\n${list}`,
    ].join('\n\n');
    const out = await chatJSON(SYSTEM, user, SCHEMA);
    const timed = S.lines.map((l) => ({ ...l, t: l.t ?? 0 }));
    return toScenes(out.scenes, timed);
  }

  /* ---------- 3) 그림 그리기 ---------- */
  async function drawImage(prompt) {
    const model = opts.imageModel || DEFAULTS.imageModel;
    const tall = S.aspect === '9:16';
    const isDalle = /^dall-e/.test(model);
    const body = { model, prompt: `${prompt}\nStyle: ${opts.style || DEFAULTS.style}. No text, no letters, no watermark.`, n: 1 };
    if (isDalle) {
      Object.assign(body, { size: tall ? '1024x1792' : '1792x1024', response_format: 'b64_json', quality: 'standard' });
    } else {
      Object.assign(body, { size: tall ? '1024x1536' : '1536x1024', quality: 'medium', output_format: 'jpeg', output_compression: 88 });
    }
    const j = await call('images/generations', body);
    const b64 = j.data && j.data[0] && j.data[0].b64_json;
    if (!b64) throw new Error('그림을 받지 못했어요.');
    const bin = atob(b64), arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return new Blob([arr], { type: isDalle ? 'image/png' : 'image/jpeg' });
  }

  async function drawAll(onProgress) {
    const n = S.scenes.length;
    let done = 0, next = 0, failed = 0, lastErr = null;
    sceneImgs.length = n; sceneBlobs.length = n;
    const worker = async () => {
      while (next < n) {
        const i = next++;
        try { await setSceneImage(i, await drawImage(S.scenes[i].prompt)); }
        catch (e) { failed++; lastErr = e; }
        done++;
        onProgress(done, n);
        renderScenes();
      }
    };
    await Promise.all([worker(), worker()]);   // 두 장씩 동시에
    if (failed === n && lastErr) throw lastErr;
    return failed;
  }

  /* ---------- 전체 흐름 ---------- */
  function setBusy(b) {
    busy = b;
    ['autoBtn2', 'imagesBtn'].forEach((id) => { el(id).disabled = b; });
    document.body.classList.toggle('auto-busy', b);
  }

  async function run(mode) {
    if (busy) return;
    if (!getKey()) { status('OpenAI API 키를 먼저 넣어주세요.', 'err'); keyIn.focus(); return; }
    if (mode === 'all' && !audioBlob) { status('음악 파일을 먼저 넣어주세요.', 'err'); return; }
    setBusy(true);
    const n = opts.images ? Math.max(1, Math.min(10, opts.count | 0)) : 0;
    const t0 = performance.now();
    try {
      if (mode === 'all') {
        const ref = $('#lyricsIn').value.trim();
        status(ref ? '1/3 노래를 듣고 붙여넣은 가사와 맞춰보는 중… (30초~1분)' : '1/3 노래를 듣고 가사를 받아 적는 중… (30초~1분)');
        const words = await transcribe(ref);
        if (!words.length) throw new Error('노래에서 가사를 알아듣지 못했어요. 가사를 붙여넣고 다시 해보세요.');
        status('2/3 ChatGPT가 가사를 줄로 나누고 장면을 정하는 중… (30초~1분)');
        const { lines, scenes } = await arrange(words, ref, n);
        if (syncing) stopSync();
        S.lines = lines;
        $('#lyricsIn').value = lines.map((l) => l.text).join('\n');
        scrollY = null;
        clearScenes();
        S.scenes = scenes;
        renderList(); renderScenes(); save();
      } else {
        if (!S.lines.length) { throw new Error('가사가 없어요. 먼저 「자동으로 만들기」를 하거나 가사를 넣어주세요.'); }
        if (!n) throw new Error('「배경 그림 만들기」를 켜주세요.');
        status('ChatGPT가 장면을 정하는 중…');
        const scenes = await planScenes(n);
        clearScenes();
        S.scenes = scenes;
        renderScenes(); save();
      }
      let failed = 0;
      if (n && S.scenes.length) {
        status(`3/3 가사에 맞는 배경 그림을 그리는 중… (0/${S.scenes.length})`);
        failed = await drawAll((d, total) => status(`3/3 가사에 맞는 배경 그림을 그리는 중… (${d}/${total})`));
        S.bg = 'ai'; $('#bgMode').value = 'ai';
        save();
      }
      const sec = Math.round((performance.now() - t0) / 1000);
      status(`완료! (${sec}초) ▶ 재생을 눌러 확인해보세요.${failed ? ` 그림 ${failed}장은 실패했어요. 아래에서 「다시」를 눌러주세요.` : ''}`, 'ok');
    } catch (e) {
      console.error(e);
      status(e.message || String(e), 'err');
    } finally {
      setBusy(false);
    }
  }

  async function redrawOne(i) {
    if (busy) return;
    setBusy(true);
    status(`${i + 1}번 그림을 다시 그리는 중…`);
    try {
      await setSceneImage(i, await drawImage(S.scenes[i].prompt));
      renderScenes();
      status(`${i + 1}번 그림을 바꿨어요.`, 'ok');
    } catch (e) { status(e.message, 'err'); } finally { setBusy(false); }
  }

  /* ---------- 장면 목록 ---------- */
  const thumbURLs = [];
  window.renderScenes = function renderScenes() {
    const box = el('sceneList');
    thumbURLs.splice(0).forEach((u) => URL.revokeObjectURL(u));
    box.innerHTML = '';
    S.scenes.forEach((sc, i) => {
      const item = document.createElement('div');
      item.className = 'scene';
      const blob = sceneBlobs[i];
      let thumb;
      if (blob) {
        thumb = document.createElement('img');
        const u = URL.createObjectURL(blob); thumbURLs.push(u);
        thumb.src = u; thumb.alt = sc.prompt;
      } else {
        thumb = document.createElement('div');
        thumb.className = 'ph';
        thumb.textContent = busy ? '그리는 중' : '없음';
      }
      thumb.title = sc.prompt;
      thumb.addEventListener('click', () => { audio.currentTime = Math.max(0, sc.t + S.offset); });
      const cap = document.createElement('div');
      cap.className = 'cap';
      const tm = Math.max(0, sc.t);
      cap.textContent = `${Math.floor(tm / 60)}:${String(Math.floor(tm % 60)).padStart(2, '0')}`;
      const again = document.createElement('button');
      again.type = 'button'; again.textContent = '다시';
      again.addEventListener('click', () => redrawOne(i));
      cap.appendChild(again);
      item.append(thumb, cap);
      box.appendChild(item);
    });
  };

  el('autoBtn2').addEventListener('click', () => run('all'));
  el('imagesBtn').addEventListener('click', () => run('images'));

  // 음악 파일을 넣으면 바로 시작
  window.addEventListener('audio-added', () => {
    if (!opts.auto) return;
    if (!getKey()) { status('OpenAI API 키를 넣고 「자동으로 만들기」를 누르면 가사와 그림을 만들어요.'); return; }
    run('all');
  });
})();
