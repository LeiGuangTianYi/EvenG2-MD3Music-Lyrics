// Pure, dependency-free logic shared by the running app and the unit tests.

/** One text container is 528 px wide; at the G2 default font that is ~44 ASCII columns. */
export const LINE_UNITS = 44;

const WIDE_RANGES = [
  [0x1100, 0x115f],
  [0x2e80, 0x303e],
  [0x3041, 0x33ff],
  [0x3400, 0x4dbf],
  [0x4e00, 0x9fff],
  [0xa000, 0xa4cf],
  [0xac00, 0xd7a3],
  [0xf900, 0xfaff],
  [0xfe10, 0xfe19],
  [0xfe30, 0xfe6f],
  [0xff00, 0xff60],
  [0xffe0, 0xffe6],
  [0x1f300, 0x1f64f],
  [0x1f900, 0x1f9ff],
  [0x20000, 0x2fffd],
];

/** CJK and emoji take roughly two ASCII columns on the glasses. */
export function charUnits(ch) {
  const code = ch.codePointAt(0);
  for (const [lo, hi] of WIDE_RANGES) {
    if (code >= lo && code <= hi) return 2;
  }
  return 1;
}

export function textUnits(text) {
  let total = 0;
  for (const ch of text) total += charUnits(ch);
  return total;
}

/**
 * Trim one lyric line so it fits a single 528 px container.
 * Keeps the head of the line (that is what the listener needs) and marks the cut.
 */
export function clampLine(value, limit = LINE_UNITS) {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  if (!text) return '—';
  if (textUnits(text) <= limit) return text;

  let out = '';
  let used = 0;
  for (const ch of text) {
    const w = charUnits(ch);
    if (used + w > limit - 1) break;
    out += ch;
    used += w;
  }
  return `${out.trimEnd()}…`;
}

/** Keep only well-formed lines with a non-empty text, in play order. */
export function normalizeLines(raw) {
  if (!Array.isArray(raw)) return [];
  const lines = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const text = String(item.t ?? item.text ?? '').trim();
    if (!text) continue;
    const begin = Number(item.b ?? item.begin ?? 0);
    const end = Number(item.e ?? item.end ?? 0);
    if (!Number.isFinite(begin) || begin < 0) continue;
    lines.push({ begin, end: Number.isFinite(end) && end > begin ? end : begin + 1, text });
  }
  return lines.sort((a, b) => a.begin - b.begin);
}

/** Position of the song when this poll arrived. */
export function positionAt(payload, receivedAt, now) {
  const base = Number(payload?.positionMs ?? 0);
  if (!Number.isFinite(base) || base < 0) return 0;
  if (!payload?.isPlaying) return base;
  const drift = now - receivedAt;
  return base + (Number.isFinite(drift) && drift > 0 ? drift : 0);
}

/**
 * Index of the line that covers `position`.
 * Before the first line we stay on the first one so the display is never empty.
 */
export function findLineIndex(lines, position) {
  if (!lines.length) return -1;
  let lo = 0;
  let hi = lines.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].begin <= position) {
      found = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return found < 0 ? 0 : found;
}

/** The three rows the glasses show. */
export function buildView(lines, position) {
  const index = findLineIndex(lines, position);
  if (index < 0) {
    return { index: -1, previous: '', current: '等待 MD3Music 歌词', next: '' };
  }
  return {
    index,
    previous: index > 0 ? lines[index - 1].text : '',
    current: lines[index].text,
    next: index + 1 < lines.length ? lines[index + 1].text : '',
  };
}
