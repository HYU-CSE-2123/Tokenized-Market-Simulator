import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { RequestState } from '../src/state.js';
import { shouldRenderOrder } from '../src/trade-quote.js';

// Execute the actual orchestration functions, not a second implementation of their guards.
const source = readFileSync(new URL('../src/main.js', import.meta.url), 'utf8');
function harness() {
  const elements = new Map();
  const node = (_, text = '') => ({ textContent: text });
  const $ = (id) => {
    if (!elements.has(id)) elements.set(id, { value: '2', disabled: false, hidden: true, children: [],
      replaceChildren(...children) { this.children = children; }, append(...children) { this.children.push(...children); },
      prepend(...children) { this.children.unshift(...children); }, scrollIntoView() {} });
    return elements.get(id);
  };
  const state = { user: { role: 'ADMIN' }, orders: [], latestId: null, detailId: null };
  const context = vm.createContext({ $, state, requests: new RequestState(), api: {}, shouldRenderOrder,
    node, button: (text) => node('', text), fields: (rows) => node('', JSON.stringify(rows)),
    empty: (element, text) => element.replaceChildren(node('', text)),
    renderDiagnosis: (element, body) => element.replaceChildren(node('', body.answer)),
    renderHistory() {}, renderLatest() {}, updateTradeControls() {}, navigate() {}, selectAi() {},
    label: (value) => value, time: (value) => value, message: (id, text) => $(id).replaceChildren(node('', text)),
    errorText: () => 'not found', expireSession() {} });
  for (const name of ['read', 'acceptOrder', 'showOrder', 'renderOrderDetail', 'loadDiagnosis', 'manualDiagnosis']) {
    const match = source.match(new RegExp(`(?:async )?function ${name}\\([^]*?\\n\\}`));
    assert.ok(match, name); vm.runInContext(match[0], context);
  }
  return { context, state, $, text: (id) => $(id).children.map((item) => item.textContent).join(' ') };
}
test('order detail uses canonical terminal state and updates when an event arrives', async () => {
  const h = harness(); h.context.api.order = async (orderId) => ({ body: { orderId, status: 'PENDING_ONCHAIN' } });
  h.context.acceptOrder({ orderId: 1, status: 'FILLED' }); await h.context.showOrder(1);
  assert.match(h.text('order-detail'), /FILLED/); assert.doesNotMatch(h.text('order-detail'), /PENDING_ONCHAIN/);
  await h.context.showOrder(2); h.context.acceptOrder({ orderId: 2, status: 'FILLED' });
  assert.match(h.text('order-detail'), /FILLED/);
});
test('a newer ADMIN history selection supersedes an outstanding manual diagnosis', async () => {
  const h = harness(); let finish;
  h.context.api.diagnoseOrder = () => new Promise((resolve) => { finish = resolve; });
  h.context.api.diagnosis = async () => ({ body: { result: { answer: 'selected history' }, targetStale: true } });
  const manual = h.context.manualDiagnosis({ preventDefault() {} }); await h.context.loadDiagnosis(3);
  finish({ body: { answer: 'old manual' } }); await manual;
  assert.match(h.text('admin-result'), /진단 #3.*selected history/);
  assert.doesNotMatch(h.text('admin-result'), /old manual/); assert.equal(h.$('admin-run').disabled, false);
});
test('ADMIN detail 404 discards the previous successful result', async () => {
  const h = harness(); h.context.api.diagnosis = async () => ({ body: { result: { answer: 'old result' } } });
  await h.context.loadDiagnosis(1);
  h.context.api.diagnosis = async () => { throw { status: 404 }; }; await h.context.loadDiagnosis(4);
  assert.doesNotMatch(h.text('admin-result'), /old result/); assert.match(h.text('admin-result'), /진단 #4/);
  assert.equal(h.text('admin-message'), 'not found');
});
