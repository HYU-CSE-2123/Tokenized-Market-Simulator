import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('phase_a',Path(__file__).with_name('phase-a.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)

class PhaseAGuardTest(unittest.TestCase):
    def test_clean_environment_drops_credentials_and_all_live_gates(self):
        with patch.dict(os.environ,{'OPENAI_API_KEY':'never-inherit','TOSS_CLIENT_SECRET':'never-inherit',
                'AI_PHASE8_LIVE_EVALUATION':'true','AI_TOOL_POSTGRES_TESTS':'true',
                'RPC_URL':'http://127.0.0.1:8545','DB_URL':'jdbc:postgresql://localhost/exchange',
                'SPRING_CONFIG_IMPORT':'file:backend/.env','OPERATOR_PRIVATE_KEY':'never-inherit'}):
            env=a.clean_env(Path('new-session'))
            for key in ('OPENAI_API_KEY','TOSS_CLIENT_SECRET','RPC_URL','DB_URL','OPERATOR_PRIVATE_KEY'):
                self.assertNotIn(key,env)
            self.assertEqual(env['AI_PHASE8_LIVE_EVALUATION'],'false')
            self.assertEqual(env['AI_TOOL_POSTGRES_TESTS'],'false')
            self.assertEqual(env['SPRING_CONFIG_IMPORT'],'')
            self.assertIn('-Dhttps.proxyPort=1',env['JAVA_TOOL_OPTIONS'])
    def test_session_rejects_old_or_outside_runtime(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):a.load_session(folder)
    def test_session_identity_cannot_be_relabelled(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);s=root/'runtime/phase-a-20261013000000-12345678';s.mkdir(parents=True)
            (s/'session.json').write_text(json.dumps({'session':'wrong','externalProviders':False}))
            with patch.object(a,'ROOT',root),self.assertRaises(ValueError):a.load_session(s)
    def test_container_wrong_owner_prevents_test(self):
        data=[{'Config':{'Labels':{'phase-a.fixture':'wrong'}},'State':{'Running':True}}]
        meta={'name':'new-only','dbPort':30001,'rpcPort':30002}
        with patch.object(a,'execute',return_value=json.dumps(data)),self.assertRaises(RuntimeError):
            a.inspect_owned(Path('new'),meta)
    def test_container_public_port_prevents_test(self):
        meta={'name':'new-only','dbPort':30001,'rpcPort':30002}
        data=[{'Config':{'Labels':{'phase-a.fixture':'new-only'}},'State':{'Running':True},
               'HostConfig':{'PortBindings':{'5432/tcp':[{'HostIp':'0.0.0.0','HostPort':'30001'}]}}}]
        with patch.object(a,'execute',return_value=json.dumps(data)),self.assertRaises(RuntimeError):a.inspect_owned(Path('new'),meta)
    def test_skip_is_not_counted_as_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            d=Path(folder);(d/'TEST-x.xml').write_text('<testsuite tests="5" failures="1" errors="0" skipped="2"/>')
            self.assertEqual(a.aggregate(d),{'tests':5,'failures':1,'errors':0,'skipped':2,'passed':2})
    def test_clock_alignment_retains_existing_two_second_limit(self):
        a.require_clock_alignment({'hostEpochBefore':10,'hostEpochAfter':10.2,'dbMinusHostSeconds':1.5})
        with self.assertRaisesRegex(RuntimeError,'BLOCKED_CLOCK_SKEW'):
            a.require_clock_alignment({'hostEpochBefore':10,'hostEpochAfter':10.2,'dbMinusHostSeconds':-3.05})
    def test_clock_uncertainty_is_fail_closed(self):
        with self.assertRaises(RuntimeError):
            a.require_clock_alignment({'hostEpochBefore':10,'hostEpochAfter':10.6,'dbMinusHostSeconds':1.8})
    def test_explicit_dirty_child_env_cannot_claim_sanitized(self):
        with patch.object(a.subprocess,'run') as run,self.assertRaises(ValueError):
            a.execute(Path('unused'),['ignored'],'ignored',env={'OPENAI_API_KEY':'do-not-leak'})
        run.assert_not_called()

if __name__=='__main__':unittest.main()
