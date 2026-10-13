"""Offline by default. Explicit bounded read-only Kiwoom capture; never start the trading server."""
import argparse
from datetime import datetime
import json
import logging
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request

from kiwoom_diagnostic_core import Observer, DiagnosticError, UTC, instant, stamp, analyze, ack_success

ROOT=Path(__file__).resolve().parent
RUNTIME=ROOT/'runtime'
MAX_BODY=1_048_576


def private_write(path,text):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w',encoding='utf-8') as stream:
        stream.write(text)


def init_run():
    name='kiwoom-diagnostic-'+datetime.now(UTC).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    run=RUNTIME/name;run.mkdir(parents=True,mode=0o700)
    if os.name=='nt':
        user=subprocess.run(['whoami'],check=True,capture_output=True,text=True).stdout.strip()
        subprocess.run(['icacls',str(run),'/inheritance:r','/grant:r',user+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
    private_write(run/'credentials.json',json.dumps({'mode':'real','appKey':'','secretKey':''},indent=2))
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


def private_credentials(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size>8192:
        raise DiagnosticError('INVALID_CREDENTIAL_FILE')
    if os.name=='nt':
        literal=str(path).replace("'","''")
        script="""$ErrorActionPreference='Stop'; $id=[Security.Principal.WindowsIdentity]::GetCurrent(); $allowed=@($id.User.Value,'S-1-5-18');
        $acl=Get-Acl -LiteralPath '%s';
        foreach($rule in $acl.Access){if($rule.AccessControlType -eq 'Allow' -and
        $allowed -notcontains $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value){exit 1}}""" % literal
        if subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',script],capture_output=True).returncode:
            raise DiagnosticError('CREDENTIAL_ACL_NOT_PRIVATE')
    elif path.stat().st_mode & 0o077 or path.stat().st_uid!=os.getuid():
        raise DiagnosticError('CREDENTIAL_ACL_NOT_PRIVATE')
    config=json.loads(path.read_text(encoding='utf-8'))
    if set(config)!= {'mode','appKey','secretKey'} or config['mode'] not in ('real','demo'):
        raise DiagnosticError('INVALID_CREDENTIAL_FILE')
    for field in ('appKey','secretKey'):
        value=config[field]
        if not isinstance(value,str) or not 1<=len(value)<=512 or any(ord(c)<32 for c in value):
            raise DiagnosticError('CREDENTIALS_REQUIRED')
    return config


class Budget:
    def __init__(self,rest,ws,frames=5000):
        self.rest_limit=rest;self.ws_limit=ws;self.rest=0;self.ws=0
        self.frame_limit=frames;self.received_frames=0;self.sent_frames=0
    def take_rest(self):
        if self.rest>=self.rest_limit:raise DiagnosticError('REST_LIMIT')
        self.rest+=1
    def take_ws(self):
        if self.ws>=self.ws_limit:raise DiagnosticError('WS_LIMIT')
        self.ws+=1
    def receive(self):
        if self.received_frames>=self.frame_limit:raise DiagnosticError('WS_FRAME_LIMIT')
        self.received_frames+=1
    def send(self):
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
    if not 1<=args.max_rest_requests<=10 or not 1<=args.max_ws_connections<=2:
        raise DiagnosticError('EXPLICIT_CALL_LIMITS_REQUIRED')
    if not 5<=args.seconds<=180 or not 1<=args.max_events<=20000 or not 0<=args.chart_snapshots<=3:
        raise DiagnosticError('INVALID_CAPTURE_LIMITS')
    if args.max_rest_requests < 1+args.max_ws_connections*args.chart_snapshots:
        raise DiagnosticError('INSUFFICIENT_PLANNED_REST_BUDGET')


def capture(args,transport_factory=LiveTransport):
    validate_live(args)
    run=load_run(args.run)
    if (run/'events.jsonl').exists():raise DiagnosticError('CAPTURE_ALREADY_EXISTS')
    config=private_credentials(run/'credentials.json')
    budget=Budget(args.max_rest_requests,args.max_ws_connections,args.max_events)
    observer=Observer(args.max_events)
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
                        budget.send();ws.send(packet)
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
                            send(json.dumps({'trnm':'REG','grp_no':'1','refresh':'1','data':[
                                {'item':['005930'],'type':['0B']},{'item':[''],'type':['0s']}]}));sent_reg=True
        except DiagnosticError as error:
            reason=str(error)  # internal fixed codes only; no raw exception/cause
        except Exception:
            reason='TRANSPORT_FAILED'
        finally:flush()
    report=analyze(observer);report.update({'externalRequests':{'rest':budget.rest,'wsConnections':budget.ws,
        'receivedFrames':budget.received_frames,'sentFrames':budget.sent_frames},'captureResult':reason})
    private_write(run/'report.json',json.dumps(report,indent=2,ensure_ascii=False))
    return report


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
    mock=sub.add_parser('mock');mock.add_argument('--fixture',required=True)
    live=sub.add_parser('capture');live.add_argument('--run',required=True)
    live.add_argument('--allow-external',action='store_true')
    live.add_argument('--max-rest-requests',type=int,default=0);live.add_argument('--max-ws-connections',type=int,default=0)
    live.add_argument('--seconds',type=int,default=60);live.add_argument('--max-events',type=int,default=5000)
    live.add_argument('--chart-snapshots',type=int,default=0)
    args=parser.parse_args(argv)
    if args.action=='init':print('DIAGNOSTIC_RUN='+str(init_run()));return 0
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
