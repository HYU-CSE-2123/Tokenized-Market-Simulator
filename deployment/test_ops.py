import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ops', Path(__file__).with_name('ops.py'))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)

def values():
    return {'DB_PASSWORD': 'db-' + 'x' * 32, 'AI_DB_PASSWORD': 'ai-' + 'y' * 32,
            'JWT_SECRET': 'z' * 64, 'ADMIN_LOGIN_ID': 'demo_admin', 'ADMIN_PASSWORD': 'example-' + 'a' * 24,
            'OPERATOR_PRIVATE_KEY': '0x' + '1' * 64, 'PRICE_SIGNER_PRIVATE_KEY': '0x' + '2' * 64}

class OpsTests(unittest.TestCase):
    def test_sql_readiness_retries_probe_only_and_returns_on_sql_success(self):
        failure = subprocess.CalledProcessError(1, ['probe'])
        with patch.object(ops, 'run', side_effect=[failure, failure, None]) as call, \
             patch.object(ops.time, 'sleep'):
            ops.wait_sql_ready(['docker', 'compose'], 'postgres')
        self.assertEqual(3, call.call_count)
        for invocation in call.call_args_list:
            self.assertIn('/postgres-readiness.sh', invocation.args[0])
            self.assertNotIn('pg_restore', invocation.args[0])
            self.assertLessEqual(invocation.kwargs['timeout'], 5)
    def test_sql_readiness_deadline_is_bounded_and_public_error_safe(self):
        failure = subprocess.CalledProcessError(1, ['secret-canary'])
        with patch.object(ops.time, 'monotonic', side_effect=[0, 0, 61]), \
             patch.object(ops, 'run', side_effect=failure) as call:
            with self.assertRaises(ops.RestoreFailure) as raised:
                ops.wait_sql_ready([], 'ai-postgres')
        self.assertEqual(1, call.call_count)
        self.assertNotIn('secret-canary', str(raised.exception))
    def test_sql_readiness_invalid_scope_rejected(self):
        with patch.object(ops, 'run') as call:
            for service, timeout in [('backend', 60), ('postgres', 0), ('postgres', 121)]:
                with self.assertRaises(ValueError): ops.wait_sql_ready([], service, timeout)
            call.assert_not_called()
    def test_both_databases_ready_before_any_restore_and_ai_failure_blocks_dump(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = root / 'source'; source.mkdir()
            for name in ('state.json', 'chain.properties'): (source / name).write_text('fixture')
            (source / 'checkpoint.json').write_text('{"sha256":{}}')
            args = SimpleNamespace(project='exchange-restore-readiness', env_file='fixture.env',
                                   data_root=str(root / 'data'), source=str(source))
            def ready(cmd, service):
                if service == 'ai-postgres': raise ops.RestoreFailure('SQL unavailable')
            with patch.object(ops, 'verify_layout'), \
                 patch.object(ops, 'run', return_value=SimpleNamespace(stdout=b'')), \
                 patch.object(ops, 'wait_sql_ready', side_effect=ready) as probe, \
                 patch.object(ops, 'restore_dump') as dump:
                with self.assertRaises(ops.RestoreFailure): ops.restore(args)
            self.assertEqual(['postgres', 'ai-postgres'], [call.args[1] for call in probe.call_args_list])
            dump.assert_not_called()
    def test_restore_stderr_private_and_not_in_public_exception_no_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); dump = root / 'dump'; dump.write_bytes(b'fixture')
            def failing(command, **kwargs):
                kwargs['stderr'].write(b'FATAL: fixture-secret-canary\n')
                raise subprocess.CalledProcessError(1, command)
            with patch.object(ops, 'run', side_effect=failing) as call:
                with self.assertRaises(ops.RestoreFailure) as raised:
                    ops.restore_dump(['docker', 'compose'], 'postgres', 'exchange', dump, root)
            self.assertEqual(1, call.call_count)
            self.assertNotIn('fixture-secret-canary', str(raised.exception))
            evidence = root / '.restore-diagnostics'
            self.assertIn(b'fixture-secret-canary', (evidence / 'postgres.stderr').read_bytes())
            meta = json.loads((evidence / 'postgres.json').read_text())
            self.assertEqual(1, meta['returnCode']); self.assertFalse(meta['restoreRetried'])
            if ops.os.name == 'posix':
                self.assertEqual(0o700, evidence.stat().st_mode & 0o777)
                for path in evidence.iterdir(): self.assertEqual(0o600, path.stat().st_mode & 0o777)
    def test_restore_diagnostics_refuse_existing_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); evidence = root / '.restore-diagnostics'; evidence.mkdir()
            path = evidence / 'postgres.stderr'; path.write_text('keep')
            with patch.object(ops, 'run') as call:
                with self.assertRaises(FileExistsError):
                    ops.restore_dump([], 'postgres', 'exchange', root / 'unused', root)
            call.assert_not_called(); self.assertEqual('keep', path.read_text())
    def test_valid_separate_keys(self): ops.validate_secrets(values())
    def test_unknown_and_missing_secrets_fail(self):
        for change in ({'TOSS_CLIENT_SECRET': 'not-allowed'}, {'JWT_SECRET': ''}):
            with self.assertRaises(ValueError): ops.validate_secrets(values() | change)
    def test_same_key_rejected(self):
        with self.assertRaises(ValueError): ops.validate_secrets(values() | {'PRICE_SIGNER_PRIVATE_KEY': '0x' + '1' * 64})
    def test_properties_injection_escaped(self):
        self.assertEqual('bad\\nAI_ENABLED\\=true\\\\', ops.properties_escape('bad\nAI_ENABLED=true\\'))
        self.assertEqual('\\ud83d\\ude00', ops.properties_escape('😀'))
        spec = importlib.util.spec_from_file_location('init_ai', Path(__file__).with_name('init-ai.py'))
        ai = importlib.util.module_from_spec(spec); spec.loader.exec_module(ai)
        original = '비밀번호 😀 \\=:#\nend'
        self.assertEqual(original, ai.decode_property(ops.properties_escape(original)))
    def test_artifact_only_manifest_files(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'artifact'
            ops.artifact(path)
            manifest = json.loads((path / 'ingest-manifest.json').read_text())
            self.assertEqual(9, len(manifest['documents']))
            self.assertEqual({d['path'] for d in manifest['documents']}, {p.name for p in (path / 'documents').iterdir()})
            with self.assertRaises(ValueError): ops.artifact(path)
    def test_hash_mismatch_no_partial_artifact(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'artifact'
            with patch.object(ops.hashlib, 'sha256') as sha:
                sha.return_value.hexdigest.return_value = 'wrong'
                with self.assertRaises(ValueError): ops.artifact(path)
            self.assertFalse(path.exists())
    def test_ssm_exact_names_not_recursive(self):
        class Result:
            stdout = json.dumps({'Parameters': [{'Name': '/demo/public/' + k, 'Value': v, 'Type': 'SecureString'} for k, v in values().items()]}).encode()
        with patch.object(ops, 'run', return_value=Result()) as run:
            self.assertEqual(values(), ops.ssm_values('/demo/public/', 'ap-northeast-2'))
            command = run.call_args.args[0]
            self.assertIn('get-parameters', command)
            self.assertNotIn('--recursive', command)
            self.assertIn('--with-decryption', command)
    def test_plain_ssm_rejected(self):
        class Result:
            stdout = b'{"Parameters":[{"Name":"/demo/public/JWT_SECRET","Type":"String","Value":"x"}]}'
        with patch.object(ops, 'run', return_value=Result()):
            with self.assertRaises(ValueError): ops.ssm_values('/demo/public/', 'ap-northeast-2')
    def test_restore_existing_data_rejected(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'data').mkdir(); (root / 'data/keep').write_text('user')
            (root / 'backup').mkdir(); (root / 'backup/checkpoint.json').write_text('{"sha256":{}}')
            args = SimpleNamespace(project='exchange-restore-test', source=str(root / 'backup'), data_root=str(root / 'data'))
            with patch.object(ops, 'verify_layout'):
                with self.assertRaises(ValueError): ops.restore(args)
            self.assertEqual('user', (root / 'data/keep').read_text())
    def test_restore_public_forbidden(self):
        from types import SimpleNamespace
        with self.assertRaises(ValueError): ops.restore(SimpleNamespace(project='exchange-public'))
    def test_running_mount_mismatch_rejected_before_stop(self):
        from types import SimpleNamespace
        args = SimpleNamespace(project='exchange-public', env_file='release.env', data_root='/new-root', destination='/unused')
        def fake_run(command, **kwargs):
            if 'inspect' in command:
                return SimpleNamespace(stdout=json.dumps([{'Config': {'Labels': {'com.docker.compose.project': args.project}},
                     'Mounts': [{'Destination':'/var/lib/postgresql/data','Type':'bind','Source':'/old-root/trade'}]}]).encode())
            if 'ps' in command: return SimpleNamespace(stdout=b'a' * 64)
            raise AssertionError('No stop/dump/mutation allowed before mount verification')
        with patch.object(ops,'verify_layout'), patch.object(ops,'run',side_effect=fake_run):
            with self.assertRaises(ValueError): ops.backup(args)
    @unittest.skipUnless(ops.os.name == 'posix' and ops.os.getuid() == 0, 'POSIX root filesystem verification')
    def test_linux_restore_state_and_backend_file_permissions(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = root / 'source'; source.mkdir()
            for name in ('state.json','chain.properties','exchange.dump','exchange_ai.dump'):
                (source / name).write_text('fixture')
            (source / 'checkpoint.json').write_text(json.dumps({'sha256': {p.name: ops.hashlib.sha256(p.read_bytes()).hexdigest() for p in source.iterdir()}}))
            args = SimpleNamespace(project='exchange-restore-posix',env_file='restore.env',data_root=str(root / 'data'),source=str(source))
            with patch.object(ops,'verify_layout'), patch.object(ops,'run',return_value=SimpleNamespace(stdout=b'')):
                ops.restore(args)
            directory = root / 'data/chain'; state = directory / 'state.json'; props = root / 'data/release/chain.properties'
            self.assertEqual((10001,10001,0o700), (directory.stat().st_uid,directory.stat().st_gid,directory.stat().st_mode & 0o777))
            self.assertEqual((10001,10001,0o600), (state.stat().st_uid,state.stat().st_gid,state.stat().st_mode & 0o777))
            self.assertEqual((0,10001,0o640), (props.stat().st_uid,props.stat().st_gid,props.stat().st_mode & 0o777))
    @unittest.skipUnless(ops.os.name == 'posix' and ops.os.getuid() == 0, 'POSIX root filesystem verification')
    def test_linux_secret_permissions_and_atomic_version_rotation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'runtime'
            ops.materialize(values(), root)
            first = (root / 'current').resolve()
            self.assertEqual((10001,0o640), ((first / 'application.properties').stat().st_gid, (first / 'application.properties').stat().st_mode & 0o777))
            self.assertEqual(0o600, (first / 'db-password').stat().st_mode & 0o777)
            self.assertEqual('deny all;\n', (first / 'acceptance.conf').read_text())
            ops.materialize(values() | {'JWT_SECRET':'new-' + 'q'*60}, root)
            second = (root / 'current').resolve()
            self.assertNotEqual(first, second)
            self.assertTrue(first.is_dir())
            self.assertIn('JWT_SECRET=new-', (second / 'application.properties').read_text())

if __name__ == '__main__': unittest.main()
