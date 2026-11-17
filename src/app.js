import { buildView, normalizeLines, positionAt } from './core.js';

/**
 * The polling state machine shared by the real app and the simulator tests.
 *
 * Rules from the brief: show the page as soon as MD3Music is playing, keep it
 * while paused for another 10 s, then close it; reopen immediately on resume.
 */
export function createLyricsApp({
  renderer,
  readLyrics,
  now = Date.now,
  logger = console,
  observe = () => {},
  hideDelayMs = 10_000,
}) {
  let pauseStartedAt = 0;
  let pending = false;
  let disposed = false;
  let suppressed = false;
  let reopenAt = 0;
  let recentExits = [];
  let lastSongKey = '';
  let wasPlaying = false;

  function songKey(payload) {
    const first = payload?.lines?.[0]?.b ?? payload?.lines?.[0]?.begin ?? '';
    return `${payload?.title ?? ''}|${payload?.artist ?? ''}|${first}|${payload?.lines?.length ?? 0}`;
  }

  async function hideIfPlaybackStopped(at) {
    if (!renderer.isOpen) {
      pauseStartedAt = 0;
      return;
    }
    if (!pauseStartedAt) {
      pauseStartedAt = at;
      return;
    }
    if (at - pauseStartedAt >= hideDelayMs) {
      await renderer.close();
      pauseStartedAt = 0;
    }
  }

  async function tick() {
    if (pending || disposed) return;
    if (reopenAt && now() < reopenAt) return;
    pending = true;
    const receivedAt = now();
    try {
      const payload = await readLyrics();
      if (payload?.isPlaying) {
        pauseStartedAt = 0;
        // The user left through the glasses menu: do not steal the foreground back
        // while the same song keeps playing. A new song, a resume after a pause, or
        // a tap on the glasses clears that suppression.
        const key = songKey(payload);
        if (suppressed && (!wasPlaying || key !== lastSongKey)) suppressed = false;
        lastSongKey = key;
        wasPlaying = true;
        const position = positionAt(payload, receivedAt, now());
        const view = buildView(normalizeLines(payload.lines), position);
        observe({ payload, view, position, suppressed });
        if (suppressed) return;
        await renderer.update(view);
      } else {
        wasPlaying = false;
        const view = buildView(normalizeLines(payload?.lines), Number(payload?.positionMs ?? 0));
        observe({ payload, view, position: Number(payload?.positionMs ?? 0), suppressed, paused: true });
        await hideIfPlaybackStopped(receivedAt);
      }
    } catch (error) {
      // MD3Music or the LSPosed module is not running; behave like a pause.
      wasPlaying = false;
      observe({ error });
      await hideIfPlaybackStopped(receivedAt);
    } finally {
      pending = false;
    }
  }

  function onGlassesEvent() {
    // A tap / re-entry after an accidental page exit should repaint the same lines.
    suppressed = false;
    reopenAt = 0;
    renderer.reset();
    void tick();
  }

  /**
   * 眼镜侧「退到后台」(FOREGROUND_EXIT)：常见的不是用户真想关掉歌词，而是宿主把
   * 非前台页面收掉了。这里先等 2.5 秒再自动重开，连续 3 次被收掉才认为用户是主动
   * 离开，于是停止自动弹出（下一次点按或换歌时再恢复）。
   */
  function onForegroundExit() {
    const at = now();
    recentExits = recentExits.filter((stamp) => at - stamp < 30_000);
    recentExits.push(at);
    renderer.markClosed();
    if (recentExits.length >= 3) {
      suppressed = true;
      reopenAt = 0;
      return;
    }
    reopenAt = at + 2500;
  }

  /** The OS took the page down (abnormal/system exit); stop auto-opening until asked. */
  function suppressAutoShow() {
    suppressed = true;
    reopenAt = 0;
    renderer.markClosed();
  }

  function dispose() {
    disposed = true;
  }

  return { tick, onGlassesEvent, onForegroundExit, suppressAutoShow, dispose };
}
