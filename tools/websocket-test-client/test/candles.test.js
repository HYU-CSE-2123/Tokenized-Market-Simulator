import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CandleHistoryCursor, CandleLoadBuffer, applyBufferedTicks, applyPriceTick, bucketTime,
  normalizeCandles, prependCandleHistory,
} from '../src/candles.js';

test('normalizes REST candles in chronological order', () => {
  const result = normalizeCandles([
    candle('2026-09-16T00:01:00Z', '102'),
    candle('2026-09-16T00:00:00Z', '100'),
  ]);
  assert.deepEqual(result.map(({ time }) => time), [1789516800, 1789516860]);
});

test('merges a tick into the current candle OHLCV', () => {
  const initial = normalizeCandles([candle('2026-09-16T00:00:00Z', '100')]);
  const result = applyPriceTick(initial, {
    price: '103', volume: '2.5', observedAt: '2026-09-16T00:00:30Z',
  }, '1m');
  assert.deepEqual(result.candles.at(-1), {
    time: 1789516800, open: 100, high: 103, low: 100, close: 103, volume: 3.5, closed: false,
  });
});

test('starts a new candle and ignores an older bucket', () => {
  const initial = normalizeCandles([candle('2026-09-16T00:00:00Z', '100')]);
  const advanced = applyPriceTick(initial, {
    price: '101', volume: '1', observedAt: '2026-09-16T00:01:02Z',
  }, '1m');
  assert.equal(advanced.candles.length, 2);
  assert.equal(advanced.candles.at(-1).open, 101);
  const ignored = applyPriceTick(advanced.candles, {
    price: '90', volume: '1', observedAt: '2026-09-15T23:59:59Z',
  }, '1m');
  assert.equal(ignored.changed, false);
  assert.strictEqual(ignored.candles, advanced.candles);
});

test('uses KST midnight for daily candle buckets', () => {
  assert.equal(bucketTime(new Date('2026-09-16T14:59:59Z'), '1d'), 1789484400);
  assert.equal(bucketTime(new Date('2026-09-16T15:00:00Z'), '1d'), 1789570800);
});

test('uses standard wall-clock boundaries for aggregated intraday candles', () => {
  const observedAt = new Date('2026-09-22T01:47:23Z'); // KST 10:47:23

  assert.equal(bucketTime(observedAt, '5m'), Date.parse('2026-09-22T01:45:00Z') / 1000);
  assert.equal(bucketTime(observedAt, '15m'), Date.parse('2026-09-22T01:45:00Z') / 1000);
  assert.equal(bucketTime(observedAt, '30m'), Date.parse('2026-09-22T01:30:00Z') / 1000);
  assert.equal(bucketTime(observedAt, '1h'), Date.parse('2026-09-22T01:00:00Z') / 1000);
});

test('replays ticks received during REST loading in observed order', () => {
  const restSnapshot = normalizeCandles([candle('2026-09-16T00:00:00Z', '100')]);
  const result = applyBufferedTicks(restSnapshot, [
    { price: '103', volume: '2', observedAt: '2026-09-16T00:00:40Z' },
    { price: '99', volume: '3', observedAt: '2026-09-16T00:00:20Z' },
    { price: '105', volume: '4', observedAt: '2026-09-16T00:01:01Z' },
  ], '1m');

  assert.deepEqual(result, [
    { time: 1789516800, open: 100, high: 103, low: 99, close: 103, volume: 6, closed: false },
    { time: 1789516860, open: 105, high: 105, low: 105, close: 105, volume: 4, closed: false },
  ]);
});

test('isolates buffered ticks when interval changes during an older request', () => {
  const buffer = new CandleLoadBuffer();
  const minuteLoad = buffer.begin('1m');
  buffer.buffer({ price: '101', volume: '1', observedAt: '2026-09-16T00:00:10Z' });
  const dailyLoad = buffer.begin('1d');
  buffer.buffer({ price: '200', volume: '2', observedAt: '2026-09-16T01:00:00Z' });

  assert.equal(buffer.resolve(minuteLoad, []), null);
  const daily = buffer.resolve(dailyLoad, []);
  assert.equal(daily.tickCount, 1);
  assert.equal(daily.candles[0].open, 200);
  assert.equal(daily.candles[0].time, 1789484400);
});

test('keeps reconnect ticks and rejects the stale reconnect response', () => {
  const buffer = new CandleLoadBuffer();
  const firstReconnect = buffer.begin('1m');
  const latestReconnect = buffer.begin('1m');
  buffer.buffer({ price: '102', volume: '3', observedAt: '2026-09-16T00:02:10Z' });

  assert.equal(buffer.resolve(firstReconnect, []), null);
  const latest = buffer.resolve(latestReconnect, []);
  assert.equal(latest.tickCount, 1);
  assert.equal(latest.candles[0].close, 102);
});

test('prepends older candles in order without replacing current realtime values', () => {
  const current = normalizeCandles([
    candle('2026-09-16T00:01:00Z', '101'),
    candle('2026-09-16T00:02:00Z', '102'),
  ]);
  current[0] = { ...current[0], close: 105, closed: false };
  const history = normalizeCandles([
    candle('2026-09-16T00:00:00Z', '100'),
    candle('2026-09-16T00:01:00Z', '999'),
  ]);

  const result = prependCandleHistory(current, history);

  assert.equal(result.prepended, 1);
  assert.deepEqual(result.candles.map(({ time }) => time), [1789516800, 1789516860, 1789516920]);
  assert.equal(result.candles[1].close, 105);
  assert.equal(result.candles[1].closed, false);
});

test('serializes history loads and stops when the cursor is exhausted', () => {
  const cursor = new CandleHistoryCursor();
  cursor.reset('5m', '2026-09-16T00:00:00Z');
  const first = cursor.begin('5m');

  assert.equal(first.before, '2026-09-16T00:00:00Z');
  assert.equal(cursor.begin('5m'), null);
  assert.deepEqual(cursor.resolve(first, '2026-09-15T23:00:00Z'), {
    exhausted: false, nextBefore: '2026-09-15T23:00:00Z',
  });

  const second = cursor.begin('5m');
  assert.equal(second.before, '2026-09-15T23:00:00Z');
  assert.deepEqual(cursor.resolve(second, null), { exhausted: true, nextBefore: null });
  assert.equal(cursor.begin('5m'), null);
});

test('rejects a stale history response after interval reset and allows retry after failure', () => {
  const cursor = new CandleHistoryCursor();
  cursor.reset('1m', '2026-09-16T00:00:00Z');
  const stale = cursor.begin('1m');
  cursor.reset('1h', '2026-09-15T00:00:00Z');

  assert.equal(cursor.resolve(stale, '2026-09-14T00:00:00Z'), null);
  const active = cursor.begin('1h');
  assert.equal(cursor.reject(active), true);
  assert.equal(cursor.begin('1h').before, '2026-09-15T00:00:00Z');
});

function candle(startedAt, price) {
  return { startedAt, open: price, high: price, low: price, close: price, volume: '1', closed: true };
}
