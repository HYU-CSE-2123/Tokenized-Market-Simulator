import { node, fields, button } from './ui.js';
import { time } from './state.js';

const STATUS = { MATCH: '장부·체인 수량 일치', MISMATCH: '설명되지 않는 불일치',
  INCONCLUSIVE: '동일 시점·근거 확인 불가', UNAVAILABLE: '필수 데이터 조회 불가' };
const REASON = { CUT_CHANGED: '조회 중 DB 또는 블록이 변경됐습니다. 거래를 멈추지 않고 다음 수동 조회를 기다립니다.',
  UNSETTLED_ORDER_OR_REVIEW: '미정산·검토 필요 주문이 있어 이번 대사로 정상/불일치를 확정할 수 없습니다.',
  BASELINE_REQUIRED: '불변 기준점을 먼저 생성해야 합니다.',
  EXACT_QUANTITY_MATCH: '검증 가능한 범위의 정확한 수량이 일치합니다.',
  UNEXPLAINED_QUANTITY_OR_CONTRACT_DIFFERENCE: '상세 차이와 계약 연결을 확인하세요.' };
// Preserve 18 digits and arbitrarily large integer values; never convert to JS Number.
export function tokenText(wei) {
  if (!/^-?\d+$/.test(String(wei))) return '확인 불가';
  const value=BigInt(wei), negative=value<0n, magnitude=negative?-value:value;
  const tail=(magnitude%10n**18n).toString().padStart(18,'0').replace(/0+$/,'');
  return (negative?'-':'')+(magnitude/10n**18n).toString()+(tail?'.'+tail:'');
}
export function renderReserveResult(container, result) {
  container.replaceChildren(node('h3', STATUS[result?.status] || '판정 확인 불가'));
  container.append(node('p', REASON[result?.reason] || '세부 근거를 확인하세요. 자동 보정은 하지 않습니다.', 'notice'),
    fields([['조회 시각',time(result?.createdAt)],['체인 블록',result?.chain?.block?.number ?? '없음'],
      ['블록 hash',result?.chain?.block?.hash ?? '없음'],['DB 기준 시각',time(result?.dbAt)]]));
  for(const check of result?.checks || []) {
    container.append(node('h4', check.name), fields([['예상 최소단위',check.expected],['실제 최소단위',check.actual],
      ['차이 최소단위',check.difference],['판정',check.matches?'일치':'불일치']]));
  }
  container.append(node('h3','거래 가능 유동성 · 정합성 판정과 별개'));
  const l=result?.liquidity;
  if(!l) { container.append(node('p','이번 실행에서는 유동성을 확인할 수 없습니다.')); return; }
  container.append(fields([['operator ETH 잔고',l.operatorEthWei==null?'조회 불가':tokenText(l.operatorEthWei)+' ETH'],
    ['Gas 지급 조건','비용 미추정 · 충분/부족 판정 안 함'],['operator 매수 자금',tokenText(l.operatorBuyFunds)+' mKRW'],
    ['Vault 사용 승인',tokenText(l.buyAllowance)+' mKRW'],['operator 매도 토큰',tokenText(l.operatorSellTokens)+' mSEC'],
    ['Vault 매도 지급 자금',tokenText(l.vaultSellFunds)+' mKRW'],['사용자 mKRW 합계',tokenText(l.userKrw)+' mKRW'],
    ['사용자 mSEC 합계',tokenText(l.userSec)+' mSEC'],['운영자금 / 사용자 할당량 (참고)',l.operatorAllocationReference ?? '분모 0 또는 확인 불가']]),
    node('p','위 값은 실행 자원·할당량 참고 정보입니다. 준비금 보증 비율이나 Proof of Reserves가 아니며 전체 매도·지급을 보장하지 않습니다.','muted'),
    node('p','ETH는 지정 블록의 읽기 전용 잔고입니다. 주문별 Gas 추정·미전송 거래의 비용·미래 Gas 가격을 반영하지 않아 거래 실행 가능성을 보증하지 않습니다. 잔고 0 또는 조회 불가이면 별도 확인이 필요합니다. 자동 ETH 충전은 하지 않습니다.','muted'));
}
export function createReservePanel(api) {
  const panel=document.getElementById('reserve-panel');
  if(!panel) return { setContext() {} };
  const get=id=>document.getElementById(id);
  let actor=null, generation=0, busy=false;
  const clear=()=> { get('reserve-result').replaceChildren(); get('reserve-history').replaceChildren(); get('reserve-message').textContent=''; };
  async function action(operation, render) {
    if(actor?.role!=='ADMIN' || busy) return;
    busy=true; const current=generation;
    get('reserve-message').textContent='장부와 지정 블록을 확인 중입니다.';
    for(const id of ['reserve-run','reserve-create','reserve-refresh']) get(id).disabled=true;
    try {
      const {body}=await operation();
      if(current!==generation) return;
      render(body); get('reserve-message').textContent='완료. 거래·잔고를 자동 변경하지 않았습니다.';
    } catch(error) {
      if(current!==generation) return;
      get('reserve-result').replaceChildren();
      get('reserve-message').textContent=error.status===409?'기준점은 신규 빈 장부에서 한 번만 생성할 수 있습니다. 초기 조건·기존 기준점을 확인하세요.':
        error.status===404?'이 서버에서는 대사 기능이 꺼져 있거나 기록이 없습니다.':'결과를 확인하지 못했습니다. 자동 재요청하지 않습니다. 이력을 조회하세요.';
    } finally {
      if(current===generation) {
        busy=false; for(const id of ['reserve-run','reserve-create','reserve-refresh']) get(id).disabled=false;
      }
    }
  }
  get('reserve-run').onclick=()=>action(()=>api.reconcileAssets(),value=>renderReserveResult(get('reserve-result'),value));
  get('reserve-create').onclick=()=> {
    if(actor?.role!=='ADMIN' || !window.confirm('기준점은 신규 빈 장부에서 한 번 생성하며 수정·재설정할 수 없습니다. 생성할까요?')) return;
    void action(()=>api.createReserveBaseline(),b=>get('reserve-result').replaceChildren(node('h3','불변 기준점 생성'),
      fields([['실행 식별자',b.executionId],['기준점 ID',b.id],['블록',b.chain.block.number],['DB 기준 시각',time(b.dbAt)]])));
  };
  get('reserve-refresh').onclick=()=>action(()=>api.reserveHistory(),items=> {
    get('reserve-history').replaceChildren(node('h3','최근 대사 20건'));
    for(const r of items) get('reserve-history').append(button(time(r.createdAt)+' · '+(STATUS[r.status]||'확인 필요'),
      ()=>action(()=>api.reserveDetail(r.id),detail=>renderReserveResult(get('reserve-result'),detail))));
  });
  return { setContext(user, route) {
    if(actor!==user) { actor=user; generation++; busy=false; clear();
      for(const id of ['reserve-run','reserve-create','reserve-refresh']) get(id).disabled=false; }
    panel.hidden=user?.role!=='ADMIN' || route!=='admin';
  } };
}
