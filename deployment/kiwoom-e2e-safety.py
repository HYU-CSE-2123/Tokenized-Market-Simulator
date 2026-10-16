"""Local-only egress and detached watchdog checks. No credentials, broker APIs, DB or chain writes."""
import argparse
import datetime as dt
import json
from pathlib import Path
import secrets
import subprocess
import time

ROOT=Path(__file__).resolve().parent
OWNER='kiwoom-e2e-20261014171302-662c5ba0'

WATCHDOG=r'''
import http.client,json,socket,sys,time
config=json.loads(sys.argv[1])
class Docker(http.client.HTTPConnection):
 def __init__(self):super().__init__('localhost',timeout=2)
 def connect(self):
  self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(2);self.sock.connect('/var/run/docker.sock')
def api(method,path,body=None):
 c=Docker();c.request(method,path,None if body is None else json.dumps(body),{'Content-Type':'application/json'});r=c.getresponse();data=r.read();c.close();return r.status,json.loads(data) if data else None
def owned(cid):
 status,o=api('GET','/containers/'+cid+'/json')
 if status!=200:raise RuntimeError('Target missing')
 labels=o['Config'].get('Labels') or {}
 if config['mode']=='probe':good=labels.get('kiwoom-safety.owner')==config['owner']
 else:
  role='backend' if cid==config['targets'][0] else 'web'
  good=labels.get('com.docker.compose.project')==config['project'] and labels.get('com.docker.compose.service')==role
  networks=set(o['NetworkSettings']['Networks'])
  if role=='backend':good=good and config['project']+'-isolated' in networks and networks.issubset({config['project']+'-isolated',config['network']})
  else:good=good and networks.issubset({config['network']})
 if not good:raise RuntimeError('Foreign target')
 return o
targets=config['targets']
if len(targets)!=2 or len(set(targets))!=2:raise RuntimeError('Exactly two pinned targets required')
for cid in targets:owned(cid)
status,net=api('GET','/networks/'+config['network'])
if status!=200 or net['Labels'].get('com.docker.compose.project')!=config['project']:raise RuntimeError('Foreign egress')
start=time.monotonic();print(json.dumps({'phase':'ARMED','cutoffSeconds':config['cutoff'],'forceSeconds':config['force']}),flush=True)
while time.monotonic()-start<config['cutoff']:time.sleep(.2)
owned(targets[0]);status,_=api('POST','/networks/'+config['network']+'/disconnect',{'Container':targets[0],'Force':True})
print(json.dumps({'phase':'EGRESS_DISCONNECT','status':status,'elapsed':round(time.monotonic()-start,3)}),flush=True)
egress_verified=status==200 and set(owned(targets[0])['NetworkSettings']['Networks'])=={config['project']+'-isolated'}
for cid in targets:
 owned(cid);status,_=api('POST','/containers/'+cid+'/kill?signal=SIGTERM');print(json.dumps({'phase':'TERM','status':status}),flush=True)
while time.monotonic()-start<config['force'] and any(owned(cid)['State']['Running'] for cid in targets):time.sleep(.2)
for cid in targets:
 if owned(cid)['State']['Running']:
  status,_=api('POST','/containers/'+cid+'/kill?signal=SIGKILL');print(json.dumps({'phase':'KILL','status':status}),flush=True)
print(json.dumps({'phase':'FINISHED','egressVerified':egress_verified,'allStopped':all(not owned(cid)['State']['Running'] for cid in targets),'elapsed':round(time.monotonic()-start,3)}),flush=True)
'''

CURRENT=None
def cmd(args,timeout=30):
    r=subprocess.run(args,capture_output=True,timeout=timeout)
    if r.returncode:
        if CURRENT:private(CURRENT/('safety-command-failure-'+secrets.token_hex(4)+'.json'),{'operation':args[:3],'returncode':r.returncode,'stderr':r.stderr.decode(errors='replace')})
        raise RuntimeError('Local safety command failed; no shared cleanup')
    return r.stdout.decode('utf-8').strip()

def private(path,value):
    with path.open('x',encoding='utf-8') as f:json.dump(value,f,indent=2)

def run(path):
    global CURRENT
    root=Path(path).resolve(strict=True)
    if root.parent!= (ROOT/'runtime').resolve() or root.name!=OWNER:raise RuntimeError('Approved existing run only')
    CURRENT=root
    meta=json.loads((root/'run.json').read_text(encoding='utf-8')); project='exchange-'+OWNER
    if meta['project']!=project:raise RuntimeError('Wrong owner')
    image=meta['images']['client']; cmd(['docker','image','inspect',image])
    suffix=secrets.token_hex(4);prefix=project+'-safety-'+suffix
    network=project+'_egress'
    found=subprocess.run(['docker','network','inspect',network],capture_output=True,timeout=15)
    if found.returncode:
        failures=[]
        for octet in secrets.SystemRandom().sample(range(256),8):
            created_net=subprocess.run(['docker','network','create','--subnet',f'10.244.{octet}.0/24','--label','com.docker.compose.project='+project,'--label','com.docker.compose.network=egress',network],capture_output=True,timeout=20)
            if created_net.returncode==0:break
            failures.append(created_net.stderr.decode(errors='replace'))
            if b'overlap' not in created_net.stderr.lower():
                private(root/('safety-network-failure-'+suffix+'.json'),{'errors':failures});raise RuntimeError('Network preparation blocked')
        else:
            private(root/('safety-network-failure-'+suffix+'.json'),{'errors':failures});raise RuntimeError('No free subnet')
    obj=json.loads(cmd(['docker','network','inspect',network]))[0]
    if obj['Labels'].get('com.docker.compose.project')!=project:raise RuntimeError('Foreign network')
    isolated=project+'-isolated'
    if not json.loads(cmd(['docker','network','inspect',isolated]))[0]['Internal']:raise RuntimeError('Isolation not internal')
    label=['--label','kiwoom-safety.owner='+OWNER]
    server=prefix+'-server';client=prefix+'-client';web=prefix+'-web';watch=prefix+'-watchdog'
    created=[]
    evidence={'checkedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'owner':OWNER,'externalBrokerCalls':0}
    def start(name,net,code,extra=None):
        cid=cmd(['docker','run','-d','--name',name,*label,'--network',net,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',*(extra or []),image,'python','-u','-c',code]);created.append(cid);return cid
    try:
        # Entire mock destination is local to the new namespace; never contacts the internet.
        start(server,network,"import socket,threading; s=socket.socket();s.bind(('0.0.0.0',9011));s.listen()\ndef echo(c):\n try:\n  while True:\n   d=c.recv(16)\n   if not d:break\n   c.sendall(d)\n finally:c.close()\nwhile True:\n c,a=s.accept();threading.Thread(target=echo,args=(c,),daemon=True).start()")
        ip=json.loads(cmd(['docker','inspect',server]))[0]['NetworkSettings']['Networks'][network]['IPAddress']
        code=f"import socket,time\nwhile True:\n try:\n  c=socket.create_connection(('{ip}',9011),.5);c.settimeout(.5)\n  while True:\n   c.sendall(b'ping');assert c.recv(4)==b'ping';print('FLOW',flush=True);time.sleep(.1)\n except Exception:print('BLOCKED',flush=True);time.sleep(.1)"
        cid=start(client,network,code);cmd(['docker','network','connect',isolated,client])
        end=time.monotonic()+10
        while 'FLOW' not in cmd(['docker','logs',client]):
            if time.monotonic()>end:raise RuntimeError('Local TCP precondition failed')
            time.sleep(.2)
        cmd(['docker','network','disconnect',network,client])
        time.sleep(2)
        log1=cmd(['docker','logs',client]); count1=log1.count('FLOW');time.sleep(1); log2=cmd(['docker','logs',client])
        evidence['establishedFlowBlocked']=('BLOCKED' in log2 and log2.count('FLOW')==count1)
        evidence['onlyInternalNetworkRemains']=set(json.loads(cmd(['docker','inspect',client]))[0]['NetworkSettings']['Networks'])=={isolated}
        if not evidence['establishedFlowBlocked'] or not evidence['onlyInternalNetworkRemains']:raise RuntimeError('Disconnect ineffective')
        newflow=cmd(['docker','exec',client,'python','-c',f"import socket\ntry: socket.create_connection(('{ip}',9011),1); print('UNSAFE')\nexcept OSError: print('BLOCKED')"])
        evidence['newFlowBlocked']=newflow=='BLOCKED'
        if not evidence['newFlowBlocked']:raise RuntimeError('New flow not blocked')
        wid=start(web,network,'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(120)')
        cmd(['docker','network','connect',network,client])
        before_reconnect=log2.count('FLOW'); end=time.monotonic()+5
        while cmd(['docker','logs',client]).count('FLOW')<=before_reconnect:
            if time.monotonic()>end:raise RuntimeError('Reconnect did not restore local flow')
            time.sleep(.2)
        evidence['reconnectedFlowRestored']=True
        # Arm a detached Docker process; this orchestrator is not responsible for firing the timer.
        config={'mode':'probe','owner':OWNER,'project':project,'network':network,'targets':[cid,wid],'cutoff':10,'force':15}
        watchdog=cmd(['docker','run','-d','--name',watch,*label,'--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
            '-v','/var/run/docker.sock:/var/run/docker.sock:ro',image,'python','-u','-c',WATCHDOG,json.dumps(config)]);created.append(watchdog)
        armed=time.monotonic()+5
        while 'ARMED' not in cmd(['docker','logs',watch]):
            if time.monotonic()>armed:raise RuntimeError('Watchdog not armed')
            time.sleep(.2)
        # No main-process kill/disconnect is issued until the detached timer has finished.
        cmd(['docker','wait',watch],timeout=25)
        events=cmd(['docker','logs',watch]);evidence['watchdogEvents']=[json.loads(x) for x in events.splitlines()]
        evidence['independentWatchdogStoppedBoth']=all(not json.loads(cmd(['docker','inspect',x]))[0]['State']['Running'] for x in (cid,wid))
        evidence['detachedMode']='Docker network=none, two pinned target IDs, ownership checks, Docker socket control'
        evidence['liveTimerPolicy']={'cutoffSeconds':570,'forceSeconds':585,'maximumSeconds':600}
        evidence['forcedKillExercised']=any(x.get('phase')=='KILL' and x.get('status')==204 for x in evidence['watchdogEvents'])
        if not evidence['independentWatchdogStoppedBoth'] or not evidence['forcedKillExercised'] or not any(x.get('allStopped') and x.get('egressVerified') for x in evidence['watchdogEvents']):raise RuntimeError('Watchdog failed')
        evidence['status']='PASS';print(json.dumps(evidence,indent=2),flush=True)
    finally:
        for cid in created:
            obj=json.loads(cmd(['docker','inspect',cid]))[0]
            if obj['Config']['Labels'].get('kiwoom-safety.owner')!=OWNER:raise RuntimeError('Foreign cleanup target')
            if obj['State']['Running']:cmd(['docker','stop','--time','3',cid])
        private(root/('safety-check-'+suffix+'.json'),evidence)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--run',required=True);a=p.parse_args()
    try:run(a.run)
    except Exception as error:
        print('SAFETY CHECK BLOCKED: '+type(error).__name__+'; no broker calls; evidence retained')
        raise SystemExit(1) from None
