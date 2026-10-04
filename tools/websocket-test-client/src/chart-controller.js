import { MarketChart } from './market-chart.js';
import { CandleHistoryCursor, CandleLoadBuffer, normalizeCandles, applyPriceTick, prependCandleHistory } from './candles.js';
const COUNTS = { '1m': 100, '5m': 100, '15m': 50, '30m': 30, '1h': 20, '1d': 100 };
/** Public chart state is independent of navigation and private sessions. */
export class ChartController {
  interval = '1m'; candles = []; request = 0;
  buffer = new CandleLoadBuffer(); history = new CandleHistoryCursor();
  constructor(api, container, message) { this.api = api; this.message = message; this.chart = new MarketChart(container); this.chart.onNeedHistory(() => this.older()); }
  async load() {
    const request = ++this.request; const interval = this.interval;
    const load = this.buffer.begin(interval); this.history.reset(interval, null);
    this.message.textContent = '캔들을 불러오는 중입니다.';
    try {
      const { body } = await this.api.candles(interval, COUNTS[interval]);
      if (request !== this.request) return;
      const result = this.buffer.resolve(load, normalizeCandles(body.candles)); if (!result) return;
      this.candles = result.candles; this.history.reset(interval, body.nextBefore); this.chart.setData(this.candles);
      this.message.textContent = this.candles.length ? `${this.candles.length}개 봉 · 왼쪽으로 이동해 과거 조회` : '표시할 캔들이 없습니다.';
    } catch { if (request !== this.request) return; this.buffer.reject(load); this.message.textContent = '차트를 불러오지 못했습니다. 다시 불러오기를 눌러주세요.'; }
  }
  select(interval) { if (interval === this.interval) return; this.interval = interval; this.candles = []; this.chart.setData([]); return this.load(); }
  async older() {
    const load = this.history.begin(this.interval); if (!load) return;
    this.message.textContent = '과거 봉을 불러오는 중입니다.';
    try {
      const { body } = await this.api.candles(load.interval, COUNTS[load.interval], load.before);
      const result = this.history.resolve(load, body.nextBefore); if (!result) return;
      const merged = prependCandleHistory(this.candles, normalizeCandles(body.candles)); this.candles = merged.candles;
      this.chart.setData(this.candles, { prepended: merged.prepended, preserveVisibleRange: true });
      this.message.textContent = result.exhausted ? '가장 오래된 데이터까지 표시했습니다.' : `과거 봉 추가 · 총 ${this.candles.length}개`;
    } catch { if (this.history.reject(load)) this.message.textContent = '과거 봉 조회 실패 · 차트를 다시 이동해 재시도할 수 있습니다.'; }
  }
  tick(tick) {
    try { if (this.buffer.buffer(tick) !== null) return; const result = applyPriceTick(this.candles, tick, this.interval); if (result.changed) { this.candles = result.candles; this.chart.update(this.candles.at(-1)); } }
    catch { this.message.textContent = '시세를 반영하지 못했습니다. 차트를 다시 불러와주세요.'; }
  }
}
