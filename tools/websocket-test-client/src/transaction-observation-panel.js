import { node, fields, button } from './ui.js';
import { time } from './state.js';

const RESULTS = { SUBMISSION_RECORDED:'제출 상태 기록', RECEIPT_NOT_FOUND:'receipt 조회 시 미발견',
  CONFIRMATIONS_PENDING:'확인 수 대기', SUCCESS_SETTLED:'성공 정산', FAILURE_SETTLED:'실패 receipt 정산',
  REVIEW_REQUIRED:'증거 검토 필요', ATTEMPT_UNAVAILABLE:'해당 시도 결과 확인 불가' };
export function renderTransactionObservation(container, item, detail = false) {
  container.replaceChildren(node('h3',`거래 #${item.id} · ${item.status}`));
  container.append(node('p',item.attention === 'LONG_WAIT'
    ? 'LONG_WAIT · 관리자 확인 필요. 실패 확정이 아니며 기존 복구가 계속 진행됩니다.'
    : '시간 경과는 거래 성공·실패 판정이 아닙니다.',item.attention === 'LONG_WAIT'?'notice warning':'muted'));
  container.append(fields([['유형',item.kind === 'SYSTEM'?'시스템 거래':'사용자 거래'],['주문 연결',item.linkStatus],
    ['Order',item.orderId??'없음'],['Order 상태',item.orderStatus??'없음'],['조건부 주문',item.conditionalOrderId??'없음'],
    ['조건부 상태',item.conditionalState??'없음'],['생성 후 경과',`${item.totalAgeSeconds}초`],['대기 경과',`${item.waitingAgeSeconds}초`],
    ['제출 후 경과',item.submittedAgeSeconds==null?'미제출':`${item.submittedAgeSeconds}초`],
    ['경고 기준',`${item.warningSeconds}초 이상`],['관측 시각',time(item.observedAt)]]));
  const attempt = item.lastAttempt;
  if (!attempt) container.append(node('p','UNRECORDED · 마지막 복구/조회 시도 기록 없음. 과거 시도 횟수를 추정하지 않습니다.','notice'));
  else container.append(fields([['마지막 기록된 시도 시작',time(attempt.startedAt)],['완료',time(attempt.finishedAt)],
    ['phase',attempt.phase],['결과',RESULTS[attempt.resultCode]??'확인 불가'],['기록된 시도 수',attempt.recordedCount],
    ['저장 시각',time(attempt.recordedAt)],['마지막 완료 후',`${attempt.ageSeconds}초`]]),
    node('p','완료되어 저장된 최신 시도입니다. 진행 중·기록 지연·누락 시도가 있을 수 있습니다. ETH 부족이나 영구 미채굴을 단정하지 않습니다.','muted'));
  if(detail)container.append(fields([['txHash',item.txHash??'없음'],['nonce',item.nonce??'없음']]));
}
export function createTransactionObservationPanel(api, expireSession=()=>{}) {
  if(!document.getElementById('transaction-observation-panel'))return {setContext(){}};
  const get=id=>document.getElementById(id);
  let actor=null,active=false,generation=0,cursor=null;
  const clear=()=>{cursor=null;for(const id of ['tx-observation-list','tx-observation-detail'])get(id).replaceChildren();get('tx-observation-message').textContent='';get('tx-observation-next').disabled=true;};
  async function load(next=false){
    if(!active||actor?.role!=='ADMIN')return;
    const ticket=++generation;const after=next?cursor:null;
    cursor=null;get('tx-observation-next').disabled=true;get('tx-observation-detail').replaceChildren();
    get('tx-observation-list').replaceChildren();get('tx-observation-message').textContent='DB 관측 정보를 조회 중입니다.';
    try{
      const {body}=await api.transactionObservations({status:get('tx-observation-status').value,attention:get('tx-observation-attention').value,cursor:after});
      if(ticket!==generation||!active)return;
      get('tx-observation-message').textContent=`관측 ${time(body.observedAt)} · 경고 기준 ${body.warningSeconds}초 · 관측 저장 실패 ${body.recorder.storageFailures} / 포화·누락 ${body.recorder.dropped} (프로세스 재시작 시 집계 초기화)`;
      for(const item of body.items){const card=node('article','','record');renderTransactionObservation(card,item);
        card.append(button('운영 상세 보기',()=>void detail(item.id)));get('tx-observation-list').append(card);}
      if(!body.items.length)get('tx-observation-list').append(node('p','해당 조건의 거래가 없습니다.'));
      cursor=body.nextCursor;get('tx-observation-next').disabled=!cursor;
    }catch(error){if(ticket!==generation||!active)return;get('tx-observation-message').textContent='관측을 조회하지 못했습니다. 거래 실패로 판단하지 않습니다.';if(error.status===401)expireSession();}
  }
  async function detail(id){
    if(!active||actor?.role!=='ADMIN')return;
    const ticket=++generation;get('tx-observation-detail').replaceChildren();
    try{const {body}=await api.transactionObservation(id);if(ticket!==generation||!active)return;renderTransactionObservation(get('tx-observation-detail'),body,true);}
    catch(error){if(ticket!==generation||!active)return;get('tx-observation-message').textContent='상세를 조회하지 못했습니다.';if(error.status===401)expireSession();}
  }
  get('tx-observation-refresh').onclick=()=>void load();get('tx-observation-next').onclick=()=>{if(cursor)void load(true);};
  get('tx-observation-status').onchange=get('tx-observation-attention').onchange=()=>void load();
  return {setContext(user,route){const changed=actor?.id!==user?.id||actor?.role!==user?.role;actor=user;
    const wasActive=active;active=user?.role==='ADMIN'&&route==='admin';
    get('transaction-observation-panel').hidden=!active;
    if(changed||!active){generation++;clear();}if(active&&(changed||!wasActive))void load();}};
}
