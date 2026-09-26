export class RecoveryCoordinator {
  #generation = 0;

  begin() {
    const generation = ++this.#generation;
    return { generation, isCurrent: () => generation === this.#generation };
  }

  invalidate() {
    this.#generation += 1;
  }
}

export const RECONNECT_POLICY = Object.freeze({
  initialDelayMs: 1000,
  maxDelayMs: 30000,
});

export const SESSION_EVENT_CHANNELS = Object.freeze(['price', 'trade', 'order', 'portfolio']);

export function latestOrder(orders = []) {
  return orders.reduce((latest, order) => (
    !latest || Number(order.orderId) > Number(latest.orderId) ? order : latest
  ), null);
}

export function tradeForOrder(trades = [], orderId) {
  return trades.find((trade) => trade.orderId === orderId) ?? null;
}

export function recoveryState(entries) {
  const failed = entries.filter((entry) => entry.status === 'rejected').map((entry) => entry.name);
  const succeeded = entries.filter((entry) => entry.status === 'fulfilled').map((entry) => entry.name);
  return {
    state: failed.length === 0 ? 'READY' : succeeded.length === 0 ? 'ERROR' : 'PARTIAL',
    detail: failed.length === 0
      ? `${succeeded.join(' · ')} 복구 완료`
      : `성공 ${succeeded.join(', ') || '없음'} · 실패 ${failed.join(', ')}`,
  };
}

export function isAuthenticationFailure(frame = {}) {
  const text = [frame.headers?.message, frame.body].filter(Boolean).join(' ').toLowerCase();
  return ['unauthorized', 'forbidden', 'authentication', 'token', '401', '403']
    .some((keyword) => text.includes(keyword));
}
