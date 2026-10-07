"""Local resource measurement only: isolated production Compose, no AWS/paid AI.

AI provider is a bounded local fixture, not an actual LLM quality/latency evaluation.
No personal .env or existing service data is read. Evidence stays in ignored runtime.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import secrets
import ssl
import subprocess
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parent


def fixture_server():
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            size = int(self.headers.get('Content-Length', '0'))
            if size > 2_000_000:
                self.send_error(413); return
            body = json.loads(self.rfile.read(size))
            if self.path.endswith('/embeddings'):
                # Same bounded unit vector for all texts: real pgvector work, NOT semantic evaluation.
                vector = [1.0] + [0.0] * 1535
                result = {'data': [{'index': i, 'embedding': vector} for i, _ in enumerate(body['input'])],
                          'usage': {'prompt_tokens': 0}}
            else:
                incoming = json.loads(body['input'])
                name = body['text']['format']['name']
                if name == 'agent_plan':
                    result = {'route': 'MIXED', 'subject': 'PRICE'}
                    if 'skillId' in body['text']['format']['schema']['properties']: result['skillId'] = 'NONE'
                else:
                    evidence = incoming['evidence']
                    refs = [x['id'] for x in evidence['knowledge'][:1]]
                    facts = []
                    for tool in evidence['tools'][:1]:
                        for key, value in tool['data'].items():
                            if not isinstance(value, (list, dict)):
                                refs.append(tool['evidenceId'])
                                text = str(value).lower() if isinstance(value, bool) else 'null' if value is None else str(value)
                                facts.append({'evidenceId': tool['evidenceId'], 'pointer': '/' + key, 'value': text})
                                break
                    result = {'status': 'ANSWERED', 'interpretation': '로컬 자원 측정 fixture: 현재 관측과 정책을 조회했습니다.',
                              'citationIds': refs, 'facts': facts, 'uncertainties': [], 'recommendedNextCheck': []}
                # Typical historical provider wait without local inference work. Delay is disclosed.
                time.sleep(2)
                result = {'status': 'completed', 'output': [{'content': [{'type': 'output_text', 'text': json.dumps(result)}]}],
                          'usage': {'input_tokens': 0, 'output_tokens': 0}}
            payload = json.dumps(result).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)
    ThreadingHTTPServer(('127.0.0.1', 18999), Handler).serve_forever()


def internal_index():
    # Called in the isolated backend network namespace. JWT never appears in argv/output.
    props = dict(line.split('=', 1) for line in Path('/fixture/application.properties').read_text().splitlines() if '=' in line)
    def call(path, body, token=None):
        req = urllib.request.Request('http://127.0.0.1:8082' + path, data=json.dumps(body).encode(),
              headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
        with urllib.request.urlopen(req, timeout=180) as response: return json.load(response)
    token = call('/api/auth/login', {'loginId': props['ADMIN_LOGIN_ID'], 'password': props['ADMIN_PASSWORD']})['accessToken']
    indexed = call('/api/ai/index', {}, token)
    print(json.dumps({k: v for k, v in indexed.items() if k in ('indexVersion', 'documents', 'chunks')}))


def run(argv, **kwargs):
    kwargs.setdefault('capture_output', True)
    return subprocess.run(argv, check=True, **kwargs)


def mib(value):
    units = {'B': 1 / 1048576, 'KiB': 1 / 1024, 'MiB': 1, 'GiB': 1024,
             'kB': 1000 / 1048576, 'MB': 1000000 / 1048576, 'GB': 1000000000 / 1048576}
    for suffix in sorted(units, key=len, reverse=True):
        if value.strip().endswith(suffix): return float(value.strip()[:-len(suffix)]) * units[suffix]
    raise ValueError('Unknown memory unit')


def main():
    spec = importlib.util.spec_from_file_location('smoke', ROOT / 'local-smoke.py')
    smoke = importlib.util.module_from_spec(spec); spec.loader.exec_module(smoke)
    stamp = time.strftime('%Y%m%d%H%M%S')
    project = 'exchange-ops-probe-' + stamp
    base = ROOT / 'runtime' / ('probe-' + stamp); base.mkdir(parents=True)
    runtime = base / 'secrets'; runtime.mkdir()
    values = {'DB_PASSWORD': secrets.token_hex(24), 'AI_DB_PASSWORD': secrets.token_hex(24),
              'JWT_SECRET': secrets.token_hex(48), 'ADMIN_LOGIN_ID': 'probe_admin',
              'ADMIN_PASSWORD': secrets.token_hex(20), 'OPERATOR_PRIVATE_KEY': '0x' + secrets.token_hex(32),
              'PRICE_SIGNER_PRIVATE_KEY': '0x' + secrets.token_hex(32)}
    (runtime / 'application.properties').write_text(''.join(k + '=' + v + '\n' for k, v in values.items()) +
        'AI_ENABLED=true\nAI_AGENT_ENABLED=true\nAI_TOOLS_ENABLED=true\nAI_SKILLS_ENABLED=true\n'
        'OPENAI_API_KEY=resource-fixture-no-paid-key\napp.ai.api-base=http://127.0.0.1:18999/v1/\n', encoding='utf-8', newline='\n')
    for key, name in [('DB_PASSWORD', 'db-password'), ('AI_DB_PASSWORD', 'ai-db-password')]:
        (runtime / name).write_text(values[key])
    (runtime / 'acceptance.conf').write_text('allow all;\n')  # loopback-only test, never production.
    data = base / 'data'
    for name in ('trade', 'ai', 'chain', 'release'): (data / name).mkdir(parents=True)
    cert = base / 'cert'; (cert / 'live').mkdir(parents=True); (cert / 'acme').mkdir()
    run(['docker', 'run', '--rm', '--user', '0', '-v', str((cert / 'live').resolve()) + ':/cert', '--entrypoint', 'openssl',
         'tokenized-market-backend:release', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
         '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
         '-keyout', '/cert/privkey.pem', '-out', '/cert/fullchain.pem'])
    smoke.TLS_CONTEXT = ssl.create_default_context(cafile=str(cert / 'live/fullchain.pem'))
    smoke.ops.artifact(base / 'knowledge')
    replace = {'PUBLIC_DOMAIN': 'localhost', 'PUBLIC_ORIGIN': 'https://localhost:18443', 'HTTP_BIND': '127.0.0.1',
               'HTTP_PORT': '18080', 'HTTPS_PORT': '18443', 'DATA_ROOT': str(data.resolve()).replace('\\', '/'),
               'RUNTIME_ROOT': str(runtime.resolve()).replace('\\', '/'), 'KNOWLEDGE_ROOT': str((base / 'knowledge').resolve()).replace('\\', '/'),
               'CERT_ROOT': str(cert.resolve()).replace('\\', '/'), 'SIMULATION_BOOTSTRAP_DAYS': '2'}
    env = base / 'compose.env'
    env.write_text('\n'.join(k + '=' + replace.get(k, v) if '=' in line and not line.startswith('#') else line
        for line in (ROOT / '.env.example').read_text().splitlines() for k, _, v in [line.partition('=')]) + '\n')
    cmd = ['docker', 'compose', '--project-name', project, '--env-file', str(env), '-f', str(ROOT / 'compose.yml')]
    report = {'project': project, 'fixture': 'local AI HTTP; fixed embedding vector; 2s/LLM response; no paid calls',
              'host': json.loads(run(['docker', 'info', '--format', '{{json .}}']).stdout), 'phases': {}, 'samples': []}
    # Only disclose host capacity, not paths/daemon details in evidence.
    report['host'] = {k: report['host'][k] for k in ('NCPU', 'MemTotal', 'OperatingSystem', 'Architecture')}
    ids = []; stop = threading.Event(); phase = ['startup']; sample_errors = []
    provider_name = project + '-provider'
    def sample():
        while not stop.is_set():
            try:
                if ids:
                    sampled_phase = phase[0]
                    rows = run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids]).stdout.decode().splitlines()
                    services = {json.loads(row)['Name']: {'cpuPercent': float(json.loads(row)['CPUPerc'].rstrip('%')),
                                 'memoryMiB': mib(json.loads(row)['MemUsage'].split('/')[0]),
                                 'pids': int(json.loads(row)['PIDs'])} for row in rows}
                    report['samples'].append({'phase': sampled_phase, 'time': time.time(), 'services': services,
                        'cpuPercent': sum(x['cpuPercent'] for x in services.values()),
                        'memoryMiB': sum(x['memoryMiB'] for x in services.values())})
            except Exception as e: sample_errors.append(type(e).__name__)
            stop.wait(.25)
    def request(path, body=None, token=None): return smoke.request(path, body, token, timeout=25)
    def phase_run(name, seconds, action=None):
        phase[0] = name; started = time.time(); counts = {'actions': 0, 'failures': 0}; latencies = []
        while time.time() - started < seconds:
            if action:
                begin = time.time()
                try: action(); counts['actions'] += 1
                except Exception: counts['failures'] += 1
                latencies.append((time.time() - begin) * 1000)
            else: time.sleep(1)
        report['phases'][name] = {**counts, 'durationSeconds': time.time() - started, 'latencyMs': latencies}
        print('PHASE', name, counts, flush=True)
    sampler = threading.Thread(target=sample, daemon=True)
    try:
        run(cmd + ['up', '-d', '--wait', 'postgres', 'ai-postgres', 'anvil'])
        run(['docker', 'run', '--rm', '--network', project + '_chain', '-v', str(runtime.resolve()) + ':/run/secrets:ro',
             '-v', str((data / 'release').resolve()) + ':/release', 'tokenized-market-ops:release'])
        run(cmd + ['up', '-d', 'backend', 'web'])
        ids.extend(run(cmd + ['ps', '-q']).stdout.decode().split()); sampler.start()
        started = time.time(); smoke.eventually(lambda: request('/api/markets/mSEC')[0] == 200, 180)
        report['backendReadySeconds'] = time.time() - started
        backend = run(cmd + ['ps', '-q', 'backend']).stdout.decode().strip()
        run(['docker', 'run', '-d', '--name', provider_name, '--network', 'container:' + backend,
             '-v', str(Path(__file__).resolve()) + ':/probe.py:ro', '-v', str(runtime.resolve()) + ':/fixture:ro',
             'python:3.11-alpine', 'python', '/probe.py', 'provider'])
        time.sleep(2)
        report['index'] = json.loads(run(['docker', 'exec', provider_name, 'python', '/probe.py', 'index']).stdout)
        status, auth = request('/api/auth/signup', {'loginId': 'probe_user', 'password': 'ResourceProbe123!', 'nickname': 'Probe'})
        assert status in (200, 201)
        token = auth['accessToken']; assert request('/api/wallet/faucet', {}, token)[0] == 200
        phase_run('idle', 90)
        def trade():
            status, quote = request('/api/quotes/buy', {'symbol': 'mSEC', 'krwAmount': 1000}, token); assert status == 200
            status, order = request('/api/orders/buy', {'symbol': 'mSEC', 'krwAmount': 1000, 'quoteId': quote['quoteId']}, token); assert status == 202
            oid = order['orderId']
            smoke.eventually(lambda: any(x['orderId'] == oid and x['status'] == 'FILLED' for x in request('/api/orders', token=token)[1]), 25)
            time.sleep(4)
        phase_run('trade', 60, trade)
        def ai():
            status, answer = request('/api/ai/agent/answers', {'question': '현재 시장 가격과 정책을 설명해 주세요.'}, token)
            assert status == 200 and answer['status'] == 'ANSWERED', (status, answer.get('status') if isinstance(answer, dict) else None)
            assert answer['metrics']['modelCalls'] == 2 and answer['metrics']['retrievalCalls'] == 1 and answer['toolEvidence']
            time.sleep(6)  # gateway AI6/min: only pacing, does not alter provider work.
        phase_run('ai', 60, ai)
        def combined():
            with ThreadPoolExecutor(max_workers=2) as pool:
                a = pool.submit(ai); b = pool.submit(trade); a.result(); b.result()
        phase_run('trade_ai', 60, combined)
        report['backendCgroup'] = run(cmd + ['exec', '-T', 'backend', 'sh', '-c',
              'cat /sys/fs/cgroup/memory.peak; cat /sys/fs/cgroup/memory.events']).stdout.decode()
        report['filledOrders'] = request('/api/orders', token=token)[1]
        # Keep counts/status only, not per-user data/quotes.
        report['filledOrders'] = sum(x['status'] == 'FILLED' for x in report['filledOrders'])
    finally:
        stop.set()
        if sampler.is_alive(): sampler.join(10)
        report['sampleErrors'] = sample_errors
        (base / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        subprocess.run(['docker', 'rm', '-f', provider_name], capture_output=True)
        run(cmd + ['down'])  # exact task project only, bind data retained.
        print('Evidence:', base, flush=True)
    if sample_errors or any(x['failures'] for x in report['phases'].values()):
        raise RuntimeError('Measurement contains failures; do not use as successful-path capacity evidence')


if __name__ == '__main__':
    arg = argparse.ArgumentParser(); arg.add_argument('mode', nargs='?', default='measure', choices=['measure', 'provider', 'index'])
    mode = arg.parse_args().mode
    {'measure': main, 'provider': fixture_server, 'index': internal_index}[mode]()
