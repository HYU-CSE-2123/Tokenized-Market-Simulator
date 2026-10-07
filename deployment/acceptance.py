"""Explicit local acceptance namespace. No restore/reset, paid AI, or old DB/chain writes.

prepare reads ONLY Toss credentials from the nominated input file, generates new secrets,
and deploys once. start/stop never deploy again. All runtime evidence is Git-ignored/private.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
RUNTIME = ROOT / 'runtime'
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PORTS = (5542, 5543, 18545, 18082, 15173)
SERVICES = {'postgres', 'ai-postgres', 'anvil', 'backend'}
ORDER = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
spec = importlib.util.spec_from_file_location('acceptance_ops', ROOT / 'ops.py')
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


def write_private(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as output:
        output.write(text)


def protect_root(path):
    if os.name == 'nt':
        who = subprocess.run(['whoami'], check=True, capture_output=True).stdout.decode().strip()
        subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r',
                        who + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        os.chmod(path, 0o700)


def toss_credentials(source):
    result = {}
    for line in Path(source).read_text(encoding='utf-8-sig').splitlines():
        match = re.fullmatch(r'\s*(TOSS_CLIENT_ID|TOSS_CLIENT_SECRET)\s*=(.*)', line)
        if match:
            result[match[1]] = match[2].strip().strip('"').strip("'")
    if set(result) != {'TOSS_CLIENT_ID', 'TOSS_CLIENT_SECRET'} or any(
            not v or '\x00' in v for v in result.values()):
        raise ValueError('Both Toss credentials are required; no other input values are imported')
    return result


def key():
    return '0x' + format(1 + secrets.randbelow(ORDER - 1), '064x')


def validate_run(path):
    run = Path(path).resolve()
    if run.parent != RUNTIME.resolve() or not re.fullmatch(r'acceptance-\d{14}-[0-9a-f]{6}', run.name):
        raise ValueError('Only dedicated acceptance run roots are allowed')
    meta = json.loads((run / 'run.json').read_text(encoding='utf-8'))
    if meta['project'] != 'exchange-' + run.name:
        raise ValueError('Acceptance project/root mismatch')
    for name in ('data', 'secrets', 'logs', 'knowledge'):
        if not (run / name).resolve().is_relative_to(run):
            raise ValueError('Acceptance directory escapes its run root')
    return run, meta


def command(run, meta):
    return ['docker', 'compose', '-p', meta['project'], '--env-file', str(run / 'release.env'),
            '-f', str(ROOT / 'acceptance-compose.yml')]


def clean_env(run, meta):
    env = os.environ.copy()
    # Shell interpolation cannot redirect our mounts/images/project to an old environment.
    for name in ('COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_ENV_FILES'):
        env.pop(name, None)
    env.update(meta['images'])
    env['ACCEPTANCE_ROOT'] = run.as_posix()
    return env


def execute(run, meta, argv, step, capture=False, timeout=240):
    try:
        result = subprocess.run(argv, env=clean_env(run, meta), capture_output=True,
                                timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError(step + ' did not complete; no automatic destructive retry') from None
    if result.returncode:
        name = re.sub(r'[^a-zA-Z0-9-]', '-', step) + '-' + secrets.token_hex(3) + '.private.log'
        fd = os.open(run / 'logs' / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as output:
            output.write(result.stdout + b'\n' + result.stderr)
        raise RuntimeError(step + ' failed; inspect restricted logs, do not paste raw diagnostics')
    if capture:
        return result.stdout.decode('utf-8').strip()
    return None


def container_id(run, meta, service):
    if service not in SERVICES:
        raise ValueError('Unapproved acceptance service')
    cid = execute(run, meta, command(run, meta) + ['ps', '-q', service], 'get-id', True)
    if not re.fullmatch('[0-9a-f]{64}', cid):
        raise ValueError('Expected exactly one acceptance container')
    obj = json.loads(execute(run, meta, ['docker', 'inspect', cid], 'verify-container', True))[0]
    labels = obj['Config']['Labels']
    if labels.get('com.docker.compose.project') != meta['project'] or labels.get('com.docker.compose.service') != service:
        raise ValueError('Container belongs to another namespace')
    for mount in obj['Mounts']:
        # Static scripts are read-only; data/secret mounts must belong to this new run.
        source = ops.mount_path(mount['Source'])
        own = ops.mount_path(str(run)) + os.sep
        script = {'/postgres-readiness.sh': ROOT / 'postgres-readiness.sh',
                  '/anvil-entrypoint.sh': ROOT / 'anvil-entrypoint.sh'}.get(mount['Destination'])
        static_script = script is not None and source == ops.mount_path(str(script)) and not mount['RW']
        if not source.startswith(own) and not static_script:
            raise ValueError('Unexpected shared data/secret mount')
    return cid


def sql(run, meta, service, query):
    db = {'postgres': 'acceptance_exchange', 'ai-postgres': 'acceptance_ai'}[service]
    cid = container_id(run, meta, service)
    return execute(run, meta, ['docker', 'exec', cid, 'psql', '-X', '-U', db, '-d', db,
                               '-v', 'ON_ERROR_STOP=1', '-tAc', query], 'sql-' + service, True)


def http(path, data=None, token=None):
    if not path.startswith('/api/'):
        raise ValueError('Only acceptance API paths')
    req = urllib.request.Request('http://127.0.0.1:18082' + path,
        data=json.dumps(data).encode() if data is not None else None,
        headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        with LOCAL_HTTP.open(req, timeout=12) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as failure:
        try:
            body = json.loads(failure.read())
        except ValueError:
            body = {}
        return failure.code, body


def rpc(method, params):
    if method not in {'eth_chainId', 'eth_blockNumber', 'eth_getCode', 'eth_call'}:
        raise ValueError('Only read-only acceptance RPC methods')
    req = urllib.request.Request('http://127.0.0.1:18545',
        data=json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params}).encode(),
        headers={'Content-Type': 'application/json'})
    with LOCAL_HTTP.open(req, timeout=10) as response:
        body = json.load(response)
    if 'error' in body:
        raise RuntimeError('Acceptance read-only RPC failed')
    return body['result']


def wait_health():
    end = time.monotonic() + 150
    while time.monotonic() < end:
        try:
            status, body = http('/api/health')
            if status == 200 and body.get('status') == 'UP':
                market_code, market = http('/api/markets/mSEC')
                if market_code == 200 and market.get('provider') == 'TOSS':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('Acceptance backend readiness timed out; inspect its private logs')


def prepare(args):
    credentials = toss_credentials(args.toss_env)
    for port in PORTS:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')
    run = RUNTIME / ('acceptance-' + stamp + '-' + secrets.token_hex(3))
    run.mkdir(mode=0o700)
    protect_root(run)
    for name in ('secrets', 'logs', 'data/trade', 'data/ai', 'data/chain', 'data/release'):
        (run / name).mkdir(parents=True, mode=0o700)
    values = {**credentials, 'DB_PASSWORD': secrets.token_urlsafe(32),
        'AI_DB_PASSWORD': secrets.token_urlsafe(32), 'JWT_SECRET': secrets.token_urlsafe(48),
        'ADMIN_LOGIN_ID': 'acceptance_admin', 'ADMIN_PASSWORD': secrets.token_urlsafe(24),
        'OPERATOR_PRIVATE_KEY': key(), 'PRICE_SIGNER_PRIVATE_KEY': key()}
    while values['OPERATOR_PRIVATE_KEY'] == values['PRICE_SIGNER_PRIVATE_KEY']:
        values['PRICE_SIGNER_PRIVATE_KEY'] = key()
    props = ''.join(k + '=' + ops.properties_escape(v) + '\n' for k, v in values.items())
    write_private(run / 'secrets/application.properties', props)
    write_private(run / 'secrets/db-password', values['DB_PASSWORD'])
    write_private(run / 'secrets/ai-db-password', values['AI_DB_PASSWORD'])
    write_private(run / 'secrets/ui-account.json', json.dumps({'loginId': 'acceptance_user',
        'password': secrets.token_urlsafe(24), 'nickname': '인수 사용자'}, ensure_ascii=False))
    tag = run.name
    images = {'AI_POSTGRES_IMAGE': 'pgvector/pgvector:0.8.6-pg16@sha256:eac621400b7b7ff52493883e41e930e3d104695fea5b68cc0c42370cf7880067',
        'ANVIL_IMAGE': 'ghcr.io/foundry-rs/foundry@sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17',
        'BACKEND_IMAGE': 'tokenized-market-backend:' + tag, 'TOOLS_IMAGE': 'tokenized-market-ops:' + tag}
    # Resolve the local PostgreSQL immutable image ID; avoid guessing a registry digest.
    inspected = subprocess.run(['docker', 'image', 'inspect', 'postgres:16', '--format', '{{.Id}}'],
                               check=True, capture_output=True).stdout.decode().strip()
    if not re.fullmatch('sha256:[0-9a-f]{64}', inspected):
        raise ValueError('Invalid local PostgreSQL image identity')
    images['POSTGRES_IMAGE'] = inspected
    meta = {'project': 'exchange-' + tag, 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
        'images': images, 'ports': list(PORTS), 'paidAiEnabled': False, 'tradingAcceptance': 'NOT_RUN'}
    write_private(run / 'run.json', json.dumps(meta, indent=2))
    write_private(run / 'release.env', 'ACCEPTANCE_ROOT=' + run.as_posix() + '\n' +
                  ''.join(k + '=' + v + '\n' for k, v in images.items()))
    ops.artifact(run / 'knowledge')  # Local approved Markdown/manifest copy only; no AI API.
    execute(run, meta, ['docker', 'build', '-t', images['BACKEND_IMAGE'], str(REPO / 'backend')], 'build-backend', timeout=600)
    execute(run, meta, ['docker', 'build', '-f', str(ROOT / 'Dockerfile.tools'), '-t', images['TOOLS_IMAGE'], str(REPO)], 'build-tools', timeout=600)
    cmd = command(run, meta)
    execute(run, meta, cmd + ['up', '-d', '--wait', 'postgres', 'ai-postgres', 'anvil'], 'start-infra')
    for service in ('postgres', 'ai-postgres', 'anvil'):
        container_id(run, meta, service)
    sql(run, meta, 'ai-postgres', 'CREATE EXTENSION IF NOT EXISTS vector;')
    # Deployer's existing init-attempt + chain.properties guards prohibit accidental redeployment.
    execute(run, meta, ['docker', 'run', '--rm', '--network', meta['project'] + '_chain',
        '-v', str(run / 'secrets') + ':/run/secrets:ro',
        '-v', str(run / 'data/release') + ':/release', images['TOOLS_IMAGE']], 'deploy-contracts')
    execute(run, meta, cmd + ['up', '-d', 'backend'], 'start-backend')
    container_id(run, meta, 'backend')
    wait_health()
    account = json.loads((run / 'secrets/ui-account.json').read_text(encoding='utf-8'))
    status, _ = http('/api/auth/signup', account)
    if status not in (200, 201):
        raise RuntimeError('Acceptance user creation failed; no automatic retry')
    print('Prepared isolated run: ' + run.relative_to(REPO).as_posix())
    print('Backend http://127.0.0.1:18082 | RPC 18545 | DBs 5542/5543 | AI disabled')
    probe_run(run, meta)


def probe_run(run, meta):
    for service in SERVICES:
        container_id(run, meta, service)
    addresses = dict(line.split('=', 1) for line in (run / 'data/release/chain.properties').read_text().splitlines() if '=' in line)
    if len(addresses) != 4 or any(rpc('eth_getCode', [a, 'latest']) in ('0x', '0x0') for a in addresses.values()):
        raise RuntimeError('Acceptance contract code missing')
    chain_id = int(rpc('eth_chainId', []), 16)
    if chain_id != 31337:
        raise RuntimeError('Unexpected acceptance chain ID')
    anvil = container_id(run, meta, 'anvil')
    def call(address, function, *params):
        return execute(run, meta, ['docker', 'exec', anvil, 'cast', 'call', address, function,
            *params, '--rpc-url', 'http://127.0.0.1:8545'], 'read-contract', True).split()[0]
    vault = addresses['EXCHANGE_VAULT_ADDRESS']
    oracle = addresses['PRICE_ORACLE_ADDRESS']
    krw = addresses['MOCK_KRW_ADDRESS']
    token_contract = addresses['MSEC_ADDRESS']
    operator = call(vault, 'owner()(address)')
    signer = call(oracle, 'priceSigner()(address)')
    links = {
        'tokenMinter': call(token_contract, 'minter()(address)'),
        'oracleConsumer': call(oracle, 'authorizedConsumer()(address)'),
        'vaultKrw': call(vault, 'krw()(address)'),
        'vaultToken': call(vault, 'token()(address)'),
        'vaultOracle': call(vault, 'oracle()(address)')}
    expected = {'tokenMinter': vault, 'oracleConsumer': vault,
                'vaultKrw': krw, 'vaultToken': token_contract, 'vaultOracle': oracle}
    if signer.lower() == operator.lower() or any(links[k].lower() != v.lower() for k, v in expected.items()):
        raise RuntimeError('Acceptance signer/contract link invariant failed')
    funding = {'operatorKrwWei': call(krw, 'balanceOf(address)(uint256)', operator),
        'operatorMsecWei': call(token_contract, 'balanceOf(address)(uint256)', operator),
        'vaultKrwWei': call(krw, 'balanceOf(address)(uint256)', vault),
        'vaultAllowanceWei': call(krw, 'allowance(address,address)(uint256)', operator, vault)}
    if any(int(funding[k]) < 1_000_000 * 10**18 for k in ('operatorKrwWei', 'vaultKrwWei', 'vaultAllowanceWei')):
        raise RuntimeError('Acceptance initial funding is insufficient')
    state = run / 'data/chain/state.json'
    if not state.is_file() or state.stat().st_size == 0:
        raise RuntimeError('Acceptance persistent Anvil state not saved yet')
    status, market = http('/api/markets/mSEC')
    if status != 200 or market.get('provider') != 'TOSS':
        raise RuntimeError('Actual Toss provider not available; no simulated fallback')
    account = json.loads((run / 'secrets/ui-account.json').read_text(encoding='utf-8'))
    code, login = http('/api/auth/login', {k: account[k] for k in ('loginId', 'password')})
    if code != 200:
        raise RuntimeError('Acceptance login failed')
    token = login['accessToken']
    closed_check = None
    if market.get('marketStatus') == 'CLOSED':
        code, rejection = http('/api/quotes/buy', {'symbol': 'mSEC', 'krwAmount': '1000'}, token)
        closed_check = code == 409 and rejection.get('code') == 'MARKET_CLOSED'
        if not closed_check:
            raise RuntimeError('Closed market quote boundary failed')
    ai_status, _ = http('/api/ai/agent/answers', {'question': '현재 시장 상태'}, token)
    if ai_status != 503:
        raise RuntimeError('Expected disabled Agent before paid approval')
    result = {'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'chainId': chain_id,
        'blockNumber': int(rpc('eth_blockNumber', []), 16), 'contracts': addresses,
        'operator': operator, 'priceSigner': signer, 'links': links, 'funding': funding,
        'persistentStatePresent': True,
        'market': {k: market.get(k) for k in ('provider', 'price', 'marketStatus', 'priceStatus', 'observedAt')},
        'closedQuoteRejected': closed_check, 'agentDisabled': ai_status == 503,
        'orders': int(sql(run, meta, 'postgres', 'SELECT count(*) FROM orders;')),
        'trades': int(sql(run, meta, 'postgres', 'SELECT count(*) FROM trades;')),
        'pgvector': sql(run, meta, 'ai-postgres', "SELECT extversion FROM pg_extension WHERE extname='vector';"),
        'tradingAcceptance': 'NOT_RUN', 'automaticMatchDiagnosis': 'NOT_RUN'}
    path = run / ('readiness-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S') + '.json')
    write_private(path, json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='action', required=True)
    init = subs.add_parser('prepare'); init.add_argument('--toss-env', required=True)
    for name in ('start', 'stop', 'probe'):
        subs.add_parser(name).add_argument('--run', required=True)
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            prepare(args)
        else:
            run, meta = validate_run(args.run)
            if args.action == 'probe':
                probe_run(run, meta)
            elif args.action == 'start':
                if not (run / 'knowledge').exists():
                    ops.artifact(run / 'knowledge')
                execute(run, meta, command(run, meta) + ['up', '-d', '--wait', 'postgres', 'ai-postgres', 'anvil'], 'resume-infra')
                execute(run, meta, command(run, meta) + ['up', '-d', 'backend'], 'resume-backend')
                wait_health()
                probe_run(run, meta)
            else:
                for service in SERVICES:
                    container_id(run, meta, service)
                execute(run, meta, command(run, meta) + ['stop'], 'stop-acceptance')
                print('Only acceptance containers stopped; all data preserved')
    except Exception as failure:
        print('Failure class: ' + type(failure).__name__, file=sys.stderr)
        if isinstance(failure, (RuntimeError, ValueError)):
            print(str(failure), file=sys.stderr)
        print('Acceptance step failed. Preserve the new run; inspect restricted logs. No reset/restore/redeploy attempted.', file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
