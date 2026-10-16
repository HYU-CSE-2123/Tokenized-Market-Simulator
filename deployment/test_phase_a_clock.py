import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock,patch
import phase_a_clock as clock
import importlib.util
import tempfile

spec=importlib.util.spec_from_file_location('phase_a_fixture',Path(__file__).with_name('phase-a.py'))
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

class ClockDiagnosticTest(unittest.TestCase):
    def test_roundtrip_uses_monotonic_high_resolution_timer(self):
        with patch.object(clock.time,'time',side_effect=[100,100.2]),patch.object(clock.time,'perf_counter',side_effect=[10,10.2]):
            result=clock.sample(lambda:97)
        self.assertAlmostEqual(result['offsetSeconds'],-3.1)
        self.assertAlmostEqual(result['uncertaintySeconds'],.1)

    def test_blocked_preflight_is_recorded_each_time_without_business_calls(self):
        commands=[];records=[]
        java=Mock();java.stdout=iter(['READY\n']+['100\n']*5)
        def execute(args,**kwargs):
            commands.append(args)
            value=b'' if args[0]=='wsl' else (b'{"timestamp":"0x1"}' if 'eth_getBlockByNumber' in args else b'100')
            return SimpleNamespace(stdout=value)
        guard=Mock(side_effect=RuntimeError('BLOCKED_CLOCK_SKEW'))
        with patch.object(clock.subprocess,'Popen',return_value=java),patch.object(clock.subprocess,'run',side_effect=execute),patch.object(clock.time,'sleep'),patch('builtins.print'):
            clock.probe(Path('session'),{'runId':'reserve-12345678','name':'new-only'}, {},
                Mock(return_value={'hostEpochBefore':100,'hostEpochAfter':100.2,'dbEpoch':97,'dbMinusHostSeconds':-3.1}),
                guard,lambda p,s:records.append(json.loads(s)))
        self.assertEqual(guard.call_count,5)
        self.assertEqual(len(records),1)
        self.assertTrue(all(s['existingPreflight']=='BLOCKED_CLOCK_SKEW' for s in records[0]['samples']))
        self.assertFalse(records[0]['authoritativeUtcVerified'])
        self.assertFalse(records[0]['wslDockerAlreadyRunning'])
        self.assertFalse(any(any(x in ('start','stop','shutdown','restart','resync','eth_sendRawTransaction') for x in command) for command in commands))
        java.stdin.close.assert_called_once()

    def test_clock_only_fixture_never_deploys_initializes_schema_or_runs_e2e(self):
        commands=[]
        def execute(session,args,step,*unused,**kwargs):
            commands.append(args)
            if 'inspect' in args:
                name=args[2]
                return name.rsplit('-',1)[0]
            return ''
        with tempfile.TemporaryDirectory() as directory:
            session=Path(directory);(session/'fixtures').mkdir()
            with patch.object(fixture,'free_port',side_effect=[30001,30002]),patch.object(fixture,'execute',side_effect=execute),patch.object(fixture,'inspect_owned'),patch.object(fixture,'clean_env',return_value={}),patch.object(fixture.subprocess,'run',return_value=SimpleNamespace(returncode=0)),patch.object(clock,'probe') as probe,patch.object(fixture,'java_test') as tests:
                fixture.fixture(session,{},'reserve',clock_only=True)
            probe.assert_called_once();tests.assert_not_called()
            self.assertFalse(any('--rm' in command for command in commands))
            self.assertFalse(any(any('CREATE TABLE' in part or 'INSERT INTO' in part for part in command) for command in commands))
            self.assertEqual(sum('stop' in command for command in commands),2)

if __name__=='__main__':unittest.main()
