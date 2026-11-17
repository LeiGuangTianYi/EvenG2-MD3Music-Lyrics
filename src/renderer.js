import { clampLine } from './core.js';

/** Brightness range on the G2 is 0..4 and the panels are monochrome. */
export const BRIGHT = 4;
export const DIM = 2;

/** 三行布局，画布 576×288。 */
export const LAYOUT = [
  { key: 'previous', id: 1, name: 'previous-line', x: 24, y: 52, width: 528, height: 44, color: DIM },
  { key: 'current', id: 2, name: 'current-line', x: 24, y: 118, width: 528, height: 60, color: BRIGHT },
  { key: 'next', id: 3, name: 'next-line', x: 24, y: 184, width: 528, height: 44, color: DIM },
];

/** 单容器兜底：整屏一行，只保证「歌词能看见」。 */
const FULL_SCREEN = [{ key: 'current', id: 1, name: 'current-line', x: 0, y: 0, width: 576, height: 288, color: BRIGHT }];

/** 单容器兜底：居中一行。 */
const ONE_ROW = [{ key: 'current', id: 1, name: 'current-line', x: 24, y: 118, width: 528, height: 60, color: BRIGHT }];

function buildText(row, content, extra) {
  const base = {
    xPosition: row.x,
    yPosition: row.y,
    width: row.width,
    height: row.height,
    containerID: row.id,
    containerName: row.name,
    content,
    isEventCapture: row.key === 'current' ? 1 : 0,
  };
  return { ...base, ...extra };
}

const withBorder = (extra) => ({ borderWidth: 0, borderColor: 15, borderRadius: 0, paddingLength: 8, ...extra });
const noExtras = {};

/**
 * Even App 2.4.0 的宿主校验比 SDK 自身严格：容器字段组合不对就返回
 * StartUpPageCreateResult.invalid(1)。不同版本的宿主接受的形状不一样，所以这里
 * 逐个候选去试，第一个被接受的形状即锁定并复用；全部失败时页面日志里能看到
 * 每一种候选的返回码。
 */
export const CANDIDATES = [
  {
    name: 'rows3+border',
    rows: LAYOUT,
    extra: () => withBorder(),
    color: false,
  },
  {
    name: 'rows3+border+z+color',
    rows: LAYOUT,
    extra: (row) => withBorder({ zOrderIndex: row.id, textColor: row.color }),
    color: true,
  },
  {
    name: 'rows3+plain',
    rows: LAYOUT,
    extra: () => noExtras,
    color: false,
  },
  {
    name: 'single-full',
    rows: FULL_SCREEN,
    extra: () => withBorder({ textColor: BRIGHT }),
    color: true,
  },
  {
    name: 'single-row',
    rows: ONE_ROW,
    extra: () => withBorder({ textColor: BRIGHT }),
    color: true,
  },
];

function payloadFor(candidate, view) {
  return {
    containerTotalNum: candidate.rows.length,
    textObject: candidate.rows.map((row) =>
      buildText(row, clampLine(view[row.key]), candidate.extra(row)),
    ),
  };
}

/** 兼容旧测试/外部调用：默认形状的页面对象。 */
export function pageObject(view) {
  return payloadFor(CANDIDATES[0], view);
}

function isSuccess(result) {
  return result === 0 || (typeof result === 'string' && result.toLowerCase() === 'success');
}

/**
 * Owns the glasses page: negotiates a page shape the host accepts, creates it on
 * demand, patches only the rows that changed (each patch is a Bluetooth round trip),
 * and tears it down when playback stops.
 */
export function createRenderer(bridge, { logger = console } = {}) {
  let pageOpen = false;
  let active = null; // the CANDIDATES entry the host accepted
  let nextRetryAt = 0;
  const shown = { previous: undefined, current: undefined, next: undefined };

  function activeRows() {
    return active?.rows ?? LAYOUT;
  }

  function markShown(view) {
    for (const row of activeRows()) shown[row.key] = clampLine(view[row.key]);
  }

  async function attempt(candidate, view) {
    const result = await bridge.createStartUpPageContainer(payloadFor(candidate, view));
    logger.info?.(`[g2-lyrics] shape ${candidate.name} -> ${result}`);
    if (isSuccess(result)) return true;
    logger.warn?.(`[g2-lyrics] shape ${candidate.name} rejected ${result}`);
    // 刻意不在这里 shutDownPageContainer：宿主对「已存在的页面」重创建也会回
    // invalid，盲目关页会把正在正常显示的那一页一起清掉。
    return false;
  }

  async function openPage(view) {
    if (Date.now() < nextRetryAt) return false;
    const order = active ? [active, ...CANDIDATES.filter((c) => c !== active)] : CANDIDATES;
    for (const candidate of order) {
      if (await attempt(candidate, view)) {
        active = candidate;
        pageOpen = true;
        // 不调用 markShown：让第一次 update 把三行都重新刷一遍，并带上各自的
        // 亮度（当前句 4、上下句 2）。创建时可选的 textColor 在部分宿主上会被忽略，
        // 升级阶段再明确给一次最稳。
        logger.info?.(`[g2-lyrics] page opened with ${candidate.name}`);
        return true;
      }
    }
    nextRetryAt = Date.now() + 4000;
    return false;
  }

  async function update(view) {
    if (!pageOpen && !(await openPage(view))) return false;
    const patches = [];
    for (const row of activeRows()) {
      const next = clampLine(view[row.key]);
      if (shown[row.key] === next) continue;
      patches.push(
        bridge.textContainerUpgrade({
          containerID: row.id,
          containerName: row.name,
          contentOffset: 0,
          contentLength: next.length,
          content: next,
          textColor: row.color,
        }),
      );
      shown[row.key] = next;
    }
    const results = await Promise.all(patches);
    const refused = results.filter((ok) => ok === false).length;
    if (refused) logger.warn?.(`[g2-lyrics] ${refused} upgrades refused`);
    return true;
  }

  async function close() {
    if (!pageOpen) return false;
    pageOpen = false;
    for (const row of activeRows()) shown[row.key] = undefined;
    try {
      await bridge.shutDownPageContainer(0);
    } catch (error) {
      logger.warn?.('[g2-lyrics] shutdown failed', error);
    }
    return true;
  }

  return {
    update,
    close,
    reset() {
      for (const row of activeRows()) shown[row.key] = undefined;
    },
    /** The OS tore the page down behind our back (user left via the glasses menu). */
    markClosed() {
      pageOpen = false;
      for (const row of activeRows()) shown[row.key] = undefined;
    },
    get isOpen() {
      return pageOpen;
    },
    get shapeName() {
      return active?.name ?? 'none';
    },
  };
}
