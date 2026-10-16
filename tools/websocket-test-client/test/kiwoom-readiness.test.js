import test from 'node:test';
import assert from 'node:assert/strict';
import { marketNotice, mergeMarketRead } from '../src/state.js';
const at = Date.parse('2026-10-14T01:00:00Z');
const base = { provider: 'KIWOOM', marketStatus: 'UNKNOWN', priceStatus: 'LIVE', observedAt: new Date(at).toISOString(), mockQuoteAllowed: true };
test('REST readiness survives an intervening WS tick without replacing its newer price', () => {
  const tick = { ...base, price: 76000, observedAt: new Date(at + 1000).toISOString(), mockQuoteAllowed: false };
  const response = { ...base, price: 75000 };
  const merged = mergeMarketRead(tick, response, false);
  assert.equal(merged.price, 76000);
  assert.equal(merged.observedAt, tick.observedAt);
  assert.equal(merged.mockQuoteObservedAt, response.observedAt);
  assert.match(marketNotice(merged, at + 1000), /조건이 충족됐습니다/);
  assert.match(marketNotice(merged, at + 6000), /충족되지/);
});
test('UNKNOWN market and mock quote readiness are separate; no OPEN assertion', () => {
  const notice = marketNotice(base, at);
  assert.match(notice, /모의 견적 시세 조건이 충족/);
  assert.doesNotMatch(notice, /개장|시장 OPEN/);
  assert.equal(base.marketStatus, 'UNKNOWN');
});
test('stale readiness is not refreshed by a newer WS price; CLOSED and disabled deny', () => {
  const newer = { ...base, observedAt: new Date(at + 6000).toISOString(), mockQuoteObservedAt: base.observedAt };
  assert.match(marketNotice(newer, at + 6000), /충족되지/);
  assert.match(marketNotice({ ...base, marketStatus: 'CLOSED' }, at), /충족되지/);
  assert.match(marketNotice({ ...base, mockQuoteAllowed: false }, at), /충족되지/);
});
test('legacy response, Toss and synthetic messages remain compatible', () => {
  assert.match(marketNotice({ ...base, provider: 'TOSS', marketStatus: 'OPEN' }, at), /실제 삼성전자/);
  assert.match(marketNotice({ ...base, priceStatus: 'SIMULATED' }, at), /시뮬레이션/);
  assert.match(marketNotice({ ...base, mockQuoteAllowed: undefined }, at), /새 견적 발급/);
});
