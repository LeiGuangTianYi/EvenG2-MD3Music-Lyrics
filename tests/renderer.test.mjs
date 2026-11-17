import test from 'node:test';
import assert from 'node:assert/strict';
import { BRIGHT, DIM, createRenderer, pageObject } from '../src/renderer.js';

function fakeBridge() {
  const calls = [];
  return {
    calls,
    async createStartUpPageContainer(page) {
      calls.push({ type: 'create', page });
      return 0;
    },
    async textContainerUpgrade(patch) {
      calls.push({ type: 'upgrade', patch });
      return true;
    },
    async shutDownPageContainer(mode) {
      calls.push({ type: 'shutdown', mode });
      return true;
    },
  };
}

const silent = { warn() {}, error() {} };

test('pageObject builds three rows in the host-validated shape', () => {
  const page = pageObject({ previous: '上一句', current: '本句', next: '下一句' });
  assert.equal(page.containerTotalNum, 3);
  assert.equal(page.textObject.length, 3);
  // Even App 2.4.0 only accepts containers that carry the full border/padding set.
  for (const item of page.textObject) {
    assert.equal(item.borderWidth, 0);
    assert.equal(item.borderColor, 15);
    assert.equal(item.borderRadius, 0);
    assert.equal(typeof item.paddingLength, 'number');
    assert.ok(!('zOrderIndex' in item));
  }
  assert.equal(page.textObject[1].content, '本句');
  assert.equal(page.textObject[1].isEventCapture, 1);
  assert.equal(page.textObject[2].isEventCapture, 0);
  assert.deepEqual(
    page.textObject.map((item) => item.containerID),
    [1, 2, 3],
  );
});

test('opens once, paints brightness, then patches only the rows that changed', async () => {
  const bridge = fakeBridge();
  const renderer = createRenderer(bridge, { logger: silent });

  await renderer.update({ previous: 'A', current: 'B', next: 'C' });
  assert.equal(bridge.calls[0].type, 'create');
  // 创建之后立刻把三行各刷一次，带上 4/2 的亮度，中间句才比上下句亮。
  const paints = bridge.calls.slice(1);
  assert.equal(paints.length, 3);
  assert.deepEqual(
    paints.map((call) => call.patch.containerID),
    [1, 2, 3],
  );
  assert.deepEqual(
    paints.map((call) => call.patch.textColor),
    [DIM, BRIGHT, DIM],
  );
  assert.equal(renderer.isOpen, true);

  bridge.calls.length = 0;
  await renderer.update({ previous: 'A', current: 'B', next: 'C' });
  assert.equal(bridge.calls.length, 0, 'no bluetooth traffic when nothing changed');

  await renderer.update({ previous: 'A', current: 'B2', next: 'C' });
  assert.equal(bridge.calls.length, 1);
  assert.equal(bridge.calls[0].type, 'upgrade');
  assert.equal(bridge.calls[0].patch.containerID, 2);
  assert.equal(bridge.calls[0].patch.content, 'B2');
  assert.equal(bridge.calls[0].patch.textColor, 4);
});

test('falls back to a placeholder when a row is empty', async () => {
  const bridge = fakeBridge();
  const renderer = createRenderer(bridge, { logger: silent });
  await renderer.update({ previous: '', current: 'B', next: '' });
  const page = bridge.calls[0].page;
  assert.equal(page.textObject[0].content, '—');
  assert.equal(page.textObject[2].content, '—');
});

test('close shuts the page down and the next update rebuilds it', async () => {
  const bridge = fakeBridge();
  const renderer = createRenderer(bridge, { logger: silent });
  await renderer.update({ previous: 'A', current: 'B', next: 'C' });
  await renderer.close();
  assert.equal(renderer.isOpen, false);
  const shutdown = bridge.calls.find((call) => call.type === 'shutdown');
  assert.equal(shutdown.mode, 0);

  await renderer.update({ previous: 'A', current: 'B', next: 'C' });
  assert.equal(bridge.calls.filter((call) => call.type === 'create').length, 2);
});

test('markClosed forgets the page without another bluetooth call', async () => {
  const bridge = fakeBridge();
  const renderer = createRenderer(bridge, { logger: silent });
  await renderer.update({ previous: 'A', current: 'B', next: 'C' });
  renderer.markClosed();
  assert.equal(renderer.isOpen, false);
  assert.equal(bridge.calls.filter((call) => call.type === 'shutdown').length, 0);
});
