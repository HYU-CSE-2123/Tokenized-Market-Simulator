import { label, number, time } from './state.js';

/** Text-only DOM construction: no API/model value ever becomes HTML, a URL or executable markup. */
export function node(tag, text = '', className = '') {
  const element = document.createElement(tag);
  element.textContent = text ?? ''; if (className) element.className = className; return element;
}
export function button(text, action, className = 'button secondary') {
  const element = node('button', text, className); element.type = 'button';
  element.addEventListener('click', action); return element;
}
export function fields(entries) {
  const list = node('dl', '', 'facts');
  for (const [key, value] of entries) { list.append(node('dt', key), node('dd', value)); }
  return list;
}
export function empty(container, message) { container.replaceChildren(node('p', message, 'empty')); }
export function orderCard(order, trade, onDetail) {
  const card = node('article', '', 'record');
  const top = node('div', '', 'record-head');
  top.append(node('strong', `${order.side === 'BUY' ? '매수' : '매도'} mSEC · #${order.orderId}`), node('span', label(order.status), `pill ${order.status === 'FILLED' ? 'good' : ''}`));
  card.append(top, fields([
    ['주문 시각', time(order.createdAt)], ['입력량', number(order.inputAmount, order.side === 'BUY' ? 'mKRW' : 'mSEC')],
    ['예상 수령량', number(order.outputAmount, order.side === 'BUY' ? 'mSEC' : 'mKRW')],
    ...(trade ? [['실제 체결가', number(trade.price, 'mKRW')], ['수수료', number(trade.fee, 'mKRW')]] : []),
  ]), button('주문 상세', () => onDetail(order.orderId)));
  return card;
}
export function tradeCard(trade) {
  const card = node('article', '', 'record');
  card.append(node('h3', `${trade.side === 'BUY' ? '매수' : '매도'} 체결 · 주문 #${trade.orderId}`), fields([
    ['체결 시각', time(trade.createdAt)], ['체결 가격', number(trade.price, 'mKRW')],
    ['mSEC 수량', number(trade.tokenAmount, 'mSEC', 8)], ['mKRW 금액', number(trade.krwAmount, 'mKRW')], ['수수료', number(trade.fee, 'mKRW')],
  ])); return card;
}

const FACT_LABELS = { status: '상태', symbol: '자산', side: '방향', price: '가격', currentPrice: '현재 기준 가격',
  inputAmount: '입력량', outputAmount: '출력량', expectedOutputAmount: '예상 출력량', fee: '수수료',
  storedStatus: '저장 상태', minimumOutputAmount: '최소 수령량', expiredByTime: '현재 만료 여부',
  marketStatus: '장 상태', priceStatus: '가격 상태', provider: '공급자', receiptStatus: 'receipt 상태',
  matchStatus: '이벤트 일치', transactionStatus: '트랜잭션 상태', confirmations: '확인 수',
  confirmationStatus: '확인 상태', orderId: '주문 번호', quoteId: '견적 번호', krwBalance: '모의 현금',
  tokenBalance: '보유 토큰', totalValue: '총 평가액', unrealizedProfit: '미실현 손익', averageBuyPrice: '평균 매수가' };
function factRows(data, prefix = '', depth = 0) {
  if (!data || typeof data !== 'object' || depth > 3) return [];
  return Object.entries(data).flatMap(([key, value]) => {
    if (value && typeof value === 'object') return factRows(value, `${prefix}${FACT_LABELS[key] || key} / `, depth + 1);
    return [[`${prefix}${FACT_LABELS[key] || key}`, value === null ? '확인 불가' : typeof value === 'boolean' ? (value ? '예' : '아니오') : label(String(value))]];
  }).slice(0, 80);
}
export function renderDiagnosis(container, response) {
  container.replaceChildren();
  if (!response) { empty(container, '결과가 없거나 보관 기간이 끝났습니다.'); return; }
  container.append(node('span', label(response.status || response.responseStatus), 'pill'), node('h3', '진단 설명'), node('p', response.answer || '설명을 확정할 근거가 없습니다.', 'answer'));
  container.append(node('p', '읽기 전용 분석입니다. 거래·잔고를 수정하거나 주문을 재전송하지 않습니다.', 'notice'));
  if (response.approvalStatus) container.append(node('p', '현재 문서 승인을 확인할 수 없어 정책 해석이 숨겨졌습니다.', 'notice warning'));
  if (response.error) container.append(node('p', `처리 안내: ${response.error}`, 'notice warning'));
  for (const [title, values] of [['불확실성', response.uncertainties], ['다음 확인 사항', response.recommendedNextCheck]]) {
    if (values?.length) { const list = node('ul'); values.forEach((value) => list.append(node('li', value))); container.append(node('h3', title), list); }
  }
  for (const evidence of response.toolEvidence || []) {
    const section = node('details'); section.append(node('summary', `관측 사실 · ${evidence.tool} · ${label(evidence.status)} · ${time(evidence.retrievedAt)}`), fields(factRows(evidence.data)));
    if (evidence.error) section.append(node('p', evidence.error)); container.append(section);
  }
  if (response.knowledgeSources?.length) {
    container.append(node('h3', '정책 출처'));
    for (const source of response.knowledgeSources) container.append(node('p', `${source.title || source.path || source.id} · ${source.heading || ''} · 버전 ${source.version || '—'} · 근거 ${source.id}`, 'source'));
  }
  if (response.skill) {
    const section = node('details'); section.append(node('summary', `진단 단계 · ${response.skill.id}`));
    section.append(node('p', `분류: ${response.skill.diagnosis?.classification || '확인 불가'}`));
    for (const trace of response.skill.trace || []) section.append(node('p', `${trace.stepId} · ${trace.status} · ${trace.resultCode || '—'} · 근거 ${(trace.evidenceRefs || []).join(', ') || '없음'}`));
    container.append(section);
  }
}
