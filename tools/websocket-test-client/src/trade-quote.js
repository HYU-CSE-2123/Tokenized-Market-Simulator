export function normalizeQuote(side, amount, quote) {
  if (!quote || quote.side !== side) throw new Error('견적 방향이 요청과 일치하지 않습니다.');
  return {
    side,
    amount: String(amount),
    quoteId: quote.quoteId ?? null,
    price: quote.price,
    fee: quote.fee,
    output: side === 'BUY' ? quote.expectedTokenAmount : quote.expectedKrwAmount,
    minimumOutput: quote.minimumOutputAmount ?? null,
    observedAt: quote.observedAt ?? null,
    validUntil: quote.validUntil ?? null,
    status: quote.status ?? 'SIMULATED',
  };
}

export function quoteUsability(quote, currentAmount, now = Date.now()) {
  if (!quote) return { usable: false, reason: '견적이 없습니다.' };
  if (String(currentAmount) !== quote.amount) return { usable: false, reason: '입력량이 변경되었습니다.' };
  if (quote.status !== 'ISSUED' && quote.status !== 'SIMULATED') {
    return { usable: false, reason: `사용할 수 없는 견적 상태: ${quote.status}` };
  }
  if (quote.validUntil && now > Date.parse(quote.validUntil)) {
    return { usable: false, reason: '견적이 만료되었습니다.' };
  }
  return { usable: true, reason: null };
}

export function remainingSeconds(quote, now = Date.now()) {
  if (!quote?.validUntil) return null;
  return Math.max(0, Math.ceil((Date.parse(quote.validUntil) - now) / 1000));
}

export const FRIENDLY_TRADE_ERRORS = {
  PRICE_QUOTE_NOT_FOUND: '이 견적을 찾을 수 없거나 다른 사용자의 견적입니다.',
  PRICE_QUOTE_UNAVAILABLE: '견적이 만료·소비되었거나 주문 조건과 일치하지 않습니다. 새 견적을 받으세요.',
  INSUFFICIENT_BALANCE: '사용 가능한 잔고가 부족합니다.',
  MARKET_CLOSED: '현재 시장이 닫혀 있어 새 주문을 만들 수 없습니다.',
  PRICE_STALE: '시장 가격이 오래되어 새 견적을 만들 수 없습니다.',
  OPERATOR_NOT_READY: '온체인 운영자 잔고 또는 승인 상태를 확인해야 합니다.',
  BLOCKCHAIN_UNAVAILABLE: '블록체인 연결 또는 트랜잭션 처리에 실패했습니다.',
};

export function friendlyTradeError(body, fallback) {
  return FRIENDLY_TRADE_ERRORS[body?.code] || body?.message || fallback || '거래 요청에 실패했습니다.';
}

export function orderFillPlaceholder(previousOrderId, order) {
  if (order?.status === 'FAILED') return '최종 체결 없음 · 주문 실패';
  if (order?.orderId !== previousOrderId || order?.status !== 'FILLED') {
    return '최종 체결 가격 확정 대기';
  }
  return null;
}

const ORDER_STATUS_RANK = {
  REQUESTED: 0,
  PENDING_ONCHAIN: 1,
  FILLED: 2,
  FAILED: 2,
  CANCELED: 2,
};

export function shouldRenderOrder(current, incoming) {
  if (!incoming?.orderId) return false;
  if (!current?.orderId) return true;
  if (incoming.orderId !== current.orderId) return incoming.orderId > current.orderId;
  if (incoming.status === current.status) return true;
  const currentRank = ORDER_STATUS_RANK[current.status] ?? -1;
  const incomingRank = ORDER_STATUS_RANK[incoming.status] ?? -1;
  return currentRank < 2 && incomingRank > currentRank;
}

export function isCurrentOrder(current, orderId) {
  return Boolean(current?.orderId && current.orderId === orderId);
}
