import { node,button,fields } from './ui.js';
import { time,positiveAmount } from './state.js';

const STATES={WAITING:'가격 조건 대기',PREPARING:'실행 조건 확인 중',EXECUTING:'온체인 실행·정산 대기',SUCCEEDED:'체결 완료',FAILED:'실행 실패',CANCELLED:'취소',EXPIRED:'실행 개시 기한 만료',REVIEW_REQUIRED:'수동 확인 필요'};
const REASONS={PRICE_NOT_TRIGGERED:'목표 가격에 도달하지 않았습니다.',MARKET_UNAVAILABLE:'시장 상태 또는 시세 신선도를 확인할 수 없습니다.',QUOTE_CONDITION_CHANGED:'새 견적 가격이 조건을 벗어나 다시 대기합니다.',QUOTE_UNAVAILABLE:'유효한 새 견적을 기다립니다.',INSUFFICIENT_AVAILABLE_BALANCE:'실행 시 사용 가능한 자산이 부족합니다.',EXECUTION_FAILED:'실행 실패가 확인됐습니다.',AWAITING_RECEIPT:'Receipt 및 이벤트 검증을 기다립니다.',PRE_SUBMIT_INTERRUPTED:'전송 중단 근거 확인이 필요합니다. 새 주문을 자동 실행하지 않습니다.',RECEIPT_REVIEW_REQUIRED:'정산 검증에 수동 확인이 필요합니다.',CONDITIONAL_DISABLED:'조건부 주문 기능이 비활성 상태입니다.',CONDITIONAL_PROVIDER_DISABLED:'이 환경에서는 조건부 주문을 등록할 수 없습니다.'};
export function conditionalReason(code){return REASONS[code]||'상태를 다시 확인해 주세요.';}
REASONS.PRICE_CONDITION_CHANGED='최신 시세가 목표 조건을 벗어나 다시 대기합니다.';
REASONS.INVALID_CONDITIONAL_ORDER=REASONS.INVALID_EXPIRY=REASONS.INVALID_IDEMPOTENCY_KEY='등록 입력이 거절됐습니다. 값을 수정해 새로 등록하세요.';
const rejected=e=>e.status===400&&['INVALID_CONDITIONAL_ORDER','INVALID_EXPIRY','INVALID_IDEMPOTENCY_KEY'].includes(e.body?.code);
export function conditionalRequest(side,input,target,expiry){
  if(!['BUY','SELL'].includes(side)||!positiveAmount(input)||!/^\d+(\.\d{1,8})?$/.test(target)||Number(target)<=0)
    throw new Error('입력 금액·수량과 목표 가격을 확인하세요.');
  if(input.split('.')[0].replace(/^0+/,'').length>12||target.split('.')[0].replace(/^0+/,'').length>70)
    throw new Error('입력량 정수12자리·목표 가격 정수70자리 한도를 확인하세요.');
  const date=new Date(expiry);if(!Number.isFinite(date.getTime())||date.getTime()<=Date.now())throw new Error('미래의 실행 개시 만료 시각을 선택하세요.');
  return {symbol:'mSEC',side,inputAmount:input,targetPrice:target,expiresAt:new Date(Math.floor(date.getTime()/1000)*1000).toISOString().replace('.000Z','Z')};
}
export async function pendingConditional(uid,request,storage=localStorage,locks=navigator.locks){
  const select=()=>{const name='conditional-pending:'+uid;const old=storage.getItem(name);if(old)return JSON.parse(old);
    const intent={key:crypto.randomUUID(),request};storage.setItem(name,JSON.stringify(intent));return intent;};
  return locks?locks.request('conditional-intent:'+uid,select):select();
}
export function clearConditional(uid,key,storage=localStorage){const name='conditional-pending:'+uid;const text=storage.getItem(name);if(text&&JSON.parse(text).key===key)storage.removeItem(name);}
export function createConditionalPanel(api,onExecution,onAuthExpired=()=>{}){
  const $=id=>document.getElementById(id);let user=null,route=null,generation=0,revision=0,busy=false,canCreate=false,before=null;
  const notice=text=>$('conditional-message').textContent=text;
  function cards(items,admin=false){return items.map(c=>{const card=node('article','','record');card.append(node('h3',`${c.side==='BUY'?'조건부 매수':'조건부 매도'} · ${STATES[c.state]||'상태 확인 필요'}`),fields([
    ['목표 조건',`${c.side==='BUY'?'이하':'이상'} ${c.targetPrice} mKRW`],['입력',`${c.inputAmount} ${c.side==='BUY'?'mKRW (수수료 포함)':'mSEC'}`],['실행 개시 기한',time(c.expiresAt)],['자산 확보','대기 중 미예약 · 실행 준비 시 확보'],['안내',c.reasonCode?conditionalReason(c.reasonCode):''],
  ]));if(c.executionOrderId)card.append(admin?node('p',`실행 Order #${c.executionOrderId}`):button(`실행 주문 #${c.executionOrderId} 보기`,()=>onExecution(c.executionOrderId)));
    if(!admin&&['WAITING','PREPARING'].includes(c.state))card.append(button('주문 취소',()=>cancel(c.id)));return card;});}
  async function refresh(append=false){
    if(!user)return;const ticket=generation,version=++revision,isAdmin=route==='admin'&&user.role==='ADMIN';
    try{const {body}=await(isAdmin?api.conditionalAdmin(append?before:null):api.conditionalOrders(append?before:null));if(ticket!==generation||version!==revision)return;
      const container=$(isAdmin?'conditional-admin-history':'conditional-history');const rows=cards(body.items,isAdmin);if(append)container.append(...rows);else container.replaceChildren(...rows);
      if(!body.items.length&&!append)container.append(node('p','조건부 주문이 없습니다.'));before=body.nextBefore;$('conditional-older').disabled=!before||isAdmin;$('conditional-admin-older').disabled=!before||!isAdmin;
      if(!isAdmin){const capabilities=await api.conditionalCapabilities();if(ticket!==generation||version!==revision)return;canCreate=capabilities.body.registrationEnabled===true;$('conditional-submit').disabled=busy||!canCreate;
        $('conditional-availability').textContent=canCreate?'조건부 주문 등록 가능':'현재 환경에서는 조건부 주문 등록이 비활성 상태입니다. 기존 즉시 거래는 별도입니다.';}
    }catch(e){if(ticket===generation&&version===revision){canCreate=false;$('conditional-submit').disabled=true;notice('조건부 주문 조회 실패');if(e.status===401)onAuthExpired();}}
  }
  async function submit(event){event?.preventDefault();if(!user||busy||!canCreate)return;const uid=user.userId??user.id,ticket=generation;
    let request;try{request=conditionalRequest($('conditional-side').value,$('conditional-input').value.trim(),$('conditional-target').value.trim(),$('conditional-expiry').value);}catch(e){notice(e.message);return;}
    busy=true;$('conditional-submit').disabled=true;
    try{const pending=await pendingConditional(uid,request);if(ticket!==generation)return;
      if(JSON.stringify(pending.request)!==JSON.stringify(request)){notice('이전 등록 결과가 불확실합니다. 입력을 변경해 재등록하지 말고 이전 결과 확인 또는 같은 요청 재시도를 사용하세요.');return;}
      await send(uid,pending,ticket);
    }catch(e){if(ticket===generation)notice(e.status?conditionalReason(e.body?.code):'등록 응답이 유실됐을 수 있습니다. 이전 결과를 확인하세요.');}
    finally{if(ticket===generation){busy=false;$('conditional-submit').disabled=!canCreate;}}
  }
  async function send(uid,pending,ticket){
    try{const {body}=await api.createConditional(pending.request,pending.key);if(ticket!==generation)return;
      clearConditional(uid,pending.key);notice(`조건부 주문을 확인했습니다 · ${STATES[body.state]||'접수'}. 조건 충족은 체결 보장이 아닙니다.`);void refresh();
    }catch(e){if(ticket===generation&&rejected(e))clearConditional(uid,pending.key);throw e;}
  }
  async function resolve(retry=false){if(!user||busy)return;const uid=user.userId??user.id,text=localStorage.getItem('conditional-pending:'+uid),ticket=generation;
    if(!text){notice('확인할 이전 등록 요청이 없습니다.');return;}busy=true;
    try{const pending=JSON.parse(text);if(retry)await send(uid,pending,ticket);else{await api.conditionalRequest(pending.key);if(ticket!==generation)return;clearConditional(uid,pending.key);notice('기존 등록 결과를 확인했습니다.');void refresh();}}
    catch(e){if(ticket===generation)notice(retry&&rejected(e)?conditionalReason(e.body.code):e.status===404?'아직 등록 근거가 없습니다. 자동 재등록하지 않습니다. 같은 요청을 명시적으로 재시도할 수 있습니다.':'결과 확인 실패 · 기존 요청을 유지합니다.');}
    finally{if(ticket===generation)busy=false;}
  }
  async function cancel(id){if(!user||busy)return;const ticket=generation;busy=true;
    try{await api.cancelConditional(id);if(ticket!==generation)return;notice('취소 결과를 확인했습니다.');void refresh();}
    catch(e){if(ticket===generation)notice(e.status===409?'이미 실행 준비가 확정됐거나 종료된 주문입니다. 체결 결과를 확인하세요.':'취소 결과가 불확실합니다. 이력을 다시 조회하세요.');}
    finally{if(ticket===generation)busy=false;}
  }
  $('conditional-form').onsubmit=submit;$('conditional-resolve').onclick=()=>resolve();$('conditional-retry').onclick=()=>resolve(true);
  $('conditional-refresh').onclick=()=>refresh();$('conditional-admin-refresh').onclick=()=>refresh();$('conditional-older').onclick=()=>refresh(true);$('conditional-admin-older').onclick=()=>refresh(true);
  return {refresh,setContext(next,nextRoute){if((user?.userId??user?.id)!==(next?.userId??next?.id)||user?.role!==next?.role||route!==nextRoute){generation++;revision++;busy=false;canCreate=false;before=null;notice('');$('conditional-history').replaceChildren();$('conditional-admin-history').replaceChildren();$('conditional-submit').disabled=true;
      if(!next){for(const name of ['conditional-input','conditional-target','conditional-expiry'])$(name).value='';}}
    user=next;route=nextRoute;$('conditional-panel').hidden=!user||route!=='market';$('conditional-admin-panel').hidden=user?.role!=='ADMIN'||route!=='admin';
    if(user&&(route==='market'||(route==='admin'&&user.role==='ADMIN')))void refresh();}};
}
