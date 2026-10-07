"""Linux host operations. No AWS resources created; SSM/S3 calls require explicit commands.

Secrets never go to stdout, command arguments or Compose environment. Python 3 stdlib + AWS CLI.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


class RestoreFailure(RuntimeError):
    """Safe public failure; raw diagnostics stay in the restricted data root."""


def wait_sql_ready(cmd, service, timeout_seconds=60):
    """Retry ONLY a read-only readiness probe; never retry pg_restore."""
    if service not in ('postgres', 'ai-postgres') or not 0 < timeout_seconds <= 120:
        raise ValueError('Invalid bounded SQL readiness request')
    deadline = time.monotonic() + timeout_seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RestoreFailure('SQL readiness deadline exceeded; restore not started')
        try:
            run(cmd + ['exec', '-T', service, 'sh', '/postgres-readiness.sh'],
                timeout=min(5, remaining))
            return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RestoreFailure('SQL readiness deadline exceeded; restore not started') from None
            # Probe cadence inside the deadline, not an assumed startup delay.
            time.sleep(min(.25, remaining))


def restore_dump(cmd, service, db, dump, data):
    evidence = Path(data) / '.restore-diagnostics'
    evidence.mkdir(mode=0o700, exist_ok=True)
    if os.name == 'posix': os.chmod(evidence, 0o700)
    path = evidence / (service + '.stderr')
    # Exclusive create: never follow/overwrite an existing diagnostic file.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    began = dt.datetime.now(dt.timezone.utc).isoformat()
    try:
        with os.fdopen(fd, 'wb') as diagnostic, Path(dump).open('rb') as payload:
            run(cmd + ['exec', '-T', service, 'pg_restore', '-U', db, '-d', db,
                       '--exit-on-error', '--no-owner'], stdin=payload,
                stdout=subprocess.DEVNULL, stderr=diagnostic)
    except subprocess.CalledProcessError as failure:
        # Do not print stderr or exception argv (database data may be present).
        metadata = {'service': service, 'database': db, 'returnCode': failure.returncode,
                    'startedAt': began, 'stderrFile': path.name, 'restoreRetried': False}
        meta_fd = os.open(evidence / (service + '.json'), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(meta_fd, 'w', encoding='utf-8') as output:
            json.dump(metadata, output)
        raise RestoreFailure('Restore failed; inspect restricted .restore-diagnostics; no retry performed') from None

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
REQUIRED = {"DB_PASSWORD", "AI_DB_PASSWORD", "JWT_SECRET", "ADMIN_LOGIN_ID", "ADMIN_PASSWORD",
            "OPERATOR_PRIVATE_KEY", "PRICE_SIGNER_PRIVATE_KEY"}
OPTIONAL = {"OPENAI_API_KEY"}

def run(argv, **kwargs):
    # Never echo argv/exception output: subprocess may contain service diagnostics.
    if not kwargs.get('capture_output'):
        kwargs.setdefault('stdout', subprocess.PIPE)
        kwargs.setdefault('stderr', subprocess.PIPE)
    return subprocess.run(argv, check=True, **kwargs)

def properties_escape(value):
    def unicode_escape(c):
        encoded = c.encode('utf-16-be')
        return ''.join('\\u%04x' % int.from_bytes(encoded[i:i+2], 'big') for i in range(0, len(encoded), 2))
    return ''.join(unicode_escape(c) if ord(c) > 127 else
                   {'\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t', '=': '\\=',
                    ':': '\\:', '#': '\\#', '!': '\\!', ' ': '\\ '}.get(c, c) for c in value)

def validate_secrets(values):
    if not REQUIRED <= values.keys() or values.keys() - REQUIRED - OPTIONAL:
        raise ValueError('Secret names do not match approved allowlist')
    for key, value in values.items():
        if not isinstance(value, str) or not value or '\x00' in value:
            raise ValueError('Invalid secret value')
    if len(values['JWT_SECRET'].encode()) < 32 or not 12 <= len(values['ADMIN_PASSWORD'].encode()) <= 72:
        raise ValueError('JWT/admin secret too short')
    if not re.fullmatch(r'[a-zA-Z0-9_-]{4,30}', values['ADMIN_LOGIN_ID']):
        raise ValueError('Invalid admin login id')
    if any(len(values[k]) < 24 or '\n' in values[k] or '\r' in values[k]
           for k in ('DB_PASSWORD', 'AI_DB_PASSWORD')):
        raise ValueError('DB passwords must be single-line and at least 24 characters')
    for key in ('OPERATOR_PRIVATE_KEY', 'PRICE_SIGNER_PRIVATE_KEY'):
        if not re.fullmatch(r'0x[0-9a-fA-F]{64}', values[key]) or not 0 < int(values[key], 16) < 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141:
            raise ValueError('Invalid private key')
    if values['OPERATOR_PRIVATE_KEY'].lower() == values['PRICE_SIGNER_PRIVATE_KEY'].lower():
        raise ValueError('Operator and signer must be different')

def ssm_values(prefix, region):
    if not re.fullmatch(r'/[a-zA-Z0-9_/-]+/', prefix):
        raise ValueError('Prefix must be a dedicated namespace ending in slash')
    # Explicit names, not recursive path access: no neighboring secrets are collected.
    names = [prefix + key for key in sorted(REQUIRED | OPTIONAL)]
    output = run(['aws', 'ssm', 'get-parameters', '--names', *names, '--with-decryption',
                  '--region', region, '--output', 'json', '--no-cli-pager'], capture_output=True).stdout
    result = json.loads(output)
    values = {}
    for param in result['Parameters']:
        if param['Type'] != 'SecureString' or param['Name'] not in names:
            raise ValueError('Only exact SecureString parameters are accepted')
        values[param['Name'][len(prefix):]] = param['Value']
    validate_secrets(values)
    return values

def materialize(values, runtime):
    validate_secrets(values)
    runtime = Path(runtime).resolve()
    runtime.mkdir(parents=True, exist_ok=True, mode=0o750)
    # New version directory + atomic symlink switch. Recreate containers after rotation.
    version = runtime / ('release-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    version.mkdir(mode=0o750)
    app = ''.join(k + '=' + properties_escape(v) + '\n' for k, v in sorted(values.items()))
    app += 'AI_ENABLED=' + str(bool(values.get('OPENAI_API_KEY'))).lower() + '\n'
    app += 'AI_TOOLS_ENABLED=true\nAI_AGENT_ENABLED=true\nAI_SKILLS_ENABLED=true\n'
    content = {'application.properties': app, 'db-password': values['DB_PASSWORD'],
               'ai-db-password': values['AI_DB_PASSWORD'], 'acceptance.conf': 'deny all;\n'}
    os.umask(0o077)
    for name, text in content.items():
        path = version / name
        path.write_text(text, encoding='utf-8', newline='\n')
        if os.name == 'posix':
            os.chown(path, os.getuid(), 10001)
            os.chmod(path, 0o640 if name == 'application.properties' else 0o644 if name == 'acceptance.conf' else 0o600)
    link = runtime / 'current.next'
    if link.exists() or link.is_symlink():
        raise ValueError('Stale current.next exists; inspect before retry')
    link.symlink_to(version.name, target_is_directory=True)
    link.replace(runtime / 'current')

def artifact(destination):
    destination = Path(destination).resolve()
    if destination.exists():
        raise ValueError('Artifact destination already exists; use a new release path')
    manifest = json.loads((REPO / 'docs/ai/ingest-manifest.json').read_text(encoding='utf-8'))
    if manifest['version'] != 1 or not 1 <= len(manifest['documents']) <= 50:
        raise ValueError('Unsupported manifest')
    verified = []
    seen = set()
    knowledge_root = (REPO / 'docs/ai-knowledge').resolve()
    for entry in manifest['documents']:
        name = entry['path']
        if not re.fullmatch(r'[a-z0-9-]+\.md', name) or name in seen:
            raise ValueError('Invalid artifact path')
        seen.add(name)
        source = (knowledge_root / name).resolve()
        if not source.is_relative_to(knowledge_root) or source.stat().st_size > 100_000:
            raise ValueError('Artifact source outside knowledge boundary')
        text = source.read_text(encoding='utf-8').replace('\r\n', '\n').replace('\r', '\n')
        if hashlib.sha256(text.encode()).hexdigest() != entry['sha256']:
            raise ValueError('Knowledge approval hash mismatch')
        if '\nstatus: active\n' not in text or '\nversion: ' + entry['documentVersion'] + '\n' not in text:
            raise ValueError('Knowledge is not approved active version')
        verified.append((name, text))
    (destination / 'documents').mkdir(parents=True)
    for name, text in verified:
        (destination / 'documents' / name).write_text(text, encoding='utf-8', newline='\n')
    (destination / 'ingest-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    release = {'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'documents': len(verified),
               'manifestSha256': hashlib.sha256((destination / 'ingest-manifest.json').read_bytes()).hexdigest()}
    (destination / 'release.json').write_text(json.dumps(release, indent=2) + '\n', encoding='utf-8')
    print('Verified knowledge artifact:', len(verified), 'documents')

def compose(args):
    if not re.fullmatch(r'exchange-(public|ops-[a-z0-9-]+|restore-[a-z0-9-]+)', args.project):
        raise ValueError('Explicit deployment/isolated project required')
    return ['docker', 'compose', '--project-name', args.project, '--env-file', args.env_file,
            '-f', str(ROOT / 'compose.yml')]

def verify_layout(args):
    # Check the EFFECTIVE Compose sources (including ambient env overrides), not just CLI paths.
    data = Path(args.data_root).resolve()
    config = json.loads(run(compose(args) + ['config', '--format', 'json'], capture_output=True).stdout)
    expected = {'postgres': ('/var/lib/postgresql/data', data / 'trade'),
                'ai-postgres': ('/var/lib/postgresql/data', data / 'ai'),
                'anvil': ('/data', data / 'chain')}
    for service, (target, source) in expected.items():
        mounts = config['services'][service]['volumes']
        found = [m for m in mounts if m['target'] == target]
        if len(found) != 1 or Path(found[0]['source']).resolve() != source:
            raise ValueError('Effective Compose data root differs from requested checkpoint root')
    return config

def mount_path(value):
    # Docker Desktop reports Linux host-mount prefixes for a Windows bind source.
    if os.name == 'nt':
        match = re.fullmatch(r'/(?:run/desktop/mnt/host|host_mnt)/([a-zA-Z])/(.*)', value)
        if match: value = match[1] + ':/' + match[2]
    return os.path.normcase(str(Path(value).resolve()))

def verify_running_mounts(args):
    expected = {'postgres': ('/var/lib/postgresql/data', Path(args.data_root).resolve() / 'trade'),
                'ai-postgres': ('/var/lib/postgresql/data', Path(args.data_root).resolve() / 'ai'),
                'anvil': ('/data', Path(args.data_root).resolve() / 'chain')}
    for service, (target, source) in expected.items():
        cid = run(compose(args) + ['ps', '-q', service], capture_output=True).stdout.decode().strip()
        if not re.fullmatch(r'[0-9a-f]{12,64}', cid):
            raise ValueError('Checkpoint requires exactly one running container per data service')
        info = json.loads(run(['docker', 'inspect', cid], capture_output=True).stdout)[0]
        if info['Config']['Labels'].get('com.docker.compose.project') != args.project:
            raise ValueError('Container does not belong to checkpoint project')
        mounts = [m for m in info['Mounts'] if m['Destination'] == target]
        if len(mounts) != 1 or mounts[0]['Type'] != 'bind' or mount_path(mounts[0]['Source']) != mount_path(str(source)):
            raise ValueError('Running container mount differs from requested checkpoint root')

def backup(args):
    verify_layout(args)
    verify_running_mounts(args)
    target = Path(args.destination).resolve()
    if target.exists():
        raise ValueError('Backup destination already exists')
    chain = Path(args.data_root).resolve() / 'chain/state.json'
    release = Path(args.data_root).resolve() / 'release/chain.properties'
    cmd = compose(args)
    # Whole ingress stopped: no new REST/WS orders. Explicit pending check after graceful backend stop.
    run(cmd + ['stop', 'web', 'backend'])
    count = run(cmd + ['exec', '-T', 'postgres', 'psql', '-U', 'exchange', '-d', 'exchange', '-tAc',
                     "SELECT (SELECT count(*) FROM orders WHERE status IN ('REQUESTED','PENDING_ONCHAIN','REVIEW_REQUIRED')) + (SELECT count(*) FROM blockchain_transactions WHERE status IN ('CREATED','SIGNED','SUBMITTED','REVIEW_REQUIRED')) + (SELECT count(*) FROM user_balances WHERE locked_amount <> 0)"], capture_output=True).stdout.decode().strip()
    if count != '0':
        raise ValueError('Unresolved orders exist; services remain stopped. Inspect and resolve before checkpoint')
    run(cmd + ['stop', 'anvil'])
    if not chain.is_file() or not release.is_file():
        raise ValueError('Missing chain/release state; services remain stopped')
    target.mkdir(parents=True, mode=0o700)
    for service, db in [('postgres', 'exchange'), ('ai-postgres', 'exchange_ai')]:
        with (target / (db + '.dump')).open('wb') as output:
            run(cmd + ['exec', '-T', service, 'pg_dump', '-U', db, '-d', db, '-Fc'], stdout=output)
    shutil.copy2(chain, target / 'state.json')
    shutil.copy2(release, target / 'chain.properties')
    shutil.copy2(args.env_file, target / 'release.env')  # NON-secret image/version/domain configuration only.
    files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in target.iterdir() if p.is_file()}
    (target / 'checkpoint.json').write_text(json.dumps({'project': args.project, 'sha256': files,
            'createdAt': dt.datetime.now(dt.timezone.utc).isoformat()}, indent=2) + '\n', encoding='utf-8')
    print('Consistent checkpoint saved; services deliberately remain stopped until operator resumes')

def restore(args):
    if not args.project.startswith('exchange-restore-'):
        raise ValueError('Restore permitted only into exchange-restore-* isolated projects')
    verify_layout(args)
    source = Path(args.source).resolve()
    checkpoint = json.loads((source / 'checkpoint.json').read_text(encoding='utf-8'))
    for name, digest in checkpoint['sha256'].items():
        if Path(name).name != name or hashlib.sha256((source / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Backup integrity check failed')
    data = Path(args.data_root).resolve()
    if data.exists() and any(data.iterdir()):
        raise ValueError('Restore requires a fresh empty data root; no overwrite')
    cmd = compose(args)
    services = run(cmd + ['ps', '--all', '--format', 'json'], capture_output=True).stdout.strip()
    if services not in (b'', b'[]'):
        raise ValueError('Restore project must have no existing containers')
    (data / 'chain').mkdir(parents=True)
    (data / 'release').mkdir()
    shutil.copy2(source / 'state.json', data / 'chain/state.json')
    shutil.copy2(source / 'chain.properties', data / 'release/chain.properties')
    if os.name == 'posix':
        os.chown(data / 'chain', 10001, 10001)
        os.chmod(data / 'chain', 0o700)
        os.chown(data / 'chain/state.json', 10001, 10001)
        os.chmod(data / 'chain/state.json', 0o600)
        os.chown(data / 'release/chain.properties', os.getuid(), 10001)
        os.chmod(data / 'release/chain.properties', 0o640)
    run(cmd + ['up', '-d', '--wait', 'postgres', 'ai-postgres'])
    # Both databases must pass SQL before either dump is applied.
    for service in ('postgres', 'ai-postgres'):
        wait_sql_ready(cmd, service)
    for service, db in [('postgres', 'exchange'), ('ai-postgres', 'exchange_ai')]:
        wait_sql_ready(cmd, service)
        restore_dump(cmd, service, db, source / (db + '.dump'), data)
    print('DBs and chain checkpoint restored into isolated root. Verify release/keys/chain before starting ingress.')

def main():
    parser = argparse.ArgumentParser()
    subs = parser.add_subparsers(dest='command', required=True)
    p = subs.add_parser('ssm'); p.add_argument('--prefix', required=True); p.add_argument('--region', required=True); p.add_argument('--runtime', required=True)
    p = subs.add_parser('artifact'); p.add_argument('--destination', required=True)
    for name in ('backup', 'restore'):
        p = subs.add_parser(name)
        p.add_argument('--project', required=True); p.add_argument('--env-file', required=True); p.add_argument('--data-root', required=True)
        p.add_argument('--destination' if name == 'backup' else '--source', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'ssm': materialize(ssm_values(args.prefix, args.region), args.runtime)
        elif args.command == 'artifact': artifact(args.destination)
        elif args.command == 'backup': backup(args)
        elif args.command == 'restore': restore(args)
    except Exception:
        print('Operation failed; secret/service output suppressed. Inspect prerequisites and private host diagnostics.', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
