import test from 'node:test';
import assert from 'node:assert/strict';
import { formatChartAxisTime, formatChartTime } from '../src/chart-time.js';

test('formats chart timestamps in Asia/Seoul instead of UTC', () => {
  const timestamp = Date.parse('2026-09-22T09:00:00Z') / 1000;

  assert.match(formatChartAxisTime(timestamp), /09\. 22\./);
  assert.match(formatChartAxisTime(timestamp), /18:00/);
  assert.match(formatChartTime(timestamp), /2026\. 09\. 22\./);
  assert.match(formatChartTime(timestamp), /18:00/);
});

test('rejects unsupported chart time values', () => {
  assert.throws(() => formatChartTime({ year: 2026, month: 9, day: 22 }), /Unix timestamp/);
});
