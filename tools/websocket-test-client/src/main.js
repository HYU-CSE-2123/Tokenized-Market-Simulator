import './style.css';
import { ApiClient, ApiError } from './api.js';
import { MarketSocket } from './websocket.js';
import {
  CandleHistoryCursor, CandleLoadBuffer, applyPriceTick, normalizeCandles, prependCandleHistory,
} from './candles.js';
import { MarketChart } from './market-chart.js';
import {
  friendlyTradeError, isCurrentOrder, normalizeQuote, orderFillPlaceholder, quoteUsability,
  remainingSeconds, shouldRenderOrder,
} from './trade-quote.js';

const api = new ApiClient();
const CHART_COUNTS = { '1m': 100, '5m': 100, '15m': 50, '30m': 30, '1h': 20, '1d': 100 };
let eventCount = 0;
let chartInterval = '1m';
let chartCandles = [];
let chartRequest = 0;
const chartLoadBuffer = new CandleLoadBuffer();
const chartHistory = new CandleHistoryCursor();
const tradeQuotes = { BUY: null, SELL: null };
let lastOrder = null;
let restBusy = false;

const elements = Object.fromEntries(
  [...document.querySelectorAll('[id]')].map((element) => [element.id, element]),
);
const marketChart = new MarketChart(elements['market-chart']);
marketChart.onNeedHistory(loadOlderCandles);

const socket = new MarketSocket({
  onStatus: renderSocketStatus,
  onEvent: renderEvent,
  onDebug: (message) => console.debug('[STOMP]', message),
});

elements.signup.addEventListener('click', () => authRequest('signup'));
elements.login.addEventListener('click', () => authRequest('login'));
elements.me.addEventListener('click', () => runRest(() => api.me()));
elements.market.addEventListener('click', () => runRest(() => api.market(), renderMarket));
elements.faucet.addEventListener('click', () => runRest(() => api.faucet()));
elements.portfolio.addEventListener('click', () => runRest(() => api.portfolio()));
elements.orders.addEventListener('click', () => runRest(() => api.orders()));
elements['quote-buy'].addEventListener('click', () => issueQuote('BUY'));
elements['quote-sell'].addEventListener('click', () => issueQuote('SELL'));
elements.buy.addEventListener('click', () => executeQuote('BUY'));
elements.sell.addEventListener('click', () => executeQuote('SELL'));
elements['buy-amount'].addEventListener('input', () => syncQuoteState('BUY'));
elements['sell-amount'].addEventListener('input', () => syncQuoteState('SELL'));
elements.connect.addEventListener('click', connectSocket);
elements.disconnect.addEventListener('click', () => socket.disconnect());
elements['reload-chart'].addEventListener('click', loadChart);
document.querySelectorAll('[data-interval]').forEach((button) => {
  button.addEventListener('click', () => selectChartInterval(button.dataset.interval));
});
elements['clear-events'].addEventListener('click', clearEvents);
elements['clear-token'].addEventListener('click', () => {
  socket.disconnect();
  api.clearToken();
  renderToken();
});

async function authRequest(action) {
  const loginId = elements['login-id'].value.trim();
  const password = elements.password.value;
  const nickname = elements.nickname.value.trim();
  await runRest(async () => {
    const result = action === 'signup'
      ? await api.signup(loginId, password, nickname)
      : await api.login(loginId, password);
    api.setToken(result.body.accessToken);
    renderToken();
    return result;
  });
}

async function runRest(request, onSuccess, onError) {
  restBusy = true;
  setButtonsDisabled(true);
  try {
    const result = await request();
    elements['rest-output'].textContent = JSON.stringify(result, null, 2);
    onSuccess?.(result.body);
  } catch (error) {
    const output = error instanceof ApiError
      ? { status: error.status, message: error.message, body: error.body }
      : { message: error.message || String(error) };
    elements['rest-output'].textContent = JSON.stringify(output, null, 2);
    onError?.(error);
  } finally {
    restBusy = false;
    setButtonsDisabled(false);
  }
}

async function issueQuote(side) {
  let amount;
  try {
    amount = requiredAmount(side === 'BUY' ? 'buy-amount' : 'sell-amount');
  } catch (error) {
    setTradeStatus('ERROR', error.message);
    return;
  }
  setTradeStatus('LOADING', `${side === 'BUY' ? '매수' : '매도'} 견적 발급 중`);
  await runRest(async () => {
    const result = side === 'BUY' ? await api.quoteBuy(amount) : await api.quoteSell(amount);
    tradeQuotes[side] = normalizeQuote(side, amount, result.body);
    renderQuote(side);
    setTradeStatus('READY', '견적 내용을 확인하고 주문을 실행하세요.');
    return result;
  }, null, (error) => {
    setTradeStatus('ERROR', friendlyTradeError(error.body, error.message));
  });
}

async function executeQuote(side) {
  const amountId = side === 'BUY' ? 'buy-amount' : 'sell-amount';
  let amount;
  try {
    amount = requiredAmount(amountId);
  } catch (error) {
    setTradeStatus('ERROR', error.message);
    return;
  }
  const quote = tradeQuotes[side];
  const usability = quoteUsability(quote, amount);
  if (!usability.usable) {
    setTradeStatus('ERROR', `${usability.reason} 새 견적을 발급받으세요.`);
    renderQuote(side);
    return;
  }
  setTradeStatus('LOADING', '주문을 준비하고 온체인 트랜잭션을 전송하는 중입니다.');
  restBusy = true;
  setButtonsDisabled(true);
  try {
    const result = side === 'BUY'
      ? await api.buy(amount, quote.quoteId) : await api.sell(amount, quote.quoteId);
    tradeQuotes[side] = null;
    renderQuote(side);
    renderOrder(result.body);
    elements['rest-output'].textContent = JSON.stringify(result, null, 2);
  } catch (error) {
    const body = error instanceof ApiError ? error.body : null;
    const message = friendlyTradeError(body, error.message || String(error));
    setTradeStatus('ERROR', message);
    elements['rest-output'].textContent = JSON.stringify({
      status: error.status, code: body?.code, message, body,
    }, null, 2);
    if (body?.code === 'PRICE_QUOTE_UNAVAILABLE' || body?.code === 'PRICE_QUOTE_NOT_FOUND') {
      tradeQuotes[side] = null;
      renderQuote(side);
    }
  } finally {
    restBusy = false;
    setButtonsDisabled(false);
  }
}

function renderMarket(market) {
  elements['current-price'].textContent = formatNumber(market.price, '원');
  elements['change-rate'].textContent = `${formatNumber(market.changeRate, '%')} · ${displayTime(market.observedAt)}`;
  elements['market-health'].textContent = `${market.marketStatus} · ${market.priceStatus} · ${market.provider}`;
  const style = market.priceStatus === 'LIVE' || market.priceStatus === 'SIMULATED'
    ? 'success' : market.priceStatus === 'STALE' ? 'error' : 'neutral';
  elements['market-health'].className = `badge ${style}`;
}

function connectSocket() {
  const transport = document.querySelector('input[name="transport"]:checked').value;
  socket.connect({
    transport,
    token: api.token,
    debug: elements['stomp-debug'].checked,
  });
}

function renderToken() {
  const authenticated = Boolean(api.token);
  elements['auth-status'].textContent = authenticated ? 'JWT 준비됨' : '로그인 안 됨';
  elements['auth-status'].className = `badge ${authenticated ? 'success' : 'neutral'}`;
  elements['token-value'].textContent = api.token || '없음';
}

function renderSocketStatus(status, detail) {
  elements['socket-status'].textContent = detail ? `${status}: ${detail}` : status;
  const style = status === 'CONNECTED' ? 'success' : status === 'ERROR' ? 'error' : 'neutral';
  elements['socket-status'].className = `badge ${style}`;
  if (status === 'CONNECTED') loadChart();
}

function renderEvent(channel, event) {
  eventCount += 1;
  elements['event-count'].textContent = `${eventCount} events received`;
  elements['last-event'].textContent = event.type || 'UNKNOWN';
  if (channel === 'price') {
    elements['current-price'].textContent = formatNumber(event.data?.price, '원');
    elements['change-rate'].textContent = `${formatNumber(event.data?.changeRate, '%')} · ${displayTime(event.data?.observedAt)}`;
    renderPriceHealth(event.data);
    updateChart(event.data);
  }
  if (channel === 'order') {
    const rendered = renderOrder(event.data);
    if (rendered && event.data?.status === 'FILLED') void renderFinalTrade(event.data.orderId);
  }

  const container = elements[`${channel}-events`];
  container.classList.remove('empty');
  const item = document.createElement('details');
  item.className = 'event-item';
  item.open = container.children.length === 0;
  const summary = document.createElement('summary');
  summary.textContent = `${displayTime(event.occurredAt)}  ${event.type || 'UNKNOWN'}  ${summaryText(channel, event.data)}`;
  const body = document.createElement('pre');
  body.textContent = JSON.stringify(event, null, 2);
  item.append(summary, body);
  container.prepend(item);
  while (container.children.length > 100) container.lastElementChild.remove();
}

async function loadChart() {
  const request = ++chartRequest;
  const interval = chartInterval;
  const load = chartLoadBuffer.begin(interval);
  chartHistory.reset(interval, null);
  setChartStatus('LOADING', `${interval} 캔들 불러오는 중`);
  elements['reload-chart'].disabled = true;
  try {
    const result = await api.candles(interval, CHART_COUNTS[interval]);
    if (request !== chartRequest) return;
    const reconciled = chartLoadBuffer.resolve(load, normalizeCandles(result.body.candles));
    if (!reconciled) return;
    chartCandles = reconciled.candles;
    chartHistory.reset(interval, result.body.nextBefore);
    marketChart.setData(chartCandles);
    const receivedLiveTick = reconciled.tickCount > 0;
    setChartStatus(receivedLiveTick ? 'LIVE' : 'READY',
      `${result.body.provider} · ${chartCandles.length}개`);
    const latest = chartCandles.at(-1);
    elements['chart-updated'].textContent = latest
      ? `마지막 봉 ${displayTime(latest.time * 1000)}` : '표시할 캔들이 없습니다';
  } catch (error) {
    if (request !== chartRequest) return;
    chartLoadBuffer.reject(load);
    setChartStatus('ERROR', error.message || String(error));
  } finally {
    if (request === chartRequest) {
      elements['reload-chart'].disabled = false;
    }
  }
}

async function loadOlderCandles() {
  const load = chartHistory.begin(chartInterval);
  if (!load) return;
  let accepted = false;
  setChartStatus('LOADING', `${load.interval} 이전 캔들 불러오는 중`);
  try {
    const result = await api.candles(load.interval, CHART_COUNTS[load.interval], load.before);
    const history = normalizeCandles(result.body.candles);
    const pageState = chartHistory.resolve(load, result.body.nextBefore);
    if (!pageState) return;
    accepted = true;
    const merged = prependCandleHistory(chartCandles, history);
    chartCandles = merged.candles;
    marketChart.setData(chartCandles, {
      prepended: merged.prepended,
      preserveVisibleRange: true,
    });
    const detail = pageState.exhausted ? '가장 오래된 데이터까지 표시' : `총 ${chartCandles.length}개`;
    setChartStatus('READY', `${result.body.provider} · ${detail}`);
    elements['chart-updated'].textContent = merged.prepended > 0
      ? `과거 봉 ${merged.prepended}개 추가` : '추가할 과거 봉이 없습니다';
  } catch (error) {
    if (!accepted && !chartHistory.reject(load)) return;
    setChartStatus('ERROR', error.message || String(error));
  }
}

function selectChartInterval(interval) {
  if (interval === chartInterval) return;
  chartInterval = interval;
  chartCandles = [];
  marketChart.setData([]);
  document.querySelectorAll('[data-interval]').forEach((button) => {
    button.classList.toggle('active', button.dataset.interval === interval);
  });
  loadChart();
}

function updateChart(tick) {
  try {
    const pendingCount = chartLoadBuffer.buffer(tick);
    if (pendingCount !== null) {
      elements['chart-updated'].textContent = `로딩 중 tick 대기 ${pendingCount}개`;
      return;
    }
    const result = applyPriceTick(chartCandles, tick, chartInterval);
    if (!result.changed) return;
    chartCandles = result.candles;
    marketChart.update(chartCandles.at(-1));
    setChartStatus('LIVE', `${tick.provider || 'UNKNOWN'} · ${chartInterval}`);
    elements['chart-updated'].textContent = `실시간 ${displayTime(tick.observedAt)}`;
  } catch (error) {
    setChartStatus('ERROR', error.message || String(error));
  }
}

function renderPriceHealth(data = {}) {
  if (!data.marketStatus && !data.priceStatus) return;
  elements['market-health'].textContent = `${data.marketStatus} · ${data.priceStatus} · ${data.provider}`;
  const style = data.priceStatus === 'LIVE' || data.priceStatus === 'SIMULATED'
    ? 'success' : data.priceStatus === 'STALE' ? 'error' : 'neutral';
  elements['market-health'].className = `badge ${style}`;
}

function setChartStatus(state, detail) {
  elements['chart-status'].textContent = `${state} · ${detail}`;
  const style = state === 'LIVE' || state === 'READY' ? 'success' : state === 'ERROR' ? 'error' : 'neutral';
  elements['chart-status'].className = `badge ${style}`;
}

function summaryText(channel, data = {}) {
  if (channel === 'price') return formatNumber(data.price, '원');
  if (channel === 'trade') return `${data.side || ''} ${formatNumber(data.baseAmount, 'mSEC')}`;
  if (channel === 'order') return `#${data.orderId ?? '?'} ${data.status || ''}`;
  if (channel === 'portfolio') return `총 ${formatNumber(data.totalValue, '원')}`;
  return '';
}

function renderQuote(side) {
  const quote = tradeQuotes[side];
  const lower = side.toLowerCase();
  const container = elements[`${lower}-quote`];
  if (!quote) {
    container.replaceChildren();
    container.classList.add('empty');
    syncQuoteState(side);
    return;
  }
  container.classList.remove('empty');
  const seconds = remainingSeconds(quote);
  const outputUnit = side === 'BUY' ? 'mSEC' : 'mKRW';
  const rows = [
    ['서명 가격', formatNumber(quote.price, '원')],
    ['수수료', formatNumber(quote.fee, side === 'BUY' ? 'mKRW' : 'mKRW')],
    ['예상 수령량', formatNumber(quote.output, outputUnit)],
    ['최소 수령량', formatNumber(quote.minimumOutput, outputUnit)],
    ['관측 시각', quote.observedAt ? displayDateTime(quote.observedAt) : '모의 가격'],
    ['남은 시간', seconds === null ? '모의 거래 · 만료 없음' : `${seconds}초`],
    ['quoteId', quote.quoteId || '모의 거래 · 서버 서명 없음'],
  ];
  container.replaceChildren(...rows.map(([label, value]) => {
    const row = document.createElement('div');
    const key = document.createElement('span');
    const data = document.createElement(label === 'quoteId' ? 'code' : 'strong');
    key.textContent = label;
    data.textContent = value;
    row.append(key, data);
    return row;
  }));
  syncQuoteState(side);
}

function syncQuoteState(side) {
  const lower = side.toLowerCase();
  const amount = elements[`${lower}-amount`].value.trim();
  const usability = quoteUsability(tradeQuotes[side], amount);
  elements[lower].disabled = restBusy || !usability.usable;
  const container = elements[`${lower}-quote`];
  container.classList.toggle('expired', Boolean(tradeQuotes[side]) && !usability.usable);
}

function setTradeStatus(state, detail) {
  elements['trade-status'].textContent = `${state} · ${detail}`;
  elements['trade-status'].className = `trade-status ${state === 'ERROR' ? 'error' : state === 'READY' ? 'success' : 'neutral'}`;
}

function renderOrder(order = {}) {
  if (!shouldRenderOrder(lastOrder, order)) return false;
  const fillPlaceholder = orderFillPlaceholder(lastOrder?.orderId, order);
  lastOrder = { orderId: order.orderId, status: order.status };
  elements['last-order-status'].textContent = order.status || 'UNKNOWN';
  elements['last-order-detail'].textContent = `#${order.orderId} ${order.side || ''} · 입력 ${order.inputAmount ?? '—'} · 출력 ${order.outputAmount ?? '—'}`;
  if (fillPlaceholder) elements['last-fill-price'].textContent = fillPlaceholder;
  if (order.status === 'PENDING_ONCHAIN') setTradeStatus('PENDING', `주문 #${order.orderId} receipt 대기 중`);
  if (order.status === 'FILLED') setTradeStatus('FILLED', `주문 #${order.orderId} 체결 완료`);
  if (order.status === 'FAILED') setTradeStatus('ERROR', `주문 #${order.orderId}이 실패했습니다.`);
  return true;
}

async function renderFinalTrade(orderId) {
  try {
    const result = await api.trades();
    const trade = result.body.find((item) => item.orderId === orderId);
    if (!trade || !isCurrentOrder(lastOrder, orderId)) return;
    elements['last-fill-price'].textContent = `최종 체결 ${formatNumber(trade.price, '원')} · 수수료 ${formatNumber(trade.fee, 'mKRW')}`;
  } catch (error) {
    if (!isCurrentOrder(lastOrder, orderId)) return;
    elements['last-fill-price'].textContent = `체결 조회 실패 · ${error.message || error}`;
  }
}

function clearEvents() {
  ['price', 'trade', 'order', 'portfolio'].forEach((channel) => {
    const container = elements[`${channel}-events`];
    container.replaceChildren();
    container.classList.add('empty');
  });
  eventCount = 0;
  elements['event-count'].textContent = '0 events received';
  elements['last-event'].textContent = '—';
}

function requiredAmount(id) {
  const value = elements[id].value.trim();
  if (!value || Number(value) <= 0) throw new Error('거래 수량은 0보다 커야 합니다.');
  return value;
}

function formatNumber(value, suffix) {
  if (value === undefined || value === null || value === '') return '—';
  const numeric = Number(value);
  const formatted = Number.isFinite(numeric)
    ? new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 8 }).format(numeric)
    : String(value);
  return `${formatted} ${suffix}`;
}

function displayTime(value) {
  const date = value ? new Date(value) : new Date();
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleTimeString('ko-KR');
}

function displayDateTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('ko-KR');
}

function setButtonsDisabled(disabled) {
  document.querySelectorAll('.controls-panel button').forEach((button) => {
    button.disabled = disabled;
  });
  if (!disabled) {
    syncQuoteState('BUY');
    syncQuoteState('SELL');
  }
}

renderToken();
renderSocketStatus('DISCONNECTED');
renderQuote('BUY');
renderQuote('SELL');
setInterval(() => {
  if (tradeQuotes.BUY) renderQuote('BUY');
  if (tradeQuotes.SELL) renderQuote('SELL');
}, 1000);
loadChart();
