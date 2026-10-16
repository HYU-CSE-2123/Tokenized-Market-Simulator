"""Offline only: fake broker transport, fake secret fixtures, and an own sleeping worker."""
import argparse
from datetime import datetime,timedelta
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock,patch
from kiwoom_diagnostic_core import Observer,UTC,DiagnosticError,analyze,subscription

spec=importlib.util.spec_from_file_location('nxt_diagnostic',Path(__file__).with_name('kiwoom-diagnostic.py'))
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
T=datetime(2026,10,15,9,0,0,tzinfo=UTC)
CANARY='SECRET_CANARY_NEVER_LOG'

def args(**kw):
    values=dict(profile='nxt-observe',allow_external=True,run='unused',credentials_file='explicit-private-reference',
        max_rest_requests=1,max_ws_connections=1,seconds=180,max_events=5000,chart_snapshots=0)
    values.update(kw);return argparse.Namespace(**values)

def prepared(a):
    return {'status':'AWAITING_EXTERNAL_APPROVAL','profile':'nxt-observe','limits':d.capture_limits(a),
        'sourceHashes':d.source_hashes(),'registration':subscription('nxt-observe'),'credentialReference':a.credentials_file}

def tick(item='005930_NX',session='7',exchange='9'):
    return {'trnm':'REAL','Authorization':CANARY,'data':[{'type':'0B','item':item,
        'values':{'10':'-75000','15':'-3','13':'103','20':'180000','290':session,'9081':exchange,'token':CANARY}}]}

def state(item=''):
    return {'trnm':'REAL','data':[{'type':'0s','item':item,'values':{'215':'U','20':'154000','token':CANARY}}]}

class NxtParserTest(unittest.TestCase):
    def observer(self):
        o=Observer(nxt_observe=True);o.begin(T)
        o.frame({'trnm':'LOGIN','return_code':0},T);o.frame({'trnm':'REG','return_code':0},T);return o
    def test_exact_subscription_and_legacy_defaults(self):
        self.assertEqual(subscription('nxt-observe')['data'],[{'item':['005930_NX'],'type':['0B']},{'item':[],'type':['0s']}])
        self.assertEqual(subscription()['data'][0]['item'],['005930'])
        self.assertEqual(subscription('0s-only')['data'],[{'item':[],'type':['0s']}])
    def test_codes_and_old_state_are_observed_without_market_interpretation(self):
        o=self.observer();o.frame(tick(),T);o.frame(state(),T)
        self.assertEqual(o.rows[-2]['sessionCode'],'7');self.assertEqual(o.rows[-2]['exchangeCode'],'9')
        self.assertEqual(o.rows[-2]['price'],'75000');self.assertEqual(o.rows[-2]['volume'],'3')
        self.assertEqual(o.rows[-1]['stateCode'],'U');self.assertFalse(o.rows[-1]['timeWithinExistingWindow'])
        report=analyze(o);g=report['generations'][0]
        self.assertEqual(g['tradeEntries'],1);self.assertEqual(g['stateEntries'],1)
        self.assertEqual(g['sessionInterpretation'],'UNVERIFIED');self.assertEqual(g['exchangeInterpretation'],'UNVERIFIED')
        self.assertEqual(report['priceEligibilityEvaluation'],'NOT_RUN')
        text=json.dumps(report)+json.dumps(o.rows)
        for value in (CANARY,'REGULAR','OPEN','CLOSED','Authorization','token'):self.assertNotIn(value,text)
    def test_items_are_separate_not_assumed_nxt_from_plain_symbol(self):
        o=self.observer()
        for item in ('005930_NX','005930','005930_AL',CANARY):o.frame(tick(item),T)
        g=analyze(o)['generations'][0]
        self.assertEqual(g['tradeItems'],{'005930_NX':1,'005930':1,'005930_AL':1,'MISSING_OR_REDACTED':1})
        self.assertNotIn(CANARY,json.dumps(o.rows))
    def test_wire_before_reg_is_distinguished_and_old_generation_ignored(self):
        o=Observer(nxt_observe=True);o.begin(T);o.frame(tick(),T)
        self.assertFalse(o.rows[-1]['regAcknowledged'])
        before=len(o.rows);o.frame(tick(),T,99);self.assertEqual(len(o.rows),before)
    def test_malformed_fields_are_redacted_and_valid_fields_survive(self):
        o=self.observer();f=tick(CANARY,CANARY,CANARY)
        f['data'][0]['values'].update({'10':CANARY,'20':CANARY});o.frame(f,T)
        row=o.rows[-1]
        for field in ('item','sessionCode','exchangeCode','price','wireTime','observedAt'):self.assertIsNone(row[field])
        self.assertEqual(row['volume'],'3');self.assertNotIn(CANARY,json.dumps(row))
    def test_nxt_event_limit_covers_both_kinds(self):
        o=Observer(3,nxt_observe=True);o.begin(T);o.frame(tick(),T);o.frame(state(),T)
        with self.assertRaisesRegex(DiagnosticError,'EVENT_LIMIT'):o.frame(tick(),T)
        self.assertEqual(len(o.rows),3);self.assertTrue(analyze(o)['captureTruncated'])
    def test_exchange_presence_type_and_format_without_raw_value(self):
        cases=[(None,'NULL',False),(True,'BOOLEAN',False),(2,'NUMBER',False),
            (2.5,'NUMBER',False),('2','STRING',True),('123','STRING',True),
            ('1234','STRING',False),('','STRING',False),(CANARY,'STRING',False),
            ('１２','STRING',False),([CANARY],'ARRAY',False),({'secret':CANARY},'OBJECT',False)]
        for value,kind,allowed in cases:
            with self.subTest(kind=kind,allowed=allowed):
                o=self.observer();o.frame(tick(exchange=value),T);row=o.rows[-1]
                self.assertTrue(row['exchangeFieldPresent']);self.assertEqual(row['exchangeJsonType'],kind)
                self.assertEqual(row['exchangeFormatAllowed'],allowed)
                self.assertEqual(row['exchangeCode'],value if allowed else None)
                self.assertNotIn(CANARY,json.dumps(o.rows)+json.dumps(analyze(o)))
    def test_missing_exchange_distinct_from_explicit_null(self):
        o=self.observer();frame=tick();del frame['data'][0]['values']['9081'];o.frame(frame,T)
        row=o.rows[-1];self.assertFalse(row['exchangeFieldPresent'])
        self.assertEqual(row['exchangeJsonType'],'MISSING');self.assertFalse(row['exchangeFormatAllowed'])
        self.assertIsNone(row['exchangeCode'])
    def test_exchange_metadata_not_added_to_legacy_or_state_rows(self):
        for o in (Observer(),Observer(observe_state_wire=True),self.observer()):
            if not o.generation:o.begin(T)
            o.frame(state(),T)
            if not o.nxt_observe:o.frame(tick(item='005930',session='2'),T)
            for row in o.rows:
                for name in ('exchangeFieldPresent','exchangeJsonType','exchangeFormatAllowed'):
                    self.assertNotIn(name,row)

class NxtGuardTest(unittest.TestCase):
    def test_gate_and_invalid_limits_precede_secret_or_transport(self):
        for override in ({'allow_external':False},{'max_rest_requests':2},{'max_ws_connections':2},
                         {'chart_snapshots':1},{'max_events':5001},{'seconds':181},{'credentials_file':None}):
            with patch.object(d,'load_run') as load,self.assertRaises(DiagnosticError):d.capture(args(**override),Mock())
            load.assert_not_called()
    def test_manifest_tampering_and_profile_downgrade_are_blocked(self):
        a=args();valid=prepared(a)
        mutations=[('status','CHANGED'),('profile','mixed'),('sourceHashes',{}),('limits',{}),('registration',subscription()),('credentialReference','another')]
        for field,value in mutations:
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                    patch.object(d,'secret_reference',side_effect=lambda v:v),patch.object(d,'private_e2e_credentials') as secret:
                d.private_write(Path(folder)/'prepared.json',json.dumps({**valid,field:value}))
                factory=Mock()
                with self.assertRaisesRegex(DiagnosticError,'PREPARED_MISMATCH'):d.capture(a,factory)
                secret.assert_not_called();factory.assert_not_called()
        with tempfile.TemporaryDirectory() as folder:
            d.private_write(Path(folder)/'prepared.json',json.dumps(valid))
            with self.assertRaisesRegex(DiagnosticError,'PREPARED_MISMATCH'):d.validate_prepared(Path(folder),args(profile='mixed'))
    def test_oauth_ws_login_reg_budgets_and_no_chart(self):
        b=d.Budget(1,1,nxt=True);b.take_rest();b.take_ws();b.send('LOGIN');b.send('REG')
        for method in (b.take_rest,b.take_ws,lambda:b.send('LOGIN'),lambda:b.send('REG'),lambda:b.send('ORDER')):
            with self.assertRaises(DiagnosticError):method()
        with patch.dict(sys.modules,{'websockets':Mock(__version__='16.0')}):t=d.LiveTransport({'mode':'real'},d.Budget(1,1,nxt=True))
        with self.assertRaisesRegex(DiagnosticError,'NXT_NO_MARKET_REST'):t.chart(CANARY)
        self.assertEqual(t.budget.rest,0)
    def test_explicit_properties_reference_reads_without_modifying_or_copying(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'kiwoom.properties';original='KIWOOM_APP_KEY='+CANARY+'\nKIWOOM_SECRET_KEY='+CANARY+'\n';path.write_text(original)
            with patch.object(d,'E2E_SECRET',path),patch.object(d,'check_private_file') as acl:
                config=d.private_e2e_credentials(str(path.resolve()));self.assertEqual(config['mode'],'real');acl.assert_called_once()
                self.assertEqual(config['appKey'],CANARY)
                with self.assertRaises(DiagnosticError):d.secret_reference(str(Path(folder)/'another.properties'))
            self.assertEqual(path.read_text(),original);self.assertEqual(len(list(Path(folder).iterdir())),1)
    def test_reference_rejects_unrelated_properties_duplicates_and_acl_failure(self):
        for content in ('KIWOOM_APP_KEY=x\nOTHER='+CANARY,'KIWOOM_APP_KEY=x\nKIWOOM_APP_KEY=y\nKIWOOM_SECRET_KEY=z','KIWOOM_APP_KEY=\nKIWOOM_SECRET_KEY=z'):
            with tempfile.TemporaryDirectory() as folder:
                path=Path(folder)/'kiwoom.properties';path.write_text(content)
                with patch.object(d,'E2E_SECRET',path),patch.object(d,'check_private_file'):
                    with self.assertRaises(DiagnosticError) as e:d.private_e2e_credentials(str(path.resolve()))
                    self.assertNotIn(CANARY,str(e.exception))
        with patch.object(d,'secret_reference',return_value=Path('unused')),patch.object(d,'check_private_file',side_effect=DiagnosticError('CREDENTIAL_ACL_NOT_PRIVATE')),patch.object(Path,'read_text') as read:
            with self.assertRaises(DiagnosticError):d.private_e2e_credentials('explicit')
            read.assert_not_called()
    def test_prepare_contains_reference_only_and_does_not_connect(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'secret_reference',side_effect=lambda x:x),\
                patch.object(d,'private_e2e_credentials',return_value={'appKey':CANARY,'secretKey':CANARY}),\
                patch.object(d,'init_run',return_value=Path(folder)),patch.object(d,'LiveTransport') as transport:
            run=d.prepare_nxt('explicit-private-reference');data=json.loads((run/'prepared.json').read_text())
            self.assertEqual(data['credentialReference'],'explicit-private-reference');self.assertEqual(data['limits']['totalSeconds'],210)
            self.assertNotIn(CANARY,json.dumps(data));self.assertFalse((run/'credentials.json').exists());transport.assert_not_called()
    def test_internal_worker_option_cannot_bypass_supervisor_accidentally(self):
        with patch.dict(os.environ,{'KIWOOM_NXT_WORKER_PARENT':'not-parent'}),self.assertRaisesRegex(DiagnosticError,'SUPERVISOR_REQUIRED'):
            d.main(['capture','--run','unused','--profile','nxt-observe','--_nxt-worker'])

class NxtCaptureTest(unittest.TestCase):
    def run_capture(self,phase=None,event_limit=5000):
        sent=[];counts={'auth':0,'connect':0,'chart':0};frames=[{'trnm':'LOGIN','return_code':1 if phase=='login_reject' else 0},
            {'trnm':'REG','return_code':1 if phase=='reg_reject' else 0},tick(),state(),{'trnm':'LOGIN','return_code':0},{'trnm':'PING','token':CANARY}]
        class FakeSocket:
            def __enter__(self):return self
            def __exit__(self,*unused):pass
            def send(self,packet):sent.append(json.loads(packet))
            def recv(self,timeout):
                if phase=='recv':raise RuntimeError(CANARY)
                if frames:return json.dumps(frames.pop(0))
                raise TimeoutError()
        class FakeTransport:
            def __init__(self,config,budget):self.budget=budget
            def token(self):
                counts['auth']+=1;self.budget.take_rest()
                if phase=='auth':raise RuntimeError(CANARY)
                return CANARY
            def connect(self):
                counts['connect']+=1;self.budget.take_ws()
                if phase=='connect':raise RuntimeError(CANARY)
                return FakeSocket()
            def chart(self,*unused):counts['chart']+=1;raise AssertionError('Forbidden')
        a=args(max_events=event_limit);clock=iter(i*.5 for i in range(2000))
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                patch.object(d,'secret_reference',side_effect=lambda x:x),\
                patch.object(d,'private_e2e_credentials',return_value={'mode':'real','appKey':CANARY,'secretKey':CANARY}),\
                patch.object(d.time,'monotonic',side_effect=lambda:next(clock)),\
                patch('socket.create_connection',side_effect=AssertionError('NO_NETWORK')):
            d.private_write(Path(folder)/'prepared.json',json.dumps(prepared(a)))
            report=d.capture(a,FakeTransport)
            artifacts=''.join(p.read_text() for p in Path(folder).iterdir())
        self.assertNotIn(CANARY,artifacts);return report,sent,counts
    def test_complete_mock_capture_once_180_seconds_no_chart_or_interpretation(self):
        report,sent,counts=self.run_capture()
        self.assertEqual(report['captureResult'],'COMPLETE');self.assertEqual(counts,{'auth':1,'connect':1,'chart':0})
        self.assertEqual([p['trnm'] for p in sent],['LOGIN','REG','PING']);self.assertEqual(sent[1],subscription('nxt-observe'))
        self.assertEqual(report['externalRequests']['login'],1);self.assertEqual(report['externalRequests']['reg'],1)
        self.assertEqual(report['generations'][0]['tradeEntries'],1);self.assertEqual(report['generations'][0]['stateEntries'],1)
        self.assertEqual(report['priceEligibilityEvaluation'],'NOT_RUN')
    def test_auth_connect_and_receive_failures_do_not_retry(self):
        for phase in ('auth','connect','recv'):
            report,sent,counts=self.run_capture(phase)
            self.assertEqual(report['captureResult'],'TRANSPORT_FAILED');self.assertEqual(counts['auth'],1)
            self.assertEqual(counts['connect'],0 if phase=='auth' else 1)
    def test_event_budget_stops_capture_instead_of_truncating_silently(self):
        report,_,_=self.run_capture(event_limit=4)
        self.assertEqual(report['captureResult'],'EVENT_LIMIT');self.assertTrue(report['captureTruncated'])
    def test_login_and_reg_denial_stop_without_additional_packets_or_retry(self):
        for phase,packets in (('login_reject',['LOGIN']),('reg_reject',['LOGIN','REG'])):
            report,sent,counts=self.run_capture(phase)
            self.assertEqual(report['captureResult'],'WS_ACK_REJECTED');self.assertEqual([p['trnm'] for p in sent],packets)
            self.assertEqual(counts,{'auth':1,'connect':1,'chart':0})

class NxtSupervisorTest(unittest.TestCase):
    def test_partial_report_and_invalid_schema_are_preserved_as_worker_failure(self):
        for body in ('{"profile":','[]','{"profile":"nxt-observe","captureResult":42}'):
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                    patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen') as popen:
                worker=popen.return_value;worker.returncode=1;worker.poll.return_value=1
                def finished(*a,**kw):(Path(folder)/'report.json').write_text(body)
                worker.wait.side_effect=finished
                result=d.supervise_nxt(args(),['capture'])
                self.assertEqual(result['captureResult'],'WORKER_FAILED');self.assertTrue((Path(folder)/'supervisor-report.json').exists())
                self.assertEqual((Path(folder)/'report.json').read_text(),body)
    def test_normal_report_and_worker_exit_both_required_for_complete(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen') as popen:
            worker=popen.return_value;worker.returncode=0;worker.poll.return_value=0
            def finished(*a,**kw):d.private_write(Path(folder)/'report.json',json.dumps({'profile':'nxt-observe','captureResult':'COMPLETE'}))
            worker.wait.side_effect=finished
            result=d.supervise_nxt(args(),['capture'])
            self.assertEqual(result['captureResult'],'COMPLETE');self.assertTrue(result['workerStopped'])
            self.assertEqual(json.loads((Path(folder)/'supervisor-report.json').read_text())['workerExitCode'],0)
    def test_journal_read_failure_is_inconclusive_not_an_exception(self):
        with patch.object(Path,'exists',return_value=True),patch.object(Path,'stat',side_effect=OSError()):
            evidence=d.attempt_evidence(Path('fixture'))
            self.assertTrue(evidence['attemptJournalIncomplete']);self.assertEqual(evidence['externalRequests'],'INCONCLUSIVE')
    def test_user_interrupt_and_timeout_keep_partial_journal_and_stop_worker(self):
        for exception,code in ((KeyboardInterrupt(),'USER_INTERRUPTED'),(subprocess.TimeoutExpired('worker',210),'TOTAL_TIME_LIMIT')):
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                    patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen') as popen:
                worker=popen.return_value;worker.poll.return_value=-9;waited=[]
                def wait_once(*a,**kw):
                    if not waited:
                        waited.append(True);(Path(folder)/'attempts.jsonl').write_text('{"rest":');raise exception
                worker.wait.side_effect=wait_once
                result=d.supervise_nxt(args(),['capture'])
                self.assertEqual(result['captureResult'],code);self.assertTrue(result['workerStopped']);worker.kill.assert_called_once()
                self.assertTrue(result['attemptJournalIncomplete']);self.assertEqual(result['externalRequests'],'INCONCLUSIVE')
                self.assertEqual((Path(folder)/'attempts.jsonl').read_text(),'{"rest":')
    def test_partial_attempt_line_is_preserved_and_never_claimed_complete(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'attempts.jsonl';original='{"rest":1,"wsConnections":0,"login":0,"reg":0}\n{"rest":1,"wsCon'
            path.write_text(original);report=d.attempt_evidence(Path(folder))
            self.assertTrue(report['attemptJournalIncomplete']);self.assertEqual(report['externalRequests'],'INCONCLUSIVE')
            self.assertEqual(report['lastCompleteAttemptCounts']['rest'],1);self.assertEqual(path.read_text(),original)
    def test_crashed_worker_cannot_make_complete_report_success(self):
        for code in (1,-9,2):
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                    patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen') as popen:
                worker=popen.return_value;worker.returncode=code;worker.poll.return_value=code
                def finished(*a,**kw):
                    d.private_write(Path(folder)/'report.json',json.dumps({'profile':'nxt-observe','captureResult':'COMPLETE'}))
                worker.wait.side_effect=finished
                result=d.supervise_nxt(args(),['capture'])
                self.assertEqual(result['captureResult'],'WORKER_FAILED');self.assertEqual(result['workerExitCode'],code)
                self.assertEqual(json.loads((Path(folder)/'report.json').read_text())['captureResult'],'COMPLETE')
    def test_supervisor_run_is_claimed_exclusively_even_before_events_exist(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen') as popen:
            d.private_write(Path(folder)/'supervisor-start.json','{}')
            with self.assertRaisesRegex(DiagnosticError,'CAPTURE_ALREADY_EXISTS'):d.supervise_nxt(args(),['capture'])
            popen.assert_not_called()
    def test_real_own_blocked_worker_is_killed_at_scaled_total_deadline(self):
        original=subprocess.Popen;workers=[]
        def sleeper(*a,**kw):
            worker=original([sys.executable,'-c','import time; time.sleep(30)'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            workers.append(worker);return worker
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen',side_effect=sleeper),patch.object(d,'TOTAL_SECONDS',2.4):
            started=time.monotonic();report=d.supervise_nxt(args(),['capture'])
            self.assertLess(time.monotonic()-started,2.4)
            self.assertIsNotNone(workers[0].poll());self.assertEqual(report['captureResult'],'TOTAL_TIME_LIMIT')
            self.assertTrue(report['workerStopped']);self.assertTrue((Path(folder)/'supervisor-report.json').exists())
    def test_existing_capture_never_spawns_worker(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),\
                patch.object(d,'validate_prepared'),patch.object(d.subprocess,'Popen') as popen:
            (Path(folder)/'events.jsonl').touch()
            with self.assertRaisesRegex(DiagnosticError,'CAPTURE_ALREADY_EXISTS'):d.supervise_nxt(args(),[])
            popen.assert_not_called()

if __name__=='__main__':unittest.main()
