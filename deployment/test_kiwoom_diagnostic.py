import argparse
from datetime import datetime,timedelta
import importlib.util
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch,Mock
from kiwoom_diagnostic_core import Observer,UTC,analyze,DiagnosticError,stamp,number,subscription,asof_timing_comparison

spec=importlib.util.spec_from_file_location('kiwoom_diagnostic',Path(__file__).with_name('kiwoom-diagnostic.py'))
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
T=datetime(2026,10,14,1,0,0,tzinfo=UTC)


def ack(o):
    o.begin(T-timedelta(seconds=62))
    o.frame({'trnm':'LOGIN','return_code':0,'token':'SECRET_CANARY','return_msg':'SECRET_CANARY'},T-timedelta(seconds=62))
    o.frame({'trnm':'REG','return_code':0},T-timedelta(seconds=62))


def trade(o,offset,price,volume,cumulative,generation=None):
    t=T+timedelta(seconds=offset)
    f={'trnm':'REAL','Authorization':'SECRET_CANARY','data':[{'type':'0B','item':'005930',
       'values':{'10':str(-price),'15':str(-volume),'13':str(cumulative),
       '20':(t+timedelta(hours=9)).strftime('%H%M%S'),'290':'2','secretKey':'SECRET_CANARY'}}]}
    o.frame(f,t+timedelta(milliseconds=10),generation)


def state(o,code='3',age=0):
    o.frame({'trnm':'REAL','data':[{'type':'0s','item':'','values':{'215':code,
        '20':(T+timedelta(hours=9)).strftime('%H%M%S'),'token':'SECRET_CANARY'}}]},T+timedelta(seconds=age))


def chart(o,offset=0,volume=4,received=130,requested=129,prices=(102,104,102,103)):
    at=T+timedelta(seconds=offset)
    row=dict(zip(('open_pric','high_pric','low_pric','cur_prc'),map(str,prices)))
    row.update({'cntr_tm':(at+timedelta(hours=9)).strftime('%Y%m%d%H%M%S'),'trde_qty':str(volume),'token':'SECRET_CANARY'})
    o.candles({'return_code':0,'stk_min_pole_chart_qry':[row],'secretKey':'SECRET_CANARY'},
        T+timedelta(seconds=requested),T+timedelta(seconds=received))


def complete_observer():
    o=Observer();ack(o)
    for args in [(-61,99,1,1),(-60,100,1,2),(-30,101,2,4),(-1,100,1,5),
                 (0,102,1,6),(30,104,2,8),(59,103,1,9),(60,105,1,10),(90,106,1,11),(120,107,1,12)]:
        trade(o,*args)
    return o


class ParserTest(unittest.TestCase):
    def test_state_only_subscription_is_exact_official_single_entry(self):
        self.assertEqual(subscription('0s-only'),{'trnm':'REG','grp_no':'1','refresh':'1',
            'data':[{'item':[],'type':['0s']}]})
        with self.assertRaisesRegex(DiagnosticError,'INVALID_PROFILE'):subscription('SECRET_CANARY')

    def test_wire_observation_precedes_ack_item_and_time_filters_without_secrets(self):
        o=Observer(observe_state_wire=True);o.begin(T)
        items=[('MISSING',{}),('NULL',{'item':None}),('EMPTY_STRING',{'item':''}),
            ('STRING',{'item':'SECRET_CANARY'}),('EMPTY_ARRAY',{'item':[]}),
            ('ARRAY',{'item':['SECRET_CANARY']}),('OTHER',{'item':{'token':'SECRET_CANARY'}})]
        for shape,fields in items:
            o.frame({'trnm':'REAL','token':'SECRET_CANARY','data':[{'type':'0s',**fields,
                'values':{'215':'3','20':'090000','secretKey':'SECRET_CANARY'}}]},T)
        wire=[r for r in o.rows if r['kind']=='MARKET_STATE_WIRE']
        self.assertEqual(len(wire),7)
        self.assertEqual([r['itemFormat'] for r in wire],[shape for shape,_ in items])
        self.assertTrue(all(r['stateCode']=='3' and r['marketTime']=='090000' and not r['regAcknowledged'] for r in wire))
        self.assertNotIn('SECRET_CANARY',json.dumps(o.rows))
        self.assertEqual(analyze(o)['generations'][0]['stateWireEntries'],7)
        self.assertEqual(analyze(o)['generations'][0]['stateWireFrames'],7)
        self.assertEqual(analyze(o)['generations'][0]['stateParsedEntries'],0)

    def test_wire_invalid_values_are_redacted_and_old_generation_not_counted(self):
        o=Observer(observe_state_wire=True);ack(o)
        f={'trnm':'REAL','data':[{'type':'0s','item':'','values':{'215':'SECRET_CANARY','20':'SECRET_CANARY'}}]}
        o.frame(f,T)
        wire=next(r for r in o.rows if r['kind']=='MARKET_STATE_WIRE')
        self.assertIsNone(wire['stateCode']);self.assertIsNone(wire['marketTime'])
        before=len(o.rows);o.frame(f,T,99);self.assertEqual(len(o.rows),before)
        self.assertNotIn('SECRET_CANARY',json.dumps(o.rows)+json.dumps(analyze(o)))

    def test_wire_and_parsed_rows_share_one_hard_event_limit(self):
        o=Observer(4,observe_state_wire=True);ack(o)
        with self.assertRaisesRegex(DiagnosticError,'EVENT_LIMIT'):state(o)
        self.assertEqual(len(o.rows),4);self.assertTrue(o.truncated)

    def test_wire_fresh_state_alone_is_not_price_or_bootstrap_guarantee(self):
        o=Observer(observe_state_wire=True);ack(o);state(o)
        r=analyze(o)['generations'][0]
        self.assertEqual(r['stateWireEntries'],1);self.assertEqual(r['stateParsedEntries'],1)
        self.assertEqual(r['productEligibilityEvidence'],'INCONCLUSIVE')
    def test_registration_matches_official_empty_market_item_array(self):
        self.assertEqual(subscription()['data'],[
            {'item':['005930'],'type':['0B']},{'item':[],'type':['0s']}])

    def test_asof_clock_comparison_is_offline_inconclusive_and_does_not_mutate_evidence(self):
        o=complete_observer();chart(o,requested=30)
        original=json.dumps(o.rows,sort_keys=True)
        result=asof_timing_comparison(o.rows)
        self.assertEqual(len(result),1)
        self.assertEqual(result[0]['verdict'],'INCONCLUSIVE')
        self.assertFalse(result[0]['watermarkGuaranteed'])
        self.assertEqual(result[0]['generation'],o.generation)
        self.assertEqual(result[0]['alignmentAssumption'],'NATIVE_START')
        self.assertEqual(result[0]['observedPrefix'],'3')
        self.assertEqual(result[0]['receivedPrefix'],'1')
        self.assertEqual(json.dumps(o.rows,sort_keys=True),original)
    def test_nonregular_trade_requires_new_open_state_before_recovery(self):
        o=Observer();ack(o);state(o)
        t={'trnm':'REAL','data':[{'type':'0B','item':'005930','values':{'10':'100','15':'1','13':'1','20':'100000','290':'3'}}]}
        o.frame(t,T);trade(o,0,100,1,2)
        self.assertEqual(analyze(o)['generations'][0]['productEligibilityEvidence'],'INCONCLUSIVE')
        state(o);self.assertEqual(analyze(o)['generations'][0]['productEligibilityEvidence'],'SUPPORTED')
    def test_malformed_state_is_distinguished_from_no_state_delivery(self):
        o=Observer();ack(o)
        o.frame({'trnm':'REAL','data':[{'type':'0s','item':'','values':{'215':'3','20':'SECRET_CANARY'}}]},T)
        result=analyze(o)['generations'][0]
        self.assertTrue(result['stateReceived']);self.assertEqual(result['productEligibilityEvidence'],'INCONCLUSIVE')
        self.assertNotIn('SECRET_CANARY',json.dumps(o.rows))
    def test_only_allowlisted_fields_and_signed_magnitudes(self):
        o=Observer();ack(o);state(o);trade(o,0,75000,2,100)
        text=json.dumps(o.rows)+json.dumps(analyze(o))
        for forbidden in ('SECRET_CANARY','Authorization','secretKey','token','return_msg'):
            self.assertNotIn(forbidden,text)
        self.assertEqual(o.rows[-1]['price'],'75000');self.assertEqual(o.rows[-1]['volume'],'2')
        self.assertEqual(o.rows[-1]['observedAt'],stamp(T))
    def test_unknown_other_symbol_and_before_reg_are_not_accepted(self):
        o=Observer();o.begin(T);state(o)
        self.assertEqual(o.rows[-1]['reason'],'BEFORE_REG')
        ack(o);before=len(o.rows)
        o.frame({'trnm':'REAL','data':[{'type':'0B','item':'005930_NX','values':{}}]},T)
        self.assertEqual(len(o.rows),before)
    def test_stale_initial_state_is_observed_but_not_product_compatible(self):
        o=Observer();ack(o);state(o,age=6);trade(o,6,100,1,1)
        report=analyze(o)['generations'][0]
        self.assertTrue(report['stateReceived']);self.assertEqual(report['productEligibilityEvidence'],'INCONCLUSIVE')
    def test_missing_state_and_reconnect_do_not_inherit_open(self):
        o=Observer();ack(o);state(o);trade(o,0,100,1,1);ack(o)
        trade(o,0,100,1,2)
        state_before=len(o.rows)
        o.frame({'trnm':'REAL','data':[{'type':'0s','item':'','values':{'215':'3','20':'100000'}}]},T,1)
        self.assertEqual(len(o.rows),state_before)
        self.assertFalse(analyze(o)['generations'][1]['stateReceived'])
        state(o);self.assertEqual(analyze(o)['generations'][1]['productEligibilityEvidence'],'SUPPORTED')
    def test_close_state_overrides_prior_open(self):
        o=Observer();ack(o);state(o);trade(o,0,100,1,1);state(o,'8')
        self.assertEqual(analyze(o)['generations'][0]['productEligibilityEvidence'],'INCONCLUSIVE')
    def test_malformed_numeric_field_and_ack_do_not_echo_secret(self):
        with self.assertRaises(DiagnosticError):number('SECRET_CANARY')
        o=Observer();o.begin(T);o.frame({'trnm':'LOGIN','return_code':'SECRET_CANARY','return_msg':'SECRET_CANARY'},T)
        self.assertNotIn('SECRET_CANARY',json.dumps(o.rows))
    def test_start_boundary_requires_both_hypotheses_and_full_capture(self):
        o=complete_observer();chart(o)
        self.assertEqual(analyze(o)['boundaryChecks'][0]['verdict'],'START_SUPPORTED')
        o.rows=[r for r in o.rows if r.get('observedAt')!=stamp(T-timedelta(seconds=61))]
        self.assertEqual(analyze(o)['boundaryChecks'][0]['verdict'],'INCONCLUSIVE')
    def test_end_boundary(self):
        o=complete_observer();chart(o,volume=4,prices=(100,101,100,100))
        self.assertEqual(analyze(o)['boundaryChecks'][0]['verdict'],'END_SUPPORTED')
    def test_both_complete_hypotheses_disagree_is_mismatch(self):
        o=complete_observer();chart(o,volume=99)
        self.assertEqual(analyze(o)['boundaryChecks'][0]['verdict'],'MISMATCH')
    def test_cumulative_gap_duplicate_and_truncation_do_not_make_false_match(self):
        o=complete_observer();trade(o,120,107,1,12);chart(o)
        self.assertEqual(analyze(o)['boundaryChecks'][0]['verdict'],'START_SUPPORTED')
        o.truncated=True;self.assertEqual(analyze(o)['boundaryChecks'][0]['verdict'],'INCONCLUSIVE')
        o.truncated=False;o.rows=[r for r in o.rows if r.get('observedAt')!=stamp(T+timedelta(seconds=30))]
        self.assertTrue(analyze(o)['boundaryChecks'][0]['captureGap'])
    def test_partial_snapshot_uses_complete_bar_alignment_for_asof_risk(self):
        o=complete_observer();chart(o)
        chart(o,offset=60,volume=2,received=92,requested=85,prices=(105,106,105,106))
        checks=analyze(o)
        self.assertEqual(checks['boundaryChecks'][1]['verdict'],'INCONCLUSIVE')
        self.assertEqual(checks['asOfChecks'][1]['verdict'],'MISMATCH')
        self.assertFalse(checks['asOfChecks'][1]['watermarkGuaranteed'])
    def test_asof_prefix_match(self):
        o=complete_observer();chart(o)
        chart(o,offset=60,volume=1,received=92,requested=85,prices=(105,105,105,105))
        self.assertEqual(analyze(o)['asOfChecks'][1]['verdict'],'SUPPORTED')
    def test_event_limit(self):
        o=Observer(1);o.begin(T)
        with self.assertRaisesRegex(DiagnosticError,'EVENT_LIMIT'):o.frame({'trnm':'LOGIN','return_code':0},T)
        self.assertTrue(o.truncated)


class GuardTest(unittest.TestCase):
    def test_state_only_limits_fail_before_credentials_or_factory(self):
        for override in ({'max_rest_requests':2},{'max_ws_connections':2},{'chart_snapshots':1},{'max_events':5001}):
            values=dict(allow_external=True,profile='0s-only',max_rest_requests=1,max_ws_connections=1)
            values.update(override)
            with patch.object(d,'load_run') as load,self.assertRaisesRegex(DiagnosticError,'STATE_ONLY_LIMITS_REQUIRED'):
                d.capture(self.args(**values),Mock())
            load.assert_not_called()

    def test_state_only_prepared_mismatch_blocks_before_secret_and_network(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),patch.object(d,'private_credentials') as secret:
            factory=Mock()
            with self.assertRaisesRegex(DiagnosticError,'PREPARED_MISMATCH'):
                d.capture(self.args(allow_external=True,profile='0s-only',max_rest_requests=1,max_ws_connections=1),factory)
            secret.assert_not_called();factory.assert_not_called()

    def test_prepared_state_only_run_cannot_be_downgraded_to_default_or_explicit_mixed(self):
        for profile in (None,'mixed'):
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),patch.object(d,'private_credentials') as secret:
                state_args=self.args(allow_external=True,profile='0s-only',max_rest_requests=1,max_ws_connections=1)
                d.private_write(Path(folder)/'prepared.json',json.dumps({'status':'AWAITING_EXTERNAL_APPROVAL','profile':'0s-only',
                    'limits':d.capture_limits(state_args),'registration':subscription('0s-only'),'sourceHashes':d.source_hashes()}))
                args=self.args(allow_external=True,max_rest_requests=1,max_ws_connections=1)
                if profile is not None:args.profile=profile
                factory=Mock()
                with self.assertRaisesRegex(DiagnosticError,'PREPARED_MISMATCH'):d.capture(args,factory)
                secret.assert_not_called();factory.assert_not_called()

    def test_manifest_source_limits_payload_status_profile_mutations_fail_closed(self):
        args=self.args(allow_external=True,profile='0s-only',max_rest_requests=1,max_ws_connections=1)
        valid={'status':'AWAITING_EXTERNAL_APPROVAL','profile':'0s-only','limits':d.capture_limits(args),
            'registration':subscription('0s-only'),'sourceHashes':d.source_hashes()}
        for field,value in [('sourceHashes',{}),('limits',{}),('registration',subscription()),
                            ('status','CHANGED'),('profile','mixed')]:
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),patch.object(d,'private_credentials') as secret:
                prepared={**valid,field:value};d.private_write(Path(folder)/'prepared.json',json.dumps(prepared))
                factory=Mock()
                with self.assertRaisesRegex(DiagnosticError,'PREPARED_MISMATCH'):d.capture(args,factory)
                secret.assert_not_called();factory.assert_not_called()
                self.assertFalse((Path(folder)/'events.jsonl').exists())

    def test_state_only_transport_failure_never_retries_and_masks_raw_exception(self):
        for phase in ('auth','connect'):
            counters={'factory':0,'auth':0,'connect':0}
            class FakeTransport:
                def __init__(self,config,budget):self.budget=budget;counters['factory']+=1
                def token(self):
                    self.budget.take_rest();counters['auth']+=1
                    if phase=='auth':raise RuntimeError('SECRET_CANARY')
                    return 'SECRET_CANARY'
                def connect(self):
                    self.budget.take_ws();counters['connect']+=1
                    raise RuntimeError('SECRET_CANARY')
            args=self.args(allow_external=True,profile='0s-only',max_rest_requests=1,max_ws_connections=1)
            with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),patch.object(d,'private_credentials',return_value={'mode':'real'}):
                d.private_write(Path(folder)/'prepared.json',json.dumps({'status':'AWAITING_EXTERNAL_APPROVAL','profile':'0s-only',
                    'limits':d.capture_limits(args),'registration':subscription('0s-only'),'sourceHashes':d.source_hashes()}))
                report=d.capture(args,FakeTransport)
                self.assertEqual(report['captureResult'],'TRANSPORT_FAILED')
                self.assertNotIn('SECRET_CANARY',(Path(folder)/'report.json').read_text())
            self.assertEqual(counters,{'factory':1,'auth':1,'connect':0 if phase=='auth' else 1})

    def test_state_only_mock_capture_sends_once_no_chart_and_records_wire(self):
        sent=[]
        frames=[{'trnm':'LOGIN','return_code':0,'token':'SECRET_CANARY'},
            {'trnm':'REAL','data':[{'type':'0s','item':'SECRET_CANARY','values':{'215':'3','20':'090000'}}]},
            {'trnm':'REG','return_code':0},{'trnm':'LOGIN','return_code':0},
            {'trnm':'REAL','data':[{'type':'0s','item':'','values':{'215':'3','20':'100000'}}]}]
        class FakeSocket:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def send(self,packet):sent.append(json.loads(packet))
            def recv(self,timeout):
                if frames:return json.dumps(frames.pop(0))
                raise TimeoutError()
        class FakeTransport:
            def __init__(self,config,budget):self.budget=budget
            def token(self):self.budget.take_rest();return 'SECRET_CANARY'
            def connect(self):self.budget.take_ws();return FakeSocket()
            def chart(self,*args):raise AssertionError('Chart is forbidden')
        args=self.args(allow_external=True,profile='0s-only',max_rest_requests=1,max_ws_connections=1,seconds=5)
        clock=iter(i*.1 for i in range(300))
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)),patch.object(d,'private_credentials',return_value={'mode':'real'}),patch.object(d.time,'monotonic',side_effect=lambda:next(clock)):
            prepared={'status':'AWAITING_EXTERNAL_APPROVAL','profile':'0s-only','limits':d.capture_limits(args),
                'registration':subscription('0s-only'),'sourceHashes':d.source_hashes()}
            d.private_write(Path(folder)/'prepared.json',json.dumps(prepared))
            report=d.capture(args,FakeTransport)
            evidence=(Path(folder)/'events.jsonl').read_text()+(Path(folder)/'report.json').read_text()
        self.assertEqual([r['trnm'] for r in sent],['LOGIN','REG'])
        self.assertEqual(sent[1],subscription('0s-only'))
        self.assertEqual(report['externalRequests']['rest'],1);self.assertEqual(report['externalRequests']['wsConnections'],1)
        self.assertEqual(report['generations'][0]['stateWireEntries'],2)
        self.assertEqual(report['generations'][0]['stateWireFrames'],2)
        self.assertEqual(report['priceEligibilityEvaluation'],'NOT_RUN')
        self.assertEqual(report['generations'][0]['productEligibilityEvidence'],'INCONCLUSIVE')
        self.assertEqual(report['generations'][0]['unexpectedTradeEntries'],0)
        self.assertIn('REG_SENT',evidence);self.assertNotIn('SECRET_CANARY',evidence)

    @unittest.skipUnless(os.name=='nt','Windows ACL check only')
    def test_acl_failure_prevents_credential_content_read(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'credentials.json';path.write_text('{}')
            with patch.object(d.subprocess,'run',return_value=Mock(returncode=1)),patch.object(Path,'read_text') as read:
                with self.assertRaisesRegex(DiagnosticError,'CREDENTIAL_ACL_NOT_PRIVATE'):d.private_credentials(path)
                read.assert_not_called()
    def args(self,**kw):
        values=dict(allow_external=False,max_rest_requests=0,max_ws_connections=0,seconds=60,max_events=100,chart_snapshots=0,run='unused')
        values.update(kw);return argparse.Namespace(**values)
    def test_default_external_gate_before_credentials_or_network(self):
        factory=Mock()
        with patch.object(d,'load_run') as load,patch.object(d,'private_credentials') as credentials, \
                self.assertRaisesRegex(DiagnosticError,'EXTERNAL_DISABLED'):
            d.capture(self.args(),factory)
        load.assert_not_called();credentials.assert_not_called();factory.assert_not_called()
    def test_explicit_budget_required_and_planned_calls_fit(self):
        for values in [dict(allow_external=True),dict(allow_external=True,max_rest_requests=3,max_ws_connections=2,chart_snapshots=3)]:
            with self.assertRaises(DiagnosticError):d.validate_live(self.args(**values))
        d.validate_live(self.args(allow_external=True,max_rest_requests=4,max_ws_connections=1,chart_snapshots=3))
    def test_hard_request_and_frame_limits(self):
        b=d.Budget(1,1,1);b.take_rest();b.take_ws();b.receive()
        for f in (b.take_rest,b.take_ws,b.receive):
            with self.assertRaises(DiagnosticError):f()
    def test_only_readonly_official_path_and_no_redirect(self):
        with patch.dict(sys.modules,{'websockets':Mock(__version__='16.0')}):
            t=d.LiveTransport({'mode':'real'},d.Budget(1,1))
        with self.assertRaisesRegex(DiagnosticError,'READ_ONLY_PATH_REQUIRED'):t.post('/api/dostk/ordr',{})
        self.assertEqual(t.budget.rest,0)
        with self.assertRaisesRegex(DiagnosticError,'REDIRECT_BLOCKED'):d.NoRedirect().redirect_request()
    def test_cli_errors_do_not_echo_argument_secrets(self):
        with self.assertRaisesRegex(DiagnosticError,'INVALID_ARGUMENTS') as e:d.main(['--app-key','SECRET_CANARY'])
        self.assertNotIn('SECRET_CANARY',str(e.exception))
    def test_exclusive_artifacts_never_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'artifact';d.private_write(path,'one')
            with self.assertRaises(FileExistsError):d.private_write(path,'two')
            self.assertEqual(path.read_text(),'one')
    def test_environment_credentials_never_implicitly_used(self):
        with patch.dict(os.environ,{'KIWOOM_APP_KEY':'SECRET_CANARY','KIWOOM_SECRET_KEY':'SECRET_CANARY'}), \
                self.assertRaisesRegex(DiagnosticError,'EXTERNAL_DISABLED'):
            d.capture(self.args())
    def test_mock_capture_lifecycle_and_safe_artifacts_without_external_calls(self):
        class FakeSocket:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def send(self,packet):sent.append(json.loads(packet))
            def recv(self,timeout):
                if frames:return json.dumps(frames.pop(0))
                raise TimeoutError()
        class FakeTransport:
            def __init__(self,config,budget):self.budget=budget
            def token(self):self.budget.take_rest();return 'SECRET_CANARY'
            def connect(self):self.budget.take_ws();return FakeSocket()
        sent=[]
        frames=[{'trnm':'LOGIN','return_code':0,'token':'SECRET_CANARY'},
                {'trnm':'PING','token':'SECRET_CANARY'}, {'trnm':'REG','return_code':0}]
        clock=iter(i*.1 for i in range(300))
        with tempfile.TemporaryDirectory() as folder,patch.object(d,'load_run',return_value=Path(folder)), \
             patch.object(d,'private_credentials',return_value={'mode':'real','appKey':'SECRET_CANARY','secretKey':'SECRET_CANARY'}), \
             patch.object(d.time,'monotonic',side_effect=lambda:next(clock)):
            report=d.capture(self.args(allow_external=True,max_rest_requests=1,max_ws_connections=1,seconds=5),FakeTransport)
            evidence=(Path(folder)/'events.jsonl').read_text()+(Path(folder)/'report.json').read_text()
        self.assertNotIn('SECRET_CANARY',evidence)
        self.assertEqual([p['trnm'] for p in sent],['LOGIN','REG','PING'])
        self.assertEqual(sent[1],subscription())
        self.assertEqual(sent[-1],{'trnm':'PING'})
        self.assertEqual(report['captureResult'],'COMPLETE')
        self.assertEqual(report['externalRequests']['rest'],1)
        self.assertEqual(report['externalRequests']['wsConnections'],1)
    def test_token_body_auth_whitelist_and_http_error_sanitization(self):
        with patch.dict(sys.modules,{'websockets':Mock(__version__='16.0')}):
            transport=d.LiveTransport({'mode':'demo','appKey':'FAKE_APP','secretKey':'FAKE_SECRET'},d.Budget(1,1))
        with patch.object(transport,'post',return_value={'return_code':0,'token':'FAKE_TOKEN'}) as post:
            self.assertEqual(transport.token(),'FAKE_TOKEN')
            self.assertEqual(post.call_args.args[0],'/oauth2/token')
            self.assertEqual(post.call_args.args[1]['grant_type'],'client_credentials')
        with patch.object(transport.opener,'open',side_effect=RuntimeError('SECRET_CANARY')):
            with self.assertRaisesRegex(DiagnosticError,'HTTP_FAILED') as error:transport.chart('FAKE_TOKEN')
            self.assertNotIn('SECRET_CANARY',str(error.exception))
    def test_official_fixture_replay_and_no_credentials_access(self):
        fixture=Path(__file__).with_name('fixtures')/'kiwoom-diagnostic-mock.json'
        with patch.object(d,'private_credentials') as credentials,patch.object(d,'LiveTransport') as transport:
            report=d.replay(fixture)
        credentials.assert_not_called();transport.assert_not_called()
        self.assertEqual(report['generations'][0]['productEligibilityEvidence'],'SUPPORTED')
        self.assertEqual(report['generations'][1]['productEligibilityEvidence'],'INCONCLUSIVE')


if __name__=='__main__':unittest.main()
