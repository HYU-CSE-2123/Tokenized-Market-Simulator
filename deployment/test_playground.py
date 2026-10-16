import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('playground',Path(__file__).with_name('playground.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)


class PlaygroundTests(unittest.TestCase):
    def test_environment_does_not_inherit_credentials_or_compose_overrides(self):
        with patch.dict(os.environ,{'TOSS_CLIENT_SECRET':'canary','OPENAI_API_KEY':'canary',
                    'OPERATOR_PRIVATE_KEY':'canary','SPRING_CONFIG_IMPORT':'old.env','COMPOSE_FILE':'old.yml'}):
            env=p.clean_env()
            for key in ('TOSS_CLIENT_SECRET','OPENAI_API_KEY','OPERATOR_PRIVATE_KEY','SPRING_CONFIG_IMPORT','COMPOSE_FILE'):
                self.assertNotIn(key,env)

    def test_private_write_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'evidence.json';p.private(target,'original')
            with self.assertRaises(FileExistsError):p.private(target,'replacement')
            self.assertEqual(target.read_text(),'original')

    def test_old_runtime_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as folder:
            pointer=Path(folder)/'pointer.json';p.private(pointer,'{"run":"'+str(Path(folder)).replace('\\','\\\\')+'"}')
            with patch.object(p,'POINTER',pointer),patch.object(p,'execute') as calls:
                with self.assertRaises(ValueError):p.load_run()
                calls.assert_not_called()

    def test_restart_and_stop_have_no_deploy_or_reset(self):
        import inspect
        body=inspect.getsource(p.main)
        self.assertNotIn('first-deploy',body)
        self.assertNotIn('down',body)
        self.assertNotIn('remove',body)
        self.assertIn('state.json',body)
        self.assertIn('PG_VERSION',body)

    def test_compose_is_local_and_external_features_off(self):
        text=(p.ROOT/'playground-compose.yml').read_text()
        self.assertIn('127.0.0.1:25173:80',text)
        self.assertIn('PRICE_PROVIDER: simulated',text)
        for name in ('AI_ENABLED','AI_AGENT_ENABLED','AI_AUTO_DIAGNOSIS_ENABLED','CONDITIONAL_ORDERS_ENABLED'):
            self.assertIn(name+": 'false'",text)
        self.assertNotIn('TOSS_CLIENT',text)
        self.assertNotIn('KIWOOM_APP',text)

    def test_prepare_refuses_existing_pointer(self):
        with patch.object(p.POINTER.__class__,'exists',return_value=True),patch.object(p,'execute') as calls:
            with self.assertRaises(ValueError):p.prepare()
            calls.assert_not_called()

    def test_incomplete_preparation_never_reinitializes(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);run=root/'runtime/playground-20261014000000-12345678';run.mkdir(parents=True)
            pointer=root/'runtime/pointer.json';p.private(pointer,'{"run":"'+run.as_posix()+'"}')
            p.private(run/'run.json','{"project":"exchange-'+run.name+'","sourceHash":"test"}')
            with patch.object(p,'ROOT',root),patch.object(p,'POINTER',pointer),patch.object(p,'source_hash',return_value='test'),patch.object(p,'prepare') as prep:
                with self.assertRaises(ValueError):p.main(['start'])
                prep.assert_not_called()

    def test_reservation_precedes_mutation_and_is_exclusive(self):
        import inspect
        body=inspect.getsource(p.prepare)
        self.assertLess(body.index('private(POINTER'),body.index("'backend-build'"))
        self.assertLess(body.index('private(POINTER'),body.index("'first-deploy'"))
        with tempfile.TemporaryDirectory() as folder:
            pointer=Path(folder)/'pointer';p.private(pointer,'winner')
            with self.assertRaises(FileExistsError):p.private(pointer,'concurrent loser')
            self.assertEqual(pointer.read_text(),'winner')

if __name__=='__main__':unittest.main()
