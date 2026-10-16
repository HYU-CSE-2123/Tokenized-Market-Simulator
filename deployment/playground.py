"""Persistent manual playground. No old .env/runtime import, reset, paid APIs or redeploy on start."""
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
import urllib.request

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
POINTER = ROOT / 'runtime/playground-current.json'
FOUNDRY = 'ghcr.io/foundry-rs/foundry@sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17'
SERVICES = {'postgres', 'anvil', 'backend', 'web'}
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def private(path, value):
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w', encoding='utf-8', newline='\n') as f:
        f.write(value)


def clean_env():
    keep = {'PATH','PATHEXT','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE',
            'APPDATA','LOCALAPPDATA','GRADLE_USER_HOME','PROGRAMFILES','PROGRAMFILES(X86)'}
    return {k:v for k,v in os.environ.items() if k.upper() in keep}


def execute(run, args, step, cwd=REPO, env=None, timeout=600):
    result = subprocess.run(args, cwd=cwd, env=env or clean_env(), capture_output=True, timeout=timeout)
    if run:
        private(run/'logs'/(step+'-'+secrets.token_hex(4)+'.private.log'),
                (result.stdout+result.stderr).decode('utf-8', errors='replace'))
    if result.returncode:
        raise RuntimeError(step+' failed; private evidence preserved; no reset/retry')
    return result.stdout.decode('utf-8', errors='replace').strip()


def source_hash():
    files = [*(REPO/'backend/src/main').rglob('*'), *(REPO/'contracts/src').rglob('*'),
             *(REPO/'tools/websocket-test-client/src').rglob('*'), REPO/'tools/websocket-test-client/index.html',
             ROOT/'playground-compose.yml', ROOT/'playground.py', ROOT/'init-chain.sh',
             ROOT/'anvil-entrypoint.sh', ROOT/'Dockerfile.playground-backend', ROOT/'Dockerfile.playground-web']
    digest = hashlib.sha256()
    for f in sorted(files):
        if f.is_file():
            digest.update(f.relative_to(REPO).as_posix().encode()+b'\0'+f.read_bytes())
    return digest.hexdigest()


def load_run(check_source=True):
    path = Path(json.loads(POINTER.read_text(encoding='utf-8'))['run']).resolve(strict=True)
    if path.parent != (ROOT/'runtime').resolve() or not re.fullmatch(r'playground-\d{14}-[0-9a-f]{8}', path.name):
        raise ValueError('Only this playground namespace is allowed')
    meta = json.loads((path/'run.json').read_text(encoding='utf-8'))
    if meta['project'] != 'exchange-'+path.name or (check_source and meta['sourceHash'] != source_hash()):
        raise ValueError('Playground identity/source changed; no automatic upgrade')
    if not (path/'ready.json').exists():
        raise ValueError('Incomplete preparation; inspect evidence, no automatic redeploy')
    for name in ('data','secrets','logs','build'):
        if not (path/name).resolve().is_relative_to(path):
            raise ValueError('Playground mount escapes its root')
    expected=json.loads((path/'images.json').read_text(encoding='utf-8'))
    actual=dict(line.split('=',1) for line in (path/'release.env').read_text().splitlines())
    if actual.get('PLAYGROUND_ROOT') != path.as_posix() or actual.get('PLAYGROUND_ID') != path.name:
        raise ValueError('Runtime configuration redirected')
    if set(actual) != {'PLAYGROUND_ROOT','PLAYGROUND_ID','PLAYGROUND_SUBNET',*expected}:
        raise ValueError('Unexpected runtime configuration')
    if not re.fullmatch(r'10\.241\.[0-9]{1,3}\.0/24',actual['PLAYGROUND_SUBNET']):
        raise ValueError('Invalid private network')
    for name, value in expected.items():
        if actual[name] != value['name'] or image_id(actual[name]) != value['id']:
            raise ValueError('Runtime image changed')
    return path, meta


def compose(run, meta):
    return ['docker','compose','--project-name',meta['project'],'--env-file',str(run/'release.env'),
            '-f',str(ROOT/'playground-compose.yml')]


def ownership(run, meta):
    ids = execute(None, ['docker','ps','-aq','--filter','label=com.docker.compose.project='+meta['project']], 'ownership')
    for cid in ids.splitlines():
        info = json.loads(execute(None, ['docker','inspect',cid], 'inspect'))[0]
        if info['Config']['Labels'].get('com.docker.compose.service') not in SERVICES:
            raise ValueError('Unexpected playground service')
        for mount in info['Mounts']:
            if mount['Type'] != 'bind':
                raise ValueError('Unexpected persistent mount type')
            # Docker Desktop normalizes the Windows host path in inspect output.
            source = mount['Source'].replace('/run/desktop/mnt/host/c/', 'C:/')
            if mount['Destination'].startswith(('/data','/var/lib/postgresql','/run/secrets')):
                if not Path(source).resolve().is_relative_to(run):
                    raise ValueError('Foreign data/secret mount')


def image_id(name):
    value = execute(None,['docker','image','inspect',name,'--format','{{.Id}}'],'cached-image')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}',value):
        raise ValueError('Image missing/invalid; no automatic pull')
    return value


def api(path, body=None, token=None):
    request = urllib.request.Request('http://127.0.0.1:28082'+path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type':'application/json', **({'Authorization':'Bearer '+token} if token else {})})
    with HTTP.open(request, timeout=3) as response:
        return json.load(response)


def wait_backend():
    end=time.monotonic()+120
    while time.monotonic()<end:
        try:
            api('/api/health')
            market=api('/api/markets/mSEC')
            if market.get('provider') != 'SIMULATED':
                raise ValueError('Unexpected market provider')
            if market.get('priceStatus') == 'SIMULATED':
                return
        except ValueError:
            raise
        except Exception:
            time.sleep(1)
    raise RuntimeError('Backend readiness failed; no reset')


def prepare():
    if POINTER.exists():
        raise ValueError('Playground already selected; use start/stop, not prepare')
    for port in (28082,25173):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1',port))
    base='tokenized-market-backend:release'
    for required in ('postgres:16',FOUNDRY,base,'nginx:1.28-alpine','tokenized-reserve-tools:reserve-e2e-20261009043259-4ac3a5'):
        image_id(required)
    name='playground-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    run=ROOT/'runtime'/name
    run.mkdir(parents=True,mode=0o700)
    if os.name=='nt':
        who=execute(None,['whoami'],'user')
        execute(None,['icacls',str(run),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],'private-acl')
    for child in ('data/trade','data/chain','data/release','secrets','logs','build/backend','build/web'):
        (run/child).mkdir(parents=True)
    values={'DB_PASSWORD':secrets.token_urlsafe(32),'JWT_SECRET':secrets.token_urlsafe(48),
        'ADMIN_LOGIN_ID':'playground_admin','ADMIN_PASSWORD':secrets.token_urlsafe(24),
        'OPERATOR_PRIVATE_KEY':'0x'+secrets.token_hex(32),'PRICE_SIGNER_PRIVATE_KEY':'0x'+secrets.token_hex(32)}
    private(run/'secrets/application.properties',''.join(k+'='+v+'\n' for k,v in values.items()))
    private(run/'secrets/db-password',values['DB_PASSWORD'])
    private(run/'secrets/admin-account.json',json.dumps({'loginId':values['ADMIN_LOGIN_ID'],'password':values['ADMIN_PASSWORD']},indent=2))
    meta={'project':'exchange-'+name,'sourceHash':source_hash(),'createdAt':dt.datetime.now(dt.timezone.utc).isoformat()}
    private(run/'run.json',json.dumps(meta,indent=2))
    # Reserve exclusively before any build/service/deployment mutation. Failure and
    # concurrent starts cannot silently initialize a second environment.
    private(POINTER,json.dumps({'run':str(run)}))
    print('Preparing new playground; private progress logs: '+run.relative_to(REPO).as_posix(),flush=True)
    java=Path('C:/Program Files/Java/jdk-21.0.10')
    if not (java/'bin/java.exe').exists():
        raise ValueError('Java21 location unavailable; no automatic installation')
    env=clean_env();env['JAVA_HOME']=str(java);env['PATH']=str(java/'bin')+os.pathsep+env['PATH']
    execute(run,[str(REPO/'backend/gradlew.bat'),'bootJar','--offline','--no-daemon'],'backend-build',REPO/'backend',env)
    jars=list((REPO/'backend/build/libs').glob('*-SNAPSHOT.jar'))
    if len(jars)!=1: raise ValueError('Expected one current backend artifact')
    shutil.copy2(jars[0],run/'build/backend/app.jar')
    shutil.copy2(ROOT/'Dockerfile.playground-backend',run/'build/backend/Dockerfile')
    execute(run,['npm.cmd','run','build'],'web-build',REPO/'tools/websocket-test-client',{**clean_env(),'PUBLIC_DEMO':'true'})
    shutil.copytree(REPO/'tools/websocket-test-client/dist',run/'build/web/dist')
    shutil.copytree(REPO/'tools/websocket-test-client/deploy',run/'build/web/deploy')
    shutil.copy2(ROOT/'Dockerfile.playground-web',run/'build/web/Dockerfile')
    images={'POSTGRES_IMAGE':image_id('postgres:16'),'ANVIL_IMAGE':FOUNDRY,
            'BACKEND_IMAGE':'tokenized-playground-backend:'+name,'WEB_IMAGE':'tokenized-playground-web:'+name}
    for kind in ('backend','web'):
        execute(run,['docker','build','--network=none','--pull=false','-t',images[kind.upper()+'_IMAGE'],str(run/'build'/kind)],kind+'-image')
    private(run/'images.json',json.dumps({k:{'name':v,'id':image_id(v)} for k,v in images.items()}))
    tools='tokenized-playground-tools:'+name
    execute(run,['docker','build','--network=none','--pull=false','-f',str(ROOT/'Dockerfile.phase-a-tools'),'-t',tools,str(REPO)],'contract-image')
    subnet='10.241.'+str(secrets.randbelow(200)+20)+'.0/24'
    private(run/'release.env','PLAYGROUND_ROOT='+run.as_posix()+'\nPLAYGROUND_ID='+name+'\nPLAYGROUND_SUBNET='+subnet+'\n'+''.join(k+'='+v+'\n' for k,v in images.items()))
    execute(run,compose(run,meta)+['up','-d','--wait','postgres','anvil'],'infra-start')
    ownership(run,meta)
    execute(run,['docker','run','--rm','--network',meta['project']+'_default','-v',str(run/'secrets')+':/run/secrets:ro',
        '-v',str(run/'data/release')+':/release',tools],'first-deploy')
    execute(run,compose(run,meta)+['up','-d','backend','web'],'services-start')
    wait_backend()
    token=api('/api/auth/login',{'loginId':values['ADMIN_LOGIN_ID'],'password':values['ADMIN_PASSWORD']})['accessToken']
    api('/api/admin/reserve-reconciliations/baseline',{},token)
    private(run/'ready.json',json.dumps({'initialized':True,'sourceHash':meta['sourceHash']}))
    print('Ready: http://127.0.0.1:25173 | Synthetic + local Anvil | AI/conditional disabled')


def main(argv=None):
    parser=argparse.ArgumentParser()
    parser.add_argument('action',nargs='?',default='start',choices=['prepare','start','stop','status','account'])
    args=parser.parse_args(argv)
    if args.action=='prepare' or (args.action=='start' and not POINTER.exists()):
        prepare();return
    run,meta=load_run(check_source=args.action=='start')
    if not (run/'ready.json').exists(): raise ValueError('Incomplete preparation; no automatic redeploy')
    ownership(run,meta)
    if args.action=='start':
        if not (run/'data/chain/state.json').exists() or not (run/'data/trade/PG_VERSION').exists():
            raise ValueError('Persistent DB/chain missing; refusing recreation')
        execute(run,compose(run,meta)+['up','-d','--wait','postgres','anvil'],'restart-infra')
        execute(run,compose(run,meta)+['up','-d','backend','web'],'restart-services')
        wait_backend();print('Ready: http://127.0.0.1:25173')
    elif args.action=='stop':
        execute(run,compose(run,meta)+['stop'],'stop');print('Stopped this playground; DB/chain preserved')
    elif args.action=='status':
        print(execute(run,compose(run,meta)+['ps'],'status'))
    else:
        if os.name!='nt':raise ValueError('Open the private admin-account.json locally; never paste secrets')
        subprocess.Popen(['notepad.exe',str(run/'secrets/admin-account.json')])
        print('Opened private administrator account file locally; not printed')


if __name__=='__main__':
    try:main()
    except Exception as e:
        print('Playground stopped: '+type(e).__name__+'. Restricted logs retained; no reset/redeploy/old environment changes.')
        raise SystemExit(1) from None
