"""Prepare a NEW feature-only PostgreSQL+Anvil fixture. Never inspect/import old .env/runtime.
Run once; secrets/logs/state are retained privately, no automatic teardown/redeploy/reset.
"""
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent

def private(path,text):
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w',encoding='utf-8',newline='\n') as file: file.write(text)

def main():
    if os.name!='nt': raise RuntimeError('Windows/Docker Desktop fixture only; Linux ownership setup not validated')
    for port in (26542,26545):
        with socket.socket() as probe: probe.bind(('127.0.0.1',port))
    name='audit-e2e-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(3)
    run=ROOT/'runtime'/name
    run.mkdir(parents=True,mode=0o700)
    who=subprocess.run(['whoami'],capture_output=True,check=True).stdout.decode().strip()
    subprocess.run(['icacls',str(run),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],
        check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    for directory in ('secrets','chain','release','logs','postgres'): (run/directory).mkdir()
    def command(args,step,timeout=300):
        result=subprocess.run(args,capture_output=True,timeout=timeout)
        if result.returncode:
            private(run/'logs'/(step+'.private.log'),(result.stdout+result.stderr).decode('utf-8',errors='replace'))
            raise RuntimeError(step+' failed; restricted evidence retained, no reset/retry')
        return result.stdout.decode('utf-8').strip()
    password=secrets.token_urlsafe(32)
    operator='0x'+secrets.token_hex(32); signer='0x'+secrets.token_hex(32)
    # Generated fixture keys are not imported from any other run.
    private(run/'secrets/application.properties','OPERATOR_PRIVATE_KEY='+operator+'\nPRICE_SIGNER_PRIVATE_KEY='+signer+'\n')
    private(run/'secrets/db-password',password)
    private(run/'run.json',json.dumps({'name':name,'dbPort':26542,'rpcPort':26545,'paidAi':False}))
    network=name+'-network'
    command(['docker','network','create','--label','audit.fixture='+name,network],'network')
    command(['docker','run','--pull=never','-d','--name',name+'-postgres','--label','audit.fixture='+name,
        '--network',network,'-p','127.0.0.1:26542:5432','-e','POSTGRES_DB=exchange_trade_audit_e2e',
        '-e','POSTGRES_USER=reserve','-e','POSTGRES_PASSWORD_FILE=/run/secrets/db-password',
        '-v',str(run/'secrets/db-password')+':/run/secrets/db-password:ro',
        '-v',str(run/'postgres')+':/var/lib/postgresql/data','postgres:16'],'postgres')
    foundry='ghcr.io/foundry-rs/foundry@sha256:0c00cb0bda1ab1b91c9a6bf60f4c76c09c1a8870824b6d4718afbabacf6f9a17'
    command(['docker','run','--pull=never','-d','--name',name+'-anvil','--label','audit.fixture='+name,
        '--network',network,'--network-alias','anvil','-p','127.0.0.1:26545:8545',
        '-v',str(run/'chain')+':/data','--entrypoint','anvil',foundry,'--host','0.0.0.0','--accounts','0',
        '--chain-id','31337','--state','/data/state.json','--state-interval','5','--preserve-historical-states','--quiet'],'anvil')
    for service,args in [('postgres',['psql','-X','-U','reserve','-d','exchange_trade_audit_e2e','-h','127.0.0.1','-tAc','SELECT 1']),
        ('anvil',['cast','rpc','eth_chainId','--rpc-url','http://127.0.0.1:8545'])]:
        end=time.monotonic()+90
        while time.monotonic()<end:
            probe=subprocess.run(['docker','exec',name+'-'+service,*args],capture_output=True)
            if probe.returncode==0: break
            time.sleep(0.5)
        else: raise RuntimeError(service+' SQL/RPC readiness failed; no retry of initialization')
    image='tokenized-reserve-tools:reserve-e2e-20261009043259-4ac3a5'
    command(['docker','image','inspect',image],'cached-tools')
    command(['docker','run','--pull=never','--rm','--network',network,
        '-v',str(run/'secrets')+':/run/secrets:ro','-v',str(run/'release')+':/release',image],'deploy')
    addresses=dict(line.split('=',1) for line in (run/'release/chain.properties').read_text().splitlines() if '=' in line)
    values={
        'spring.config.import':'',
        'spring.datasource.url':'jdbc:postgresql://127.0.0.1:26542/exchange_trade_audit_e2e?connectTimeout=2&socketTimeout=5',
        'spring.datasource.username':'reserve','spring.datasource.password':password,
        'spring.datasource.driver-class-name':'org.postgresql.Driver',
        'spring.jpa.database-platform':'org.hibernate.dialect.PostgreSQLDialect','spring.jpa.hibernate.ddl-auto':'none',
        'spring.sql.init.mode':'always','app.reserve.enabled':'true','app.reserve.execution-id':name,
        'app.price.provider':'simulated','app.price.initial-delay-ms':'2147483647',
        'app.blockchain.enabled':'true','app.blockchain.rpc-url':'http://127.0.0.1:26545',
        'app.blockchain.operator-private-key':operator,'app.blockchain.price-report.enabled':'true',
        'app.blockchain.price-report.signer-private-key':signer,
        'app.blockchain.mock-krw-address':addresses['MOCK_KRW_ADDRESS'],
        'app.blockchain.m-sec-address':addresses['MSEC_ADDRESS'],
        'app.blockchain.price-oracle-address':addresses['PRICE_ORACLE_ADDRESS'],
        'app.blockchain.exchange-vault-address':addresses['EXCHANGE_VAULT_ADDRESS'],
        'app.blockchain.reconciliation.initial-delay-ms':'2147483647',
        'app.trade-audit.enabled':'true','app.admin.password':'','app.ai.enabled':'false','app.ai.tools.enabled':'false',
        'app.ai.agent.enabled':'false','app.ai.skills.enabled':'false','app.ai.diagnosis.enabled':'false'}
    private(run/'secrets/test.properties',''.join(k+'='+v+'\n' for k,v in values.items()))
    print('New isolated fixture ready: '+run.relative_to(REPO).as_posix())
    print('Set AUDIT_E2E_TESTS=true and AUDIT_E2E_RUN to this absolute root for TradeAuditPostgresAnvilE2ETest.')
    print('No Toss/AI/old DB/RPC used. Containers/data retained; stop only the two names in this run.json when finished.')

if __name__=='__main__':
    try: main()
    except Exception:
        print('Feature fixture preparation failed; private evidence retained. No automatic cleanup/reset.')
        raise SystemExit(1) from None
