/** Built-in Node CDP browser smoke: real Chromium/compiled UI, deterministic REST + STOMP fixtures.
 * No real users, trading DB writes or paid AI calls. Screenshots stay under ignored dist/.smoke.
 */
import { createServer } from 'node:http';
import { readFile, mkdir, mkdtemp } from 'node:fs/promises';
import { resolve, join, extname } from 'node:path';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

const root = resolve('dist'); const artifacts = join(root, '.smoke'); await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(join(artifacts, 'profile-'));
let role = 'USER', orders = [], failWrite = false, failAi = false, delayedAi = false;
const quoteId = '0x' + '1'.repeat(64); let expiry = 30;
const agent = { status: 'PARTIAL', answer: '<img src=x onerror=alert(1)> 관측 가능한 범위에서 설명합니다.', uncertainties: ['receipt를 확인할 수 없습니다.'],
  toolEvidence: [{ tool: 'getOrder', status: 'OK', data: { status: 'PENDING_ONCHAIN' } }],
  knowledgeSources: [{ id: 'k1', title: '거래 정책', version: '6' }],
  skill: { id: 'signed-quote-diagnosis', diagnosis: { classification: 'WAITING_OBSERVED' }, trace: [{ stepId: 'CHECK', status: 'DONE', resultCode: 'OK', evidenceRefs: ['t1'] }] } };
const requests = [];
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://fixture');
  if (!url.pathname.startsWith('/api/')) {
    const file = resolve(root, '.' + (url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(root + '\\') && !file.startsWith(root + '/')) { res.writeHead(403).end(); return; }
    try { res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' })[extname(file)] || 'application/octet-stream'); res.end(await readFile(file)); } catch { res.writeHead(404).end(); } return;
  }
  let text = ''; for await (const chunk of req) text += chunk; const input = text ? JSON.parse(text) : {};
  requests.push({ path: url.pathname, method: req.method, input });
  const now = new Date().toISOString(); let body = {}, status = 200;
  if (url.pathname.startsWith('/api/auth/')) { role = input.loginId === 'admin' ? 'ADMIN' : 'USER'; body = { accessToken: 'fixture-token', expiresIn: 3600 }; }
  else if (url.pathname === '/api/me') body = { nickname: role === 'ADMIN' ? '관리자' : '모의 사용자', role };
  else if (url.pathname.includes('/candles')) {
    const interval = url.searchParams.get('interval'); const max = { '1m': 100, '5m': 100, '15m': 50, '30m': 30, '1h': 20, '1d': 100 }[interval];
    if (+url.searchParams.get('count') > max) { status = 400; body = { code: 'INVALID_CANDLE_QUERY' }; }
    else body = { provider: 'SIMULATED', candles: [{ startedAt: now, open: '75000', high: '76000', low: '74000', close: '75500', volume: '10' }], nextBefore: null };
  }
  else if (url.pathname === '/api/markets/mSEC') body = { price: '75000', change: '1000', changeRate: '1.35', provider: 'SIMULATED', marketStatus: 'UNKNOWN', priceStatus: 'SIMULATED', observedAt: now };
  else if (url.pathname === '/api/portfolio') body = { krwBalance: '1000000', tokenBalance: '2', currentPrice: '75000', averageBuyPrice: '70000', tokenValue: '150000', unrealizedProfit: '10000', totalValue: '1150000' };
  else if (url.pathname.startsWith('/api/quotes/')) body = { side: url.pathname.endsWith('buy') ? 'BUY' : 'SELL', price: '75000', fee: '100', expectedTokenAmount: '1.332', expectedKrwAmount: '74925', minimumOutputAmount: '1.332', quoteId, observedAt: now, validUntil: new Date(Date.now() + expiry * 1000).toISOString(), status: 'ISSUED' };
  else if (url.pathname === '/api/orders') body = orders;
  else if (url.pathname.endsWith('/buy') || url.pathname.endsWith('/sell')) {
    if (failWrite) { status = 503; body = { code: 'BLOCKCHAIN_UNAVAILABLE' }; }
    else { const order = { orderId: orders.length + 1, side: input.krwAmount ? 'BUY' : 'SELL', status: 'PENDING_ONCHAIN', inputAmount: input.krwAmount || input.tokenAmount, outputAmount: '1.3', createdAt: now }; orders.push(order); body = order; status = 202; }
  }
  else if (url.pathname.startsWith('/api/orders/')) body = orders.find((order) => order.orderId === Number(url.pathname.split('/').at(-1)));
  else if (url.pathname === '/api/trades') body = [];
  else if (url.pathname === '/api/admin/trade-audits') body = req.method === 'POST'
    ? { id: 'audit-fixture', lifecycle: 'RUNNING', verdict: 'INCONCLUSIVE' }
    : { items: [{ id: 'audit-fixture', lifecycle: 'COMPLETED', verdict: 'INCONCLUSIVE', startedAt: now }], nextBefore: null };
  else if (url.pathname === '/api/admin/trade-audits/audit-fixture') body = {
    id: 'audit-fixture', lifecycle: 'COMPLETED', verdict: 'INCONCLUSIVE', reason: 'ITEM_EVIDENCE_INCOMPLETE',
    startedAt: now, coverage: { dbComplete: true, chainComplete: false, cutStable: true, environmentVerified: true }, match: 1, inconclusive: 1 };
  else if (url.pathname === '/api/admin/trade-audits/audit-fixture/items') body = {
    items: [{ key: 'order:1', orderId: 1, mode: 'ONCHAIN', side: 'BUY', verdict: 'INCONCLUSIVE', reason: 'HISTORICAL_CONFIG_UNAVAILABLE', checks: [] }], nextBefore: null };
  else if (url.pathname === '/api/admin/reserve-reconciliations') body = req.method === 'POST'
    ? { id: 'reserve-fixture', status: 'MATCH', reason: 'EXACT_QUANTITY_MATCH', createdAt: now, dbAt: now,
        chain: { block: { number: 123, hash: '0x' + 'a'.repeat(64) } }, checks: [],
        liquidity: { operatorBuyFunds: '1000000000000000000000000', buyAllowance: '1000000000000000000000000',
          operatorSellTokens: '0', vaultSellFunds: '5000000000000000000000000', userKrw: '1000000000000000000000000', userSec: '0', operatorAllocationReference: '1.0000', operatorEthWei: '1000000000000000000', gasAssessment: 'READ_ONLY_NOT_ESTIMATED' } }
    : [{ id: 'reserve-fixture', status: 'MATCH', createdAt: now }];
  else if (url.pathname === '/api/admin/reserve-reconciliations/reserve-fixture') body = {
    id: 'reserve-fixture', status: 'INCONCLUSIVE', reason: 'CUT_CHANGED', createdAt: now, checks: [], liquidity: null };
  else if (url.pathname === '/api/ai/agent/answers') { if (delayedAi) await new Promise((done) => setTimeout(done, 400)); if (failAi) { status = 503; body = { error: 'AGENT_DISABLED' }; } else body = agent; }
  else if (url.pathname === '/api/ai/diagnoses') body = { items: [{ id: 1, orderId: 1, jobStatus: 'COMPLETED', detectedAt: now }], nextBefore: null };
  else if (url.pathname === '/api/ai/diagnoses/1') body = { completedAt: now, targetStale: true, result: { ...agent, responseStatus: 'PARTIAL' } };
  else if (url.pathname === '/api/ai/observability') body = { startedAt: now, diagnosis: { status: 'AVAILABLE' }, series: { 'LLM:call': { calls: 2, failures: 0, averageLatencyMs: 123, reportedInputTokens: 20, reportedOutputTokens: 5, usageUnknownCalls: 0 } } };
  res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body));
});
await new Promise((done) => server.listen(0, '127.0.0.1', done)); const port = server.address().port;
const chrome = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const child = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
let ws, serial = 0; const pending = new Map(); const errors = [];
async function poll(operation, timeout = 15000) { const start = Date.now(); for (;;) { try { const value = await operation(); if (value) return value; } catch {} if (Date.now() - start > timeout) throw new Error('Browser smoke wait timed out'); await new Promise((done) => setTimeout(done, 50)); } }
function command(method, params = {}) { return new Promise((resolve, reject) => { const id = ++serial; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); }); }
async function evaluate(expression) { const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (result.exceptionDetails) throw new Error(result.exceptionDetails.text); return result.result.value; }
const click = (id) => evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill = (id, value) => evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
const submit = (id) => evaluate(`document.getElementById(${JSON.stringify(id)}).requestSubmit()`);
const waitText = (id, text) => poll(() => evaluate(`document.getElementById(${JSON.stringify(id)}).textContent.includes(${JSON.stringify(text)})`));
async function screenshot(name) { const data = await command('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }); await writeFile(join(artifacts, name + '.png'), Buffer.from(data.data, 'base64')); }
try {
  const debugPort = await poll(async () => (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]);
  const targets = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
  ws = new WebSocket(targets.find((target) => target.type === 'page').webSocketDebuggerUrl); await new Promise((done) => ws.addEventListener('open', done));
  ws.addEventListener('message', (event) => { const result = JSON.parse(event.data); if (result.id) { const task = pending.get(result.id); pending.delete(result.id); if (result.error) task.reject(new Error(result.error.message)); else task.resolve(result.result); } else if (result.method === 'Runtime.exceptionThrown') errors.push(result.params.exceptionDetails.text); });
  await command('Runtime.enable'); await command('Page.enable');
  // Use compiled STOMP client with a deterministic transport; not a real backend STOMP claim.
  await command('Page.addScriptToEvaluateOnNewDocument', { source: `window.__sockets=[];window.WebSocket=class{static OPEN=1;constructor(){this.readyState=0;this.subs={};window.__sockets.push(this);setTimeout(()=>{this.readyState=1;this.onopen?.({});},20);}send(frame){if(typeof frame!=='string')frame=new TextDecoder().decode(frame);if(frame.startsWith('CONNECT'))setTimeout(()=>this.onmessage?.({data:'CONNECTED\\nversion:1.2\\nheart-beat:0,0\\n\\n\\0'}),5);if(frame.startsWith('SUBSCRIBE')){const id=frame.match(/id:([^\\n]+)/)?.[1],dest=frame.match(/destination:([^\\n]+)/)?.[1];this.subs[dest]=id;}if(frame.startsWith('DISCONNECT')){const r=frame.match(/receipt:([^\\n]+)/)?.[1];if(r)this.onmessage?.({data:'RECEIPT\\nreceipt-id:'+r+'\\n\\n\\0'});}}close(){this.readyState=3;this.onclose?.({});}emit(dest,event){const sub=this.subs[dest];if(sub)this.onmessage?.({data:'MESSAGE\\nsubscription:'+sub+'\\nmessage-id:fixture\\ndestination:'+dest+'\\n\\n'+JSON.stringify(event)+'\\0'});}};` });
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await command('Page.navigate', { url: `http://127.0.0.1:${port}/` });
  await waitText('price', '75,000'); await waitText('connection', '연결됨');
  await waitText('market-mode', 'SIMULATED'); await waitText('market-description', '실제 삼성전자 시세가 아닙니다');
  await waitText('volume-description', '합성 거래량'); await screenshot('desktop-market');
  assert.equal(await evaluate(`document.getElementById('admin-nav').hidden`), true);
  assert.equal(requests.some((request) => request.path === '/api/portfolio'), false);
  await click('auth-open'); await click('auth-switch'); await fill('login-id', 'fixtureuser'); await fill('password', 'password123'); await fill('nickname', '모의 사용자'); await submit('auth-form'); await waitText('identity', '모의 사용자');
  assert.ok(requests.some((request) => request.path === '/api/auth/signup'));
  await fill('trade-amount', '100000'); await submit('trade-form'); await waitText('quote-summary', '75,000');
  await fill('trade-amount', '110000'); assert.equal(await evaluate(`document.getElementById('quote-confirm').disabled`), true);
  await submit('trade-form'); await waitText('quote-summary', '75,000'); await click('quote-confirm'); await click('quote-submit'); await waitText('latest-order', '블록체인 확인 중');
  assert.equal(requests.filter((request) => request.path === '/api/orders/buy').length, 1);
  assert.doesNotMatch(await evaluate(`document.getElementById('latest-order').textContent`), /실제 체결가/);
  await evaluate(`window.__sockets.at(-1).emit('/user/queue/orders',{eventId:'fill1',type:'ORDER_FILLED',data:{orderId:1,side:'BUY',status:'FILLED',inputAmount:'110000',outputAmount:'1.3'}})`);
  await waitText('latest-order', '체결 완료');
  // An older REST snapshot must not roll back a terminal event.
  await poll(() => evaluate(`document.getElementById('latest-order').textContent.includes('체결 완료')`));
  await evaluate(`location.hash='portfolio'`); await waitText('portfolio-summary', '평균 매수가'); await screenshot('desktop-portfolio');
  await evaluate(`location.hash='ai'`); await click('ask-policy'); await submit('ai-form'); await waitText('ai-result', '정책 출처');
  assert.equal(await evaluate(`document.getElementById('ai-result').querySelector('img')===null`), true); await screenshot('desktop-ai');
  failAi = true; await submit('ai-form'); await waitText('ai-message', '준비되지'); failAi = false;
  assert.equal(await evaluate(`document.getElementById('logout').hidden`), false);
  // Late private AI response after logout cannot restore the old user's analysis.
  delayedAi = true; await submit('ai-form'); await click('logout'); await new Promise((done) => setTimeout(done, 650));
  assert.equal(await evaluate(`document.getElementById('ai-result').textContent`), ''); delayedAi = false;
  await click('auth-open'); await click('auth-switch'); await fill('login-id', 'admin'); await fill('password', 'password123'); await submit('auth-form'); await waitText('identity', '관리자');
  await evaluate(`location.hash='admin'`); await waitText('admin-history', '진단 #1'); await waitText('observation', 'LLM:call');
  await evaluate(`document.getElementById('admin-history').querySelector('button').click()`); await waitText('admin-result', '과거 관측'); await screenshot('desktop-admin');
  await click('reserve-run'); await waitText('reserve-result', '장부·체인 수량 일치');
  await waitText('reserve-result', '1 ETH'); await waitText('reserve-result', '비용 미추정');
  await waitText('reserve-result', '준비금 보증 비율이나 Proof of Reserves가 아니며');
  await click('reserve-refresh'); await waitText('reserve-history', '최근 대사 20건');
  await evaluate(`document.getElementById('reserve-history').querySelector('button').click()`);
  await waitText('reserve-result', '동일 시점·근거 확인 불가');
  await evaluate(`window.confirm=()=>true`); await click('audit-run');
  await waitText('audit-result', '전수 MATCH를 뜻하지 않습니다');
  await waitText('audit-items', 'HISTORICAL_CONFIG_UNAVAILABLE');
  await click('audit-refresh'); await waitText('audit-history', 'INCONCLUSIVE');
  await command('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  for (const route of ['market', 'history', 'portfolio', 'ai', 'admin']) {
    await evaluate(`location.hash=${JSON.stringify(route)}`); await new Promise((done) => setTimeout(done, 120));
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=window.innerWidth`), true, 'horizontal overflow: ' + route);
    await screenshot('mobile-' + route);
  }
  for (const interval of ['1m', '5m', '15m', '30m', '1h', '1d']) await evaluate(`document.querySelector('[data-interval="${interval}"]').click()`);
  await evaluate(`location.hash='market'`); await click('side-sell'); await fill('trade-amount', '1'); expiry = 1; await submit('trade-form'); await waitText('quote-summary', '75,000');
  // Wait for the real 1s UI timer rather than assuming its phase relative to a fixed 1300ms sleep.
  await waitText('quote-timer', '견적이 만료됐습니다');
  assert.equal(await evaluate(`document.getElementById('quote-confirm').disabled`), true); expiry = 30;
  await submit('trade-form'); await waitText('quote-summary', '75,000'); await click('quote-confirm'); await screenshot('mobile-quote-sheet');
  failWrite = true; await click('quote-submit'); await waitText('trade-message', '자동 재주문하지');
  assert.equal(await evaluate(`document.getElementById('quote-request').disabled`), true);
  assert.equal(requests.filter((request) => request.path === '/api/orders/sell').length, 1);
  await click('resolve-order'); await waitText('history-list', '블록체인 확인 중');
  assert.deepEqual(errors, []); console.log('PASS browser smoke: guest / signup-auto-login / quotes / pending+event / portfolio / AI+safe text+disabled / logout race / ADMIN history+observation / six intervals / mobile five routes+sheet / uncertain write (fixtures).');
} finally {
  if (ws?.readyState === WebSocket.OPEN) { try { await command('Browser.close'); } catch {} ws.close(); }
  else child.kill(); server.close();
}
