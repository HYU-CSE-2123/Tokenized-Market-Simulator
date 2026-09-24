import { CandlestickSeries, ColorType, HistogramSeries, createChart } from 'lightweight-charts';
import { formatChartAxisTime, formatChartTime } from './chart-time.js';

export class MarketChart {
  #chart;
  #candles;
  #volume;
  #resizeObserver;
  #historyIntent = false;
  #historyListener = null;
  #markHistoryIntent;

  constructor(container) {
    this.#chart = createChart(container, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: '#101722' },
        textColor: '#9aa8bb',
        // lightweight-charts의 TradingView 저작자 표시와 링크를 유지한다.
        attributionLogo: true,
      },
      grid: {
        vertLines: { color: '#202a38' },
        horzLines: { color: '#202a38' },
      },
      rightPriceScale: { borderColor: '#334155' },
      timeScale: {
        borderColor: '#334155',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: formatChartAxisTime,
      },
      localization: { locale: 'ko-KR', timeFormatter: formatChartTime },
    });
    this.#candles = this.#chart.addSeries(CandlestickSeries, {
      upColor: '#ef4444', downColor: '#3b82f6', borderVisible: false,
      wickUpColor: '#ef4444', wickDownColor: '#3b82f6',
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 },
    });
    this.#candles.priceScale().applyOptions({ scaleMargins: { top: 0.08, bottom: 0.3 } });
    this.#volume = this.#chart.addSeries(HistogramSeries, {
      priceScaleId: '', priceFormat: { type: 'volume' },
    });
    this.#volume.priceScale().applyOptions({ scaleMargins: { top: 0.78, bottom: 0 } });
    this.#resizeObserver = new ResizeObserver(() => this.#chart.timeScale().fitContent());
    this.#resizeObserver.observe(container);
    this.#markHistoryIntent = () => { this.#historyIntent = true; };
    container.addEventListener('wheel', this.#markHistoryIntent, { passive: true });
    container.addEventListener('pointerdown', this.#markHistoryIntent, { passive: true });
    container.addEventListener('touchstart', this.#markHistoryIntent, { passive: true });
  }

  setData(candles, { prepended = 0, preserveVisibleRange = false } = {}) {
    const visibleRange = preserveVisibleRange
      ? this.#chart.timeScale().getVisibleLogicalRange() : null;
    this.#candles.setData(candles.map(({ time, open, high, low, close }) => (
      { time, open, high, low, close }
    )));
    this.#volume.setData(candles.map(({ time, volume, open, close }) => ({
      time, value: volume, color: close >= open ? '#ef444466' : '#3b82f666',
    })));
    if (visibleRange && prepended > 0) {
      this.#chart.timeScale().setVisibleLogicalRange({
        from: visibleRange.from + prepended,
        to: visibleRange.to + prepended,
      });
    } else if (!preserveVisibleRange) {
      this.#chart.timeScale().fitContent();
    }
  }

  onNeedHistory(listener) {
    if (this.#historyListener) {
      this.#chart.timeScale().unsubscribeVisibleLogicalRangeChange(this.#historyListener);
    }
    this.#historyListener = (range) => {
      if (!this.#historyIntent || !range || range.from > 10) return;
      this.#historyIntent = false;
      listener();
    };
    this.#chart.timeScale().subscribeVisibleLogicalRangeChange(this.#historyListener);
  }

  update(candle) {
    const { time, open, high, low, close, volume } = candle;
    this.#candles.update({ time, open, high, low, close });
    this.#volume.update({
      time, value: volume, color: close >= open ? '#ef444466' : '#3b82f666',
    });
  }

  destroy() {
    this.#resizeObserver.disconnect();
    if (this.#historyListener) {
      this.#chart.timeScale().unsubscribeVisibleLogicalRangeChange(this.#historyListener);
    }
    this.#chart.remove();
  }
}
