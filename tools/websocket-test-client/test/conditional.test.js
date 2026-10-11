import test from 'node:test';import assert from 'node:assert/strict';
import { conditionalRequest,pendingConditional,clearConditional,conditionalReason,createConditionalPanel } from '../src/conditional-panel.js';
import { ApiClient } from '../src/api.js';
function storage(){const data=new Map();return {getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)};}
test('conditional BUY and SELL preserve exact decimal strings and no signature/quote fields',()=>{for(const side of ['BUY','SELL']){const r=conditionalRequest(side,'1.000000000000000001','75000.00000001','2099-01-01T00:00');assert.equal(r.inputAmount,'1.000000000000000001');assert.equal(r.targetPrice,'75000.00000001');assert.equal(r.side,side);assert.equal(Object.hasOwn(r,'quoteId'),false);assert.match(r.expiresAt,/Z$/);}});
test('conditional UI rejects excessive precision and expired intent',()=>{assert.throws(()=>conditionalRequest('BUY','1.0000000000000000001','1','2099-01-01T00:00'));assert.throws(()=>conditionalRequest('SELL','1','1.000000001','2099-01-01T00:00'));assert.throws(()=>conditionalRequest('BUY','1','1','2000-01-01T00:00'));});
test('uncertain registration preserves original key and payload across tabs and form changes',async()=>{const store=storage(),locks={request:async(_,fn)=>fn()};const r={side:'BUY',inputAmount:'1'};const a=await pendingConditional(1,r,store,locks),b=await pendingConditional(1,{side:'SELL',inputAmount:'2'},store,locks);assert.deepEqual(a,b);clearConditional(1,'different',store);assert.deepEqual(JSON.parse(store.getItem('conditional-pending:1')),a);clearConditional(1,a.key,store);assert.equal(store.getItem('conditional-pending:1'),null);});
test('conditional API uses owner JWT and bounded encoded paths, opaque key never in URL',async()=>{const calls=[];globalThis.fetch=async(path,options)=>{calls.push({path,options});return{ok:true,status:200,text:async()=>'{"items":[]}'};};const api=new ApiClient();await api.createConditional({symbol:'mSEC',side:'BUY',inputAmount:'1',targetPrice:'75000',expiresAt:'2099-01-01T00:00:00Z'},'opaque-conditional-key');await api.conditionalRequest('opaque-conditional-key');await api.cancelConditional('a/b');assert.equal(calls[0].options.headers['Idempotency-Key'],'opaque-conditional-key');assert.equal(calls[1].path,'/api/conditional-orders/requests');assert.ok(calls.every(c=>!c.path.includes('opaque-conditional-key')));assert.match(calls[2].path,/a%2Fb/);assert.equal(JSON.parse(calls[0].options.body).userId,undefined);});
test('unknown diagnosis codes are not rendered as raw errors',()=>{assert.equal(conditionalReason('<img src=x>'),'상태를 다시 확인해 주세요.');assert.match(conditionalReason('PRE_SUBMIT_INTERRUPTED'),/새 주문을 자동 실행하지/);});

class Element {
  constructor(){this.children=[];this.text='';this.disabled=false;this.value='';}
  set textContent(v){this.text=String(v??'');this.children=[];}
  get textContent(){return this.text+this.children.map(x=>x.textContent).join(' ');}
  append(...x){this.children.push(...x);}replaceChildren(...x){this.text='';this.children=x;}
  addEventListener(name,action){this['on'+name]=action;}
}
function setup(){
  const ids=new Map(['form','side','input','target','expiry','message','submit','resolve','retry','refresh','admin-refresh','older','admin-older','history','admin-history','availability','panel','admin-panel'].map(x=>['conditional-'+x,new Element()]));
  globalThis.document={getElementById:x=>ids.get(x),createElement:()=>new Element()};globalThis.localStorage=storage();
  ids.get('conditional-side').value='BUY';ids.get('conditional-input').value='100000';ids.get('conditional-target').value='75000';ids.get('conditional-expiry').value='2099-01-01T00:00';return ids;
}
const flush=()=>new Promise(r=>setImmediate(r));
const reads={conditionalOrders:async()=>({body:{items:[],nextBefore:null}}),conditionalCapabilities:async()=>({body:{registrationEnabled:true}})};
test('panel rejects over-wide input before creating a pending request',async()=>{
  const ids=setup();let calls=0;const panel=createConditionalPanel({...reads,createConditional:async()=>calls++},()=>{});
  panel.setContext({id:1,role:'USER'},'market');await flush();ids.get('conditional-input').value='1000000000000';
  await ids.get('conditional-form').onsubmit();assert.equal(calls,0);assert.equal(localStorage.getItem('conditional-pending:1'),null);
});
test('definitive validation400 allows corrected registration with a new key',async()=>{
  const ids=setup(),keys=[];const panel=createConditionalPanel({...reads,createConditional:async(body,key)=>{keys.push(key);if(keys.length===1)throw {status:400,body:{code:'INVALID_EXPIRY'}};return {body:{id:'accepted',state:'WAITING'}};}},()=>{});
  panel.setContext({id:1,role:'USER'},'market');await flush();await ids.get('conditional-form').onsubmit();
  assert.equal(localStorage.getItem('conditional-pending:1'),null);ids.get('conditional-input').value='200000';await ids.get('conditional-form').onsubmit();
  assert.equal(keys.length,2);assert.notEqual(keys[0],keys[1]);assert.equal(localStorage.getItem('conditional-pending:1'),null);
});
test('lost response blocks parallel and changed requests, lookup404 preserves the same intent',async()=>{
  const ids=setup();let reject;const calls=[];
  const panel=createConditionalPanel({...reads,createConditional:(body,key)=>{calls.push({body,key});return new Promise((resolve,no)=>reject=no);},conditionalRequest:async()=>{throw {status:404};}},()=>{});
  panel.setContext({id:1,role:'USER'},'market');await flush();const pending=ids.get('conditional-form').onsubmit();await flush();
  await ids.get('conditional-form').onsubmit();assert.equal(calls.length,1);reject(new TypeError('response lost'));await pending;
  const old=localStorage.getItem('conditional-pending:1');ids.get('conditional-input').value='200000';await ids.get('conditional-form').onsubmit();
  assert.equal(calls.length,1);await ids.get('conditional-resolve').onclick();assert.equal(localStorage.getItem('conditional-pending:1'),old);
});
test('server503 and unrecognized400 remain uncertain rather than generating a replacement key',async()=>{
  for(const error of [{status:503},{status:400,body:{code:'PROXY_ERROR'}}]){
    const ids=setup();const keys=[];const panel=createConditionalPanel({...reads,createConditional:async(body,key)=>{keys.push(key);throw error;}},()=>{});
    panel.setContext({id:1,role:'USER'},'market');await flush();await ids.get('conditional-form').onsubmit();await ids.get('conditional-form').onsubmit();
    assert.equal(keys[0],keys[1]);assert.notEqual(localStorage.getItem('conditional-pending:1'),null);
  }
});
test('logout discards a late registration result and preserves its uncertain key',async()=>{
  const ids=setup();let finish;const panel=createConditionalPanel({...reads,createConditional:()=>new Promise(r=>finish=r)},()=>{});
  panel.setContext({id:1,role:'USER'},'market');await flush();const pending=ids.get('conditional-form').onsubmit();await flush();
  const key=localStorage.getItem('conditional-pending:1');panel.setContext(null,'market');finish({body:{id:'private',state:'WAITING'}});await pending;
  assert.equal(ids.get('conditional-message').textContent,'');assert.equal(localStorage.getItem('conditional-pending:1'),key);assert.equal(ids.get('conditional-panel').hidden,true);
});
test('late history does not repopulate private DOM after logout and USER never reads ADMIN history',async()=>{
  const ids=setup();let finish,admin=0;const panel=createConditionalPanel({...reads,conditionalOrders:()=>new Promise(r=>finish=r),conditionalAdmin:()=>admin++},()=>{});
  panel.setContext({id:1,role:'USER'},'admin');await flush();assert.equal(admin,0);
  panel.setContext({id:1,role:'USER'},'market');panel.setContext(null,'market');finish({body:{items:[],nextBefore:null}});await flush();
  assert.equal(ids.get('conditional-history').textContent,'');assert.equal(ids.get('conditional-admin-panel').hidden,true);
});
