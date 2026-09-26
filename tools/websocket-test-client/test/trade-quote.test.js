import test from 'node:test';
import assert from 'node:assert/strict';
import {
  friendlyTradeError, isCurrentOrder, normalizeQuote, orderFillPlaceholder, quoteUsability,
  remainingSeconds, shouldRenderOrder,
} from '../src/trade-quote.js';

test('normalizes a signed buy quote without exposing signature fields', () => {
  const quote = normalizeQuote('BUY', '100000', {
    side: 'BUY', price: 75300, fee: 100, expectedTokenAmount: 1.32,
    minimumOutputAmount: 1.32, quoteId: '0x1234', status: 'ISSUED',
    observedAt: '2026-09-27T00:00:00Z', validUntil: '2026-09-27T00:00:30Z',
  });
  assert.equal(quote.output, 1.32);
  assert.equal(quote.quoteId, '0x1234');
  assert.equal(Object.hasOwn(quote, 'signature'), false);
});

test('invalidates a quote when amount changes or validity expires', () => {
  const quote = { amount: '100000', status: 'ISSUED', validUntil: '2026-09-27T00:00:30Z' };
  assert.equal(quoteUsability(quote, '99999', Date.parse('2026-09-27T00:00:10Z')).usable, false);
  assert.equal(quoteUsability(quote, '100000', Date.parse('2026-09-27T00:00:31Z')).usable, false);
  assert.equal(quoteUsability(quote, '100000', Date.parse('2026-09-27T00:00:30Z')).usable, true);
});

test('counts down signed quotes and keeps simulated quotes timeless', () => {
  assert.equal(remainingSeconds({ validUntil: '2026-09-27T00:00:30Z' }, Date.parse('2026-09-27T00:00:21Z')), 9);
  assert.equal(remainingSeconds({ validUntil: null }), null);
});

test('maps quote failures to an actionable message', () => {
  assert.match(friendlyTradeError({ code: 'PRICE_QUOTE_UNAVAILABLE' }), /새 견적/);
  assert.equal(friendlyTradeError({ message: 'custom' }), 'custom');
});

test('clears a previous fill when a new order starts or fails', () => {
  assert.equal(orderFillPlaceholder(41, { orderId: 42, status: 'PENDING_ONCHAIN' }),
    '최종 체결 가격 확정 대기');
  assert.equal(orderFillPlaceholder(42, { orderId: 42, status: 'FAILED' }),
    '최종 체결 없음 · 주문 실패');
  assert.equal(orderFillPlaceholder(42, { orderId: 42, status: 'FILLED' }), null);
});

test('keeps order rendering monotonic when HTTP and WebSocket arrive out of order', () => {
  const filled = { orderId: 42, status: 'FILLED' };
  assert.equal(shouldRenderOrder(filled, { orderId: 42, status: 'PENDING_ONCHAIN' }), false);
  assert.equal(shouldRenderOrder(filled, { orderId: 41, status: 'FAILED' }), false);
  assert.equal(shouldRenderOrder({ orderId: 42, status: 'PENDING_ONCHAIN' }, filled), true);
  assert.equal(shouldRenderOrder(filled, { orderId: 43, status: 'PENDING_ONCHAIN' }), true);
  assert.equal(shouldRenderOrder(filled, { orderId: 42, status: 'FAILED' }), false);
});

test('applies late trade lookup results only to the currently displayed order', () => {
  const current = { orderId: 43, status: 'PENDING_ONCHAIN' };
  assert.equal(isCurrentOrder(current, 42), false);
  assert.equal(isCurrentOrder(current, 43), true);
  assert.equal(isCurrentOrder(null, 43), false);
});
