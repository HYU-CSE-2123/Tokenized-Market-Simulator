const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

export function normalizeCandles(candles = []) {
  return candles.map((candle) => ({
    time: epochSeconds(candle.startedAt),
    open: number(candle.open, 'open'),
    high: number(candle.high, 'high'),
    low: number(candle.low, 'low'),
    close: number(candle.close, 'close'),
    volume: number(candle.volume, 'volume'),
    closed: Boolean(candle.closed),
  })).sort((left, right) => left.time - right.time);
}

export function applyPriceTick(candles, tick, interval) {
  const observedAt = new Date(tick.observedAt);
  const price = number(tick.price, 'price');
  const volume = number(tick.volume, 'volume');
  if (Number.isNaN(observedAt.getTime())) throw new Error('가격 관측 시각이 올바르지 않습니다.');
  const time = bucketTime(observedAt, interval);
  const previous = candles.at(-1);
  if (previous && time < previous.time) return { candles, changed: false };

  const next = [...candles];
  if (!previous || time > previous.time) {
    next.push({ time, open: price, high: price, low: price, close: price, volume, closed: false });
  } else {
    next[next.length - 1] = {
      ...previous,
      high: Math.max(previous.high, price),
      low: Math.min(previous.low, price),
      close: price,
      volume: previous.volume + volume,
      closed: false,
    };
  }
  return { candles: next, changed: true };
}

export function applyBufferedTicks(candles, ticks, interval) {
  return [...ticks]
    .sort((left, right) => new Date(left.observedAt) - new Date(right.observedAt))
    .reduce((current, tick) => applyPriceTick(current, tick, interval).candles, candles);
}

/** REST snapshot 요청 세대별로 로딩 중 tick을 격리한다. */
export class CandleLoadBuffer {
  #generation = 0;
  #active = null;

  begin(interval) {
    const load = { id: ++this.#generation, interval };
    this.#active = { ...load, ticks: [] };
    return load;
  }

  buffer(tick) {
    if (!this.#active) return null;
    this.#active.ticks.push(tick);
    return this.#active.ticks.length;
  }

  resolve(load, candles) {
    if (!this.#active || this.#active.id !== load.id) return null;
    const ticks = this.#active.ticks;
    this.#active = null;
    return {
      candles: applyBufferedTicks(candles, ticks, load.interval),
      tickCount: ticks.length,
    };
  }

  reject(load) {
    if (this.#active?.id === load.id) this.#active = null;
  }
}

export function bucketTime(date, interval) {
  const instant = date.getTime();
  if (interval === '1m') return Math.floor(instant / MINUTE_MS) * 60;
  if (interval === '1d') {
    return Math.floor((instant + KST_OFFSET_MS) / DAY_MS) * 86_400 - KST_OFFSET_MS / 1000;
  }
  throw new Error(`지원하지 않는 캔들 주기입니다: ${interval}`);
}

function epochSeconds(value) {
  const milliseconds = new Date(value).getTime();
  if (Number.isNaN(milliseconds)) throw new Error('캔들 시작 시각이 올바르지 않습니다.');
  return Math.floor(milliseconds / 1000);
}

function number(value, field) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error(`${field} 값이 올바르지 않습니다.`);
  return parsed;
}
