/** Actual isolated backend + browser/STOMP readiness. No fixtures, orders, AI, or old services.
 * One explicit UI faucet into the acceptance user's NEW DB; secrets remain in memory/private root.
 */
import { readFile, mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
// scripts is under tools/websocket-test-client: three parents reach the repository.
const run = resolve(process.argv[2] || '');
assert.equal(dirname(run), join(repo, 'deployment/runtime'));
assert.match(run.split(/[\\/]/).at(-1), /^acceptance-\d{14}-[0-9a-f]{6}$/);
const meta = JSON.parse(await readFile(join(run, 'run.json'), 'utf8'));
assert.equal(meta.project, 'exchange-' + run.split(/[\\/]/).at(-1));
const account = JSON.parse(await readFile(join(run, 'secrets/ui-account.json'), 'utf8'));
const attempt = process.argv[3] || 'initial';
assert.match(attempt, /^[a-z0-9-]{1,40}$/);
// A new named attempt requires checking the previous failure phase/DB balance first.
// Never reuse/delete an earlier marker or silently repeat an uncertain faucet operation.
const artifacts = join(run, 'browser', attempt); await mkdir(artifacts, { recursive: true });
// Refuse silent repetitions after any partial browser run (including an uncertain faucet response).
await writeFile(join(artifacts, 'readiness-attempt.json'), JSON.stringify({ startedAt: new Date().toISOString() }), { flag: 'wx', mode: 0o600 });
const profile = await mkdtemp(join(artifacts, 'profile-'));
const chrome = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const child = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
let ws, serial = 0; const pending = new Map(); const subscriptions = new Set(); const received = [];
let phase = 'browser-start';
async function poll(fn, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { try { const v = await fn(); if (v) return v; } catch {} await new Promise(r => setTimeout(r, 100)); }
  throw new Error('Acceptance condition timed out');
}
function command(method, params = {}) {
  return new Promise((resolve, reject) => { const id = ++serial; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
}
async function evaluate(expression) {
  const r = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('Browser expression failed');
  return r.result.value;
}
const click = id => evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill = (id, value) => evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
const waitText = (id, value) => poll(() => evaluate(`document.getElementById(${JSON.stringify(id)}).textContent.includes(${JSON.stringify(value)})`));
try {
  const port = await poll(async () => (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]);
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r));
  ws.addEventListener('message', event => {
    const msg = JSON.parse(event.data);
    if (msg.id) { const task = pending.get(msg.id); pending.delete(msg.id); if (msg.error) task.reject(new Error('CDP failed')); else task.resolve(msg.result); }
    else if (msg.method === 'Network.webSocketFrameSent') {
      const text = msg.params.response.payloadData;
      // Never capture CONNECT Authorization headers.
      if (text.startsWith('SUBSCRIBE')) { const dest = text.match(/destination:([^\n]+)/)?.[1]; if (dest) subscriptions.add(dest.trim()); }
    } else if (msg.method === 'Network.webSocketFrameReceived') {
      const text = msg.params.response.payloadData; const at = text.indexOf('\n\n');
      if (!text.startsWith('MESSAGE') || at < 0) return;
      try { const body = JSON.parse(text.slice(at + 2).replace(/\0$/, '')); received.push({ type: body.type, eventId: body.eventId }); } catch {}
    }
  });
  await command('Runtime.enable'); await command('Page.enable'); await command('Network.enable');
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  phase = 'actual-toss-page';
  await command('Page.navigate', { url: 'http://127.0.0.1:15173/' });
  await waitText('market-mode', 'Toss 기준 시세'); await waitText('connection', '연결됨');
  phase = 'login-and-private-subscriptions';
  await click('auth-open'); await fill('login-id', account.loginId); await fill('password', account.password);
  await evaluate(`document.getElementById('auth-form').requestSubmit()`);
  await waitText('identity', account.nickname);
  await poll(() => subscriptions.has('/user/queue/orders') && subscriptions.has('/user/queue/portfolio'));
  phase = 'actual-faucet-and-private-event';
  await evaluate(`location.hash='portfolio'`);
  await waitText('portfolio-summary', '평균 매수가');
  await click('faucet'); await waitText('faucet-message', '모의 자금을 받았습니다');
  await poll(() => received.some(e => e.type === 'PORTFOLIO_UPDATED'));
  const shot = await command('Page.captureScreenshot', { format: 'png' });
  await writeFile(join(artifacts, 'portfolio.png'), Buffer.from(shot.data, 'base64'), { flag: 'wx', mode: 0o600 });
  phase = 'responsive-views';
  await command('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  for (const route of ['market', 'history', 'portfolio']) {
    await evaluate(`location.hash=${JSON.stringify(route)}`);
    await poll(() => evaluate(`document.getElementById('view-'+${JSON.stringify(route)}).hidden===false`));
    assert.equal(await evaluate('document.documentElement.scrollWidth<=window.innerWidth'), true);
  }
  const result = { browser: 'ACTUAL_CHROME_NO_FIXTURES', provider: 'TOSS', login: 'PASS',
    subscriptions: [...subscriptions], privatePortfolioEvent: received.some(e => e.type === 'PORTFOLIO_UPDATED'),
    receivedEventTypes: [...new Set(received.map(e => e.type))], mobileViews: ['market', 'history', 'portfolio'],
    tradesExecuted: 0, aiCalls: 0, marketOpenBuySellAcceptance: 'NOT_RUN' };
  await writeFile(join(artifacts, 'readiness.json'), JSON.stringify(result, null, 2), { flag: 'wx', mode: 0o600 });
  console.log(JSON.stringify(result));
} catch {
  await writeFile(join(artifacts, 'failure.json'), JSON.stringify({ phase, failedAt: new Date().toISOString() }), { flag: 'wx', mode: 0o600 }).catch(() => {});
  console.error('Actual acceptance browser failed at ' + phase + '. Preserve evidence; do not auto-repeat faucet/orders.');
  process.exitCode = 1;
} finally {
  if (ws?.readyState === WebSocket.OPEN) { try { await command('Browser.close'); } catch {} ws.close(); }
  else child.kill();
}
