"""Explicitly approved, bounded live acceptance; existing namespace only, never reset/redeploy."""
import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import time

ROOT=Path(__file__).resolve().parent
OWNER='kiwoom-e2e-20261014171302-662c5ba0'
D=['C:/Program Files/Docker/Docker/resources/bin/docker.exe','--context','desktop-linux']
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
h=module('offline_helpers',ROOT/'playground.py')
safety=module('safety_helpers',ROOT/'kiwoom-e2e-safety.py')
ENV=h.clean_env()
def cmd(args,timeout=15):
    r=subprocess.run(args,capture_output=True,env=ENV,timeout=timeout)
    if r.returncode:raise RuntimeError('COMMAND_FAILED:'+str(args[0:2]))
    return r.stdout.decode('utf-8',errors='replace').strip()
def inspect(cid):return json.loads(cmd(D+['inspect',cid]))[0]
def put(path,data):
    with path.open('x',encoding='utf-8') as f:json.dump(data,f,indent=2)

def main():
    p=argparse.ArgumentParser();p.add_argument('--execute-approved-live',action='store_true');p.add_argument('--start-before-utc',required=True);a=p.parse_args()
    if not a.execute_approved_live:raise RuntimeError('EXPLICIT_APPROVAL_OPTION_REQUIRED')
    deadline=dt.datetime.fromisoformat(a.start_before_utc.replace('Z','+00:00'))
    if deadline.tzinfo is None or not 0<(deadline-dt.datetime.now(dt.timezone.utc)).total_seconds()<=120:raise RuntimeError('FRESH_START_WINDOW_REQUIRED')
    run=(ROOT/'runtime'/OWNER).resolve(strict=True)
    meta=json.loads((run/'run.json').read_text(encoding='utf-8'));project='exchange-'+OWNER
    if meta['project']!=project or meta['sourceHash']!=h.source_hash():raise RuntimeError('SOURCE_OR_OWNER_CHANGED')
    out=run/('live-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+secrets.token_hex(4));out.mkdir()
    result={'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'runId':OWNER,'sourceHead':meta['sourceHead'],'sourceHash':meta['sourceHash'],'externalStarted':False}
    targets=[];watch=None
    def owned(service):
        rows=json.loads(cmd(D+['ps','-a','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service='+service,'--format','json']).replace('\n',',').join(['[',']']))
        if len(rows)!=1:raise RuntimeError('EXPECTED_ONE_'+service)
        cid=rows[0]['ID'];o=inspect(cid)
        if o['Config']['Labels'].get('com.docker.compose.project')!=project or o['Config']['Labels'].get('com.docker.compose.service')!=service or o['Image']!=meta['images'][service if service!='initializer' else 'backend']:raise RuntimeError('OWNER_IMAGE_'+service)
        for mount in o['Mounts']:
            source=mount['Source'].replace('/run/desktop/mnt/host/c/','C:/')
            if not Path(source).resolve().is_relative_to(run):raise RuntimeError('FOREIGN_MOUNT_'+service)
        return o['Id'],o
    pg,_=owned('postgres');anvil,_=owned('anvil')
    def sql(q):return cmd(D+['exec',pg,'psql','-X','-U',meta['database'],'-d',meta['database'],'-v','ON_ERROR_STOP=1','-tAc',q])
    def rpc(method,*params):return json.loads(cmd(D+['exec',anvil,'cast','rpc',method,*params,'--rpc-url','http://127.0.0.1:8545']))
    try:
        result['dockerVersion']=cmd(D+['version','--format','{{.Server.Version}}'])
        result['dockerInfo']=json.loads(cmd(D+['info','--format','{{json .ServerVersion}}']))
        for image in meta['images'].values():
            if json.loads(cmd(D+['image','inspect',image]))[0]['Id']!=image:raise RuntimeError('IMAGE_CHANGED')
        for service in ('postgres','anvil'):
            if owned(service)[1]['State'].get('Health',{}).get('Status')!='healthy':raise RuntimeError('INFRA_NOT_HEALTHY')
        if owned('initializer')[1]['State']['Running']:raise RuntimeError('INITIALIZER_RUNNING')
        baseline=json.loads((run/'baseline.json').read_text(encoding='utf-8'))['baseline']
        if json.loads(sql('SELECT payload FROM reserve_baselines WHERE id=1;'))!=baseline:raise RuntimeError('BASELINE_CHANGED')
        block=rpc('eth_getBlockByNumber',hex(baseline['chain']['block']['number']),'false')
        if block['hash']!=baseline['chain']['block']['hash'] or int(rpc('eth_chainId'),16)!=meta['chainId']:raise RuntimeError('CHAIN_CHANGED')
        result['baseline']={'id':baseline['id'],'block':baseline['chain']['block']}
        if sql('SELECT (SELECT count(*) FROM orders),(SELECT count(*) FROM trades),(SELECT count(*) FROM price_quotes),(SELECT count(*) FROM faucet_grants),(SELECT count(*) FROM user_balances WHERE amount<>0 OR locked_amount<>0);')!='0|0|0|0|0':raise RuntimeError('NOT_PRISTINE_NO_REPLAY')
        credentials=dict(line.split('=',1) for line in (run/'secrets/kiwoom.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.lstrip().startswith('#'))
        result['credentialsPresent']={k:bool(credentials.get(k,'').strip()) for k in ('KIWOOM_APP_KEY','KIWOOM_SECRET_KEY')};del credentials
        if not all(result['credentialsPresent'].values()):raise RuntimeError('CREDENTIALS_ABSENT')
        for service in ('backend','web'):
            cid,o=owned(service)
            if o['State']['Running']:raise RuntimeError('LIVE_ALREADY_RUNNING')
            targets.append(cid)
        isolated=project+'-isolated';network=project+'_egress'
        if not json.loads(cmd(D+['network','inspect',isolated]))[0]['Internal']:raise RuntimeError('NOT_INTERNAL')
        config={'mode':'live','owner':OWNER,'project':project,'network':network,'targets':targets,'cutoff':570,'force':585}
        if dt.datetime.now(dt.timezone.utc)>=deadline:raise RuntimeError('START_WINDOW_EXPIRED')
        arm_started=time.monotonic()
        watch=cmd(D+['run','-d','--name',project+'-live-watch-'+out.name[-8:],'--label','kiwoom-safety.owner='+OWNER,'--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','-v','/var/run/docker.sock:/var/run/docker.sock:ro',meta['images']['client'],'python','-u','-c',safety.WATCHDOG,json.dumps(config)])
        end=time.monotonic()+5
        while True:
            logs=cmd(D+['logs',watch]);events=[json.loads(x) for x in logs.splitlines() if x.startswith('{')]
            if any(e.get('phase')=='ARMED' for e in events) and inspect(watch)['State']['Running']:break
            if time.monotonic()>end:raise RuntimeError('WATCHDOG_NOT_ARMED')
            time.sleep(.2)
        if time.monotonic()-arm_started>20 or dt.datetime.now(dt.timezone.utc)>=deadline:raise RuntimeError('ARMING_DELAY_OR_WINDOW_EXPIRED')
        result['watchdog']={'id':watch,'targets':targets,'events':events,'cutoffSeconds':570,'forceSeconds':585}
        put(out/'armed.json',result)
        print('ARMED: exact backend/web targets; live start next',flush=True)
        result['externalStarted']=True
        cmd(D+['start',*targets],timeout=15)
        proc=subprocess.run(['node',str(ROOT/'kiwoom-live-browser.mjs'),str(run),str(out)],env=ENV,capture_output=True,timeout=420)
        result['browserExitCode']=proc.returncode
        if (out/'browser-result.json').exists():
            b=json.loads((out/'browser-result.json').read_text(encoding='utf-8'));result['status']=b['status'];result['stages']=b['stages'];result['error']=b.get('error')
        else:raise RuntimeError('BROWSER_EVIDENCE_MISSING')
    except Exception as e:
        result['status']='BLOCKED';result['error']=type(e).__name__+':'+str(e)[:120]
    finally:
        if targets:
            try:
                for i,cid in enumerate(targets):
                    expected='backend' if i==0 else 'web'
                    if owned(expected)[0]!=cid:raise RuntimeError('CLEANUP_OWNER_CHANGED')
                if result['externalStarted']:
                    backend=inspect(targets[0]);network=project+'_egress'
                    if network in backend['NetworkSettings']['Networks']:cmd(D+['network','disconnect',network,targets[0]])
                    result['egressBlocked']=set(inspect(targets[0])['NetworkSettings']['Networks'])=={project+'-isolated'}
                    cmd(D+['stop','--time','10',*targets],timeout=25)
                result['allStopped']=all(not inspect(cid)['State']['Running'] for cid in targets)
                if watch and result['allStopped']:
                    result['watchdogFinalLog']=cmd(D+['logs',watch]);cmd(D+['stop','--time','2',watch])
            except Exception as e:result['cleanupError']=type(e).__name__ # Independent watchdog remains armed.
        if result['externalStarted'] and (result.get('cleanupError') or result.get('egressBlocked') is not True or result.get('allStopped') is not True):
            result['status']='BLOCKED';result['shutdownSafety']='UNCONFIRMED'
        result['completedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
        put(out/'control-result.json',result)
        print(json.dumps({'evidence':str(out),'status':result.get('status'),'stages':result.get('stages'),'error':result.get('error'),'externalStarted':result['externalStarted'],'egressBlocked':result.get('egressBlocked'),'allStopped':result.get('allStopped'),'cleanupError':result.get('cleanupError')}),flush=True)

if __name__=='__main__':main()
