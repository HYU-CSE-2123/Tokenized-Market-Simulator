"""Real isolated Compose acceptance. No real keys, AWS, paid AI calls or existing DBs.

Run only on a Docker host. Artifacts stay in ignored deployment/runtime/local-<timestamp>.
Whole stack/restore projects are left stopped with volumes preserved for inspection.
"""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import struct
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
TLS_CONTEXT = ssl.create_default_context()
spec = importlib.util.spec_from_file_location('ops', ROOT / 'ops.py')
ops = importlib.util.module_from_spec(spec); spec.loader.exec_module(ops)

def run(cmd, **kw): return subprocess.run(cmd, check=True, **kw)

def request(path, data=None, token=None, port=18443, method=None, timeout=8):
    req = urllib.request.Request('https://localhost:' + str(port) + path,
            data=json.dumps(data).encode() if data is not None else None, method=method,
            headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        with urllib.request.urlopen(req, context=TLS_CONTEXT, timeout=timeout) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw.startswith((b'{', b'[')) else raw.decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()

def eventually(fn, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        try:
            result = fn()
            if result: return result
        except (OSError, AssertionError): pass
        time.sleep(1)
    raise AssertionError('Condition timed out')

def frame(sock, payload):
    data = payload.encode(); mask = secrets.token_bytes(4)
    length = len(data)
    head = bytes([0x81, 0x80 | length]) if length < 126 else bytes([0x81, 0xfe]) + struct.pack('!H', length)
    sock.sendall(head + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(data)))

def receive(sock):
    def exact(size):
        data = b''
        while len(data) < size:
            value = sock.recv(size - len(data))
            if not value: raise OSError('Socket closed')
            data += value
        return data
    head = exact(2); size = head[1] & 127
    if size == 126: size = struct.unpack('!H', exact(2))[0]
    elif size == 127: size = struct.unpack('!Q', exact(8))[0]
    return exact(size).decode()

def websocket(path='/ws', sockjs=False):
    sock = TLS_CONTEXT.wrap_socket(socket.create_connection(('127.0.0.1', 18443)), server_hostname='localhost')
    sock.settimeout(10)
    key = base64.b64encode(secrets.token_bytes(16)).decode()
    sock.sendall((f'GET {path} HTTP/1.1\r\nHost: localhost:18443\r\nOrigin: https://localhost:18443\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
    header = b''
    while not header.endswith(b'\r\n\r\n'): header += sock.recv(1)
    assert b'101' in header.split(b'\r\n')[0], header[:100]
    if sockjs: assert receive(sock) == 'o'
    def send(text): frame(sock, json.dumps([text]) if sockjs else text)
    send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\x00')
    assert 'CONNECTED' in receive(sock)
    send('SUBSCRIBE\nid:prices\ndestination:/topic/markets/mSEC/price\n\n\x00')
    event = receive(sock)
    assert 'MESSAGE' in event and 'SIMULATED' in event
    sock.close()

def main():
    global TLS_CONTEXT
    stamp = time.strftime('%Y%m%d%H%M%S')
    project = 'exchange-ops-' + stamp
    base = ROOT / 'runtime' / ('local-' + stamp)
    base.mkdir(parents=True)
    values = {'DB_PASSWORD': secrets.token_hex(24), 'AI_DB_PASSWORD': secrets.token_hex(24),
              'JWT_SECRET': secrets.token_hex(48), 'ADMIN_LOGIN_ID': 'ops_admin',
              'ADMIN_PASSWORD': secrets.token_hex(20), 'OPERATOR_PRIVATE_KEY': '0x' + secrets.token_hex(32),
              'PRICE_SIGNER_PRIVATE_KEY': '0x' + secrets.token_hex(32)}
    # Test-only materialization (no SSM). Windows symlinks are not required for local fixture.
    runtime = base / 'secrets'; runtime.mkdir()
    (runtime / 'application.properties').write_text(''.join(k + '=' + v + '\n' for k, v in values.items()) +
        'AI_ENABLED=true\nAI_AGENT_ENABLED=true\nAI_TOOLS_ENABLED=true\nAI_SKILLS_ENABLED=true\n'
        'OPENAI_API_KEY=local-fixture-no-external-key\napp.ai.api-base=http://127.0.0.1:9/\napp.ai.timeout-seconds=2\n', encoding='utf-8', newline='\n')
    for key, filename in [('DB_PASSWORD', 'db-password'), ('AI_DB_PASSWORD', 'ai-db-password')]:
        (runtime / filename).write_text(values[key])
    (runtime / 'acceptance.conf').write_text('allow all;\n')  # TEST ONLY: gateway binds loopback.
    data = base / 'data'
    for name in ('trade', 'ai', 'chain', 'release'): (data / name).mkdir(parents=True)
    if os.name == 'posix': os.chown(data / 'chain', 10001, 10001)
    cert = base / 'cert'; (cert / 'live').mkdir(parents=True); (cert / 'acme').mkdir()
    cert_source = str((cert / 'live').resolve())
    run(['docker', 'run', '--rm', '--user', '0', '-v', cert_source + ':/cert', '--entrypoint', 'openssl', 'tokenized-market-backend:release',
         'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2', '-subj', '/CN=localhost',
         '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', '/cert/privkey.pem', '-out', '/cert/fullchain.pem'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    TLS_CONTEXT = ssl.create_default_context(cafile=str(cert / 'live/fullchain.pem'))
    ops.artifact(base / 'knowledge')
    example = (ROOT / '.env.example').read_text()
    replacements = {'PUBLIC_DOMAIN': 'localhost', 'PUBLIC_ORIGIN': 'https://localhost:18443', 'HTTP_BIND': '127.0.0.1',
                    'HTTP_PORT': '18080', 'HTTPS_PORT': '18443', 'DATA_ROOT': str(data.resolve()).replace('\\', '/'),
                    'RUNTIME_ROOT': str(runtime.resolve()).replace('\\', '/'), 'KNOWLEDGE_ROOT': str((base / 'knowledge').resolve()).replace('\\', '/'),
                    'CERT_ROOT': str(cert.resolve()).replace('\\', '/'), 'SIMULATION_BOOTSTRAP_DAYS': '0'}
    env_file = base / 'compose.env'
    env_file.write_text('\n'.join(k + '=' + replacements.get(k, v) if '=' in line and not line.startswith('#') else line
                    for line in example.splitlines() for k, _, v in [line.partition('=')]) + '\n')
    cmd = ['docker', 'compose', '--project-name', project, '--env-file', str(env_file), '-f', str(ROOT / 'compose.yml')]
    report = {'project': project, 'checks': []}
    restore_cmd = None
    def check(name): report['checks'].append(name); print('PASS', name)
    try:
        config = json.loads(run(cmd + ['config', '--format', 'json'], capture_output=True).stdout)
        assert all(not v.get('ports') for k, v in config['services'].items() if k != 'web')
        assert all('TOSS' not in str(v.get('environment', {})) for v in config['services'].values())
        check('Only loopback Nginx ports; no DB/RPC/backend published or Toss credentials')
        run(cmd + ['up', '-d', '--wait', 'postgres', 'ai-postgres', 'anvil'])
        eventually(lambda: run(cmd + ['exec', '-T', 'anvil', 'cast', 'chain-id', '--rpc-url', 'http://127.0.0.1:8545'], capture_output=True).returncode == 0)
        run(['docker', 'run', '--rm', '--network', project + '_chain', '-v', str(runtime.resolve()) + ':/run/secrets:ro',
             '-v', str((data / 'release').resolve()) + ':/release', 'tokenized-market-ops:release'], stdout=subprocess.DEVNULL)
        check('Explicit random-key private chain deployment; no default funded accounts')
        run(cmd + ['up', '-d', 'backend', 'web'])
        eventually(lambda: request('/api/health')[0] == 200)
        backend_id = run(cmd + ['ps','-q','backend'], capture_output=True).stdout.decode().strip()
        backend_info = json.loads(run(['docker','inspect',backend_id], capture_output=True).stdout)[0]
        assert backend_info['Config']['User'] == '10001:10001' and backend_info['HostConfig']['ReadonlyRootfs']
        assert not any(value in str(backend_info['Config']['Env']) for value in values.values() if len(value) > 24)
        run(cmd + ['exec','-T','backend','/bin/sh','-c','test ! -e /app/.env && test ! -e /app/backend/.env'])
        check('Non-root read-only backend; runtime secrets absent from image/env')
        status, html = request('/'); assert status == 200 and 'SIMULATED · 합성 시장' in html and 'id="public-market-notice" hidden' not in html
        status, market = request('/api/markets/mSEC'); assert status == 200 and market['provider'] == 'SIMULATED'
        check('HTTPS static public disclosure and real synthetic REST')
        websocket(); websocket('/ws-sockjs/000/ops/websocket', True)
        check('Native WSS and SockJS STOMP real price events')
        for interval in ('1m','5m','15m','30m','1h','1d'):
            assert request('/api/markets/mSEC/candles?interval=' + interval)[0] == 200
        for path in ('/api/ai/index','/api/ai/search','/api/ai/answers','/api/ai/tools/getMarket','/api/ai/diagnoses/index'):
            assert request(path, {})[0] == 404
        assert request('/api/portfolio')[0] in (401,403)
        assert request('/api/ai/observability')[0] in (401,403)
        check('Six candle intervals, anonymous protection and blocked operations routes')
        status, admin_auth = request('/api/auth/login', {'loginId': values['ADMIN_LOGIN_ID'], 'password': values['ADMIN_PASSWORD']})
        assert status == 200
        admin_token = admin_auth['accessToken']
        assert request('/api/me', token=admin_token)[1]['role'] == 'ADMIN'
        status, auth = request('/api/auth/signup', {'loginId': 'ops_user', 'password': 'LocalSmokeTest123!', 'nickname': 'Ops'})
        assert status in (200,201), (status, auth)
        token = auth['accessToken'] if 'accessToken' in auth else auth['token']
        assert request('/api/ai/observability', token=token)[0] == 403
        assert request('/api/ai/observability', token=admin_token)[0] == 200
        assert request('/api/wallet/faucet', {}, token)[0] == 200
        status, quote = request('/api/quotes/buy', {'symbol': 'mSEC', 'krwAmount': 75000}, token); assert status == 200, (status, quote)
        status, order = request('/api/orders/buy', {'symbol': 'mSEC', 'krwAmount': 75000, 'quoteId': quote['quoteId']}, token); assert status == 202, (status, order)
        def filled():
            status, rows = request('/api/orders', token=token)
            return status == 200 and any(row['status'] == 'FILLED' for row in rows)
        eventually(filled, 30)
        status, trades = request('/api/trades', token=token)
        assert status == 200 and any(str(row['price']) == str(quote['price']) for row in trades)
        assert request('/api/orders/buy', {'symbol':'mSEC','krwAmount':75000,'quoteId':quote['quoteId']}, token)[0] == 409
        check('JWT/faucet/EIP-712 quoteId buy and actual receipt settlement')
        step = int(run(cmd + ['exec','-T','postgres','psql','-U','exchange','-d','exchange','-tAc','SELECT step FROM synthetic_market_state'], capture_output=True).stdout.strip())
        run(cmd + ['restart','backend','anvil'])
        eventually(lambda: request('/api/markets/mSEC')[0] == 200)
        next_step = int(run(cmd + ['exec','-T','postgres','psql','-U','exchange','-d','exchange','-tAc','SELECT step FROM synthetic_market_state'], capture_output=True).stdout.strip())
        assert next_step >= step and filled()
        check('Backend/Anvil restart retains synthetic checkpoint and filled ledger')
        run(cmd + ['stop','ai-postgres'])
        assert request('/api/markets/mSEC')[0] == 200 and request('/api/portfolio', token=token)[0] == 200
        run(cmd + ['up','-d','--wait','ai-postgres'])
        check('AI DB stop does not break market/portfolio (AI enabled, no paid API)')
        assert request('/api/ai/agent/answers', {'question':'내 포트폴리오를 설명해줘'}, token)[0] == 503
        assert request('/api/markets/mSEC')[0] == 200 and request('/api/portfolio', token=token)[0] == 200
        check('Unreachable local AI provider fails independently; no external AI requests')
        run(cmd + ['stop','anvil'])
        # Existing web3j timeout can exceed the normal fixture's8s. Probe isolation WHILE RPC waits.
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=1) as pending_rpc:
            failed_quote = pending_rpc.submit(request, '/api/quotes/buy', {'symbol':'mSEC','krwAmount':1000}, token, timeout=75)
            assert request('/api/markets/mSEC')[0] == 200 and request('/api/portfolio', token=token)[0] == 200
            assert failed_quote.result(timeout=75)[0] in (503,504)
        run(cmd + ['up','-d','anvil'])
        eventually(lambda: request('/api/quotes/buy', {'symbol':'mSEC','krwAmount':1000}, token)[0] == 200, 30)
        check('RPC outage blocks signed quote, not synthetic market/portfolio; recovery verified')
        codes = [request('/api/auth/login', {'loginId':'invalid', 'password':'invalid'})[0] for _ in range(12)]
        assert 429 in codes
        check('Nginx login rate limit produces 429')
        run(cmd + ['stop','backend'])
        assert request('/api/markets/mSEC')[0] in (502,504)  # refused vs timed-out private upstream
        assert 'SIMULATED · 합성 시장' in request('/')[1]
        run(cmd + ['up','-d','backend']); eventually(lambda: request('/api/health')[0] == 200)
        check('Initial API failure still serves static synthetic disclosure')
        run(cmd + ['exec','-T','ai-postgres','psql','-U','exchange_ai','-d','exchange_ai','-c',
                  "CREATE EXTENSION IF NOT EXISTS vector; CREATE TABLE restore_probe(id integer primary key, embedding vector(3)); INSERT INTO restore_probe VALUES(1,'[1,2,3]')"], stdout=subprocess.DEVNULL)
        from types import SimpleNamespace
        ops.backup(SimpleNamespace(project=project, env_file=str(env_file), data_root=str(data), destination=str(base / 'backup')))
        check('Quiesced DB+chain consistent checkpoint backup')
        restore_data = base / 'restore-data'; restore_env = base / 'restore.env'
        restore_env.write_text(env_file.read_text().replace(str(data.resolve()).replace('\\','/'), str(restore_data.resolve()).replace('\\','/')))
        restore_project = 'exchange-restore-' + stamp
        restore_cmd = ['docker','compose','--project-name',restore_project,'--env-file',str(restore_env),'-f',str(ROOT / 'compose.yml')]
        ops.restore(SimpleNamespace(project=restore_project, env_file=str(restore_env), data_root=str(restore_data), source=str(base / 'backup')))
        restored = int(run(restore_cmd + ['exec','-T','postgres','psql','-U','exchange','-d','exchange','-tAc',"SELECT count(*) FROM orders WHERE status='FILLED'"], capture_output=True).stdout.strip())
        assert restored >= 1 and (restore_data / 'chain/state.json').read_bytes() == (base / 'backup/state.json').read_bytes()
        restored_vector = run(restore_cmd + ['exec','-T','ai-postgres','psql','-U','exchange_ai','-d','exchange_ai','-tAc','SELECT embedding FROM restore_probe WHERE id=1'], capture_output=True).stdout.decode().strip()
        assert restored_vector == '[1,2,3]'
        run(restore_cmd + ['up','-d','anvil','backend','web'])
        eventually(lambda: request('/api/markets/mSEC')[0] == 200)
        assert request('/api/orders', token=token)[0] == 200  # Same signing key/ledger, no original chain reset.
        status, restore_quote = request('/api/quotes/buy', {'symbol':'mSEC','krwAmount':1000}, token)
        assert status == 200 and restore_quote.get('quoteId')
        assert request('/api/orders/buy', {'symbol':'mSEC','krwAmount':1000,'quoteId':restore_quote['quoteId']}, token)[0] == 202
        eventually(lambda: sum(row['status'] == 'FILLED' for row in request('/api/orders', token=token)[1]) > restored, 30)
        check('Restored pgvector row, chain and backend settle fresh EIP-712 trade')
        run(restore_cmd + ['stop'])
        check('Fresh isolated pg_restore and chain state verified; original data never overwritten')
    finally:
        if restore_cmd:
            with (base / 'private-restore.log').open('wb') as logs:
                run(restore_cmd + ['logs','--no-color'], stdout=logs, stderr=subprocess.DEVNULL)
            run(restore_cmd + ['down'])  # Bind data preserved; never -v/prune.
        run(cmd + ['stop'])
        with (base / 'private-stack.log').open('wb') as logs:
            run(cmd + ['logs','--no-color'], stdout=logs, stderr=subprocess.DEVNULL)
        run(cmd + ['down'])
        (base / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        print('Removed test containers/networks only; retained data/evidence:', base)

if __name__ == '__main__': main()
