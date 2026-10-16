// One real browser tab, 1m only. No fixtures, no raw auth/transaction logging.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
const run=resolve(process.argv[2]), out=resolve(process.argv[3]);
const meta=JSON.parse(await readFile(join(run,'run.json'),'utf8'));
const accounts=JSON.parse(await readFile(join(run,'secrets/accounts.json'),'utf8'));
const base=`http://127.0.0.1:${meta.ports.backend}`, web=`http://127.0.0.1:${meta.ports.web}`;
const profile=join(out,'chrome-profile');await mkdir(profile,{recursive:true});
const result={startedAt:new Date().toISOString(),stages:{},http:[],privateEvents:[],orders:[],quotes:[],market:null};
let browser,ws,serial=0,userToken=null;const pending=new Map(),responses=new Map(),privateSubscriptions=new Set(),responseRequests=new Map();
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function poll(f,ms=30000){const end=Date.now()+ms;while(Date.now()<end){const x=await f();if(x)return x;await sleep(150);}throw Error('WAIT_TIMEOUT');}
async function api(path,token=null,body=undefined){const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(12000)});const b=await r.json();if(!r.ok)throw Error(`API_${r.status}_${b.code||'ERROR'}`);return b;}
function command(method,params={}){return new Promise((resolve,reject)=>{const id=++serial;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP_TIMEOUT'));},8000);pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v);},reject:e=>{clearTimeout(timer);reject(e);}});ws.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const r=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error('BROWSER_EVALUATION');return r.result.value;}
const click=id=>evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
const fill=(id,value)=>evaluate(`{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));}`);
function sql(query){return execFileSync('C:/Program Files/Docker/Docker/resources/bin/docker.exe',['--context','desktop-linux','exec',meta.project+'-postgres-1','psql','-X','-U',meta.database,'-d',meta.database,'-tAc',query],{encoding:'utf8',timeout:12000,windowsHide:true}).trim();}
async function ready(){const m=await api('/api/markets/mSEC');const age=Date.now()-Date.parse(m.observedAt);if(m.provider!=='KIWOOM'||m.marketStatus==='CLOSED'||m.priceStatus!=='LIVE'||m.mockQuoteAllowed!==true||age>5000||age< -2000)return false;result.market=m;return m;}
function messageFrame(p,sent){try{const raw=p.response.opcode===2?Buffer.from(p.response.payloadData,'base64').toString():p.response.payloadData;for(const f of raw.split('\0')){if(sent){const dest=f.match(/destination:([^\n]+)/)?.[1];if(f.includes('SUBSCRIBE')&&dest?.startsWith('/user/queue/'))privateSubscriptions.add(dest);continue;}const cut=f.indexOf('\n\n');if(cut<0||!f.includes('MESSAGE'))continue;const dest=f.match(/destination:([^\n]+)/)?.[1];if(!dest?.includes('/queue/'))continue;const e=JSON.parse(f.slice(cut+2));const d=e.data||e.payload||{};result.privateEvents.push({destination:dest,type:e.type,eventId:e.eventId,data:d,receivedAt:new Date().toISOString()});}}catch{}}
async function trade(side,amount){
  if(!await ready())throw Error('MARKET_UNAVAILABLE');
  await evaluate("location.hash='market'");await click(side==='BUY'?'side-buy':'side-sell');await fill('trade-amount',amount);
  responses.delete('/api/quotes/'+side.toLowerCase());await evaluate("document.getElementById('trade-form').requestSubmit()");
  const q=await poll(()=>responses.get('/api/quotes/'+side.toLowerCase()));if(q.status!==200)throw Error(`QUOTE_${q.status}_${q.body.code||'ERROR'}`);
  const quote=q.body;result.quotes.push(quote);if(!quote.quoteId||quote.status!=='ISSUED')throw Error('SIGNED_QUOTE_MISSING');
  await poll(()=>evaluate("!document.getElementById('quote-confirm').disabled"));await click('quote-confirm');await poll(()=>evaluate("document.getElementById('quote-dialog').open"));
  responses.delete('/api/orders/'+side.toLowerCase());await click('quote-submit');
  const submitted=await poll(()=>responses.get('/api/orders/'+side.toLowerCase()));if(![200,201,202].includes(submitted.status))throw Error(`ORDER_${submitted.status}_${submitted.body.code||'ERROR'}`);
  const id=submitted.body.orderId;if(!Number.isSafeInteger(id))throw Error('ORDER_ID_MISSING');result.orders.push(submitted.body);
  const filled=await poll(async()=>{const o=await api('/api/orders/'+id,userToken);result.orders[result.orders.length-1]=o;if(o.status==='FAILED'||o.status==='REVIEW_REQUIRED')throw Error('ORDER_'+o.status);return o.status==='FILLED'?o:false;},45000);
  result.stages[side]='PASS';return filled;
}
try{
  await poll(async()=>{try{return (await api('/api/health')).status==='UP';}catch{return false;}},75000);
  await poll(()=>ready(),45000);result.stages.market='PASS';
  browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--disable-background-networking','--disable-component-update','--disable-sync','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
  const port=await poll(async()=>{try{return (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];}catch{return false;}});
  const targets=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();ws=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r));
  ws.addEventListener('message',async e=>{const x=JSON.parse(e.data);if(x.id){const p=pending.get(x.id);pending.delete(x.id);if(x.error)p?.reject(Error('CDP_ERROR'));else p?.resolve(x.result);return;}
    if(x.method==='Network.webSocketFrameReceived')messageFrame(x.params,false);
    if(x.method==='Network.webSocketFrameSent')messageFrame(x.params,true);
    if(x.method==='Network.responseReceived'){const p=x.params;const u=new URL(p.response.url);if(!u.pathname.startsWith('/api/'))return;result.http.push({path:u.pathname,status:p.response.status,at:new Date().toISOString()});
      if(['/api/auth/login','/api/wallet/faucet','/api/quotes/buy','/api/quotes/sell','/api/orders/buy','/api/orders/sell'].includes(u.pathname))responseRequests.set(p.requestId,{path:u.pathname,status:p.response.status});
    }
    if(x.method==='Network.loadingFinished'){
      const p=responseRequests.get(x.params.requestId);if(!p)return;responseRequests.delete(x.params.requestId);
      try{const r=await command('Network.getResponseBody',{requestId:x.params.requestId});const b=JSON.parse(r.base64Encoded?Buffer.from(r.body,'base64').toString():r.body);if(p.path==='/api/auth/login'){if(b.accessToken)userToken=b.accessToken;}else responses.set(p.path,{status:p.status,body:b});}catch{}
    }
  });
  await command('Runtime.enable');await command('Page.enable');await command('Network.enable');await command('Page.navigate',{url:web});await poll(()=>evaluate("Boolean(document.getElementById('auth-open'))"));
  await click('auth-open');await fill('login-id',accounts.user.loginId);await fill('password',accounts.user.password);await evaluate("document.getElementById('auth-form').requestSubmit()");
  await poll(()=>userToken);const me=await api('/api/me',userToken);if(me.role!=='USER'||!Number.isSafeInteger(me.id))throw Error('USER_ROLE_REQUIRED');result.userId=me.id;
  await poll(()=>privateSubscriptions.has('/user/queue/orders')&&privateSubscriptions.has('/user/queue/portfolio'));
  const chart=await poll(()=>result.http.find(x=>x.path.endsWith('/candles')));if(chart.status!==200)throw Error('CHART_UNAVAILABLE');result.stages.browser='PASS';
  await evaluate("location.hash='portfolio'");await poll(()=>evaluate("!document.getElementById('faucet').disabled"));await click('faucet');
  const grant=await poll(()=>responses.get('/api/wallet/faucet'));if(grant.status!==200||Number(grant.body.receivedAmount)!==1000000||grant.body.replayed!==false)throw Error('FAUCET_FAILED');result.faucet=grant.body;result.stages.faucet='PASS';
  result.portfolioAfterFaucet=await api('/api/portfolio',userToken);
  await trade('BUY','100000');result.portfolioAfterBuy=await api('/api/portfolio',userToken);
  const tokens=sql(`SELECT amount::text FROM user_balances WHERE user_id=${Number(me.id)} AND symbol='mSEC';`);if(!tokens||Number(tokens)<=0)throw Error('NO_TOKEN_BALANCE');
  const sellEventStart=result.privateEvents.length;
  await trade('SELL',tokens);result.portfolioAfterSell=await api('/api/portfolio',userToken);
  await poll(()=>result.orders.every(o=>result.privateEvents.some(x=>x.destination.includes('/orders')&&x.data.orderId===o.orderId&&x.data.status==='FILLED'))&&result.privateEvents.slice(sellEventStart).some(x=>x.destination.includes('/portfolio')&&Number(x.data.tokenBalance)===0&&x.data.krwBalance===result.portfolioAfterSell.krwBalance));
  result.stages.privateWs='PASS';
  const login=await api('/api/auth/login',null,accounts.admin);const admin=login.accessToken;
  result.reserve=await api('/api/admin/reserve-reconciliations',admin,{});if(result.reserve.status!=='MATCH')throw Error('RESERVE_'+result.reserve.status);result.stages.reserve='PASS';
  const audit=await api('/api/admin/trade-audits',admin,{});result.audit=await poll(async()=>{const a=await api('/api/admin/trade-audits/'+audit.id,admin);if(['FAILED','CANCELED'].includes(a.lifecycle))throw Error('AUDIT_FAILED');return a.lifecycle==='COMPLETED'?a:false;},45000);
  result.auditItems=await api('/api/admin/trade-audits/'+audit.id+'/items',admin);
  if(result.audit.verdict!=='MATCH'||!['dbComplete','chainComplete','cutStable','environmentVerified'].every(k=>result.audit.coverage?.[k]===true)||result.audit.coverage.sourceOrders!==2)throw Error('AUDIT_INCOMPLETE');result.stages.audit='PASS';result.status='PASS';
}catch(e){result.status='BLOCKED';result.error=String(e.message).slice(0,140);}
finally{
  result.completedAt=new Date().toISOString();await writeFile(join(out,'browser-result.json'),JSON.stringify(result,null,2));
  if(ws?.readyState===WebSocket.OPEN){try{await command('Browser.close');}catch{}ws.close();}if(browser)browser.kill();
  console.log(JSON.stringify({status:result.status,stages:result.stages,error:result.error,orders:result.orders.map(o=>({id:o.orderId,status:o.status,txHash:o.txHash}))}));
}
