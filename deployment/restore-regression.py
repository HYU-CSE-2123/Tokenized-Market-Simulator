"""Repeat restore ONLY into new isolated roots; no retry of a partial restore."""
import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ops', ROOT / 'ops.py')
ops = importlib.util.module_from_spec(spec); spec.loader.exec_module(ops)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True)
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.rounds <= 5: raise ValueError('1..5 fresh rounds only')
    source = Path(args.source).resolve()
    # Deliberately test-fixture only; never import personal deployment config.
    runtime = (ROOT / 'runtime').resolve()
    if not source.is_relative_to(runtime) or source.name != 'backup':
        raise ValueError('Only preserved local fixture checkpoints allowed')
    lines = (source / 'release.env').read_text().splitlines()
    old = dict(line.split('=', 1) for line in lines if '=' in line and not line.startswith('#'))
    if old.get('HTTP_BIND') != '127.0.0.1': raise ValueError('Loopback fixture required')
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S%f')
    base = runtime / ('restore-regression-' + stamp); base.mkdir()
    results = []
    for index in range(args.rounds):
        project = 'exchange-restore-regression-' + stamp + '-' + str(index)
        data = base / ('data-' + str(index)); env = base / ('restore-' + str(index) + '.env')
        env.write_text('\n'.join('DATA_ROOT=' + str(data).replace('\\', '/') if line.startswith('DATA_ROOT=')
                                 else line for line in lines) + '\n')
        request = SimpleNamespace(project=project, env_file=str(env), data_root=str(data), source=str(source))
        cmd = ops.compose(request); row = {'project': project, 'success': False}
        try:
            ops.restore(request)
            trade = ops.run(cmd + ['exec', '-T', 'postgres', 'psql', '-X', '-U', 'exchange', '-d', 'exchange',
                                  '-tAc', "SELECT count(*) FROM orders WHERE status='FILLED'"]).stdout.strip()
            ai = ops.run(cmd + ['exec', '-T', 'ai-postgres', 'psql', '-X', '-U', 'exchange_ai', '-d', 'exchange_ai',
                               '-tAc', 'SELECT embedding FROM restore_probe WHERE id=1']).stdout.strip()
            assert int(trade) >= 1 and ai == b'[1,2,3]'
            assert (data / 'chain/state.json').read_bytes() == (source / 'state.json').read_bytes()
            row.update(success=True, filledOrders=int(trade), vectorRestored=True, chainEqual=True)
        except Exception as failure:
            row['failureType'] = type(failure).__name__  # no argv/stderr/secret in summary
        finally:
            with (base / (str(index) + '-private.log')).open('wb') as log:
                subprocess.run(cmd + ['logs', '--no-color'], stdout=log, stderr=subprocess.DEVNULL)
            ops.run(cmd + ['down'])  # no -v: retain exact test bind roots/evidence
            results.append(row)
            (base / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
            print('Fresh round', index, 'PASS' if row['success'] else 'FAIL', flush=True)
        if not row['success']: break  # report first issue, do not try partial recovery
    print('Evidence:', base)
    if len(results) != args.rounds or not all(row['success'] for row in results):
        raise RuntimeError('Fresh restore regression failed; inspect restricted evidence')


if __name__ == '__main__': main()
