import test from 'node:test';
import assert from 'node:assert/strict';
import { createTradeAuditPanel, renderAuditRun, renderAuditItems } from '../src/trade-audit-panel.js';
import { ApiClient } from '../src/api.js';
class Element {
  constructor() { this.children=[]; this.text=''; this.value=''; this.disabled=false; this.hidden=false; }
  set textContent(v) { this.text=String(v??'');this.children=[]; }
  get textContent() { return this.text+this.children.map(c=>c.textContent).join(' '); }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.text='';this.children=items; }
  addEventListener() {}
}
function dom() {
  const ids=new Map(['trade-audit-panel','audit-result','audit-items','audit-history','audit-message','audit-run','audit-refresh','audit-history-next','audit-items-next','audit-filter-submit','audit-verdict','audit-mode','audit-side','audit-order','audit-filter'].map(id=>[id,new Element()]));
  globalThis.document={createElement:()=>new Element(),getElementById:id=>ids.get(id)};globalThis.window={confirm:()=>true};return ids;
}
const flush=()=>new Promise(r=>setImmediate(r));
test('coverage warning remains visible even when individual counts match',()=>{
  dom();const e=new Element();renderAuditRun(e,{lifecycle:'INCOMPLETE',verdict:'INCONCLUSIVE',match:9,coverage:{dbComplete:true,chainComplete:false,cutStable:true,environmentVerified:true}});
  assert.match(e.textContent,/전수 MATCH를 뜻하지 않습니다/);assert.match(e.textContent,/체인 근거·역방향 스캔/);
});
test('detail uses safe text and exact amounts rather than HTML or numeric conversion',()=>{
  dom();const e=new Element();renderAuditItems(e,[{kind:'CHAIN_EVENT',mode:'ONCHAIN',verdict:'MISMATCH',checks:[{code:'<img>',expected:'100000000000000000000001',actual:'1',matches:false}]}]);
  assert.match(e.textContent,/<img>/);assert.match(e.textContent,/100000000000000000000001/);
});
test('USER and inactive ADMIN cannot submit audit',()=>{
  const ids=dom();let calls=0;const p=createTradeAuditPanel({startAudit:()=>calls++});p.setContext({role:'USER'},'admin');ids.get('audit-run').onclick();p.setContext({role:'ADMIN'},'market');ids.get('audit-run').onclick();assert.equal(calls,0);assert.equal(ids.get('trade-audit-panel').hidden,true);
});
test('logout drops a late history response',async()=>{
  const ids=dom();let resolve;const p=createTradeAuditPanel({auditHistory:()=>new Promise(r=>{resolve=r;})});p.setContext({role:'ADMIN'},'admin');ids.get('audit-refresh').onclick();p.setContext(null,'market');resolve({body:{items:[{id:'old',verdict:'MATCH'}]}});await flush();assert.equal(ids.get('audit-history').textContent,'');assert.equal(ids.get('audit-run').disabled,false);
});
test('failed POST is not automatically retried and double click is gated',async()=>{
  const ids=dom();let calls=0;const p=createTradeAuditPanel({startAudit:async()=>{calls++;throw new TypeError('network');}});p.setContext({role:'ADMIN'},'admin');ids.get('audit-run').onclick();ids.get('audit-run').onclick();await flush();assert.equal(calls,1);assert.match(ids.get('audit-message').textContent,/자동으로 다시 실행하지 않습니다/);
});
test('API encodes IDs and cursor/filter without operator or RPC injection',async()=>{
  const calls=[];globalThis.fetch=async(path,options)=>{calls.push({path,options});return {ok:true,status:200,text:async()=>'{}'};};const api=new ApiClient();await api.startAudit();await api.auditHistory('a/b');await api.auditItems('a/b','50',{verdict:'MISMATCH',mode:'ONCHAIN',orderId:'1',rpc:'evil'});
  assert.equal(calls[0].options.body,undefined);assert.equal(calls[0].options.method,'POST');assert.match(calls[1].path,/a%2Fb/);assert.match(calls[2].path,/before=50/);assert.doesNotMatch(calls[2].path,/rpc|evil/);
});
