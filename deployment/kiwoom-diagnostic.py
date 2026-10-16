"""Offline by default. Explicit bounded read-only Kiwoom capture; never start the trading server."""
import argparse
import hashlib
from datetime import datetime
import json
import logging
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import urllib.request

from kiwoom_diagnostic_core import Observer, DiagnosticError, UTC, instant, stamp, analyze, ack_success, subscription

ROOT=Path(__file__).resolve().parent
RUNTIME=ROOT/'runtime'
MAX_BODY=1_048_576
TOTAL_SECONDS=210
E2E_SECRET=RUNTIME/'kiwoom-e2e-20261014171302-662c5ba0/secrets/kiwoom.properties'


def private_write(path,text):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w',encoding='utf-8') as stream:
        stream.write(text)


def init_run(profile='mixed'):
    name='kiwoom-diagnostic-'+datetime.now(UTC).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    run=RUNTIME/name;run.mkdir(parents=True,mode=0o700)
    if os.name=='nt':
        user=subprocess.run(['whoami'],check=True,capture_output=True,text=True).stdout.strip()
        subprocess.run(['icacls',str(run),'/inheritance:r','/grant:r',user+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
    if profile!='nxt-observe':private_write(run/'credentials.json',json.dumps({'mode':'real','appKey':'','secretKey':''},indent=2))
    private_write(run/'run.json',json.dumps({'formatVersion':1,'name':name,'purpose':'KIWOOM_READ_ONLY_DIAGNOSTIC'},indent=2))
    return run


def load_run(value):
    path=Path(value).resolve(strict=True)
    if path.parent!=RUNTIME.resolve() or not re.fullmatch(r'kiwoom-diagnostic-\d{14}-[0-9a-f]{8}',path.name):
        raise DiagnosticError('INVALID_RUN')
    meta=json.loads((path/'run.json').read_text(encoding='utf-8'))
    if meta.get('name')!=path.name or meta.get('purpose')!='KIWOOM_READ_ONLY_DIAGNOSTIC':
        raise DiagnosticError('INVALID_RUN')
    return path


def check_private_file(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size>8192:
        raise DiagnosticError('INVALID_CREDENTIAL_FILE')
    if os.name=='nt':
        literal=str(path).replace("'","''")
        script="""$ErrorActionPreference='Stop'; $id=[Security.Principal.WindowsIdentity]::GetCurrent(); $allowed=@($id.User.Value,'S-1-5-18');
        $acl=Get-Acl -LiteralPath '%s';
        foreach($rule in $acl.Access){if($rule.AccessControlType -eq 'Allow' -and
        $allowed -notcontains $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value){exit 1}}""" % literal
        if subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',script],capture_output=True,timeout=5).returncode:
            raise DiagnosticError('CREDENTIAL_ACL_NOT_PRIVATE')
    elif path.stat().st_mode & 0o077 or path.stat().st_uid!=os.getuid():
        raise DiagnosticError('CREDENTIAL_ACL_NOT_PRIVATE')


def private_credentials(path):
    check_private_file(path)
    config=json.loads(path.read_text(encoding='utf-8'))
    if set(config)!= {'mode','appKey','secretKey'} or config['mode'] not in ('real','demo'):
        raise DiagnosticError('INVALID_CREDENTIAL_FILE')
    for field in ('appKey','secretKey'):
        value=config[field]
        if not isinstance(value,str) or not 1<=len(value)<=512 or any(ord(c)<32 for c in value):
            raise DiagnosticError('CREDENTIALS_REQUIRED')
    return config


def secret_reference(value):
    try:
        path=Path(value)
        if not path.is_absolute() or path.is_symlink() or path.resolve(strict=True)!=E2E_SECRET.resolve(strict=True):
            raise ValueError()
        return path.resolve(strict=True)
    except (TypeError,ValueError,OSError):raise DiagnosticError('INVALID_SECRET_REFERENCE') from None


def private_e2e_credentials(value):
    path=secret_reference(value);check_private_file(path)
    pairs={}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):continue
        if '=' not in line:raise DiagnosticError('INVALID_CREDENTIAL_FILE')
        key,value=line.split('=',1);key=key.strip();value=value.strip()
        if key not in ('KIWOOM_APP_KEY','KIWOOM_SECRET_KEY') or key in pairs:
            raise DiagnosticError('INVALID_CREDENTIAL_FILE')
        pairs[key]=value
    if set(pairs)!= {'KIWOOM_APP_KEY','KIWOOM_SECRET_KEY'} or any(not 1<=len(v)<=512 or any(ord(c)<32 for c in v) for v in pairs.values()):
        raise DiagnosticError('CREDENTIALS_REQUIRED')
    return {'mode':'real','appKey':pairs['KIWOOM_APP_KEY'],'secretKey':pairs['KIWOOM_SECRET_KEY']}


class Budget:
    def __init__(self,rest,ws,frames=5000,nxt=False,on_attempt=None):
        self.rest_limit=rest;self.ws_limit=ws;self.rest=0;self.ws=0
        self.frame_limit=frames;self.received_frames=0;self.sent_frames=0
        self.nxt=nxt;self.login=self.reg=0;self.on_attempt=on_attempt
    def journal(self):
        if self.on_attempt:self.on_attempt({'rest':self.rest,'wsConnections':self.ws,'login':self.login,'reg':self.reg})
    def take_rest(self):
        if self.rest>=self.rest_limit:raise DiagnosticError('REST_LIMIT')
        self.rest+=1
        self.journal()
    def take_ws(self):
        if self.ws>=self.ws_limit:raise DiagnosticError('WS_LIMIT')
        self.ws+=1
        self.journal()
    def receive(self):
        if self.received_frames>=self.frame_limit:raise DiagnosticError('WS_FRAME_LIMIT')
        self.received_frames+=1
    def send(self,kind=None):
        if self.nxt:
            if kind not in ('LOGIN','REG','PING'):raise DiagnosticError('WS_PACKET_NOT_ALLOWED')
            if kind in ('LOGIN','REG'):
                field=kind.lower()
                if getattr(self,field)>=1:raise DiagnosticError('WS_PACKET_LIMIT')
                setattr(self,field,getattr(self,field)+1);self.journal()
        if self.sent_frames>=self.frame_limit+2*self.ws_limit:raise DiagnosticError('WS_SEND_LIMIT')
        self.sent_frames+=1


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise DiagnosticError('REDIRECT_BLOCKED')


class LiveTransport:
    def __init__(self,config,budget):
        import websockets
        if websockets.__version__!='16.0':raise DiagnosticError('WEBSOCKETS_16_REQUIRED')
        self.host='api.kiwoom.com' if config['mode']=='real' else 'mockapi.kiwoom.com'
        self.config=config;self.budget=budget
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def post(self,path,body,token=None):
        if self.budget.nxt and path!='/oauth2/token':raise DiagnosticError('NXT_NO_MARKET_REST')
        if path not in ('/oauth2/token','/api/dostk/chart'):raise DiagnosticError('READ_ONLY_PATH_REQUIRED')
        self.budget.take_rest()
        headers={'Content-Type':'application/json;charset=UTF-8'}
        if token:
            headers.update({'authorization':'Bearer '+token,'api-id':'ka10080','cont-yn':'N','next-key':''})
        request=urllib.request.Request('https://'+self.host+path,data=json.dumps(body).encode(),headers=headers,method='POST')
        try:
            with self.opener.open(request,timeout=5) as response:
                raw=response.read(MAX_BODY+1)
            if len(raw)>MAX_BODY:raise DiagnosticError('RESPONSE_LIMIT')
            result=json.loads(raw)
            if not isinstance(result,dict):raise DiagnosticError('INVALID_RESPONSE')
            return result
        except Exception:
            raise DiagnosticError('HTTP_FAILED') from None
    def token(self):
        body=self.post('/oauth2/token',{'grant_type':'client_credentials','appkey':self.config['appKey'],'secretkey':self.config['secretKey']})
        if not ack_success(body) or not isinstance(body.get('token'),str) or not body['token'] or len(body['token'])>4096:
            raise DiagnosticError('AUTH_REJECTED')
        return body['token']
    def connect(self):
        self.budget.take_ws()
        # No import before explicit live consent; pinned local dependency, never auto-install.
        import websockets
        if websockets.__version__!='16.0':raise DiagnosticError('WEBSOCKETS_16_REQUIRED')
        from websockets.sync.client import connect
        logger=logging.getLogger('kiwoom_diagnostic.silent_transport');logger.disabled=True;logger.propagate=False
        return connect('wss://'+self.host+':10000/api/dostk/websocket',proxy=None,
            open_timeout=5,close_timeout=2,max_size=MAX_BODY,max_queue=16,
            ping_interval=None,compression=None,logger=logger)
    def chart(self,token):
        from kiwoom_diagnostic_core import KST
        return self.post('/api/dostk/chart',{'stk_cd':'005930','tic_scope':'1',
            'base_dt':datetime.now(KST).strftime('%Y%m%d'),'upd_stkpc_tp':'0'},token)


def validate_live(args):
    # Gate before run/credentials access, dependency import or ANY external factory invocation.
    if not args.allow_external:raise DiagnosticError('EXTERNAL_DISABLED')
    profile=getattr(args,'profile','mixed')
    if profile not in ('mixed','0s-only','nxt-observe'):raise DiagnosticError('INVALID_PROFILE')
    if profile=='nxt-observe':
        if args.max_rest_requests!=1 or args.max_ws_connections!=1 or args.chart_snapshots!=0 or args.max_events>5000:
            raise DiagnosticError('NXT_LIMITS_REQUIRED')
        if not getattr(args,'credentials_file',None):raise DiagnosticError('EXPLICIT_SECRET_REFERENCE_REQUIRED')
    if profile=='0s-only' and (args.max_rest_requests!=1 or args.max_ws_connections!=1
            or args.chart_snapshots!=0 or args.max_events>5000):
        raise DiagnosticError('STATE_ONLY_LIMITS_REQUIRED')
    if not 1<=args.max_rest_requests<=10 or not 1<=args.max_ws_connections<=2:
        raise DiagnosticError('EXPLICIT_CALL_LIMITS_REQUIRED')
    if not 5<=args.seconds<=180 or not 1<=args.max_events<=20000 or not 0<=args.chart_snapshots<=3:
        raise DiagnosticError('INVALID_CAPTURE_LIMITS')
    if args.max_rest_requests < 1+args.max_ws_connections*args.chart_snapshots:
        raise DiagnosticError('INSUFFICIENT_PLANNED_REST_BUDGET')


def capture(args,transport_factory=LiveTransport):
    validate_live(args)
    run=load_run(args.run)
    if (run/'events.jsonl').exists() or (run/'report.json').exists():raise DiagnosticError('CAPTURE_ALREADY_EXISTS')
    profile=getattr(args,'profile','mixed')
    # A prepared state-only run must not fall back to mixed if --profile is omitted.
    if profile in ('0s-only','nxt-observe') or (run/'prepared.json').exists():validate_prepared(run,args)
    nxt=profile=='nxt-observe'
    config=private_e2e_credentials(args.credentials_file) if nxt else private_credentials(run/'credentials.json')
    budget=Budget(args.max_rest_requests,args.max_ws_connections,args.max_events,nxt=nxt)
    observer=Observer(args.max_events,observe_state_wire=profile=='0s-only',nxt_observe=nxt)
    if nxt:
        # Before each external attempt/send, append counts only. Also survives a supervisor kill.
        journal=os.fdopen(os.open(run/'attempts.jsonl',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w',encoding='utf-8')
        def record_attempt(row):journal.write(json.dumps(row)+'\n');journal.flush()
        budget.on_attempt=record_attempt
    # Create exclusive log BEFORE sending authentication; failures cannot silently overwrite prior evidence.
    with os.fdopen(os.open(run/'events.jsonl',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w',encoding='utf-8') as output:
        written=0
        def flush():
            nonlocal written
            for row in observer.rows[written:]:output.write(json.dumps(row,ensure_ascii=False)+'\n')
            output.flush();written=len(observer.rows)
        reason='COMPLETE'
        try:
            transport=transport_factory(config,budget);token=transport.token()
            for _ in range(args.max_ws_connections):
                with transport.connect() as ws:
                    observer.begin(datetime.now(UTC));flush()
                    def send(packet):
                        kind='PING' if packet=='PING' else json.loads(packet).get('trnm')
                        budget.send(kind);ws.send(packet)
                    send(json.dumps({'trnm':'LOGIN','token':token}))
                    sent_reg=False;chart_count=0;started=time.monotonic();ends=started+args.seconds
                    while time.monotonic()<ends:
                        if observer.reg and chart_count<args.chart_snapshots:
                            offset=0 if args.chart_snapshots==1 else chart_count*(args.seconds-10)/(args.chart_snapshots-1)
                            if time.monotonic()-started>=offset:
                                requested=datetime.now(UTC);body=transport.chart(token)
                                observer.candles(body,requested,datetime.now(UTC));flush();chart_count+=1
                        try:raw=ws.recv(timeout=min(1,max(.01,ends-time.monotonic())))
                        except TimeoutError:continue
                        budget.receive()
                        if isinstance(raw,str) and raw.strip()=='PING':send(raw);continue
                        try:frame=json.loads(raw)
                        except Exception:raise DiagnosticError('MALFORMED_WS') from None
                        if isinstance(frame,dict) and frame.get('trnm')=='PING':
                            send(json.dumps({'trnm':'PING'}));continue
                        observer.frame(frame,datetime.now(UTC));flush()
                        if isinstance(frame,dict) and frame.get('trnm') in ('LOGIN','REG') and not ack_success(frame):
                            raise DiagnosticError('WS_ACK_REJECTED')
                        if observer.login and not sent_reg:
                            packet=subscription(profile)
                            send(json.dumps(packet));sent_reg=True
                            if profile in ('0s-only','nxt-observe'):
                                observer.add({'kind':'REG_SENT','generation':observer.generation,
                                    'receivedAt':stamp(datetime.now(UTC)),'payload':packet,'sendReturned':True});flush()
        except DiagnosticError as error:
            reason=str(error)  # internal fixed codes only; no raw exception/cause
        except Exception:
            reason='TRANSPORT_FAILED'
        finally:
            flush()
            if nxt:journal.close()
    report=analyze(observer);report.update({'profile':profile,'externalRequests':{'rest':budget.rest,'wsConnections':budget.ws,
        'receivedFrames':budget.received_frames,'sentFrames':budget.sent_frames},'captureResult':reason})
    if profile=='0s-only':
        report['priceEligibilityEvaluation']='NOT_RUN'
        for generation in report['generations']:
            generation['unexpectedTradeEntries']=sum(row['kind']=='TRADE' and row['generation']==generation['generation'] for row in observer.rows)
            generation['productEligibilityEvidence']='INCONCLUSIVE'
    if nxt:
        report['externalRequests'].update(login=budget.login,reg=budget.reg,marketRest=0)
        report['totalTimeLimitSeconds']=TOTAL_SECONDS
    private_write(run/'report.json',json.dumps(report,indent=2,ensure_ascii=False))
    return report


def source_hashes():
    return {name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
            for name in ('kiwoom-diagnostic.py','kiwoom_diagnostic_core.py')}


def capture_limits(args):
    limits={'rest':args.max_rest_requests,'wsConnections':args.max_ws_connections,
        'seconds':args.seconds,'maxEvents':args.max_events,'chartSnapshots':args.chart_snapshots}
    if getattr(args,'profile',None)=='nxt-observe':limits['totalSeconds']=TOTAL_SECONDS
    return limits


def validate_prepared(run,args):
    try:
        prepared=json.loads((run/'prepared.json').read_text(encoding='utf-8'))
        profile=getattr(args,'profile','mixed')
        if (prepared.get('status')!='AWAITING_EXTERNAL_APPROVAL' or prepared.get('profile') not in ('0s-only','nxt-observe')
                or prepared.get('profile')!=getattr(args,'profile','mixed')
                or prepared.get('sourceHashes')!=source_hashes() or prepared.get('limits')!=capture_limits(args)
                or prepared.get('registration')!=subscription(profile)):
            raise ValueError()
        if profile=='nxt-observe' and prepared.get('credentialReference')!=str(secret_reference(args.credentials_file)):
            raise ValueError()
    except Exception:
        raise DiagnosticError('PREPARED_MISMATCH') from None


def prepare_nxt(value):
    reference=secret_reference(value)
    private_e2e_credentials(reference) # validate in memory only; no key copies/fingerprints
    run=init_run('nxt-observe')
    args=argparse.Namespace(profile='nxt-observe',max_rest_requests=1,max_ws_connections=1,seconds=180,max_events=5000,chart_snapshots=0)
    private_write(run/'prepared.json',json.dumps({'status':'AWAITING_EXTERNAL_APPROVAL','profile':'nxt-observe',
        'limits':capture_limits(args),'registration':subscription('nxt-observe'),'sourceHashes':source_hashes(),
        'credentialReference':str(reference)},indent=2))
    return run


def attempt_evidence(run):
    """A killed write can leave a partial last line; never edit it or turn it into a count."""
    path=run/'attempts.jsonl';latest={};incomplete=False
    try:
        lines=[]
        if path.exists():
            if path.stat().st_size>8192:raise OSError()
            lines=path.read_text(encoding='utf-8',errors='replace').splitlines()
        for line in lines:
            try:
                row=json.loads(line)
                if not isinstance(row,dict) or set(row)!={'rest','wsConnections','login','reg'} or any(type(v) is not int or not 0<=v<=1 for v in row.values()):raise ValueError()
                latest=row
            except (ValueError,TypeError):incomplete=True
    except OSError:incomplete=True
    return {'lastCompleteAttemptCounts':latest,'attemptJournalIncomplete':incomplete,
        'externalRequests':latest if not incomplete else 'INCONCLUSIVE'}


def supervise_nxt(args,argv):
    """Own one network-worker process. Kill it before 210s even if its socket/reader blocks."""
    started=time.monotonic()
    validate_live(args);run=load_run(args.run);validate_prepared(run,args)
    if any((run/name).exists() for name in ('events.jsonl','report.json','attempts.jsonl','supervisor-report.json','supervisor-start.json')):
        raise DiagnosticError('CAPTURE_ALREADY_EXISTS')
    remaining=TOTAL_SECONDS-2-(time.monotonic()-started)
    if remaining<=0:raise DiagnosticError('TOTAL_TIME_LIMIT')
    # One owner per run, including concurrent CLI starts. Never read another worker's result as ours.
    private_write(run/'supervisor-start.json',json.dumps({'profile':'nxt-observe','startedAt':stamp(datetime.now(UTC)),
        'totalTimeLimitSeconds':TOTAL_SECONDS}))
    env=dict(os.environ);env['KIWOOM_NXT_WORKER_PARENT']=str(os.getpid())
    child=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),*argv,'--_nxt-worker'],
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=env)
    try:
        try:
            child.wait(timeout=max(.001,TOTAL_SECONDS-2-(time.monotonic()-started)))
            report_path=run/'report.json'
            try:
                if report_path.stat().st_size>MAX_BODY:raise ValueError()
                report=json.loads(report_path.read_text(encoding='utf-8'))
            except (OSError,ValueError):report=None
            if (not isinstance(report,dict) or report.get('profile')!='nxt-observe'
                    or not isinstance(report.get('captureResult'),str) or child.returncode not in (0,2)
                    or (report.get('captureResult')=='COMPLETE')!=(child.returncode==0)):
                report={'profile':'nxt-observe','captureResult':'WORKER_FAILED',**attempt_evidence(run),
                    'priceEligibilityEvaluation':'NOT_RUN','transactionExecution':'NOT_RUN'}
            else:report=dict(report)
            report.update(workerStopped=True,workerExitCode=child.returncode,totalTimeLimitSeconds=TOTAL_SECONDS)
            private_write(run/'supervisor-report.json',json.dumps(report,indent=2));return report
        except (subprocess.TimeoutExpired,KeyboardInterrupt) as stopped:
            child.kill();child.wait(timeout=2)
            report={'profile':'nxt-observe','captureResult':'TOTAL_TIME_LIMIT' if isinstance(stopped,subprocess.TimeoutExpired) else 'USER_INTERRUPTED','totalTimeLimitSeconds':TOTAL_SECONDS,
                **attempt_evidence(run),'workerStopped':True,
                'priceEligibilityEvaluation':'NOT_RUN','transactionExecution':'NOT_RUN','partialEvidenceRetained':True}
            private_write(run/'supervisor-report.json',json.dumps(report,indent=2));return report
    finally:
        if child.poll() is None:child.kill();child.wait(timeout=2)


def replay(path):
    data=json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(data,list) or len(data)>20000:raise DiagnosticError('INVALID_FIXTURE')
    observer=Observer()
    for entry in data:
        received=instant(entry['receivedAt'])
        if entry['kind']=='CONNECT':observer.begin(received)
        elif entry['kind']=='FRAME':observer.frame(entry['frame'],received,entry.get('generation'))
        elif entry['kind']=='CHART':observer.candles(entry['body'],instant(entry['requestedAt']),received)
        else:raise DiagnosticError('INVALID_FIXTURE')
    return analyze(observer)


class SafeParser(argparse.ArgumentParser):
    def error(self,message):raise DiagnosticError('INVALID_ARGUMENTS')  # never echo CLI secrets or attacker strings


def main(argv=None):
    parser=SafeParser(description='Kiwoom read-only diagnostics. External connections disabled by default.')
    sub=parser.add_subparsers(dest='action',required=True)
    sub.add_parser('init')
    prep=sub.add_parser('prepare-nxt');prep.add_argument('--credentials-file',required=True)
    mock=sub.add_parser('mock');mock.add_argument('--fixture',required=True)
    live=sub.add_parser('capture');live.add_argument('--run',required=True)
    live.add_argument('--profile',choices=('mixed','0s-only','nxt-observe'),default='mixed')
    live.add_argument('--credentials-file')
    live.add_argument('--_nxt-worker',action='store_true',help=argparse.SUPPRESS)
    live.add_argument('--allow-external',action='store_true')
    live.add_argument('--max-rest-requests',type=int,default=0);live.add_argument('--max-ws-connections',type=int,default=0)
    live.add_argument('--seconds',type=int,default=60);live.add_argument('--max-events',type=int,default=5000)
    live.add_argument('--chart-snapshots',type=int,default=0)
    args=parser.parse_args(argv)
    if args.action=='init':print('DIAGNOSTIC_RUN='+str(init_run()));return 0
    if args.action=='prepare-nxt':print('NXT_RUN='+str(prepare_nxt(args.credentials_file)));return 0
    if getattr(args,'_nxt_worker',False) and (args.profile!='nxt-observe' or os.environ.get('KIWOOM_NXT_WORKER_PARENT')!=str(os.getppid())):
        raise DiagnosticError('SUPERVISOR_REQUIRED')
    if args.action=='capture' and args.profile=='nxt-observe' and not args._nxt_worker:
        report=supervise_nxt(args,list(sys.argv[1:] if argv is None else argv))
        print(json.dumps(report,ensure_ascii=False));return 0 if report['captureResult']=='COMPLETE' else 2
    report=replay(args.fixture) if args.action=='mock' else capture(args)
    print(json.dumps(report,ensure_ascii=False))
    return 0 if args.action=='mock' or report['captureResult']=='COMPLETE' else 2


if __name__=='__main__':
    try:raise SystemExit(main())
    except DiagnosticError as error:
        # codes are generated by this code, not carried from HTTP errors or secret file values
        print(json.dumps({'status':'BLOCKED','reason':str(error)}));raise SystemExit(2) from None
    except Exception:
        print('{"status":"BLOCKED","reason":"DIAGNOSTIC_FAILED"}');raise SystemExit(2) from None
