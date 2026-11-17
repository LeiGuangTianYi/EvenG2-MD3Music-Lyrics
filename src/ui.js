/**
 * 手机端可见界面。
 *
 * 插件本身在眼镜上是「背景层应用」，手机这一页原本可以是空白的；但空白页
 * 一旦出问题就完全无法判断卡在哪一步（Even 的 WebView 里看不到 console）。
 * 所以这里把「眼镜正在显示什么」原样画出来，并把桥接状态、报错、日志都写在
 * 页面上：既满足「手机上看到眼镜状态」的诉求，也让每次失败都有可读的原因。
 */

const MAX_LOG_LINES = 12;

export function createUi(doc = typeof document === 'undefined' ? null : document) {
  if (!doc) return inertUi();

  const el = {
    prev: doc.getElementById('g-prev'),
    cur: doc.getElementById('g-cur'),
    next: doc.getElementById('g-next'),
    empty: doc.getElementById('g-empty'),
    bridge: doc.getElementById('s-bridge'),
    song: doc.getElementById('s-song'),
    play: doc.getElementById('s-play'),
    line: doc.getElementById('s-line'),
    glasses: doc.getElementById('s-glasses'),
    log: doc.getElementById('log'),
  };

  if (!el.cur) return inertUi();

  const lines = [];

  function setText(node, text) {
    if (node && node.textContent !== text) node.textContent = text;
  }

  function setTone(node, tone) {
    if (!node) return;
    node.classList.remove('ok', 'bad', 'warn');
    if (tone) node.classList.add(tone);
  }

  return {
    log(message) {
      const stamp = new Date().toISOString().slice(11, 19);
      lines.push(`${stamp} ${message}`);
      while (lines.length > MAX_LOG_LINES) lines.shift();
      if (el.log) el.log.textContent = lines.join('\n');
    },
    setLines(view) {
      const empty = !view || !view.current;
      setText(el.prev, view?.previous || '');
      setText(el.cur, view?.current || '');
      setText(el.next, view?.next || '');
      if (el.empty) el.empty.style.display = empty ? 'flex' : 'none';
    },
    setBridge(state, detail) {
      const tones = { ok: 'ok', warn: 'warn', bad: 'bad', idle: null };
      setText(el.bridge, detail);
      setTone(el.bridge, tones[state] ?? null);
    },
    setSong(title, artist) {
      setText(el.song, title ? `${title}${artist ? ' — ' + artist : ''}` : '—');
    },
    setPlayback(isPlaying, positionMs, durationMs) {
      if (positionMs == null) {
        setText(el.play, '—');
        return;
      }
      const arrow = isPlaying ? '▶ 播放中' : '⏸ 已暂停';
      setText(el.play, `${arrow} · ${fmtTime(positionMs)} / ${fmtTime(durationMs)}`);
    },
    setLineProgress(index, total) {
      setText(el.line, index && total ? `第 ${index} / ${total} 句` : '—');
    },
    setGlasses(state, detail) {
      const tones = { open: 'ok', closed: 'warn', error: 'bad', idle: null };
      setText(el.glasses, detail);
      setTone(el.glasses, tones[state] ?? null);
    },
  };
}

function inertUi() {
  const noop = () => {};
  return {
    log: noop,
    setLines: noop,
    setBridge: noop,
    setSong: noop,
    setPlayback: noop,
    setLineProgress: noop,
    setGlasses: noop,
  };
}

function fmtTime(ms) {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  const total = Math.floor(ms / 1000);
  const mm = Math.floor(total / 60);
  const ss = total % 60;
  return `${mm}:${String(ss).padStart(2, '0')}`;
}
