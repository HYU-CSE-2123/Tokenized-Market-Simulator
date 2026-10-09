import './style.css';
import { ApiClient } from './api.js';
import { MarketSocket } from './websocket.js';
import { ChartController } from './chart-controller.js';
import { normalizeQuote, quoteUsability, remainingSeconds, shouldRenderOrder, friendlyTradeError } from './trade-quote.js';
import { RequestState, number, time, label, positiveAmount, uncertainWrite, marketNotice } from './state.js';
import { node, button, fields, empty, orderCard, tradeCard, renderDiagnosis } from './ui.js';
import { createReservePanel } from './reserve-panel.js';

const $ = (id) => document.getElementById(id);
const api = new ApiClient(); const requests = new RequestState();
const reservePanel = createReservePanel(api);
const state = { user: null, market: null, portfolio: null, orders: [], trades: [], side: 'BUY', quote: null,
  historyTab: 'orders', authMode: 'login', destination: 'market', latestId: null, detailId: null, uncertain: null, history: [], nextBefore: null };
const chart = new ChartController(api, $('market-chart'), $('chart-message'));
let sessionExpiry; let toastTimer; let marketRevision = 0; let portfolioRevision = 0;
const socket = new MarketSocket({ onStatus: socketStatus, onEvent: socketEvent, onDebug: () => {} });

function message(id, text = '', style = '') { $(id).textContent = text; $(id).className = `notice ${style}`; }
function toast(text) { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 5000); }
function errorText(error) {
  const code = error?.body?.code || error?.body?.error;
  const messages = { AI_DISABLED: 'AI 기능이 비활성 상태입니다. 거래 기능은 계속 이용할 수 있습니다.', AGENT_DISABLED: 'AI 도움 기능이 준비되지 않았습니다.',
    AGENT_BUSY: 'AI 분석이 혼잡합니다. 잠시 후 다시 요청해 주세요.', TOOL_BUSY: '조회가 혼잡합니다. 잠시 후 다시 요청해 주세요.',
    DIAGNOSIS_DISABLED: '자동 진단 이력 기능이 비활성 상태입니다.', DIAGNOSIS_NOT_FOUND: '진단 이력을 찾을 수 없습니다.',
    DUPLICATE_LOGIN_ID: '이미 사용 중인 아이디입니다.', INVALID_CREDENTIALS: '아이디 또는 비밀번호를 확인해 주세요.' };
  if (messages[code]) return messages[code];
  if (error?.status === 401) return '로그인이 필요하거나 인증이 만료됐습니다.';
  if (error?.status === 403) return '이 기능을 사용할 권한이 없습니다.';
  if (error?.status === 404) return '대상을 찾을 수 없거나 접근할 수 없습니다.';
  if (error?.status === 503) return '현재 이 기능을 사용할 수 없습니다. 잠시 후 다시 확인해 주세요.';
  return error?.status === 400 ? '입력 내용과 대상 번호를 확인해 주세요.' : '요청을 완료하지 못했습니다. 연결을 확인하고 다시 조회해 주세요.';
}
/** Tickets cover auth changes and same-resource races; no blanket form locking or raw error output. */
async function read(key, operation, success, statusId, privateRead = false) {
  const ticket = requests.begin(key); if (statusId) message(statusId, '최신 정보를 불러오는 중입니다.');
  try {
    const { body } = await operation(); if (!ticket.current()) return;
    success(body); ticket.finish('ready'); if (statusId) message(statusId);
    return body;
  } catch (error) {
    if (!ticket.current()) return;
    ticket.finish('error', error); if (statusId) message(statusId, errorText(error), 'warning');
    if (privateRead && error.status === 401) expireSession();
  }
}
function showAuth(destination = routeName()) {
  state.destination = destination === 'admin' ? 'admin' : destination;
  message('auth-message'); if (!$('auth-dialog').open) $('auth-dialog').showModal();
}
function requireUser(destination = routeName()) { if (state.user) return true; showAuth(destination); return false; }
function routeName() { return location.hash.slice(1); }
function navigate(name) { if (routeName() === name) route(); else location.hash = name; }
function route() {
  let name = routeName(); if (!['market', 'history', 'portfolio', 'ai', 'admin'].includes(name)) name = 'market';
  if (name !== 'market' && !state.user) { showAuth(name); name = 'market'; }
  if (name === 'admin' && state.user?.role !== 'ADMIN') { name = 'market'; toast('ADMIN 운영 화면은 관리자 전용입니다.'); }
  document.querySelectorAll('.view').forEach((view) => { view.hidden = view.id !== `view-${name}`; });
  document.querySelectorAll('[data-route]').forEach((link) => { if (link.dataset.route === name) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current'); });
  if (name === 'portfolio') void loadPortfolio();
  if (name === 'history') { renderHistory(); void loadActivity(); }
  if (name === 'admin') { void loadDiagnosisHistory(); void loadObservation(); }
  reservePanel.setContext(state.user, name);
}
function connect() { socket.connect({ transport: $('transport').value, token: api.token, debug: false }); }
function renderIdentity() {
  $('auth-open').hidden = Boolean(state.user); $('logout').hidden = !state.user;
  $('identity').textContent = state.user ? `${state.user.nickname} 님` : '';
  $('admin-nav').hidden = state.user?.role !== 'ADMIN'; updateTradeControls();
}
function resetPrivate() {
  reservePanel.setContext(null, 'market');
  $('quote-dialog').close();
  requests.reset(); clearTimeout(sessionExpiry); state.user = null; state.quote = null; state.orders = []; state.trades = []; state.portfolio = null;
  state.latestId = null; state.detailId = null; state.uncertain = null; state.history = []; state.nextBefore = null; portfolioRevision++;
  $('ai-question').value = ''; $('ai-target').value = ''; $('ai-kind').value = 'general'; syncAiTarget();
  for (const id of ['admin-order', 'admin-filter-order', 'password', 'nickname']) $(id).value = '';
  for (const id of ['auth-message', 'ai-message', 'admin-message', 'history-message', 'portfolio-message', 'faucet-message', 'trade-message']) message(id);
  for (const id of ['ai-result', 'admin-result', 'admin-history', 'portfolio-summary', 'history-list', 'observation', 'latest-order']) $(id).replaceChildren();
  $('order-detail').hidden = true; $('order-detail').replaceChildren(); $('admin-older').disabled = true;
  $('ai-submit').disabled = false; $('admin-run').disabled = false; $('faucet').disabled = false; $('auth-submit').disabled = false;
  $('trade-amount').value = ''; renderQuote(); renderPortfolio(); renderIdentity();
}
function logout(expired = false) { socket.disconnect(); api.clearToken(); resetPrivate(); navigate('market'); connect(); toast(expired ? '인증이 만료됐습니다. 다시 로그인해 주세요.' : '로그아웃했습니다.'); }
function expireSession() { if (api.token) logout(true); }
async function authenticate(event) {
  event.preventDefault(); if (requests.busy('auth')) return;
  const login = $('login-id').value.trim(), password = $('password').value, nickname = $('nickname').value.trim();
  if (state.authMode === 'signup' && (!/^[a-zA-Z0-9_-]{4,30}$/.test(login) || password.length < 8 || password.length > 72 || nickname.length < 2 || nickname.length > 30)) {
    message('auth-message', '아이디는 영문·숫자·_·- 4~30자, 비밀번호는 8~72자, 닉네임은 2~30자로 입력해 주세요.', 'warning'); return;
  }
  const ticket = requests.begin('auth'); $('auth-submit').disabled = true; message('auth-message', '계정을 확인하는 중입니다.');
  try {
    const { body } = await (state.authMode === 'signup' ? api.signup(login, password, nickname) : api.login(login, password));
    if (!ticket.current()) return; api.setToken(body.accessToken);
    const me = await api.me(); if (!ticket.current()) return;
    const destination = state.destination; socket.disconnect(); resetPrivate(); state.user = me.body;
    sessionExpiry = setTimeout(expireSession, Math.min(Number(body.expiresIn) * 1000, 2147483647));
    renderIdentity(); $('auth-dialog').close(); navigate(destination); connect(); toast('로그인했습니다. 모의 자산 거래를 시작해 보세요.');
  } catch (error) {
    if (ticket.current()) { api.clearToken(); ticket.finish('error'); message('auth-message', errorText(error), 'warning'); }
  } finally { if (ticket.current()) $('auth-submit').disabled = false; }
}
function marketRender(market) {
  state.market = market; $('price').textContent = number(market.price, 'mKRW', 2);
  const synthetic = market.provider === 'SIMULATED';
  $('price-change').textContent = `${synthetic ? '합성 전일 대비' : '전일 대비'} ${number(market.change, 'mKRW', 2)} (${number(market.changeRate, '%', 2)})`;
  $('price-change').className = Number(market.changeRate) > 0 ? 'up' : Number(market.changeRate) < 0 ? 'down' : 'muted';
  $('provider').textContent = market.provider === 'TOSS' ? 'Toss 기준 시세' : synthetic ? 'SIMULATED · 합성 시장' : '공급자 확인 불가';
  $('market-mode').textContent = $('provider').textContent;
  $('market-description').textContent = synthetic ? '자체 생성한 합성 가격·거래량으로 거래하는 mSEC 모의 시장입니다. 실제 삼성전자 시세가 아닙니다.' : market.provider === 'TOSS' ? 'Toss 삼성전자 기준 시세로 모의 자산을 거래합니다. 실제 주식 거래가 아닙니다.' : '시세 출처를 확인할 수 없습니다.';
  $('volume-description').textContent = synthetic ? '한국 시간 기준 · 24시간 합성 시장 · 합성 거래량은 사용자 체결량이 아닙니다. 초기 과거 봉도 자체 생성한 합성 이력입니다.' : '한국 시간 기준 · 왼쪽으로 이동해 과거 봉 조회 · 거래량은 공급자 체결량입니다.';
  $('market-state').textContent = `${label(market.marketStatus)} · ${label(market.priceStatus)}`;
  $('price-time').textContent = time(market.observedAt); message('market-message', marketNotice(market));
}
async function loadMarket() {
  const revision = marketRevision;
  const body = await read('market', () => api.market(), (body) => { if (revision === marketRevision) marketRender(body); }, 'market-message');
  if (body && state.market) message('market-message', marketNotice(state.market));
}
function renderPortfolio() {
  const portfolio = state.portfolio; if (!portfolio) { empty($('portfolio-summary'), state.user ? '자산 정보를 불러와주세요.' : '로그인하면 내 모의 자산을 볼 수 있습니다.'); }
  else {
    $('portfolio-summary').replaceChildren(...[
      ['총 평가액', portfolio.totalValue, 'mKRW'], ['모의 현금 잔고', portfolio.krwBalance, 'mKRW'],
      ['보유 mSEC', portfolio.tokenBalance, 'mSEC'], ['평균 매수가', portfolio.averageBuyPrice, 'mKRW'],
      ['mSEC 평가액', portfolio.tokenValue, 'mKRW'], ['미실현 손익', portfolio.unrealizedProfit, 'mKRW'],
    ].map(([title, value, unit], index) => { const card = node('article', '', `asset-card ${index === 0 ? 'primary' : ''}`); card.append(node('p', title, 'muted'), node('strong', number(value, unit, 8))); return card; }));
  }
  $('balance-hint').textContent = portfolio ? `모의 잔고 · ${number(portfolio.krwBalance, 'mKRW')} / ${number(portfolio.tokenBalance, 'mSEC')}` : '로그인 후 내 잔고를 확인할 수 있습니다.';
}
async function loadPortfolio() {
  if (!state.user) return;
  const revision = portfolioRevision;
  return read('portfolio', () => api.portfolio(), (body) => { if (revision === portfolioRevision) { state.portfolio = body; renderPortfolio(); } }, 'portfolio-message', true);
}
function acceptOrder(order) {
  const old = state.orders.find((item) => item.orderId === order.orderId);
  if (old && !shouldRenderOrder(old, order)) return old;
  state.orders = [...state.orders.filter((item) => item.orderId !== order.orderId), { ...old, ...order }].sort((a, b) => b.orderId - a.orderId);
  if (!state.latestId || order.orderId > state.latestId) state.latestId = order.orderId;
  renderHistory(); renderLatest(); renderOrderDetail(); updateTradeControls();
  return state.orders.find((item) => item.orderId === order.orderId);
}
async function loadActivity() {
  if (!state.user) return;
  const generation = requests.generation;
  const orders = read('orders', () => api.orders(), (body) => { body.forEach(acceptOrder); renderHistory(); }, 'history-message', true);
  const trades = read('trades', () => api.trades(), (body) => { state.trades = body; renderHistory(); renderLatest(); }, null, true);
  await Promise.all([orders, trades]);
  if (generation !== requests.generation) return;
  if (state.uncertain && requests.resources.get('orders')?.status === 'ready') { state.uncertain.checked = true; updateTradeControls(); }
  if (requests.resources.get('trades')?.status === 'error') message('history-message', '체결 내역을 확인하지 못했습니다. 주문 정보는 별도로 표시합니다.', 'warning');
}
function renderLatest() {
  const order = state.orders.find((item) => item.orderId === state.latestId);
  if (!order) return;
  $('latest-order').replaceChildren(orderCard(order, state.trades.find((trade) => trade.orderId === order.orderId), showOrder));
}
function renderHistory() {
  $('history-orders').setAttribute('aria-pressed', state.historyTab === 'orders'); $('history-trades').setAttribute('aria-pressed', state.historyTab === 'trades');
  const rows = state.historyTab === 'orders' ? state.orders : state.trades;
  if (!rows.length) { empty($('history-list'), '아직 내역이 없습니다. 시장에서 첫 모의 거래를 시작해 보세요.'); return; }
  $('history-list').replaceChildren(...rows.map((row) => state.historyTab === 'orders' ? orderCard(row, state.trades.find((trade) => trade.orderId === row.orderId), showOrder) : tradeCard(row)));
}
async function showOrder(id) {
  state.detailId = id; $('order-detail').hidden = false; empty($('order-detail'), `주문 #${id} 정보를 확인하는 중입니다.`);
  navigate('history'); await read('order-detail', () => api.order(id), (order) => {
    acceptOrder(order); renderOrderDetail(); $('order-detail').scrollIntoView({ block: 'nearest' });
  }, 'history-message', true);
}
function renderOrderDetail() {
    const id = state.detailId, order = state.orders.find((item) => item.orderId === id);
    if (!order) return;
    const container = $('order-detail'); container.hidden = false;
    container.replaceChildren(node('h2', `주문 #${id}`), fields([['상태', label(order.status)], ['트랜잭션 식별자', order.txHash || '온체인 기록 없음']]));
    container.append(button('이 주문 AI 설명', () => selectAi('order', id, '이 주문의 현재 상태와 정산 근거를 설명해주세요.')));
    if (state.user?.role === 'ADMIN') container.append(button('ADMIN 정산 진단', () => { $('admin-order').value = id; navigate('admin'); }));
}
function updateTradeControls() {
  const busy = requests.busy('quote') || requests.busy('trade');
  $('quote-request').disabled = busy || Boolean(state.uncertain); $('trade-amount').disabled = requests.busy('trade');
  $('side-buy').disabled = requests.busy('trade'); $('side-sell').disabled = requests.busy('trade');
  $('quote-confirm').disabled = !state.user || busy || Boolean(state.uncertain) || !quoteUsability(state.quote, $('trade-amount').value.trim()).usable;
  $('resolve-order').hidden = !state.uncertain;
  $('acknowledge-order').hidden = !state.uncertain?.checked;
  $('quote-submit').disabled = $('quote-confirm').disabled;
}
function renderQuote() {
  const quote = state.quote;
  $('quote-confirm').textContent = `이 견적으로 ${state.side === 'BUY' ? '매수' : '매도'} 확정`;
  $('quote-diagnose').hidden = !quote?.quoteId;
  if (!quote) { empty($('quote-summary'), '입력량에 맞는 견적을 먼저 확인하세요.'); $('quote-timer').textContent = ''; }
  else {
    $('quote-summary').replaceChildren(fields([['견적 가격', number(quote.price, 'mKRW', 2)], ['수수료', number(quote.fee, 'mKRW', 8)], ['예상 수령량', number(quote.output, state.side === 'BUY' ? 'mSEC' : 'mKRW', 8)], ['최소 수령량', number(quote.minimumOutput, state.side === 'BUY' ? 'mSEC' : 'mKRW', 8)], ['기준 시세 관측', time(quote.observedAt)]]));
    const seconds = remainingSeconds(quote); $('quote-timer').textContent = seconds === null ? '모의 DB 거래 견적 · 온체인 서명 없음' : seconds ? `견적 유효 시간 ${seconds}초` : '견적이 만료됐습니다. 새 견적을 확인해 주세요.';
  }
  if ($('quote-dialog').open) {
    $('quote-review').replaceChildren(node('p', state.market?.provider === 'SIMULATED' ? 'SIMULATED · 실제 삼성전자 시세가 아닌 합성 가격으로 거래합니다.' : '공급자 기준 가격의 모의 자산 거래입니다.'), ...[...$('quote-summary').children].map((child) => child.cloneNode(true)));
    $('quote-review-timer').textContent = $('quote-timer').textContent;
  }
  updateTradeControls();
}
function invalidateQuote() { state.quote = null; requests.invalidate('quote'); renderQuote(); }
async function issueQuote(event) {
  event.preventDefault(); if (!requireUser('market') || requests.busy('quote') || requests.busy('trade') || state.uncertain) return;
  const amount = $('trade-amount').value.trim(); if (!positiveAmount(amount)) { message('trade-message', '0보다 큰 숫자를 소수점 18자리 이내로 입력해 주세요.', 'warning'); return; }
  const side = state.side; const ticket = requests.begin('quote'); state.quote = null; renderQuote(); message('trade-message', '견적을 확인하는 중입니다.');
  try {
    const { body } = await (side === 'BUY' ? api.quoteBuy(amount) : api.quoteSell(amount)); if (!ticket.current()) return;
    state.quote = normalizeQuote(side, amount, body); ticket.finish('ready'); renderQuote(); message('trade-message', '가격·수수료·수령량을 확인한 뒤 주문을 확정하세요.');
  } catch (error) { if (!ticket.current()) return; ticket.finish('error'); renderQuote(); message('trade-message', friendlyTradeError(error.body, errorText(error)), 'warning'); if (error.status === 401) expireSession(); }
}
async function executeQuote() {
  if (!requireUser('market') || requests.busy('trade') || state.uncertain) return;
  const amount = $('trade-amount').value.trim(); const quote = state.quote;
  if (!quoteUsability(quote, amount).usable) { renderQuote(); return; }
  const side = state.side; const ticket = requests.begin('trade'); const ids = state.orders.map((item) => item.orderId);
  updateTradeControls(); message('trade-message', '주문을 접수하고 있습니다. 중복 제출하지 마세요.');
  try {
    const { body } = await (side === 'BUY' ? api.buy(amount, quote.quoteId) : api.sell(amount, quote.quoteId)); if (!ticket.current()) return;
    state.quote = null; ticket.finish('ready'); $('quote-dialog').close(); const order = acceptOrder(body); renderQuote(); message('trade-message', label(order.status));
    void loadActivity(); void loadPortfolio();
  } catch (error) {
    if (!ticket.current()) return;
    state.quote = null; ticket.finish('error');
    $('quote-dialog').close();
    if (uncertainWrite(error)) { state.uncertain = { ids, side, amount }; message('trade-message', '전송 결과를 확정할 수 없습니다. 자동 재주문하지 않습니다. 주문 내역으로 확인해 주세요.', 'warning'); }
    else message('trade-message', friendlyTradeError(error.body, errorText(error)), 'warning');
    renderQuote(); if (error.status === 401) expireSession();
  }
}
function socketStatus(status) {
  $('connection').textContent = ({ CONNECTED: '실시간 연결됨', CONNECTING: '연결 중', RECONNECTING: '연결 복구 중', DISCONNECTED: '연결 안 됨', ERROR: '연결 확인 필요', AUTH_EXPIRED: '인증 만료' })[status] || '연결 확인 중';
  if (status === 'CONNECTED') { void loadMarket(); void chart.load(); if (state.user) { void loadActivity(); void loadPortfolio(); } }
  if (status === 'RECONNECTING' || status === 'DISCONNECTED') message('market-message', '실시간 연결이 끊겼습니다. 자동 복구 중이며 표시 정보가 최신이 아닐 수 있습니다.', 'warning');
  if (status === 'AUTH_EXPIRED') expireSession();
}
const seenEvents = new Set();
function socketEvent(channel, event) {
  if (event.type === 'INVALID_JSON' || !event.data) return;
  if (event.eventId && seenEvents.has(event.eventId)) return;
  if (event.eventId) { seenEvents.add(event.eventId); if (seenEvents.size > 300) seenEvents.delete(seenEvents.values().next().value); }
  if (channel === 'price') {
    if (state.market?.observedAt && Date.parse(event.data.observedAt) < Date.parse(state.market.observedAt)) return;
    marketRevision++; marketRender({ ...state.market, ...event.data }); chart.tick(event.data);
  }
  if (channel === 'trade') {
    const row = node('div', '', 'stream-row'); row.append(node('span', event.data.side === 'BUY' ? '매수 체결' : '매도 체결'), node('strong', number(event.data.price, 'mKRW')), node('span', number(event.data.baseAmount, 'mSEC', 8)), node('span', time(event.occurredAt)));
    if (!$('public-trades').querySelector('.stream-row')) $('public-trades').replaceChildren(); $('public-trades').prepend(row); while ($('public-trades').children.length > 8) $('public-trades').lastChild.remove();
  }
  if (channel === 'order' && state.user) { acceptOrder(event.data); void loadActivity(); void loadPortfolio(); toast(`주문 #${event.data.orderId} · ${label(event.data.status)}`); }
  if (channel === 'portfolio' && state.user) { portfolioRevision++; state.portfolio = event.data; renderPortfolio(); }
}
function syncAiTarget() { $('ai-target-wrap').hidden = !['quote', 'order'].includes($('ai-kind').value); }
function selectAi(kind, target, question) { if (!requireUser('ai')) return; $('ai-kind').value = kind; $('ai-target').value = target || ''; $('ai-question').value = question; syncAiTarget(); navigate('ai'); }
async function runAi(event) {
  event.preventDefault(); if (!requireUser('ai') || requests.busy('ai')) return;
  const kind = $('ai-kind').value, value = $('ai-target').value.trim(), question = $('ai-question').value.trim();
  let target = null, skill = null;
  if (kind === 'order') { const id = Number(value); if (!Number.isSafeInteger(id) || id <= 0) { message('ai-message', '양의 정수 주문 번호를 입력해 주세요.', 'warning'); return; } target = { orderId: id }; }
  if (kind === 'quote') { if (!/^0x[0-9a-fA-F]{64}$/.test(value)) { message('ai-message', '발급된 견적 번호를 선택하거나 정확히 입력해 주세요.', 'warning'); return; } target = { quoteId: value }; skill = 'signed-quote-diagnosis'; }
  if (kind === 'market') skill = 'market-availability-diagnosis';
  if (new TextEncoder().encode(JSON.stringify({ question, target, skillId: skill })).length > 4000) { message('ai-message', '질문을 조금 더 짧게 입력해 주세요.', 'warning'); return; }
  $('ai-submit').disabled = true; empty($('ai-result'), '근거를 조회하고 있습니다.');
  const generation = requests.generation;
  await read('ai', () => api.agent(question, target, skill), (body) => renderDiagnosis($('ai-result'), body), 'ai-message', true);
  if (generation === requests.generation) { $('ai-submit').disabled = false; if (requests.resources.get('ai')?.status === 'error') empty($('ai-result'), '이번 분석 결과를 받지 못했습니다. 거래 내역은 별도로 확인할 수 있습니다.'); }
}
async function loadDiagnosisHistory(before = null) {
  if (state.user?.role !== 'ADMIN' || (before && requests.busy('diagnoses'))) return;
  const text = $('admin-filter-order').value.trim(); const id = text ? Number(text) : null;
  if (text && (!Number.isSafeInteger(id) || id <= 0)) { message('admin-message', '필터에는 양의 정수 주문 번호를 입력해 주세요.', 'warning'); return; }
  $('admin-older').disabled = true;
  await read('diagnoses', () => api.diagnoses(id, before), (body) => {
    state.history = before ? [...state.history, ...body.items] : body.items; state.nextBefore = body.nextBefore;
    if (!state.history.length) empty($('admin-history'), '이력이 없습니다. 자동 진단 비활성 또는 대상 없음일 수 있습니다.');
    else $('admin-history').replaceChildren(...state.history.map((item) => { const card = node('article', '', 'record'); card.append(node('strong', `진단 #${item.id} · 주문 #${item.orderId}`), node('p', `${label(item.jobStatus)} · ${time(item.detectedAt)}`), button('근거·단계 보기', () => loadDiagnosis(item.id))); return card; }));
  }, 'admin-message', true);
  $('admin-older').disabled = state.user?.role !== 'ADMIN' || !state.nextBefore || requests.busy('diagnoses');
}
async function loadDiagnosis(id) {
  if (state.user?.role !== 'ADMIN') return;
  empty($('admin-result'), `진단 #${id} · 결과를 확인하는 중입니다.`);
  await read('admin-result', () => api.diagnosis(id), (body) => {
    renderDiagnosis($('admin-result'), body.result); $('admin-result').prepend(node('p', `과거 관측 · 분석 종료 ${time(body.completedAt)} · ${body.targetStale ? '대상 상태가 변경되었을 수 있음' : '현재 상태와 다시 대조하세요'}`, 'notice warning'));
    $('admin-result').prepend(node('h2', `진단 #${id}`));
  }, 'admin-message', true);
}
async function manualDiagnosis(event) {
  event.preventDefault(); if (state.user?.role !== 'ADMIN' || $('admin-run').disabled) return;
  const id = Number($('admin-order').value); if (!Number.isSafeInteger(id) || id <= 0) { message('admin-message', '양의 정수 주문 번호를 입력해 주세요.', 'warning'); return; }
  $('admin-run').disabled = true; const generation = requests.generation;
  empty($('admin-result'), `주문 #${id} · 수동 진단을 진행하는 중입니다.`);
  await read('admin-result', () => api.diagnoseOrder(id), (body) => { renderDiagnosis($('admin-result'), body); $('admin-result').prepend(node('h2', `주문 #${id} · 수동 진단`)); }, 'admin-message', true);
  if (generation === requests.generation) $('admin-run').disabled = false;
}
async function loadObservation() {
  if (state.user?.role !== 'ADMIN') return;
  await read('observation', () => api.observation(), (body) => {
    $('observation').replaceChildren(fields([['집계 범위', '현재 프로세스 · 재시작 시 초기화'], ['시작 시각', time(body.startedAt)], ['진단 DB', body.diagnosis?.status || '확인 불가']]));
    for (const [name, item] of Object.entries(body.series || {})) $('observation').append(node('p', `${name} · 호출 ${item.calls} · 실패 ${item.failures} · 평균 ${number(item.averageLatencyMs, 'ms')} · 입력/출력 토큰 ${item.reportedInputTokens ?? '—'}/${item.reportedOutputTokens ?? '—'} · 사용량 미보고 ${item.usageUnknownCalls ?? '—'}`));
  }, null, true);
  if (requests.resources.get('observation')?.status === 'error') empty($('observation'), '관측 정보를 불러오지 못했습니다.');
}
async function faucet() {
  if (!requireUser('portfolio') || requests.busy('faucet')) return;
  const ticket = requests.begin('faucet'); $('faucet').disabled = true; message('faucet-message', '모의 자금을 지급받는 중입니다.');
  try { await api.faucet(); if (!ticket.current()) return; ticket.finish('ready'); message('faucet-message', '모의 자금을 받았습니다.'); void loadPortfolio(); }
  catch (error) { if (!ticket.current()) return; ticket.finish('error'); message('faucet-message', uncertainWrite(error) ? '지급 결과를 확인하지 못했습니다. 자동 재요청하지 않으며 자산을 다시 조회해 주세요.' : errorText(error), 'warning'); if (error.status === 401) expireSession(); }
  finally { if (ticket.current()) $('faucet').disabled = false; }
}

window.addEventListener('hashchange', route);
$('auth-open').onclick = () => showAuth();
function cancelAuth() { if (requests.busy('auth')) { requests.invalidate('auth'); api.clearToken(); } $('auth-submit').disabled = false; $('password').value = ''; }
$('auth-close').onclick = () => { cancelAuth(); $('auth-dialog').close(); }; $('auth-dialog').addEventListener('cancel', cancelAuth); $('auth-form').onsubmit = authenticate;
$('auth-switch').onclick = () => { if (requests.busy('auth')) return; state.authMode = state.authMode === 'login' ? 'signup' : 'login'; const signup = state.authMode === 'signup'; $('auth-title').textContent = signup ? '회원가입' : '로그인'; $('auth-submit').textContent = signup ? '회원가입하고 시작하기' : '로그인'; $('auth-switch').textContent = signup ? '이미 계정이 있나요? 로그인' : '처음이신가요? 회원가입'; $('nickname-wrap').hidden = !signup; $('nickname').required = signup; $('password').autocomplete = signup ? 'new-password' : 'current-password'; message('auth-message'); };
$('logout').onclick = () => logout(); $('trade-form').onsubmit = issueQuote;
$('quote-confirm').onclick = () => { if (!state.quote) return; $('quote-dialog').showModal(); renderQuote(); };
$('quote-close').onclick = () => $('quote-dialog').close(); $('quote-submit').onclick = executeQuote;
$('trade-amount').oninput = invalidateQuote;
for (const side of ['BUY', 'SELL']) $(`side-${side.toLowerCase()}`).onclick = () => { state.side = side; $('trade-amount').value = ''; $('amount-label').textContent = side === 'BUY' ? '사용할 모의 원화 (mKRW)' : '판매할 토큰 수량 (mSEC)'; $('side-buy').setAttribute('aria-pressed', side === 'BUY'); $('side-sell').setAttribute('aria-pressed', side === 'SELL'); invalidateQuote(); };
$('quote-diagnose').onclick = () => { if (state.quote?.quoteId) selectAi('quote', state.quote.quoteId, '이 견적의 사용 가능 여부와 조건을 진단해주세요.'); };
$('resolve-order').onclick = () => { navigate('history'); void loadActivity(); };
$('acknowledge-order').onclick = () => {
  if (!state.uncertain?.checked) return;
  if (window.confirm('기존 주문 상태를 확인했나요? 새 주문은 별도로 실행되어 중복 거래가 될 수 있습니다. 확인한 경우에만 새 견적을 준비하세요.')) {
    state.uncertain = null; updateTradeControls(); message('trade-message', '이전 견적은 재사용하지 않습니다. 새 주문을 원하면 새 견적을 확인하세요.', 'warning');
  }
};
$('history-refresh').onclick = loadActivity; for (const tab of ['orders', 'trades']) $(`history-${tab}`).onclick = () => { state.historyTab = tab; renderHistory(); };
$('portfolio-refresh').onclick = loadPortfolio; $('faucet').onclick = faucet;
$('ai-kind').onchange = syncAiTarget; $('ai-form').onsubmit = runAi;
$('ask-policy').onclick = () => selectAi('general', null, 'mSEC와 모의 원화, 견적과 체결 방식을 설명해주세요.');
$('ask-market').onclick = () => selectAi('market', null, '지금 시장에서 신규 거래가 가능한지 진단해주세요.');
$('admin-manual').onsubmit = manualDiagnosis; $('admin-filter').onsubmit = (event) => { event.preventDefault(); void loadDiagnosisHistory(); };
$('admin-filter-order').oninput = () => { requests.invalidate('diagnoses'); state.nextBefore = null; $('admin-older').disabled = true; };
$('admin-older').onclick = () => { if (state.nextBefore) void loadDiagnosisHistory(state.nextBefore); }; $('observation-refresh').onclick = loadObservation;
$('reconnect').onclick = connect; $('transport').onchange = connect; $('chart-reload').onclick = () => chart.load();
document.querySelectorAll('[data-interval]').forEach((element) => element.onclick = () => { document.querySelectorAll('[data-interval]').forEach((other) => other.setAttribute('aria-pressed', other === element)); void chart.select(element.dataset.interval); });
setInterval(() => { if (state.quote) renderQuote(); }, 1000);
renderIdentity(); route(); void loadMarket(); void chart.load(); connect();
