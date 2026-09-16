import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CandleLoadBuffer, applyBufferedTicks, applyPriceTick, bucketTime, normalizeCandles,
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

function candle(startedAt, price) {
  return { startedAt, open: price, high: price, low: price, close: price, volume: '1', closed: true };
}
