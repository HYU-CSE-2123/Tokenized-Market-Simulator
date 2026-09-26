import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isAuthenticationFailure, latestOrder, RECONNECT_POLICY, RecoveryCoordinator, recoveryState,
  SESSION_EVENT_CHANNELS, tradeForOrder,
} from '../src/recovery.js';

test('invalidates an older REST recovery run', () => {
  const coordinator = new RecoveryCoordinator();
  const first = coordinator.begin();
  const second = coordinator.begin();
  assert.equal(first.isCurrent(), false);
  assert.equal(second.isCurrent(), true);
  coordinator.invalidate();
  assert.equal(second.isCurrent(), false);
});

test('uses bounded reconnect backoff suitable for mobile clients', () => {
  assert.deepEqual(RECONNECT_POLICY, { initialDelayMs: 1000, maxDelayMs: 30000 });
});

test('clears private event channels when the authenticated identity changes', () => {
  assert.ok(SESSION_EVENT_CHANNELS.includes('order'));
  assert.ok(SESSION_EVENT_CHANNELS.includes('portfolio'));
});

test('selects the newest order and its matching trade', () => {
  const orders = [{ orderId: 7, status: 'FILLED' }, { orderId: 9, status: 'PENDING_ONCHAIN' }];
  const trades = [{ orderId: 7, price: 75000 }, { orderId: 8, price: 76000 }];
  assert.equal(latestOrder(orders).orderId, 9);
  assert.equal(tradeForOrder(trades, 7).price, 75000);
  assert.equal(tradeForOrder(trades, 9), null);
});

test('summarizes complete, partial, and failed recovery', () => {
  assert.equal(recoveryState([{ name: 'market', status: 'fulfilled' }]).state, 'READY');
  assert.equal(recoveryState([
    { name: 'market', status: 'fulfilled' }, { name: 'orders', status: 'rejected' },
  ]).state, 'PARTIAL');
  assert.equal(recoveryState([{ name: 'market', status: 'rejected' }]).state, 'ERROR');
});

test('recognizes authentication STOMP errors', () => {
  assert.equal(isAuthenticationFailure({ headers: { message: '401 Unauthorized' } }), true);
  assert.equal(isAuthenticationFailure({ body: 'invalid token' }), true);
  assert.equal(isAuthenticationFailure({ body: 'broker unavailable' }), false);
});
