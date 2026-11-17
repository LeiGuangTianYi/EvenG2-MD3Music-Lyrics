import { waitForEvenAppBridge } from '@evenrealities/even_hub_sdk';
import { createBridgeClient } from './bridge-client.js';
import { createRenderer } from './renderer.js';
import { createLyricsApp } from './app.js';
import { createReporter } from './diag.js';
import { createUi } from './ui.js';

const POLL_MS = 250;
/** OsEventTypeList values that mean "the page is no longer on screen". */
const PAGE_GONE_EVENTS = new Set([5, 6, 7]);
const BRIDGE_TIMEOUT_MS = 4000;

const ui = createUi();
const report = createReporter();

function say(message) {
  ui.log(message);
  report(message);
}

function describeBridge(bridge) {
  const names = [];
  for (const key of ['createStartUpPageContainer', 'textContainerUpgrade', 'shutDownPageContainer', 'onEvenHubEvent']) {
    names.push(`${key}:${typeof bridge[key]}`);
  }
  return names.join(' ');
}

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时 ${ms}ms`)), ms)),
  ]);
}

function describeError(error) {
  if (!error) return '未知错误';
  return error.message || String(error);
}

async function boot() {
  // 先把裸 DOM 的状态写出来：即使 SDK 初始化失败，这一页也不是空白。
  ui.setBridge('warn', '正在等待 Even App 桥接…');
  ui.setGlasses('idle', '未创建');

  window.addEventListener('error', (event) => {
    say(`window.error ${event?.message} @${event?.filename}:${event?.lineno}`);
  });
  window.addEventListener('unhandledrejection', (event) => {
    say(`unhandledrejection ${event?.reason && (event.reason.stack || event.reason.message || event.reason)}`);
  });

  say(`boot origin=${location.origin || '(null)'} href=${location.href.slice(0, 120)}`);
  say(`ua ${navigator.userAgent.slice(0, 70)}`);

  let bridge;
  try {
    bridge = await withTimeout(waitForEvenAppBridge(), BRIDGE_TIMEOUT_MS, 'waitForEvenAppBridge');
    say(`bridge ready ${describeBridge(bridge)}`);
  } catch (error) {
    ui.setBridge('bad', `桥接不可用：${describeError(error)}`);
    say(`bridge missing ${describeError(error)}`);
    return;
  }

  const renderer = createRenderer(bridge, {
    logger: {
      warn: (...args) => {
        say(`warn ${args.map(String).join(' ')}`);
        ui.setGlasses('error', args.map(String).join(' ').slice(0, 44));
      },
      info: (...args) => say(`info ${args.map(String).join(' ')}`),
      log: () => {},
    },
  });

  const app = createLyricsApp({
    renderer,
    readLyrics: createBridgeClient(),
    observe: (snapshot) => {
      if (snapshot.error) {
        ui.setBridge('bad', `读歌词失败：${describeError(snapshot.error)}`);
        ui.setPlayback(null);
        return;
      }
      const { payload, view } = snapshot;
      const total = Array.isArray(payload?.lines) ? payload.lines.length : 0;
      ui.setBridge('ok', `已连通，歌词 ${total} 句`);
      ui.setSong(payload?.title, payload?.artist);
      ui.setPlayback(!!payload?.isPlaying, payload?.positionMs, payload?.durationMs);
      ui.setLineProgress(view?.index >= 0 ? view.index + 1 : null, total);
      ui.setLines(view);
      ui.setGlasses(
        renderer.isOpen ? 'open' : 'closed',
        renderer.isOpen
          ? `已推送到眼镜（${renderer.shapeName}）`
          : '未推送到眼镜（未播放或已收起）',
      );
    },
    logger: {
      warn: (...args) => say(`app.warn ${args.map(String).join(' ')}`),
    },
  });

  bridge.onEvenHubEvent((event) => {
    const sys = event?.sysEvent;
    if (sys) {
      const type = Number(sys.eventType);
      say(`sysEvent ${type}`);
      if (type === 5) {
        app.onForegroundExit();
      } else if (type === 6 || type === 7) {
        app.suppressAutoShow();
      } else {
        app.onGlassesEvent();
      }
      return;
    }
    if (event?.textEvent) app.onGlassesEvent();
  });

  bridge.onLaunchSource?.((source) => {
    // 启动来源只是「App 菜单里打开了这个插件」，并不代表眼镜页被销毁。
    // 这里绝不能标记页面已关闭，否则会立刻重复创建，而宿主对已存在的页面一律回
    // invalid(1)，形成「创建→被拒→再创建」的死循环。
    say(`launchSource ${source}`);
    app.onGlassesEvent();
  });

  let tickErrors = 0;
  const guardedTick = async () => {
    try {
      await app.tick();
    } catch (error) {
      if (tickErrors < 20) {
        tickErrors += 1;
        say(`tick failed ${describeError(error)}`);
      }
    }
  };

  await guardedTick();
  say('first tick done');
  setInterval(guardedTick, POLL_MS);
}

boot().catch((error) => {
  ui.setBridge('bad', `启动失败：${describeError(error)}`);
  say(`boot failed ${error && (error.stack || error.message || error)}`);
  console.error('[g2-lyrics] boot failed', error);
});
