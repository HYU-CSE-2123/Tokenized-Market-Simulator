import test from 'node:test';
import assert from 'node:assert/strict';
import { RequestState, label, positiveAmount, time, number, uncertainWrite, marketNotice } from '../src/state.js';
import { ApiClient } from '../src/api.js';
import { renderDiagnosis, orderCard } from '../src/ui.js';

test('requests isolate resources, same-resource races and session replacements', () => {
  const state = new RequestState(); const first = state.begin('orders'), quote = state.begin('quote');
  assert.equal(first.current(), true); assert.equal(quote.current(), true);
  const next = state.begin('orders'); assert.equal(first.finish('ready'), false);
  assert.equal(next.finish('ready'), true); assert.equal(state.busy('quote'), true);
  state.reset(); assert.equal(quote.current(), false); assert.equal(state.busy('quote'), false);
});
test('input edits invalidate in-flight quote without locking other actions', () => {
  const state = new RequestState(); const quote = state.begin('quote'); const ai = state.begin('ai');
  state.invalidate('quote'); assert.equal(quote.finish('ready'), false); assert.equal(state.busy('quote'), false); assert.equal(ai.current(), true);
});
test('decimal input allows 18 places but not exponents, negatives or non-finite values', () => {
  for (const value of ['100000', '0.1', '1.000000000000000001']) assert.equal(positiveAmount(value), true);
  for (const value of ['0', '-1', '1e5', '', 'NaN', 'Infinity', '0.0000000000000000001']) assert.equal(positiveAmount(value), false);
});
test('formatting handles unknown values and fixed KST regardless of machine timezone', () => {
  assert.equal(number(null), '—'); assert.equal(number('unknown'), '—'); assert.equal(time('bad'), '—');
  assert.match(time('2026-10-05T00:00:00Z'), /9:00:00/); assert.equal(label('PENDING_ONCHAIN'), '블록체인 확인 중');
});
test('only potentially committed writes are marked uncertain, never auto-retried', () => {
  assert.equal(uncertainWrite(new TypeError('network')), true); assert.equal(uncertainWrite({ status: 500 }), true);
  assert.equal(uncertainWrite({ status: 409 }), false); assert.equal(uncertainWrite({ status: 401 }), false);
});
test('simulated, closed and stale price notices do not invent live availability', () => {
  assert.match(marketNotice({ priceStatus: 'SIMULATED' }), /시뮬레이션/);
  assert.match(marketNotice({ priceStatus: 'LIVE', marketStatus: 'CLOSED' }), /열려 있지/);
  assert.match(marketNotice({ priceStatus: 'STALE', marketStatus: 'OPEN' }), /최신 가격/);
});
test('new UI calls existing Agent, order and observation contracts without identity or history injection', async () => {
  const previous = globalThis.fetch; const calls = [];
  globalThis.fetch = async (path, options) => { calls.push([path, options]); return { ok: true, status: 200, text: async () => '{}' }; };
  try {
    const api = new ApiClient(); api.setToken('fake-token');
    await api.agent('진단', { quoteId: '0x' + '1'.repeat(64) }, 'signed-quote-diagnosis'); await api.order(153); await api.observation();
    assert.equal(calls[0][0], '/api/ai/agent/answers'); assert.deepEqual(Object.keys(JSON.parse(calls[0][1].body)), ['question', 'target', 'skillId']);
    assert.equal(calls[0][1].headers.Authorization, 'Bearer fake-token'); assert.equal(calls[1][0], '/api/orders/153'); assert.equal(calls[2][0], '/api/ai/observability');
    api.clearToken(); assert.equal(api.token, null);
  } finally { globalThis.fetch = previous; }
});

class FakeElement {
  children = []; className = ''; constructor(tag) { this.tagName = tag; }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return (this.text || '') + this.children.map((child) => child.textContent).join(' '); }
  append(...values) { this.children.push(...values); }
  replaceChildren(...values) { this.text = ''; this.children = values; }
  addEventListener() {}
}
function withDom(operation) { const previous = globalThis.document; globalThis.document = { createElement: (tag) => new FakeElement(tag) }; try { operation(); } finally { globalThis.document = previous; } }
test('diagnosis uses literal text and separates interpretation, facts, policy and uncertainty', () => withDom(() => {
  const container = new FakeElement('section');
  renderDiagnosis(container, { status: 'PARTIAL', answer: '<img src=x onerror=alert(1)>', uncertainties: ['receipt 없음'],
    toolEvidence: [{ tool: 'getOrder', status: 'OK', data: { status: 'PENDING_ONCHAIN', inputAmount: '100000' } }],
    knowledgeSources: [{ id: 'k1', title: '거래 정책', version: '6' }], skill: { id: 'settlement-debugging', trace: [{ stepId: 'CHECK', status: 'DONE', evidenceRefs: ['t1'] }] } });
  const text = container.textContent; for (const value of ['<img', '부분 진단', '관측 사실', '정책 출처', 'receipt 없음', '블록체인 확인 중', '진단 단계']) assert.ok(text.includes(value));
  assert.equal(container.children.some((child) => child.tagName === 'img'), false);
}));
test('purged history and revoked approval are visible without fabricating a completed answer', () => withDom(() => {
  const container = new FakeElement('section'); renderDiagnosis(container, null); assert.match(container.textContent, /보관 기간/);
  renderDiagnosis(container, { responseStatus: 'PARTIAL', approvalStatus: 'APPROVAL_UNVERIFIED', answer: '' });
  assert.match(container.textContent, /숨겨졌습니다/); assert.match(container.textContent, /확정할 근거가 없습니다/);
}));
test('pending order output is labelled expected and final price comes only from an actual trade', () => withDom(() => {
  const pending = { orderId: 1, side: 'BUY', inputAmount: '100000', outputAmount: '1.2', status: 'PENDING_ONCHAIN' };
  const card = orderCard(pending, null, () => {}); assert.match(card.textContent, /예상 수령량/); assert.doesNotMatch(card.textContent, /실제 체결가/);
  assert.match(orderCard(pending, { price: '75000', fee: '100' }, () => {}).textContent, /실제 체결가/);
}));
