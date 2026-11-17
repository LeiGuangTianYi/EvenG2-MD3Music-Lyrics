import test from 'node:test';
import assert from 'node:assert/strict';
import { buildView, clampLine, findLineIndex, normalizeLines, positionAt, textUnits } from '../src/core.js';

test('width accounting treats CJK as two columns', () => {
  assert.equal(textUnits('abc'), 3);
  assert.equal(textUnits('中文'), 4);
  assert.equal(textUnits('中a'), 3);
});

test('clampLine keeps short lines untouched and trims long ones to one container', () => {
  assert.equal(clampLine(''), '—');
  assert.equal(clampLine('   '), '—');
  assert.equal(clampLine('a'.repeat(44)), 'a'.repeat(44));

  const longAscii = clampLine('a'.repeat(60));
  assert.ok(longAscii.endsWith('…'));
  assert.ok(textUnits(longAscii) <= 44);

  const longCjk = clampLine('中文歌词'.repeat(20), 44);
  assert.ok(longCjk.endsWith('…'));
  assert.ok(textUnits(longCjk) <= 44);
});

test('normalizeLines sorts, drops empty text and fixes broken end times', () => {
  const lines = normalizeLines([
    { begin: 2000, end: 3000, text: '第三句' },
    { begin: 0, end: 1000, text: '第一句' },
    { begin: 1000, end: 1000, text: '  ' },
    { begin: 500, end: 900, text: '  第二句  ' },
  ]);
  assert.deepEqual(
    lines.map((line) => line.text),
    ['第一句', '第二句', '第三句'],
  );
  assert.equal(lines[0].begin, 0);
  assert.equal(lines[1].begin, 500);
  assert.equal(lines[2].end, 3000);
  assert.equal(normalizeLines([{ begin: 100, text: '无结尾' }])[0].end, 101);
});

test('findLineIndex picks the covering line, including boundaries', () => {
  const lines = normalizeLines([
    { begin: 0, end: 1000, text: 'A' },
    { begin: 1000, end: 2000, text: 'B' },
    { begin: 2000, end: 3000, text: 'C' },
  ]);
  assert.equal(findLineIndex(lines, -50), 0);
  assert.equal(findLineIndex(lines, 0), 0);
  assert.equal(findLineIndex(lines, 999), 0);
  assert.equal(findLineIndex(lines, 1000), 1);
  assert.equal(findLineIndex(lines, 2999), 2);
  assert.equal(findLineIndex(lines, 9000), 2);
  assert.equal(findLineIndex([], 0), -1);
});

test('buildView exposes previous / current / next around the edges', () => {
  const lines = normalizeLines([
    { begin: 0, end: 1000, text: 'A' },
    { begin: 1000, end: 2000, text: 'B' },
    { begin: 2000, end: 3000, text: 'C' },
  ]);
  assert.deepEqual(buildView(lines, 100), { index: 0, previous: '', current: 'A', next: 'B' });
  assert.deepEqual(buildView(lines, 1200), { index: 1, previous: 'A', current: 'B', next: 'C' });
  assert.deepEqual(buildView(lines, 2800), { index: 2, previous: 'B', current: 'C', next: '' });
  assert.equal(buildView([], 0).current, '等待 MD3Music 歌词');
});

test('positionAt extrapolates while playing and freezes while paused', () => {
  const playing = { isPlaying: true, positionMs: 1000 };
  assert.equal(positionAt(playing, 0, 260), 1260);
  assert.equal(positionAt({ isPlaying: false, positionMs: 1000 }, 0, 5000), 1000);
  assert.equal(positionAt(undefined, 0, 5000), 0);
});
