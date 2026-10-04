/** Each resource has an independent request ticket, all private work shares a session generation. */
export class RequestState {
  generation = 0;
  resources = new Map();
  reset() { this.generation++; this.resources.clear(); }
  invalidate(key) { const serial = (this.resources.get(key)?.serial || 0) + 1; this.resources.set(key, { serial, status: 'idle', error: null }); }
  begin(key) {
    const generation = this.generation;
    const serial = (this.resources.get(key)?.serial || 0) + 1;
    this.resources.set(key, { serial, status: 'loading', error: null });
    return {
      current: () => generation === this.generation && this.resources.get(key)?.serial === serial,
      finish: (status, error = null) => {
        if (generation !== this.generation || this.resources.get(key)?.serial !== serial) return false;
        this.resources.set(key, { serial, status, error }); return true;
      },
    };
  }
  busy(key) { return this.resources.get(key)?.status === 'loading'; }
}

export const LABELS = Object.freeze({
  REQUESTED: '주문 준비', PENDING_ONCHAIN: '블록체인 확인 중', FILLED: '체결 완료',
  FAILED: '주문 실패', CANCELED: '취소', REVIEW_REQUIRED: '운영 검토 필요',
  OPEN: '개장', CLOSED: '장 마감', PRE_OPEN: '개장 전', HOLIDAY: '휴장', UNKNOWN: '확인 불가',
  LIVE: '실시간 시세', STALE: '오래된 시세', SIMULATED: '시뮬레이션',
  ANSWERED: '답변 완료', PARTIAL: '부분 진단 · 불확실성 있음', CLARIFY: '추가 정보 필요',
  UNSUPPORTED: '지원하지 않는 요청', INSUFFICIENT_EVIDENCE: '근거 부족', ERROR: '처리 불가',
  QUEUED: '분석 대기', RUNNING: '분석 중', COMPLETED: '분석 종료', INTERRUPTED: '실행 중단', SKIPPED: '분석 제외',
});
export const label = (value) => LABELS[value] || value || '—';
export function number(value, unit = '', digits = 4) {
  if (value === null || value === undefined || value === '' || !Number.isFinite(Number(value))) return '—';
  return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: digits }).format(Number(value))}${unit ? ` ${unit}` : ''}`;
}
export function time(value) {
  if (!value || !Number.isFinite(Date.parse(value))) return '—';
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value));
}
export function positiveAmount(value) {
  return /^(?:\d+)(?:\.\d{1,18})?$/.test(value) && Number.isFinite(Number(value)) && Number(value) > 0;
}
export function uncertainWrite(error) {
  return !error?.status || error.status >= 500;
}
export function marketNotice(market) {
  if (!market) return '시장 정보를 확인하는 중입니다.';
  if (market.priceStatus === 'SIMULATED') return '시뮬레이션 가격입니다. 실제 주식 거래가 아닙니다.';
  if (market.marketStatus !== 'OPEN') return '현재 시장이 열려 있지 않습니다. 새 견적 발급이 제한될 수 있습니다.';
  if (market.priceStatus !== 'LIVE') return '최신 가격을 확인할 수 없습니다. 새 견적 발급이 제한될 수 있습니다.';
  return '실제 삼성전자 기준 시세 · 거래와 손익은 모의 자산으로 처리합니다.';
}
