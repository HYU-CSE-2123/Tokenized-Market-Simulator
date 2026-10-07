"""Offline namespace/secret guards only; never contact Docker/Toss/OpenAI."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('acceptance.py'))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)


class AcceptanceGuards(unittest.TestCase):
    def test_imports_only_toss_credentials(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'input.properties'
            path.write_text('TOSS_CLIENT_ID="id"\nTOSS_CLIENT_SECRET=secret\nDB_URL=OLD\n'
                            'OPERATOR_PRIVATE_KEY=OLD\nOPENAI_API_KEY=DO_NOT_IMPORT\n', encoding='utf-8')
            self.assertEqual(a.toss_credentials(path), {'TOSS_CLIENT_ID': 'id', 'TOSS_CLIENT_SECRET': 'secret'})

    def test_missing_toss_credentials_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'input'; path.write_text('TOSS_CLIENT_ID=id\n')
            with self.assertRaises(ValueError): a.toss_credentials(path)

    def test_keys_valid_and_independent(self):
        keys = [a.key() for _ in range(8)]
        self.assertEqual(len(set(keys)), 8)
        for value in keys:
            self.assertEqual(len(value), 66)
            self.assertTrue(0 < int(value, 16) < a.ORDER)

    def test_private_write_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'secret'; a.write_private(path, 'first')
            with self.assertRaises(FileExistsError): a.write_private(path, 'second')
            self.assertEqual(path.read_text(), 'first')

    def test_old_root_rejected(self):
        with self.assertRaises(ValueError): a.validate_run(a.ROOT / 'runtime/local-20261007160704')

    def test_project_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(a, 'RUNTIME', Path(folder)):
            root = Path(folder) / 'acceptance-20261008000000-aabbcc'; root.mkdir()
            (root / 'run.json').write_text(json.dumps({'project': 'exchange-public'}))
            with self.assertRaises(ValueError): a.validate_run(root)

    def test_clean_env_overrides_ambient_mounts(self):
        with patch.dict(os.environ, {'ACCEPTANCE_ROOT': 'old-root', 'BACKEND_IMAGE': 'old', 'COMPOSE_FILE': 'old'}):
            env = a.clean_env(Path('fresh'), {'images': {'BACKEND_IMAGE': 'fresh-image'}})
        self.assertEqual(env['ACCEPTANCE_ROOT'], 'fresh')
        self.assertEqual(env['BACKEND_IMAGE'], 'fresh-image')
        self.assertNotIn('COMPOSE_FILE', env)

    def test_rpc_writes_rejected_before_network(self):
        for method in ('anvil_reset', 'anvil_loadState', 'eth_sendRawTransaction', 'evm_mine'):
            with self.assertRaises(ValueError): a.rpc(method, [])

    def test_service_allowlist_rejects_old_container_name(self):
        with self.assertRaises(ValueError): a.container_id(Path('.'), {}, 'exchange-postgres')

    def test_compose_ai_disabled_and_not_public(self):
        content = (a.ROOT / 'acceptance-compose.yml').read_text()
        self.assertIn('SPRING_PROFILES_ACTIVE: acceptance', content)
        self.assertIn('PRICE_PROVIDER: toss', content)
        for name in ('AI_ENABLED', 'AI_AGENT_ENABLED', 'AI_SKILLS_ENABLED', 'AI_AUTO_DIAGNOSIS_ENABLED'):
            self.assertIn(name + ': "false"', content)
        self.assertNotIn('external: true', content)
        self.assertNotIn('container_name:', content)
        self.assertNotIn('backend/.env:', content)


if __name__ == '__main__': unittest.main()
