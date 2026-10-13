"""Approved Phase A ONLY. New private fixtures, clean child env, offline tools, no old runtime import."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
FOUNDRY = 'ghcr.io/foundry-rs/foundry@sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17'
CASES = {'reserve': ('RESERVE_E2E', 'ReservePostgresAnvilE2ETest'),
         'audit': ('AUDIT_E2E', 'TradeAuditPostgresAnvilE2ETest'),
         'faucet': ('FAUCET_E2E', 'FaucetPostgresAnvilE2ETest'),
         'conditional': ('CONDITIONAL_E2E', 'ConditionalPostgresAnvilE2ETest')}

def private(path, text):
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w', encoding='utf-8') as f:
        f.write(text)

def clean_env(session):
    keep = {'PATH','PATHEXT','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE',
            'APPDATA','LOCALAPPDATA','JAVA_HOME','GRADLE_USER_HOME','NUMBER_OF_PROCESSORS',
            'PROCESSOR_ARCHITECTURE','PROGRAMFILES','PROGRAMFILES(X86)'}
    env = {k:v for k,v in os.environ.items() if k.upper() in keep}
    # Discover all gates, including old hardcoded/paid suites: default every opt-in OFF.
    for file in (REPO/'backend/src/test/java').rglob('*.java'):
        for gate in re.findall(r'EnabledIfEnvironmentVariable\(\s*named\s*=\s*"([A-Z0-9_]+)"', file.read_text(encoding='utf-8')):
            env[gate] = 'false'
    env.update({'PHASE_A_SESSION':str(session), 'BLOCKCHAIN_ENABLED':'false',
        'PRICE_REPORT_SIGNING_ENABLED':'false', 'PRICE_PROVIDER':'simulated',
        'AI_ENABLED':'false', 'AI_AGENT_ENABLED':'false', 'AI_TOOLS_ENABLED':'false',
        'AI_SKILLS_ENABLED':'false','AI_AUTO_DIAGNOSIS_ENABLED':'false','SPRING_CONFIG_IMPORT':'',
        'JAVA_TOOL_OPTIONS':'-Dspring.config.import= -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=1 '
            '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1 -Dhttp.nonProxyHosts=localhost|127.*|[::1]'})
    return env

def load_session(path):
    p=Path(path).resolve(strict=True)
    if p.parent != (ROOT/'runtime').resolve() or not re.fullmatch(r'phase-a-\d{14}-[0-9a-f]{8}',p.name):
        raise ValueError('New Phase A session only')
    m=json.loads((p/'session.json').read_text())
    if m['session']!=p.name or m['externalProviders']!=False:raise ValueError('Session identity mismatch')
    return p,m

def product_hash():
    digest=hashlib.sha256()
    files=sorted([*(REPO/'backend/src/main').rglob('*'),*(REPO/'contracts/src').rglob('*'),
        REPO/'contracts/script/DeployPublic.s.sol',ROOT/'init-chain.sh',ROOT/'foundry.toml'])
    for f in files:
        if f.is_file():digest.update(f.relative_to(REPO).as_posix().encode()+b'\0'+f.read_bytes())
    return digest.hexdigest()

def verify_session(session,meta):
    if meta['productHash']!=product_hash():raise RuntimeError('Product source changed since session preparation')
    actual=execute(session,['docker','image','inspect',meta['toolsImage'],'--format','{{.Id}}'],'tools-identity')
    if actual!=meta['toolsDigest']:raise RuntimeError('Tools image identity changed')

def execute(session, args, step, cwd=REPO, env=None, timeout=900):
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%f')
    child=env or clean_env(session)
    if any(k.startswith(('TOSS_','OPENAI_','AWS_')) or k in ('OPERATOR_PRIVATE_KEY','PRICE_SIGNER_PRIVATE_KEY') for k in child):
        raise ValueError('External/signing credential in child environment')
    private(session/'logs'/(step+'-'+stamp+'.metadata.json'),json.dumps({'step':step,'at':stamp,
        'operation':Path(args[0]).name,'cwd':str(cwd),'offline':'--offline' in args or '--network=none' in args,
        'testSelector':args[args.index('--tests')+1] if '--tests' in args else None,
        'gates':{k:v for k,v in child.items() if k.endswith(('_TESTS','_EVALUATION'))},
        'configImportDisabled':child.get('SPRING_CONFIG_IMPORT')=='','credentialsInherited':False},indent=2))
    try:result=subprocess.run(args,cwd=cwd,env=child,capture_output=True,timeout=timeout)
    except subprocess.TimeoutExpired as error:
        private(session/'logs'/(step+'-'+stamp+'.timeout.private.log'),
            ((error.stdout or b'')+(error.stderr or b'')).decode('utf-8',errors='replace'))
        raise RuntimeError(step+' timeout; partial evidence retained') from None
    private(session/'logs'/(step+'-'+stamp+'.private.log'),(result.stdout+result.stderr).decode('utf-8',errors='replace'))
    if result.returncode:raise RuntimeError(step+' failed; evidence retained; no reset/redeploy')
    return result.stdout.decode('utf-8',errors='replace').strip()

def init():
    if os.name!='nt':raise RuntimeError('Windows ACL setup required')
    name='phase-a-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    session=ROOT/'runtime'/name;session.mkdir(parents=True,mode=0o700)
    who=subprocess.run(['whoami'],check=True,capture_output=True).stdout.decode().strip()
    subprocess.run(['icacls',str(session),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
    for child in ('logs','fixtures','evidence'):(session/child).mkdir()
    source=execute(session,['git','rev-parse','HEAD'],'source')
    image='tokenized-phase-a-tools:'+name
    # Source-built current contracts. No pull/download, no old tool image shortcuts.
    for required in ('postgres:16',FOUNDRY,'tokenized-reserve-tools:reserve-e2e-20261009043259-4ac3a5'):
        execute(session,['docker','image','inspect',required],'image-cache')
    execute(session,['docker','build','--pull=false','--network=none','-f',str(ROOT/'Dockerfile.phase-a-tools'),'-t',image,str(REPO)],'tools-build')
    digest=execute(session,['docker','image','inspect',image,'--format','{{.Id}}'],'image-digest')
    meta={'session':name,'sourceHead':source,'productHash':product_hash(),'externalProviders':False,'toolsImage':image,'toolsDigest':digest}
    private(session/'session.json',json.dumps(meta,indent=2));print('PHASE_A_SESSION='+session.as_posix())

def aggregate(directory):
    result={k:0 for k in ('tests','failures','errors','skipped')}
    for f in directory.glob('TEST-*.xml'):
        suite=ET.parse(f).getroot()
        for k in result:result[k]+=int(suite.get(k,0))
    result['passed']=result['tests']-result['failures']-result['errors']-result['skipped']
    return result

def java_test(session, step, case=None, config=None, restart=False):
    env=clean_env(session)
    args=[str(REPO/'backend/gradlew.bat'),'test','--offline','--no-daemon','--rerun-tasks']
    if case:
        gate,clazz=CASES[case];env[gate+'_RUN']=str(config)
        env['OBSERVATION_RESTART_TESTS' if restart else gate+'_TESTS']='true'
        args+=['--tests','*'+('ObservationRestartPostgresTest' if restart else clazz)]
    started=time.time()
    try:execute(session,args,step,REPO/'backend',env)
    finally:
        target=session/'evidence'/step
        target.mkdir()  # never overwrite previous evidence
        source=REPO/'backend/build/test-results/test'
        (target/'xml').mkdir()
        if source.exists():
            for file in source.glob('TEST-*.xml'):
                if file.stat().st_mtime>=started:shutil.copy2(file,target/'xml'/file.name)
        private(target/'summary.json',json.dumps(aggregate(target/'xml'),indent=2))
    counts=aggregate(target/'xml')
    if counts['tests']==0 or (case and counts['passed']!=1) or counts['failures'] or counts['errors']:
        raise RuntimeError('Invalid test result counts; no success from SKIP')
    print(step+': '+json.dumps(counts))

def regression(session):
    java_test(session,'backend-full')
    web=REPO/'tools/websocket-test-client'
    execute(session,['npm.cmd','ci','--offline','--ignore-scripts'],'web-deps',web)
    execute(session,['npm.cmd','test'],'web-unit',web)
    execute(session,['npm.cmd','run','test:browser'],'web-browser',web)
    execute(session,['forge','test','--offline'],'foundry',REPO/'contracts')
    print('Regression commands completed; exact web/Foundry counts in private logs')

def free_port(used):
    for _ in range(100):
        p=30000+secrets.randbelow(19001)
        if p in used:continue
        try:
            with socket.socket() as s:s.bind(('127.0.0.1',p))
            used.add(p);return p
        except OSError:continue
    raise RuntimeError('No unused test port; never stop another service')

def inspect_owned(session,meta):
    for suffix,port in [('postgres',meta['dbPort']),('anvil',meta['rpcPort'])]:
        actual=json.loads(execute(session,['docker','inspect',meta['name']+'-'+suffix],'owned-inspect'))[0]
        expected=meta['name']
        if actual['Config']['Labels'].get('phase-a.fixture')!=expected or not actual['State']['Running']:
            raise RuntimeError('Wrong fixture container')
        internal='5432/tcp' if suffix=='postgres' else '8545/tcp'
        bindings=actual['HostConfig']['PortBindings'][internal]
        if bindings != [{'HostIp':'127.0.0.1','HostPort':str(port)}]:raise RuntimeError('Wrong loopback binding')

def require_clock_alignment(result):
    # Account for the round-trip uncertainty, not just a midpoint estimate.
    worst=abs(result['dbMinusHostSeconds'])+(result['hostEpochAfter']-result['hostEpochBefore'])/2
    if worst>2:raise RuntimeError('BLOCKED_CLOCK_SKEW: host/DB uncertainty exceeds existing 2-second boundary')

def measure_clock(session,meta):
    args=['docker','exec',meta['name']+'-postgres','psql','-X','-U','reserve','-d',meta['database'],
          '-tAc','SELECT EXTRACT(EPOCH FROM clock_timestamp());']
    t0=time.time();value=execute(session,args,meta['runId']+'-preflight-clock');t1=time.time()
    db=float(value.strip())
    return {'runId':meta['runId'],'hostEpochBefore':t0,'hostEpochAfter':t1,'dbEpoch':db,
            'dbMinusHostSeconds':db-(t0+t1)/2}

def fixture(session,parent,case):
    # Independent E2Es always allocate a fresh child. Each session keeps used ports forever.
    used=set()
    for f in (session/'fixtures').glob('*/run.json'):
        m=json.loads(f.read_text());used.update((m['dbPort'],m['rpcPort']))
    dbport=free_port(used);rpcport=free_port(used)
    id=case+'-'+secrets.token_hex(4);name=session.name+'-'+id
    run=session/'fixtures'/id;run.mkdir()
    for child in ('secrets','chain','release','postgres'):(run/child).mkdir()
    database='exchange_reserve_e2e' if case=='reserve' else 'exchange_trade_audit_e2e'
    meta={'runId':id,'session':session.name,'name':name,'dbPort':dbport,'rpcPort':rpcport,'database':database}
    private(run/'run.json',json.dumps(meta,indent=2))
    password=secrets.token_urlsafe(32);operator='0x'+secrets.token_hex(32);signer='0x'+secrets.token_hex(32)
    while operator==signer:signer='0x'+secrets.token_hex(32)
    private(run/'secrets/db-password',password)
    private(run/'secrets/application.properties','OPERATOR_PRIVATE_KEY='+operator+'\nPRICE_SIGNER_PRIVATE_KEY='+signer+'\n')
    network=name+'-network';created=[]
    try:
        # Explicit subnet avoids exhaustion of Docker's predefined pools. No old networks inspected/removed.
        execute(session,['docker','network','create','--subnet','10.240.'+str(secrets.randbelow(256))+'.0/24','--label','phase-a.fixture='+name,network],id+'-network')
        execute(session,['docker','run','--pull=never','-d','--name',name+'-postgres','--label','phase-a.fixture='+name,
            '--network',network,'-p',f'127.0.0.1:{dbport}:5432','-e','POSTGRES_DB='+database,'-e','POSTGRES_USER=reserve',
            '-e','POSTGRES_PASSWORD_FILE=/run/secrets/db-password','-v',str(run/'secrets/db-password')+':/run/secrets/db-password:ro',
            '-v',str(run/'postgres')+':/var/lib/postgresql/data','postgres:16'],id+'-postgres');created.append(name+'-postgres')
        execute(session,['docker','run','--pull=never','-d','--name',name+'-anvil','--label','phase-a.fixture='+name,
            '--network',network,'--network-alias','anvil','-p',f'127.0.0.1:{rpcport}:8545','-v',str(run/'chain')+':/data',
            '--entrypoint','anvil',FOUNDRY,'--host','0.0.0.0','--accounts','0','--chain-id','31337',
            '--state','/data/state.json','--state-interval','5','--preserve-historical-states','--quiet'],id+'-anvil');created.append(name+'-anvil')
        for service,args in [('postgres',['psql','-X','-U','reserve','-d',database,'-tAc','SELECT 1']),('anvil',['cast','rpc','eth_chainId','--rpc-url','http://127.0.0.1:8545'])]:
            deadline=time.monotonic()+90
            while time.monotonic()<deadline:
                r=subprocess.run(['docker','exec',name+'-'+service,*args],capture_output=True,env=clean_env(session))
                if r.returncode==0:break
                time.sleep(.5)
            else:raise RuntimeError('SQL/RPC readiness unavailable')
        inspect_owned(session,meta)
        clock=measure_clock(session,meta)
        private(run/'preflight-clock.json',json.dumps(clock,indent=2))
        require_clock_alignment(clock) # before mint/deploy/schema/test, never widen product clock limits
        execute(session,['docker','exec',name+'-postgres','psql','-X','-U','reserve','-d',database,'-c',
            "CREATE TABLE phase_a_fixture_identity(run_id text PRIMARY KEY); INSERT INTO phase_a_fixture_identity VALUES ('"+id+"');"],id+'-db-identity')
        execute(session,['docker','run','--pull=never','--rm','--network',network,'-v',str(run/'secrets')+':/run/secrets:ro',
            '-v',str(run/'release')+':/release',parent['toolsImage']],id+'-deploy')
        addresses=dict(line.split('=',1) for line in (run/'release/chain.properties').read_text().splitlines() if '=' in line)
        if set(addresses)!=set(['MOCK_KRW_ADDRESS','MSEC_ADDRESS','PRICE_ORACLE_ADDRESS','EXCHANGE_VAULT_ADDRESS']) or any(not re.fullmatch('0x[0-9a-fA-F]{40}',v) for v in addresses.values()):raise RuntimeError('Invalid contracts')
        values={'spring.config.import':'','spring.datasource.url':f'jdbc:postgresql://127.0.0.1:{dbport}/{database}?connectTimeout=2&socketTimeout=5',
            'spring.datasource.username':'reserve','spring.datasource.password':password,'spring.datasource.driver-class-name':'org.postgresql.Driver',
            'spring.jpa.database-platform':'org.hibernate.dialect.PostgreSQLDialect','spring.jpa.hibernate.ddl-auto':'none','spring.sql.init.mode':'always',
            'app.reserve.enabled':'true','app.reserve.execution-id':id,'app.trade-audit.enabled':'true','app.price.provider':'simulated',
            'app.price.initial-delay-ms':'2147483647','app.blockchain.enabled':'true','app.blockchain.rpc-url':f'http://127.0.0.1:{rpcport}',
            'app.blockchain.operator-private-key':operator,'app.blockchain.price-report.enabled':'true','app.blockchain.price-report.signer-private-key':signer,
            'app.blockchain.mock-krw-address':addresses['MOCK_KRW_ADDRESS'],'app.blockchain.m-sec-address':addresses['MSEC_ADDRESS'],
            'app.blockchain.price-oracle-address':addresses['PRICE_ORACLE_ADDRESS'],'app.blockchain.exchange-vault-address':addresses['EXCHANGE_VAULT_ADDRESS'],
            'app.blockchain.reconciliation.initial-delay-ms':'2147483647','app.admin.password':'','app.price.toss.base-url':'http://127.0.0.1:1',
            'app.ai.enabled':'false','app.ai.tools.enabled':'false','app.ai.agent.enabled':'false','app.ai.skills.enabled':'false','app.ai.diagnosis.enabled':'false'}
        private(run/'secrets/test.properties',''.join(k+'='+v+'\n' for k,v in values.items()))
        inspect_owned(session,meta)
        private(run/'isolation.json',json.dumps({'runId':id,'database':database,'dbPort':dbport,'rpcPort':rpcport,
            'ownedLoopbackBindings':True,'import':'disabled','providers':'simulated/AI-disabled','contracts':addresses,'verifiedBeforeTests':True},indent=2))
        print('Isolation verified before test: '+id+f' DB={dbport} RPC={rpcport}')
        java_test(session,id,case,run)
        if case=='conditional':java_test(session,id+'-restart',case,run,True)
    finally:
        # Only exact owned containers created by this call. Data/logs/state always remain.
        for container in created:
            label=execute(session,['docker','inspect',container,'--format','{{index .Config.Labels "phase-a.fixture"}}'],id+'-stop-owner')
            if label!=name:raise RuntimeError('Unsafe cleanup target')
            execute(session,['docker','logs',container],id+'-container-log')
            execute(session,['docker','stop','--time','60',container],id+'-stop')
        # No removal of containers/volumes/networks; preserve evidence even after failure.

def clock_evidence(session,child):
    run=session/'fixtures'/child
    if not re.fullmatch('(reserve|audit|faucet|conditional)-[0-9a-f]{8}',child):raise ValueError('Invalid child')
    meta=json.loads((run/'run.json').read_text());name=meta['name']+'-postgres';started=False
    actual=json.loads(execute(session,['docker','inspect',name],child+'-clock-owner'))[0]
    if actual['Config']['Labels'].get('phase-a.fixture')!=meta['name']:raise RuntimeError('Wrong clock evidence owner')
    try:
        if not actual['State']['Running']:
            execute(session,['docker','start',name],child+'-clock-start');started=True
        deadline=time.monotonic()+30
        query='SELECT EXTRACT(EPOCH FROM clock_timestamp());'
        while time.monotonic()<deadline:
            args=['docker','exec',name,'psql','-X','-U','reserve','-d',meta['database'],'-tAc',query]
            t0=time.time();r=subprocess.run(args,capture_output=True,env=clean_env(session));t1=time.time()
            if r.returncode==0:break
            time.sleep(.5)
        else:raise RuntimeError('Clock diagnostic SQL unavailable')
        db=float(r.stdout.decode().strip())
        result={'scope':'NEW_FIXTURE_READ_ONLY','runId':child,'hostEpochBefore':t0,'hostEpochAfter':t1,
                'dbEpoch':db,'dbMinusHostSeconds':db-(t0+t1)/2}
        try:require_clock_alignment(result);result['precondition']='PASS'
        except RuntimeError:result['precondition']='BLOCKED_CLOCK_SKEW'
        quotes=execute(session,['docker','exec',name,'psql','-X','-U','reserve','-d',meta['database'],'-c',
            'SELECT observed_at,valid_until,created_at,status FROM price_quotes; SELECT status,created_at,updated_at FROM orders;'],child+'-clock-quote-evidence')
        # Quote signatures/raw/keys/user data are not selected.
        stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%f')
        private(run/('clock-evidence-'+stamp+'.json'),json.dumps(result,indent=2));print(json.dumps(result))
    finally:
        if started:execute(session,['docker','stop','--time','60',name],child+'-clock-stop')

def main():
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='action',required=True)
    sub.add_parser('init')
    for action in ('regression','fixture','clock-evidence'):
        p=sub.add_parser(action);p.add_argument('--session',required=True)
        if action=='fixture':p.add_argument('--case',choices=CASES,required=True)
        if action=='clock-evidence':p.add_argument('--child',required=True)
    args=parser.parse_args()
    if args.action=='init':init();return
    session,meta=load_session(args.session)
    verify_session(session,meta)
    if args.action=='regression':regression(session)
    elif args.action=='fixture':fixture(session,meta,args.case)
    else:clock_evidence(session,args.child)

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('Phase A stopped: '+type(error).__name__+'; private evidence retained. No reset or shared-service cleanup.')
        raise SystemExit(1) from None
