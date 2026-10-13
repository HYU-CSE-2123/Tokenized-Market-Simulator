"""Read-only clock sampling on a newly allocated Phase A fixture; never alters clocks/services."""
import json
from pathlib import Path
import subprocess
import time
import queue
import threading

def sample(operation):
    before=time.time();start=time.perf_counter()
    value=operation()
    elapsed=time.perf_counter()-start;after=time.time()
    return {'hostEpochBefore':before,'hostEpochAfter':after,'epoch':value,
            'roundTripSeconds':elapsed,'offsetSeconds':value-(before+after)/2,
            'uncertaintySeconds':elapsed/2,'hostClockStepSeconds':after-before-elapsed}

def probe(session,meta,env,measure_clock,require_clock_alignment,private):
    run=session/'fixtures'/meta['runId']
    def command(args):
        result=subprocess.run(args,env=env,capture_output=True,timeout=20,check=True)
        return result.stdout.decode('utf-8',errors='replace').strip()
    # Listing only running distributions avoids deliberately starting an inactive WSL distro.
    raw=subprocess.run(['wsl','--list','--running','--quiet'],env=env,capture_output=True,timeout=20,check=True).stdout
    running=raw.decode('utf-16-le' if b'\0' in raw else 'utf-8').split()
    docker_wsl='docker-desktop' in running
    java=subprocess.Popen(['java',str(Path(__file__).with_name('PhaseAClockProbe.java'))],env=env,
                          stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
    lines=queue.Queue()
    def read_lines():
        for line in java.stdout:lines.put(line.strip())
        lines.put('EOF')
    threading.Thread(target=read_lines,daemon=True).start()
    result={'scope':'NEW_DIAGNOSTIC_FIXTURE_ONLY','runId':meta['runId'],
            'wslDockerAlreadyRunning':docker_wsl,'samples':[],
            'authoritativeUtcVerified':False,'anvilBlockIsNotWallClock':True}
    try:
        if lines.get(timeout=20)!='READY':raise RuntimeError('JVM clock probe unavailable')
        def jvm_time():
            java.stdin.write('sample\n');java.stdin.flush()
            return float(lines.get(timeout=5))
        for index in range(5):
            entry={'index':index,'windowsPythonUtc':time.time(),'jvm':sample(jvm_time)}
            entry['windowsDotnetUtc']=sample(lambda:float(command(['powershell','-NoProfile','-Command',
                '[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()/1000']).replace(',','.')))
            entry['dockerLinuxUtc']=sample(lambda:float(command(['docker','exec',meta['name']+'-postgres','date','+%s.%N'])))
            if docker_wsl:
                entry['wslDockerUtc']=sample(lambda:float(command(['wsl','-d','docker-desktop','--','date','+%s.%N'])))
                # Docker Desktop's BusyBox prints zero nanoseconds: conservatively
                # account for floor-to-second resolution instead of claiming nanosecond precision.
                entry['wslDockerUtc']['resolutionSeconds']=1
                entry['wslDockerUtc']['offsetIntervalSeconds']=[
                    entry['wslDockerUtc']['offsetSeconds']-entry['wslDockerUtc']['uncertaintySeconds'],
                    entry['wslDockerUtc']['offsetSeconds']+1+entry['wslDockerUtc']['uncertaintySeconds']]
            entry['postgres']=measure_clock(session,meta)
            try:require_clock_alignment(entry['postgres']);entry['existingPreflight']='PASS'
            except RuntimeError:entry['existingPreflight']='BLOCKED_CLOCK_SKEW'
            entry['anvilLatestBlock']=sample(lambda:int(json.loads(command(['docker','exec',meta['name']+'-anvil',
                'cast','rpc','eth_getBlockByNumber','latest','false','--rpc-url','http://127.0.0.1:8545']))['timestamp'],16))
            result['samples'].append(entry)
            time.sleep(.5) # diagnostic sample spacing only; never a readiness workaround
    finally:
        java.stdin.close()
        try:java.wait(timeout=5)
        except subprocess.TimeoutExpired:java.terminate();java.wait(timeout=5)
        private(run/'clock-diagnostic.json',json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))
