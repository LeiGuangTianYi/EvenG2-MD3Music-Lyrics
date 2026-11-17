import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import { createBridgeClient } from '../src/bridge-client.js';
import { createLyricsApp } from '../src/app.js';
import { buildView, normalizeLines } from '../src/core.js';

/**
 * End-to-end check of the real transport: an HTTP server that answers exactly like
 * Md3LyricsHook (same JSON field names, same headers), the real fetch client, and the
 * real polling state machine. If the Java side and the plugin ever drift apart, this
 * fails before anything reaches the glasses.
 */
function startBridge(handler) {
  const server = http.createServer((req, res) => {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
      });
      res.end();
      return;
    }
    const payload = handler(req);
    res.writeHead(payload.status ?? 200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
    res.end(JSON.stringify(payload.body));
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ url: `http://127.0.0.1:${port}/v1/lyrics`, close: () => server.close() });
    });
  });
}

function snapshot({ isPlaying = true, positionMs = 0 } = {}) {
  return {
    revision: 1,
    isPlaying,
    positionMs,
    durationMs: 60_000,
    title: '晴天',
    artist: '周杰伦',
    lines: [
      { b: 0, e: 4000, t: '故事的小黄花' },
      { b: 4000, e: 8000, t: '从出生那年就飘着' },
      { b: 8000, e: 12000, t: '童年的荡秋千' },
    ],
  };
}

function fakeRenderer() {
  const calls = { update: [], close: 0, reset: 0 };
  let open = false;
  return {
    calls,
    get isOpen() {
      return open;
    },
    async update(view) {
      calls.update.push(view);
      open = true;
    },
    async close() {
      calls.close += 1;
      open = false;
    },
    reset() {
      calls.reset += 1;
    },
  };
}

test('real transport: playing payload renders the three rows', async () => {
  const bridge = await startBridge(() => ({ body: snapshot({ positionMs: 5000 }) }));
  try {
    const readLyrics = createBridgeClient({ url: bridge.url });
    const payload = await readLyrics();
    assert.equal(payload.isPlaying, true);
    assert.equal(payload.lines.length, 3);

    const view = buildView(normalizeLines(payload.lines), payload.positionMs);
    assert.deepEqual(view, {
      index: 1,
      previous: '故事的小黄花',
      current: '从出生那年就飘着',
      next: '童年的荡秋千',
    });

    const renderer = fakeRenderer();
    const app = createLyricsApp({ renderer, readLyrics });
    await app.tick();
    assert.equal(renderer.calls.update.length, 1);
    assert.equal(renderer.calls.update[0].current, '从出生那年就飘着');
  } finally {
    bridge.close();
  }
});

test('real transport: paused page closes after the hide delay and reopens on resume', async () => {
  let playing = true;
  const bridge = await startBridge(() => ({ body: snapshot({ isPlaying: playing, positionMs: 5000 }) }));
  try {
    let clock = 1_000;
    const renderer = fakeRenderer();
    const app = createLyricsApp({
      renderer,
      readLyrics: createBridgeClient({ url: bridge.url }),
      now: () => clock,
      hideDelayMs: 10_000,
    });

    await app.tick();
    assert.equal(renderer.calls.update.length, 1);

    playing = false;
    clock += 1_000;
    await app.tick(); // starts the 10 s countdown
    assert.equal(renderer.calls.close, 0);

    clock += 10_000;
    await app.tick(); // deadline reached
    assert.equal(renderer.calls.close, 1);

    playing = true;
    clock += 1_000;
    await app.tick(); // resume repaints immediately
    assert.equal(renderer.calls.update.length, 2);
  } finally {
    bridge.close();
  }
});

test('real transport: a dead bridge behaves exactly like a pause', async () => {
  const bridge = await startBridge(() => ({ status: 500, body: { error: 'boom' } }));
  try {
    let clock = 1_000;
    const renderer = fakeRenderer();
    const app = createLyricsApp({
      renderer,
      readLyrics: createBridgeClient({ url: bridge.url }),
      now: () => clock,
      hideDelayMs: 10_000,
    });
    // Pretend a page is already up, then let the bridge fail twice.
    await renderer.update({ current: 'x' });
    await app.tick();
    clock += 10_000;
    await app.tick();
    assert.equal(renderer.calls.close, 1);
  } finally {
    bridge.close();
  }
});

test('real transport: unroutable bridge rejects instead of hanging', async () => {
  const readLyrics = createBridgeClient({ url: 'http://127.0.0.1:1/v1/lyrics', timeoutMs: 300 });
  await assert.rejects(readLyrics);
});
