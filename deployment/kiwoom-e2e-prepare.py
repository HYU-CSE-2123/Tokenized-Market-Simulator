"""Prepare NEW local infrastructure only. No Kiwoom credentials imported; no live provider starts."""
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time

ROOT=Path(__file__).resolve().parent; REPO=ROOT.parent
spec=importlib.util.spec_from_file_location('offline_helpers',ROOT/'playground.py')
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
RUN=None; SECRET=[]
FOUNDRY='ghcr.io/foundry-rs/foundry@sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17'

def put(path,value): h.private(path,value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2))
def command(args,step,cwd=REPO,env=None,log=True,timeout=600):
    r=subprocess.run(args,cwd=cwd,env=env or h.clean_env(),capture_output=True,timeout=timeout)
    if RUN and log:
        out=(r.stdout+r.stderr).decode('utf-8',errors='replace')
        for v in SECRET:out=out.replace(v,'[REDACTED]')
        put(RUN/'logs'/(step+'-'+secrets.token_hex(4)+'.log'),out)
    if r.returncode:raise RuntimeError(step+' failed; no retry/reset/redeploy')
    return r.stdout.decode('utf-8').strip()
def image(name):return command(['docker','image','inspect',name,'--format','{{.Id}}'],'cached-image')
def compose(*args,file='infra.json'):
    return ['docker','compose','-p',PROJECT,'--env-file',str(RUN/'release.env'),'-f',str(RUN/file),*args]
def owned(service,file='infra.json'):
    cid=command(compose('ps','-aq',service,file=file),'owned-id')
    obj=json.loads(command(['docker','inspect',cid],'owned-inspect',log=False))[0]
    if obj['Config']['Labels'].get('com.docker.compose.project')!=PROJECT:raise RuntimeError('Foreign container')
    for mount in obj['Mounts']:
        source=mount['Source'].replace('/run/desktop/mnt/host/c/','C:/')
        if not Path(source).resolve().is_relative_to(RUN):raise RuntimeError('Foreign mount')
    return cid,obj
def rpc(method,*params):
    cid,_=owned('anvil')
    return json.loads(command(['docker','exec',cid,'cast','rpc',method,*params,'--rpc-url','http://127.0.0.1:8545'],'local-rpc'))
def call(address,signature,*params):
    cid,_=owned('anvil')
    return command(['docker','exec',cid,'cast','call',address,signature,*params,'--rpc-url','http://127.0.0.1:8545'],'local-call').split()[0]
def sql(query):
    cid,_=owned('postgres')
    return command(['docker','exec',cid,'psql','-X','-U',DB,'-d',DB,'-v','ON_ERROR_STOP=1','-tAc',query],'local-sql')
def port(used):
    for _ in range(100):
        value=30000+secrets.randbelow(18000)
        if value in used:continue
        try:
            with socket.socket() as p:p.bind(('127.0.0.1',value))
            return value
        except OSError:pass
    raise RuntimeError('No free port; never stop existing services')

def main():
    global RUN,PROJECT,DB
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S');suffix=secrets.token_hex(4)
    name='kiwoom-e2e-'+stamp+'-'+suffix;RUN=ROOT/'runtime'/name;RUN.mkdir(mode=0o700)
    who=command(['whoami'],'user',log=False)
    command(['icacls',str(RUN),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],'acl',log=False)
    for p in ('logs','secrets','data/trade','data/chain','data/release','scripts','build/backend','build/web'):(RUN/p).mkdir(parents=True)
    print('NEW_RUN='+RUN.as_posix(),flush=True)
    PROJECT='exchange-'+name;DB='kiwoom_e2e_'+suffix;ports={}
    for k in ('db','rpc','backend','web'):ports[k]=port(ports.values())
    git=['git','-c','safe.directory='+REPO.as_posix()]
    status=command(git+['status','--porcelain'],'git-before');head=command(git+['rev-parse','HEAD'],'head');source=h.source_hash()
    images={'postgres':image('postgres:16'),'anvil':image(FOUNDRY),'client':image('python:3.11-alpine')}
    image('tokenized-market-backend:release');image('nginx:1.28-alpine')
    values={'DB_PASSWORD':secrets.token_urlsafe(32),'JWT_SECRET':secrets.token_urlsafe(48),
        'ADMIN_LOGIN_ID':'kiwoom_admin_'+suffix,'ADMIN_PASSWORD':secrets.token_urlsafe(24),
        'OPERATOR_PRIVATE_KEY':'0x'+secrets.token_hex(32),'PRICE_SIGNER_PRIVATE_KEY':'0x'+secrets.token_hex(32)}
    if values['OPERATOR_PRIVATE_KEY']==values['PRICE_SIGNER_PRIVATE_KEY']:raise RuntimeError('Separate keys required')
    SECRET.extend(values.values());put(RUN/'secrets/application.properties',''.join(k+'='+v+'\n' for k,v in values.items()))
    put(RUN/'secrets/db-password',values['DB_PASSWORD'])
    put(RUN/'secrets/kiwoom.properties','# Not populated. External calls NOT APPROVED.\nKIWOOM_APP_KEY=\nKIWOOM_SECRET_KEY=\n')
    user={'loginId':'kiwoom_user_'+suffix,'password':secrets.token_urlsafe(24),'nickname':'격리 인수 사용자'}
    SECRET.append(user['password']);put(RUN/'secrets/accounts.json',{'admin':{'loginId':values['ADMIN_LOGIN_ID'],'password':values['ADMIN_PASSWORD']},'user':user})
    java=Path('C:/Program Files/Java/jdk-21.0.10');env=h.clean_env();env['JAVA_HOME']=str(java);env['PATH']=str(java/'bin')+os.pathsep+env['PATH']
    command([str(REPO/'backend/gradlew.bat'),'bootJar','--offline','--no-daemon'],'backend-build',REPO/'backend',env)
    jars=list((REPO/'backend/build/libs').glob('*-SNAPSHOT.jar'))
    if len(jars)!=1:raise RuntimeError('Ambiguous artifact')
    shutil.copy2(jars[0],RUN/'build/backend/app.jar');shutil.copy2(ROOT/'Dockerfile.playground-backend',RUN/'build/backend/Dockerfile')
    command(['npm.cmd','run','build'],'web-build',REPO/'tools/websocket-test-client')
    shutil.copytree(REPO/'tools/websocket-test-client/dist',RUN/'build/web/dist');shutil.copytree(REPO/'tools/websocket-test-client/deploy',RUN/'build/web/deploy')
    shutil.copy2(ROOT/'Dockerfile.playground-web',RUN/'build/web/Dockerfile')
    for kind in ('backend','web'):
        tag='tokenized-kiwoom-'+kind+':'+name
        command(['docker','build','--network=none','--pull=false','-t',tag,str(RUN/'build'/kind)],kind+'-image');images[kind]=image(tag)
    tag='tokenized-kiwoom-tools:'+name
    command(['docker','build','--network=none','--pull=false','-f',str(ROOT/'Dockerfile.phase-a-tools'),'-t',tag,str(REPO)],'tools-build');images['tools']=image(tag)
    for f in ('anvil-entrypoint.sh','postgres-readiness.sh'):put(RUN/'scripts'/f,(ROOT/f).read_text(encoding='utf-8'))
    network=PROJECT+'-isolated'
    for octet in secrets.SystemRandom().sample(range(256),8):
        r=subprocess.run(['docker','network','create','--internal','--subnet',f'10.242.{octet}.0/24','--label','kiwoom-e2e.owner='+name,network],env=h.clean_env(),capture_output=True)
        if r.returncode==0:break
        if b'overlap' not in r.stderr.lower():raise RuntimeError('Network creation failed')
    else:raise RuntimeError('No subnet available')
    put(RUN/'release.env','RUN_ROOT='+RUN.as_posix()+'\n')
    def mount(path,destination,readonly=False):return str(RUN/path).replace('\\','/')+':'+destination+(':ro' if readonly else '')
    infra={'services':{
        'postgres':{'image':images['postgres'],'environment':{'POSTGRES_DB':DB,'POSTGRES_USER':DB,'POSTGRES_PASSWORD_FILE':'/run/secrets/db-password'},
            'ports':[f"127.0.0.1:{ports['db']}:5432"],'volumes':[mount('data/trade','/var/lib/postgresql/data'),mount('secrets/db-password','/run/secrets/db-password',True),mount('scripts/postgres-readiness.sh','/postgres-readiness.sh',True)],
            'networks':['isolated','host-access'],'healthcheck':{'test':['CMD','sh','/postgres-readiness.sh'],'interval':'3s','timeout':'3s','retries':30}},
        'anvil':{'image':images['anvil'],'user':'10001:10001','entrypoint':['/bin/sh','/anvil-entrypoint.sh'],
            'command':['--host','0.0.0.0','--accounts','0','--chain-id','31337','--state','/data/state.json','--state-interval','5','--preserve-historical-states','--quiet'],
            'ports':[f"127.0.0.1:{ports['rpc']}:8545"],'volumes':[mount('data/chain','/data'),mount('scripts/anvil-entrypoint.sh','/anvil-entrypoint.sh',True)],
            'networks':['isolated','host-access'],'stop_grace_period':'60s','healthcheck':{'test':['CMD-SHELL','test -f /tmp/anvil-ready && cast rpc eth_chainId --rpc-url http://127.0.0.1:8545 >/dev/null'],'interval':'3s','timeout':'3s','retries':30}}},
        'networks':{'isolated':{'external':True,'name':network},'host-access':{}}}
    put(RUN/'infra.json',infra)
    environment={'SPRING_PROFILES_ACTIVE':'kiwoom-e2e','SPRING_CONFIG_IMPORT':'file:/run/secrets/application.properties,file:/run/secrets/chain.properties',
        'DB_URL':'jdbc:postgresql://postgres:5432/'+DB,'DB_USERNAME':DB,'RPC_URL':'http://anvil:8545',
        'BLOCKCHAIN_ENABLED':'true','PRICE_REPORT_SIGNING_ENABLED':'true','RESERVE_RECONCILIATION_ENABLED':'true','RESERVE_EXECUTION_ID':name,'TRADE_AUDIT_ENABLED':'true',
        'AI_ENABLED':'false','AI_TOOLS_ENABLED':'false','AI_AGENT_ENABLED':'false','AI_SKILLS_ENABLED':'false','AI_AUTO_DIAGNOSIS_ENABLED':'false','SERVER_PORT':'8082',
        'WEBSOCKET_ALLOWED_ORIGINS':f"http://127.0.0.1:{ports['web']},http://localhost:{ports['web']}"}
    base={'image':images['backend'],'environment':environment,'volumes':[mount('secrets/application.properties','/run/secrets/application.properties',True),mount('data/release/chain.properties','/run/secrets/chain.properties',True)],
        'user':'10001:10001','read_only':True,'tmpfs':['/tmp:size=128m,mode=1777'],'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'stop_grace_period':'60s'}
    bootstrap=json.loads(json.dumps(infra));bootstrap['services']['initializer']={**base,'command':['--app.price.provider=simulated','--app.price.initial-delay-ms=2147483647','--app.price.simulation.bootstrap-days=0','--app.blockchain.reconciliation.initial-delay-ms=2147483647','--app.conditional.enabled=false'],'networks':['isolated']}
    put(RUN/'bootstrap.json',bootstrap)
    live=json.loads(json.dumps(infra));live['networks']['egress']={};backend=json.loads(json.dumps(base))
    backend.update({'profiles':['live'],'command':['--app.price.provider=kiwoom','--app.conditional.enabled=true'],'networks':['isolated','egress'],'ports':[f"127.0.0.1:{ports['backend']}:8082"]})
    backend['environment']['SPRING_CONFIG_IMPORT']+=',file:/run/secrets/kiwoom.properties';backend['volumes'].append(mount('secrets/kiwoom.properties','/run/secrets/kiwoom.properties',True))
    backend['environment']['PRICE_PROVIDER']='kiwoom'
    live['services']['backend']=backend;live['services']['web']={'profiles':['live'],'image':images['web'],'ports':[f"127.0.0.1:{ports['web']}:80"],'environment':{'BACKEND_UPSTREAM':'backend:8082'},'networks':['egress']}
    put(RUN/'live-NOT-APPROVED.json',live)
    put(RUN/'run.json',{'runId':name,'project':PROJECT,'ports':ports,'database':DB,'chainId':31337,'sourceHead':head,'sourceHash':source,'images':images,'externalCallsApproved':False})
    command(compose('up','-d','--wait','postgres','anvil'),'infra-start')
    for service in ('postgres','anvil'):owned(service)
    command(['docker','run','--rm','--network',network,'-v',str(RUN/'secrets')+':/run/secrets:ro','-v',str(RUN/'data/release')+':/release',images['tools']],'deploy-once')
    addresses=dict(line.split('=',1) for line in (RUN/'data/release/chain.properties').read_text().splitlines() if '=' in line)
    if len(addresses)!=4 or int(rpc('eth_chainId'),16)!=31337:raise RuntimeError('Chain identity invalid')
    for address in addresses.values():
        if rpc('eth_getCode',address,'latest')=='0x':raise RuntimeError('Missing contract')
    command(compose('up','-d','initializer',file='bootstrap.json'),'bootstrap-start')
    cid,info=owned('initializer','bootstrap.json')
    if set(info['NetworkSettings']['Networks'])!={network}:raise RuntimeError('Bootstrap has outside network')
    if not json.loads(command(['docker','network','inspect',network],'network-check',log=False))[0]['Internal']:raise RuntimeError('Network not internal')
    client="""import json,time,urllib.request
from pathlib import Path
a=json.loads(Path('/run/secrets/accounts.json').read_text());o=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(p,d=None,t=None):
 r=urllib.request.Request('http://initializer:8082'+p,data=json.dumps(d).encode() if d is not None else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+t} if t else {})})
 with o.open(r,timeout=5) as f:return json.load(f)
for i in range(90):
 try:
  if api('/api/health')['status']=='UP':break
 except Exception:time.sleep(1)
else:raise SystemExit('Bootstrap unavailable')
api('/api/auth/signup',a['user']);t=api('/api/auth/login',a['admin'])['accessToken'];b=api('/api/admin/reserve-reconciliations/baseline',{},t)
print(json.dumps({'baseline':b,'accountsPrepared':True,'orders':'NOT_RUN'}))
"""
    try:
        response=command(['docker','run','--rm','--network',network,'-v',str(RUN/'secrets')+':/run/secrets:ro',images['client'],'python','-c',client],'local-bootstrap',log=False,timeout=180);put(RUN/'baseline.json',response)
    finally:command(compose('stop','initializer',file='bootstrap.json'),'bootstrap-stop')
    baseline=json.loads(response)['baseline']
    counts=sql('SELECT (SELECT count(*) FROM orders),(SELECT count(*) FROM trades),(SELECT count(*) FROM price_quotes),(SELECT count(*) FROM blockchain_transactions),(SELECT count(*) FROM faucet_grants),(SELECT count(*) FROM reserve_baselines),(SELECT count(*) FROM user_balances WHERE amount<>0 OR locked_amount<>0);')
    if counts!='0|0|0|0|0|1|0':raise RuntimeError('Unexpected ledger')
    vault=addresses['EXCHANGE_VAULT_ADDRESS'];krw=addresses['MOCK_KRW_ADDRESS'];token=addresses['MSEC_ADDRESS'];oracle=addresses['PRICE_ORACLE_ADDRESS']
    operator=call(vault,'owner()(address)');signer=call(oracle,'priceSigner()(address)')
    checks={'separateSigner':operator.lower()!=signer.lower(),'minter':call(token,'minter()(address)').lower()==vault.lower(),'consumer':call(oracle,'authorizedConsumer()(address)').lower()==vault.lower(),
        'vaultKrw':call(vault,'krw()(address)').lower()==krw.lower(),'vaultToken':call(vault,'token()(address)').lower()==token.lower(),'vaultOracle':call(vault,'oracle()(address)').lower()==oracle.lower()}
    if not all(checks.values()):raise RuntimeError('Invalid links')
    funding={'operatorKrwWei':call(krw,'balanceOf(address)(uint256)',operator),'vaultKrwWei':call(krw,'balanceOf(address)(uint256)',vault),'operatorMsecWei':call(token,'balanceOf(address)(uint256)',operator),
        'totalKrwWei':call(krw,'totalSupply()(uint256)'),'totalMsecWei':call(token,'totalSupply()(uint256)'),'allowanceWei':call(krw,'allowance(address,address)(uint256)',operator,vault),'operatorEthWei':int(rpc('eth_getBalance',operator,'latest'),16)}
    nonce=rpc('eth_getTransactionCount',operator,'latest')
    command(compose('stop','anvil'),'owned-anvil-stop')
    if not (RUN/'data/chain/state.json').is_file():raise RuntimeError('Missing checkpoint')
    command(compose('up','-d','--wait','anvil'),'owned-anvil-resume')
    if rpc('eth_getTransactionCount',operator,'latest')!=nonce or call(krw,'balanceOf(address)(uint256)',operator)!=funding['operatorKrwWei']:raise RuntimeError('Restart lost state')
    block=baseline['chain']['block'];preserved=rpc('eth_getBlockByNumber',hex(int(block['number'])),'false')
    if preserved['hash'].lower()!=block['hash'].lower():raise RuntimeError('Baseline not canonical')
    if h.source_hash()!=source or command(git+['status','--porcelain'],'git-after')!=status:raise RuntimeError('Source or Git changed during preparation')
    put(RUN/'ready.json',{'contracts':addresses,'operator':operator,'priceSigner':signer,'checks':checks,'funding':funding,'ledgerCounts':counts,'baselinePreservedAfterRestart':True,'externalCalls':0,'liveBackendStarted':False,'realE2E':'NOT_RUN'})
    print('PREPARED '+name+' ports='+json.dumps(ports),flush=True)

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('PREPARATION STOPPED: '+type(error).__name__+'; new evidence preserved; no reset/redeploy/shared cleanup',flush=True)
        raise SystemExit(1) from None
