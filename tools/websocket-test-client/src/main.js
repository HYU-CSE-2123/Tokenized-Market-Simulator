import './style.css';
import { ApiClient, ApiError } from './api.js';
import { MarketSocket } from './websocket.js';

const api = new ApiClient();
let eventCount = 0;

const elements = Object.fromEntries(
  [...document.querySelectorAll('[id]')].map((element) => [element.id, element]),
);

const socket = new MarketSocket({
  onStatus: renderSocketStatus,
  onEvent: renderEvent,
  onDebug: (message) => console.debug('[STOMP]', message),
});

elements.signup.addEventListener('click', () => authRequest('signup'));
elements.login.addEventListener('click', () => authRequest('login'));
elements.me.addEventListener('click', () => runRest(() => api.me()));
elements.faucet.addEventListener('click', () => runRest(() => api.faucet()));
elements.portfolio.addEventListener('click', () => runRest(() => api.portfolio()));
elements.orders.addEventListener('click', () => runRest(() => api.orders()));
elements.buy.addEventListener('click', () => runRest(() => api.buy(requiredAmount('buy-amount'))));
elements.sell.addEventListener('click', () => runRest(() => api.sell(requiredAmount('sell-amount'))));
elements.connect.addEventListener('click', connectSocket);
elements.disconnect.addEventListener('click', () => socket.disconnect());
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

async function runRest(request) {
  setButtonsDisabled(true);
  try {
    const result = await request();
    elements['rest-output'].textContent = JSON.stringify(result, null, 2);
  } catch (error) {
    const output = error instanceof ApiError
      ? { status: error.status, message: error.message, body: error.body }
      : { message: error.message || String(error) };
    elements['rest-output'].textContent = JSON.stringify(output, null, 2);
  } finally {
    setButtonsDisabled(false);
  }
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
}

function renderEvent(channel, event) {
  eventCount += 1;
  elements['event-count'].textContent = `${eventCount} events received`;
  elements['last-event'].textContent = event.type || 'UNKNOWN';
  if (channel === 'price') {
    elements['current-price'].textContent = formatNumber(event.data?.price, '원');
    elements['change-rate'].textContent = `${formatNumber(event.data?.changeRate, '%')} · ${displayTime(event.occurredAt)}`;
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

function summaryText(channel, data = {}) {
  if (channel === 'price') return formatNumber(data.price, '원');
  if (channel === 'trade') return `${data.side || ''} ${formatNumber(data.baseAmount, 'mSEC')}`;
  if (channel === 'order') return `#${data.orderId ?? '?'} ${data.status || ''}`;
  if (channel === 'portfolio') return `총 ${formatNumber(data.totalValue, '원')}`;
  return '';
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

function setButtonsDisabled(disabled) {
  document.querySelectorAll('.controls-panel button').forEach((button) => {
    button.disabled = disabled;
  });
}

renderToken();
renderSocketStatus('DISCONNECTED');
