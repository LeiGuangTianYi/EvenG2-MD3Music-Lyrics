import test from 'node:test';
import assert from 'node:assert/strict';
import { createLyricsApp } from '../src/app.js';

function fakeRenderer() {
  const state = { open: false, updates: [], closes: 0 };
  return {
    state,
    get isOpen() {
      return state.open;
    },
    async update(view) {
      state.open = true;
      state.updates.push(view);
      return true;
    },
    async close() {
      state.open = false;
      state.closes += 1;
      return true;
    },
    reset() {},
    markClosed() {
      state.open = false;
    },
  };
}

const lines = [
  { b: 0, e: 1000, t: '第一句' },
  { b: 1000, e: 2000, t: '第二句' },
  { b: 2000, e: 3000, t: '第三句' },
];

/**
 * Mirrors the real bridge: every poll recomputes the position from the last
 * playback anchor, so the position moves forward while the song is playing.
 */
function harness() {
  let now = 0;
  const server = { playing: false, basePosition: 0, baseAt: 0, fail: false, lines };
  const renderer = fakeRenderer();
  const app = createLyricsApp({
    renderer,
    now: () => now,
    logger: { warn() {}, error() {} },
    async readLyrics() {
      if (server.fail) throw new Error('offline');
      const positionMs = server.playing
        ? server.basePosition + (now - server.baseAt)
        : server.basePosition;
      return { isPlaying: server.playing, positionMs, lines: server.lines };
    },
  });
  return {
    app,
    renderer,
    server,
    play(position = 0) {
      server.playing = true;
      server.basePosition = position;
      server.baseAt = now;
    },
    pause() {
      server.playing = false;
      server.basePosition = server.basePosition + (now - server.baseAt);
    },
    advance(ms) {
      now += ms;
    },
  };
}

test('playing opens the page and follows the line changes', async () => {
  const { app, renderer, play, advance } = harness();
  play(0);

  await app.tick();
  assert.equal(renderer.isOpen, true);
  assert.equal(renderer.state.updates.at(-1).current, '第一句');

  advance(1100);
  await app.tick();
  assert.equal(renderer.state.updates.at(-1).current, '第二句');

  advance(1000);
  await app.tick();
  assert.equal(renderer.state.updates.at(-1).current, '第三句');
});

test('pause keeps the page for 10s and then closes it once', async () => {
  const { app, renderer, play, pause, advance } = harness();
  play(0);
  await app.tick();
  assert.equal(renderer.isOpen, true);

  advance(100);
  pause();
  await app.tick();
  assert.equal(renderer.isOpen, true);

  advance(9000);
  await app.tick();
  assert.equal(renderer.isOpen, true);

  advance(1000);
  await app.tick();
  assert.equal(renderer.isOpen, false);
  assert.equal(renderer.state.closes, 1);

  advance(5000);
  await app.tick();
  assert.equal(renderer.state.closes, 1, 'already closed pages stay closed');
});

test('resume reopens immediately', async () => {
  const { app, renderer, play, pause, advance } = harness();
  play(0);
  await app.tick();

  advance(100);
  pause();
  await app.tick();
  advance(10_000);
  await app.tick();
  assert.equal(renderer.isOpen, false);

  play(2600);
  await app.tick();
  assert.equal(renderer.isOpen, true);
  assert.equal(renderer.state.updates.at(-1).current, '第三句');
});

test('an unreachable bridge behaves like a pause and never throws', async () => {
  const { app, renderer, server, play, advance } = harness();
  play(0);
  await app.tick();
  assert.equal(renderer.isOpen, true);

  advance(100);
  server.fail = true;
  await app.tick();
  assert.equal(renderer.isOpen, true);

  advance(10_000);
  await app.tick();
  assert.equal(renderer.isOpen, false);
});

test('a song without lyrics still shows the waiting placeholder', async () => {
  const { app, renderer, server, play } = harness();
  server.lines = [];
  play(0);
  await app.tick();
  assert.equal(renderer.isOpen, true);
  assert.equal(renderer.state.updates.at(-1).current, '等待 MD3Music 歌词');
});

test('leaving the page does not fight the user while the same song keeps playing', async () => {
  const { app, renderer, play, advance } = harness();
  play(0);
  await app.tick();
  assert.equal(renderer.isOpen, true);

  renderer.markClosed();       // glasses sent FOREGROUND_EXIT
  app.suppressAutoShow();

  advance(500);
  await app.tick();
  assert.equal(renderer.isOpen, false, 'must not reopen and steal the foreground');
  assert.equal(renderer.state.updates.length, 1);

  advance(500);
  await app.tick();
  assert.equal(renderer.isOpen, false, 'still suppressed on later polls');
});

test('a tap on the glasses repaints the suppressed page immediately', async () => {
  const { app, renderer, play, advance } = harness();
  play(0);
  await app.tick();
  renderer.markClosed();
  app.suppressAutoShow();
  await app.tick();
  assert.equal(renderer.isOpen, false);

  app.onGlassesEvent();
  advance(10);
  await app.tick();
  assert.equal(renderer.isOpen, true);
});

test('resuming or switching songs brings the suppressed page back', async () => {
  const { app, renderer, play, pause, advance } = harness();
  play(0);
  await app.tick();
  renderer.markClosed();
  app.suppressAutoShow();
  await app.tick();
  assert.equal(renderer.isOpen, false);

  // Next song: different title + different first line.
  advance(100);
  pause();
  await app.tick();
  play(0);
  await app.tick();
  assert.equal(renderer.isOpen, true, 'a new playback session reopens the page');
});
