// 카카오톡 대화 내보내기 파일 파서 + 분석기 (브라우저/Node 공용, 외부 의존성 없음)

// ───────────────────────── 파싱 ─────────────────────────

const to24 = (ampm, h) => {
  h = +h;
  if (!ampm) return h;
  if (/오후|PM/i.test(ampm)) return h === 12 ? 12 : h + 12;
  return h === 12 ? 0 : h; // 오전/AM
};
const mk = (y, mo, d, h, mi) => new Date(+y, +mo - 1, +d, +h, +mi);

const MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };

// 한 줄에 날짜·시간·보낸사람이 모두 들어있는 형식 (모바일 앱 내보내기)
const LINE_FORMATS = [
  // 2024년 1월 5일 오후 3:21, 홍길동 : 메시지   (안드로이드)
  { re: /^(\d{4})년 (\d{1,2})월 (\d{1,2})일 (오전|오후)? ?(\d{1,2}):(\d{2}),\s?(.+?) : ?(.*)$/,
    f: m => ({ t: mk(m[1], m[2], m[3], to24(m[4], m[5]), m[6]), who: m[7], text: m[8] }) },
  // 2024. 1. 5. 오후 3:21, 홍길동 : 메시지   (iOS)
  { re: /^(\d{4})\. ?(\d{1,2})\. ?(\d{1,2})\.? (오전|오후)? ?(\d{1,2}):(\d{2}),\s?(.+?) : ?(.*)$/,
    f: m => ({ t: mk(m[1], m[2], m[3], to24(m[4], m[5]), m[6]), who: m[7], text: m[8] }) },
  // Jan 5, 2024 at 3:21 PM, Name : message   (영문)
  { re: /^([A-Za-z]{3})[a-z]* (\d{1,2}), (\d{4})(?: at)? (\d{1,2}):(\d{2})\s?(AM|PM)?,\s?(.+?) : ?(.*)$/,
    f: m => ({ t: mk(m[3], MONTHS[m[1].toLowerCase()], m[2], to24(m[6], m[4]), m[5]), who: m[7], text: m[8] }) },
];

// 날짜·시간만 있고 " : " 가 없는 줄 = 입장/퇴장 등 시스템 메시지
const SYSTEM_LINE = [
  /^\d{4}년 \d{1,2}월 \d{1,2}일 (오전|오후)? ?\d{1,2}:\d{2},? /,
  /^\d{4}\. ?\d{1,2}\. ?\d{1,2}\.? (오전|오후)? ?\d{1,2}:\d{2},? /,
  /^[A-Za-z]{3}[a-z]* \d{1,2}, \d{4}(?: at)? \d{1,2}:\d{2}/,
];

// 날짜 구분선 (PC 버전 / 모바일 버전)
const DATE_HEADERS = [
  { re: /^-*\s*(\d{4})년 (\d{1,2})월 (\d{1,2})일 \S+요일\s*-*$/, f: m => [m[1], m[2], m[3]] },
  { re: /^-*\s*(\d{4})\. ?(\d{1,2})\. ?(\d{1,2})\.? \S+요일\s*-*$/, f: m => [m[1], m[2], m[3]] },
  { re: /^-*\s*(?:[A-Za-z]+, )?([A-Za-z]{3})[a-z]* (\d{1,2}), (\d{4})\s*-*$/, f: m => [m[3], MONTHS[m[1].toLowerCase()], m[2]] },
];

// PC 버전: [홍길동] [오후 3:21] 메시지  /  [Name] [3:21 PM] message
const PC_MSG = /^\[(.+?)\] \[(오전|오후)? ?(\d{1,2}):(\d{2}) ?(AM|PM)?\] ?(.*)$/;

function parseCSV(text) {
  const rows = [];
  let row = [], cur = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"') { if (text[i + 1] === '"') { cur += '"'; i++; } else q = false; }
      else cur += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(cur); cur = ''; }
    else if (c === '\n') { row.push(cur); rows.push(row); row = []; cur = ''; }
    else if (c !== '\r') cur += c;
  }
  if (cur || row.length) { row.push(cur); rows.push(row); }
  return rows;
}

/**
 * 카카오톡 내보내기 텍스트를 메시지 배열로 변환.
 * @returns {{title:string, messages:{t:Date, who:string, text:string, kind:string}[], format:string}}
 */
export function parseKakao(raw) {
  const text = raw.replace(/^﻿/, '').replace(/\r\n?/g, '\n');
  let title = '';
  const tm = text.match(/^(.+?) 님과 카카오톡 대화/m) || text.match(/^(.+?) 카카오톡 대화$/m) || text.match(/^KakaoTalk Chats with (.+)$/m);
  if (tm) title = (tm[1] || tm[0]).trim();

  // Mac CSV: Date,User,Message
  if (/^\s*"?Date"?,\s*"?User"?,\s*"?Message"?/i.test(text)) {
    const rows = parseCSV(text).slice(1).filter(r => r.length >= 3);
    const messages = rows.map(r => {
      const [d, who, ...rest] = r;
      const m = d.match(/(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
      return m && { t: mk(m[1], m[2], m[3], m[4], m[5]), who: who.trim(), text: rest.join(',') };
    }).filter(Boolean);
    return { title, format: 'CSV (Mac)', messages: messages.map(classify) };
  }

  const lines = text.split('\n');
  const messages = [];
  let curDate = null, last = null, format = '';

  for (const line of lines) {
    if (!line.trim()) { if (last) last.text += '\n'; continue; }

    let hit = null;
    for (const h of DATE_HEADERS) {
      const m = line.match(h.re);
      if (m) { const [y, mo, d] = h.f(m); curDate = [y, mo, d]; hit = true; break; }
    }
    if (hit) { last = null; continue; }

    for (const fm of LINE_FORMATS) {
      const m = line.match(fm.re);
      if (m) { hit = fm.f(m); format ||= '모바일'; break; }
    }
    if (!hit && curDate) {
      const m = line.match(PC_MSG);
      if (m) {
        hit = { t: mk(curDate[0], curDate[1], curDate[2], to24(m[2] || m[5], m[3]), m[4]), who: m[1], text: m[6] };
        format ||= 'PC';
      }
    }
    if (hit && !isNaN(hit.t)) {
      hit.who = hit.who.trim();
      last = hit;
      messages.push(hit);
      continue;
    }
    if (SYSTEM_LINE.some(r => r.test(line))) { last = null; continue; }
    if (/^저장한 날짜|^Date Saved|님과 카카오톡 대화$/.test(line)) continue;
    if (last) last.text += '\n' + line; // 여러 줄 메시지의 이어지는 줄
  }
  for (const m of messages) m.text = m.text.replace(/\n+$/, '');
  return { title, format: format || '알 수 없음', messages: messages.map(classify) };
}

const MEDIA = /^(사진|동영상|이모티콘|음성메시지|파일: .+|사진 \d+장|Photo|Video|Emoticons?|Voice Note|File: .+|샵검색: .+|지도: .+|연락처: .+)$/;
const DELETED = /^(삭제된 메시지입니다\.?|This message was deleted\.?|메시지가 삭제되었습니다\.?)$/;
function classify(m) {
  const s = m.text.trim();
  m.kind = DELETED.test(s) ? 'deleted' : MEDIA.test(s) ? 'media' : /^https?:\/\/\S+$/.test(s) ? 'link' : 'text';
  return m;
}

// ───────────────────────── 분석 ─────────────────────────

const QUESTION = /[?？]|(까요|나요|ㄹ까|을까|가요|는지|던지|어때|어떄|어떠|언제|어디|뭐|무엇|왜|어떻게|누가|몇|가능할|가능한가|가능하신|가능하세|해줄|해주|해 줄|해 주|부탁|알려|주실|주세요|줄래|될까|되나|되나요|있어요\?|있나|없나|맞아|맞죠|맞나|할래|갈래|올래|볼래|하실|오실|가실|되세요|드릴까)/;
export const isQuestion = s => QUESTION.test(s);

const SESSION_GAP = 12 * 3600e3; // 이 시간 이상 대화가 끊기면 새 대화로 보고, 그때까지 답 없는 말은 '미응답'
const TURN_GAP = 3 * 3600e3;     // 같은 사람이 이어서 보낸 말이라도 3시간 넘게 벌어지면 따로 묶음

/** 연속된 같은 사람의 메시지를 하나의 '발화 묶음(turn)'으로 */
export function toTurns(messages) {
  const turns = [];
  for (const m of messages) {
    const prev = turns[turns.length - 1];
    if (prev && prev.who === m.who && m.t - prev.end < TURN_GAP) {
      prev.msgs.push(m); prev.end = m.t;
    } else turns.push({ who: m.who, start: m.t, end: m.t, msgs: [m] });
  }
  for (const t of turns) {
    t.text = t.msgs.map(x => x.text).join('\n');
    t.question = t.msgs.some(x => x.kind === 'text' && isQuestion(x.text));
  }
  return turns;
}

/**
 * 분석 결과:
 *  exchanges: 다른 사람(들)이 한 말 → 내 답장  (시간순)
 *  initiated: 내가 먼저 꺼낸 말 → 상대 반응
 *  unanswered: 내가 답하지 않고 대화가 끊긴 상대 발화
 *  people: 사람별 통계 / 대화 / 키워드
 */
export function analyze(messages, me) {
  const turns = toTurns(messages);
  const exchanges = [], initiated = [], unanswered = [];
  let pending = [];
  let lastTime = null, afterMine = false;

  const flushUnanswered = () => { for (const t of pending) unanswered.push(t); pending = []; };

  for (let i = 0; i < turns.length; i++) {
    const t = turns[i];
    if (lastTime && t.start - lastTime >= SESSION_GAP) flushUnanswered();
    lastTime = t.end;

    if (t.who !== me) {
      // 내가 먼저 꺼낸 말에 대한 단순 반응(질문 아님)은 '답해야 할 말'로 보지 않음
      if (afterMine && !pending.length && !t.question) { t.reaction = true; continue; }
      afterMine = false;
      pending.push(t);
      continue;
    }
    afterMine = true;

    if (pending.length) {
      exchanges.push({ prompts: pending, reply: t, delay: t.start - pending[pending.length - 1].end, waited: t.start - pending[0].start });
      pending = [];
    } else {
      // 내가 먼저 말을 꺼낸 경우: 다음에 오는 상대 반응까지 기록
      const responses = [];
      for (let j = i + 1; j < turns.length && turns[j].who !== me && turns[j].start - t.end < SESSION_GAP; j++) responses.push(turns[j]);
      initiated.push({ turn: t, responses });
    }
  }
  flushUnanswered();

  // 사람별 집계
  const people = new Map();
  const P = who => {
    if (!people.has(who)) people.set(who, { name: who, msgCount: 0, charCount: 0, turns: 0, questions: 0, answered: 0, first: null, last: null, items: [], unanswered: [], words: new Map(), delays: [] });
    return people.get(who);
  };
  for (const m of messages) {
    const p = P(m.who);
    p.msgCount++; p.charCount += m.text.length;
    p.first ??= m.t; p.last = m.t;
    if (m.kind === 'text') countWords(p.words, m.text);
  }
  for (const t of turns) { const p = P(t.who); p.turns++; if (t.who !== me && t.question) p.questions++; }

  for (const ex of exchanges) {
    const byWho = new Map();
    for (const t of ex.prompts) { if (!byWho.has(t.who)) byWho.set(t.who, []); byWho.get(t.who).push(t); }
    for (const [who, ts] of byWho) {
      const p = P(who);
      p.items.push({ type: 'reply', time: ts[0].start, said: ts, reply: ex.reply, delay: ex.reply.start - ts[ts.length - 1].end, context: ex.prompts.length > ts.length ? ex.prompts : null });
      p.delays.push(ex.reply.start - ts[ts.length - 1].end);
      p.answered += ts.filter(t => t.question).length;
    }
  }
  for (const ini of initiated) {
    const whos = [...new Set(ini.responses.map(r => r.who))];
    for (const who of whos) P(who).items.push({ type: 'initiated', time: ini.turn.start, mine: ini.turn, responses: ini.responses.filter(r => r.who === who) });
  }
  for (const t of unanswered) P(t.who).unanswered.push(t);
  for (const p of people.values()) {
    p.items.sort((a, b) => a.time - b.time);
    p.keywords = topWords(p.words, 12);
    p.medianDelay = median(p.delays);
    delete p.words;
  }

  const meP = people.get(me);
  const others = [...people.values()].filter(p => p.name !== me).sort((a, b) => b.msgCount - a.msgCount);
  return {
    me, turns, exchanges, initiated, unanswered, people: others, mine: meP,
    stats: {
      total: messages.length,
      mine: meP?.msgCount || 0,
      participants: people.size,
      from: messages[0]?.t, to: messages[messages.length - 1]?.t,
      exchanges: exchanges.length,
      unansweredQuestions: unanswered.filter(t => t.question).length,
      medianDelay: median(exchanges.map(e => e.delay)),
    },
  };
}

// 간단한 한국어 키워드 추출 (조사 떼고 빈도 계산)
const STOP = new Set('그리고 그래서 그러면 그런데 근데 하지만 그냥 진짜 정말 너무 아주 많이 조금 이제 지금 오늘 내일 어제 우리 저희 제가 내가 나는 저는 너는 이거 그거 저거 이건 그건 여기 거기 저기 이렇게 그렇게 어떻게 하는 했는데 하고 해서 했어 했어요 있어 있어요 없어 없어요 같아 같아요 같은 있는 없는 그럼 네네 넵 응응 ㅇㅇ 아니 아니요 감사합니다 감사해요 고마워 수고하셨습니다 사진 이모티콘 동영상 삭제된 메시지입니다 될까 될까요 있을까 있을까요 할까요 하겠습니다 드립니다 드려요 합니다 ㅋㅋ ㅋㅋㅋ ㅎㅎ ㅎㅎㅎ ㅠㅠ ㅜㅜ 혹시 일단 다시 계속 정도 하나 한번 많이 좀 더 잘 또 다 안 못 왜 뭐 거 것 수 등 때 the and you for that this with are is'.split(' '));
const JOSA = /(으로|에서|에게|한테|까지|부터|처럼|보다|이랑|하고|이나|이며|이고|은|는|이|가|을|를|에|의|도|만|와|과|랑|로|요)$/;
function countWords(map, text) {
  for (let w of text.toLowerCase().match(/[가-힣a-z0-9]{2,}/g) || []) {
    if (/^\d+$/.test(w) || /^[ㅋㅎㅠㅜ]+$/.test(w)) continue;
    const s = w.replace(JOSA, '');
    if (s.length >= 2) w = s;
    if (STOP.has(w)) continue;
    map.set(w, (map.get(w) || 0) + 1);
  }
}
const topWords = (map, n) => [...map].filter(([, c]) => c >= 2).sort((a, b) => b[1] - a[1]).slice(0, n);
function median(a) { if (!a.length) return null; const s = [...a].sort((x, y) => x - y); return s[s.length >> 1]; }

// ───────────────────────── 유틸 ─────────────────────────

export function guessMe(parsed) {
  const counts = new Map();
  for (const m of parsed.messages) counts.set(m.who, (counts.get(m.who) || 0) + 1);
  const names = [...counts.keys()];
  // "홍길동 님과 카카오톡 대화" → 1:1 대화라면 홍길동이 아닌 쪽이 나
  if (parsed.title && names.length === 2 && names.includes(parsed.title)) return names.find(n => n !== parsed.title);
  return [...counts].sort((a, b) => b[1] - a[1])[0]?.[0];
}

export function fmtDelay(ms) {
  if (ms == null) return '-';
  const m = Math.round(ms / 60000);
  if (m < 1) return '1분 이내';
  if (m < 60) return `${m}분`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 ${m % 60 ? (m % 60) + '분' : ''}`.trim();
  return `${Math.floor(h / 24)}일 ${h % 24 ? (h % 24) + '시간' : ''}`.trim();
}

const pad = n => String(n).padStart(2, '0');
export const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const fmtDT = d => `${fmtDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;

/** 분석 결과를 Markdown 보고서로 */
export function toMarkdown(a, title = '카카오톡 대화') {
  const L = [];
  const q = s => s.split('\n').map(x => '> ' + x).join('\n');
  L.push(`# ${title} 정리`, '');
  L.push(`- 기간: ${fmtDT(a.stats.from)} ~ ${fmtDT(a.stats.to)}`);
  L.push(`- 전체 메시지 ${a.stats.total}개 (내 메시지 ${a.stats.mine}개), 참여자 ${a.stats.participants}명`);
  L.push(`- 내가 답한 대화 ${a.stats.exchanges}건, 답 안 한 질문 ${a.stats.unansweredQuestions}건, 보통 답장 시간 ${fmtDelay(a.stats.medianDelay)}`, '');
  for (const p of a.people) {
    L.push(`## ${p.name}`, '');
    L.push(`메시지 ${p.msgCount}개 · 질문 ${p.questions}개 · 보통 답장 시간 ${fmtDelay(p.medianDelay)}`);
    if (p.keywords.length) L.push(`자주 나온 말: ${p.keywords.map(([w]) => w).join(', ')}`);
    L.push('');
    for (const it of p.items) {
      if (it.type === 'reply') {
        L.push(`### ${fmtDT(it.time)}${it.said.some(t => t.question) ? ' ❓' : ''}`);
        L.push(`**${p.name}**`, q(it.said.map(t => t.text).join('\n')), '');
        L.push(`**나의 답** (${fmtDelay(it.delay)} 후)`, q(it.reply.text), '');
      } else {
        L.push(`### ${fmtDT(it.time)} · 내가 먼저`);
        L.push(`**나**`, q(it.mine.text), '');
        L.push(`**${p.name}의 반응**`, q(it.responses.map(t => t.text).join('\n')), '');
      }
    }
    const open = p.unanswered.filter(t => t.question);
    if (open.length) {
      L.push(`### 아직 답하지 않은 질문`);
      for (const t of open) L.push(`- ${fmtDT(t.start)}${t.question ? ' ❓' : ''} ${t.text.replace(/\n/g, ' / ')}`);
      L.push('');
    }
  }
  return L.join('\n');
}

/** 대화→답장 목록을 CSV로 */
export function toCSV(a) {
  const esc = s => `"${String(s).replace(/"/g, '""')}"`;
  const rows = [['구분', '시각', '상대', '상대가 한 말', '질문여부', '나의 답', '답장 시각', '걸린 시간']];
  for (const p of a.people) {
    for (const it of p.items) {
      if (it.type === 'reply') rows.push(['답장', fmtDT(it.time), p.name, it.said.map(t => t.text).join('\n'), it.said.some(t => t.question) ? 'Y' : '', it.reply.text, fmtDT(it.reply.start), fmtDelay(it.delay)]);
      else rows.push(['내가 먼저', fmtDT(it.time), p.name, it.responses.map(t => t.text).join('\n'), '', it.mine.text, fmtDT(it.mine.start), '']);
    }
    for (const t of p.unanswered) rows.push(['미응답', fmtDT(t.start), p.name, t.text, t.question ? 'Y' : '', '', '', '']);
  }
  return '﻿' + rows.map(r => r.map(esc).join(',')).join('\r\n');
}

/** AI 요약용으로 대화를 압축한 텍스트 */
export function toTranscript(messages, me) {
  return messages.map(m => `[${fmtDT(m.t)}] ${m.who === me ? `${m.who}(나)` : m.who}: ${m.kind === 'media' ? `(${m.text})` : m.text}`).join('\n');
}
