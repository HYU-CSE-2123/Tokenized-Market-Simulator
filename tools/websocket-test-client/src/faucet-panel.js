import { node, fields } from './ui.js';
import { time } from './state.js';

const REASON={FAUCET_DAILY_LIMIT:'오늘 지급을 받았습니다.',FAUCET_LIFETIME_LIMIT:'계정 누적 5회 지급을 모두 받았습니다.',FAUCET_BUDGET_EXHAUSTED:'전체 모의자금 지급 예산이 소진됐습니다.',FAUCET_POLICY_UNAVAILABLE:'정책 확인이 불가능해 지급을 중단했습니다.',FAUCET_ACCOUNTING_INCONSISTENT:'지급 장부와 사용량이 달라 지급을 중단했습니다.',FAUCET_INVALID_KEY:'지급 요청 식별자를 확인하세요.',FAUCET_KEY_CONFLICT:'기존 요청과 다른 내용입니다.',FAUCET_DB_UNAVAILABLE:'결과가 불확실합니다. 같은 요청 결과를 확인하세요.'};
export function reasonText(code){return REASON[code] || '지급 상태를 확인할 수 없습니다.';}
// Keep uncertain intent across tabs/relogin. Never auto-generate a new key after a lost response.
export async function pendingKey(uid,storage=localStorage,locks=navigator.locks) {
  const select=()=>{const name='faucet-pending:'+uid;let key=storage.getItem(name);if(!key){key=crypto.randomUUID();storage.setItem(name,key);}return key;};
  return locks?locks.request('faucet-intent:'+uid,select):select();
}
export function clearPending(uid,key,storage=localStorage){const name='faucet-pending:'+uid;if(storage.getItem(name)===key)storage.removeItem(name);}
export function createFaucetPanel(api,onPaid,onAuthExpired=()=>{}) {
  const $=id=>document.getElementById(id);let user=null,generation=0,busy=false,ownBefore=null,adminBefore=null,ownRevision=0,adminRevision=0,adminUser=null;
  function notice(text){$('faucet-message').textContent=text;}
  function records(container,page,append=false){const rows=page.items.map(g=>node('p',`${time(g.grantedAt)} · ${g.amount} mKRW · 계정 ${g.userId} · ${g.policyVersion || '과거 근거 없음'}`));if(append)container.append(...rows);else container.replaceChildren(...rows);if(!page.items.length&&!append)container.append(node('p','지급 이력이 없습니다.'));}
  async function refresh() {
    if(!user)return;const ticket=generation,revision=++ownRevision;
    try{const [{body:status},{body:history}]=await Promise.all([api.faucetStatus(),api.faucetGrants()]);if(ticket!==generation||revision!==ownRevision)return;
      $('faucet-policy').replaceChildren(fields([['지급액','1,000,000 mKRW'],['일일 기준','KST 달력일 1회'],['누적 남은 횟수',status.remainingCount],['상태',status.eligible?'지급 가능':reasonText(status.reason)],['다음 가능 시각',status.nextEligibleAt?time(status.nextEligibleAt):'미정 또는 해당 없음']]));
      $('faucet').disabled=busy||!status.eligible;records($('faucet-history'),history);ownBefore=history.nextBefore;$('faucet-older').disabled=!ownBefore;
    }catch(e){if(ticket===generation&&revision===ownRevision){$('faucet').disabled=true;notice('정책 조회 실패 · 불확실한 이전 요청은 결과 확인으로 조회하세요.');if(e.status===401)onAuthExpired();}}
  }
  async function pay() {
    if(!user||busy)return;const uid=user.userId??user.id,ticket=generation;busy=true;$('faucet').disabled=true;
    try{const key=await pendingKey(uid);if(ticket!==generation)return;const {body}=await api.faucet(key);if(ticket!==generation)return;
      clearPending(uid,key);notice(body.replayed?'이전 성공을 확인했습니다. 현재 자산은 별도로 새로 조회합니다.':'모의 자금 1,000,000 mKRW를 지급받았습니다.');onPaid();
    }catch(e){if(ticket===generation){notice(e.status?reasonText(e.body?.code):'응답이 유실됐을 수 있습니다. 새 요청을 만들지 말고 이전 결과 확인을 누르세요.');if(e.status===401)onAuthExpired();}}
    finally{if(ticket===generation){busy=false;void refresh();}}
  }
  async function resolve() {
    if(!user||busy)return;const uid=user.userId??user.id,key=localStorage.getItem('faucet-pending:'+uid),ticket=generation;
    if(!key){notice('확인할 이전 요청이 없습니다.');return;}busy=true;
    try{const {body}=await api.faucetClaim(key);if(ticket!==generation)return;clearPending(uid,key);notice(`이전 성공을 확인했습니다 · ${time(body.grantedAt)}. 현재 자산을 새로 조회합니다.`);onPaid();}
    catch(e){if(ticket===generation){notice(e.status===404?'성공 기록이 아직 없습니다. 자동 재지급하지 않습니다. 지급 버튼으로 동일 키를 재시도할 수 있습니다.':reasonText(e.body?.code));if(e.status===401)onAuthExpired();}}
    finally{if(ticket===generation){busy=false;void refresh();}}
  }
  async function admin() {
    if(user?.role!=='ADMIN')return;const ticket=generation,revision=++adminRevision;
    const text=$('faucet-admin-user')?.value.trim()||'';adminUser=text?Number(text):null;
    if(text&&(!Number.isSafeInteger(adminUser)||adminUser<=0)){$('faucet-admin-policy').replaceChildren(node('p','계정 번호는 양의 정수로 입력하세요.'));return;}
    try{const [{body:p},{body:history},account]=await Promise.all([api.faucetAdmin(),api.faucetAdminGrants(adminUser),adminUser?api.faucetAdminAccount(adminUser):null]);if(ticket!==generation||revision!==adminRevision)return;
      $('faucet-admin-policy').replaceChildren(fields([['정책',p.activeVersion],['적용 정책 일치',p.configured?'일치':'확인 불가'],['전체 지급액',`${p.spent} mKRW`],['남은 DB 지급 예산',`${p.remainingBudget} mKRW`],['성공 지급 횟수',p.grantCount],['journal/counter',p.journalConsistent?'일치':'불일치 · 지급 차단'],['거절 집계 범위',`현재 프로세스 · ${time(p.rejectionMetricsStartedAt)}`]]));
      if(account)$('faucet-admin-policy').append(node('p',`계정 ${adminUser} · 남은 누적 횟수 ${account.body.remainingCount} · ${account.body.eligible?'지급 가능':reasonText(account.body.reason)}`));
      for(const [code,count]of Object.entries(p.rejections||{}))$('faucet-admin-policy').append(node('p',`${reasonText(code)} · ${count}회`));records($('faucet-admin-history'),history);adminBefore=history.nextBefore;$('faucet-admin-older').disabled=!adminBefore;
    }catch{if(ticket===generation&&revision===adminRevision)$('faucet-admin-policy').replaceChildren(node('p','지급 관측 조회 실패'));}
  }
  async function older(isAdmin){const cursor=isAdmin?adminBefore:ownBefore,ticket=generation;if(!user||!cursor)return;
    const revision=isAdmin?adminRevision:ownRevision;
    const {body}=await(isAdmin?api.faucetAdminGrants(adminUser,cursor):api.faucetGrants(cursor));if(ticket!==generation||revision!==(isAdmin?adminRevision:ownRevision))return;records($(isAdmin?'faucet-admin-history':'faucet-history'),body,true);if(isAdmin){adminBefore=body.nextBefore;$('faucet-admin-older').disabled=!adminBefore;}else{ownBefore=body.nextBefore;$('faucet-older').disabled=!ownBefore;}}
  $('faucet').onclick=pay;$('faucet-resolve').onclick=resolve;$('faucet-older').onclick=()=>older(false).catch(()=>notice('이력 조회 실패'));
  $('faucet-admin-refresh').onclick=admin;$('faucet-admin-older').onclick=()=>older(true).catch(()=>{});
  return {setContext(next,route){if((user?.userId??user?.id)!==(next?.userId??next?.id)||user?.role!==next?.role){generation++;busy=false;ownBefore=null;adminBefore=null;adminUser=null;$('faucet-policy').replaceChildren();$('faucet-history').replaceChildren();$('faucet-admin-policy').replaceChildren();$('faucet-admin-history').replaceChildren();if($('faucet-admin-user'))$('faucet-admin-user').value='';notice('');}user=next;$('faucet').disabled=true;$('faucet-admin-panel').hidden=user?.role!=='ADMIN'||route!=='admin';if(route==='portfolio')void refresh();if(route==='admin')void admin();}};
}
