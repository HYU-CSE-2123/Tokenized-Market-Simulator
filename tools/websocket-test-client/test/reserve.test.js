import test from 'node:test';
import assert from 'node:assert/strict';
import { createReservePanel, renderReserveResult, tokenText } from '../src/reserve-panel.js';
import { ApiClient } from '../src/api.js';

class Element {
  constructor() { this.children=[]; this.text=''; this.hidden=false; this.disabled=false; }
  set textContent(value) { this.text=String(value??''); this.children=[]; }
  get textContent() { return this.text+this.children.map(c=>c.textContent).join(' '); }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.text=''; this.children=items; }
  addEventListener() {}
}
function dom() {
  const ids=new Map(['reserve-panel','reserve-create','reserve-run','reserve-refresh','reserve-message','reserve-result','reserve-history'].map(id=>[id,new Element()]));
  globalThis.document={createElement:()=>new Element(),getElementById:id=>ids.get(id)};
  globalThis.window={confirm:()=>true}; return ids;
}
test('reserve quantity formatting is exact for uint256 and one wei',()=>{
  assert.equal(tokenText('1'),'0.000000000000000001');
  assert.equal(tokenText('-1'),'-0.000000000000000001');
  assert.equal(tokenText('1000000000000000000000000'),'1000000');
  assert.equal(tokenText('bad'),'확인 불가');
});
test('reserve UI separates inconclusive verdict and allocation-only liquidity without unsafe markup',()=>{
  dom(); const target=new Element();
  renderReserveResult(target,{status:'INCONCLUSIVE',reason:'CUT_CHANGED',checks:[{name:'<img onerror=bad>',expected:'1',actual:'2',difference:'1',matches:false}],
    liquidity:{operatorBuyFunds:'1',buyAllowance:'2',operatorSellTokens:'3',vaultSellFunds:'4',userKrw:'5',userSec:'6',operatorAllocationReference:'0.2000'}});
  assert.match(target.textContent,/동일 시점/); assert.match(target.textContent,/유동성/);
  assert.match(target.textContent,/준비금 보증 비율이나 Proof of Reserves가 아니/);
  assert.match(target.textContent,/<img/); assert.equal(target.children.some(e=>e.tagName==='img'),false);
});
test('USER cannot initiate reserve requests',async()=>{
  const ids=dom(); let calls=0;
  const panel=createReservePanel({reconcileAssets:()=>{calls++;}});
  panel.setContext({id:1,role:'USER'},'admin');
  await ids.get('reserve-run').onclick(); assert.equal(calls,0); assert.equal(ids.get('reserve-panel').hidden,true);
});
test('operator ETH is exact read-only information and never a gas sufficiency promise',()=>{
  dom(); const target=new Element();
  for(const eth of ['1','0',null]) {
    renderReserveResult(target,{status:'MATCH',liquidity:{operatorEthWei:eth}});
    assert.match(target.textContent,/비용 미추정/); assert.match(target.textContent,/실행 가능성을 보증하지 않습니다/);
    assert.match(target.textContent,eth===null?/조회 불가/:eth==='1'?/0\.000000000000000001 ETH/:/0 ETH/);
  }
});
test('logout discards a late reserve response and restores control state',async()=>{
  const ids=dom(); let finish;
  const panel=createReservePanel({reconcileAssets:()=>new Promise(r=>{finish=r;})});
  panel.setContext({id:1,role:'ADMIN'},'admin'); const request=ids.get('reserve-run').onclick();
  panel.setContext(null,'market'); finish({body:{status:'MATCH',checks:[]}});
  await request; assert.equal(ids.get('reserve-result').textContent,''); assert.equal(ids.get('reserve-run').disabled,false);
});
test('reserve failed write is never retried and stale result is cleared',async()=>{
  const ids=dom(); let count=0;
  const panel=createReservePanel({reconcileAssets:async()=>{count++; throw new TypeError('network');}});
  panel.setContext({id:1,role:'ADMIN'},'admin'); ids.get('reserve-result').textContent='old success';
  await ids.get('reserve-run').onclick(); assert.equal(count,1); assert.equal(ids.get('reserve-result').textContent,'');
  assert.match(ids.get('reserve-message').textContent,/자동 재요청하지 않습니다/);
});
test('reserve client API uses ADMIN paths without identity or transaction injection',async()=>{
  const calls=[]; globalThis.fetch=async(path,options)=>{calls.push({path,options}); return {ok:true,status:200,text:async()=>'{}'};};
  const api=new ApiClient(); await api.createReserveBaseline(); await api.reconcileAssets(); await api.reserveHistory(); await api.reserveDetail('abc');
  assert.equal(calls[0].path,'/api/admin/reserve-reconciliations/baseline');
  assert.equal(calls[1].options.method,'POST'); assert.equal(calls[1].options.body,undefined);
  assert.equal(calls[3].path,'/api/admin/reserve-reconciliations/abc');
});
