"""Pure read-only evidence parsing. No transport, credentials, DB or product-policy writes."""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import re

KST = timezone(timedelta(hours=9))
UTC = timezone.utc
STATES = {'3': 'OPEN', **dict.fromkeys(('0','2','4','8','9','a','b','c','d'), 'CLOSED')}


class DiagnosticError(Exception):
    """Only fixed codes may leave the tool; never attach vendor input or exception text."""


def instant(value):
    try:
        if not isinstance(value, str) or len(value)>40:
            raise ValueError()
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if result.tzinfo is None:
            raise ValueError()
        return result.astimezone(UTC)
    except (ValueError, TypeError):
        raise DiagnosticError('INVALID_TIME') from None


def stamp(value):
    return value.astimezone(UTC).isoformat()


def number(value, magnitude=False, quantity=False):
    text = str(value)
    if not re.fullmatch(r'[+-]?[0-9]{1,16}(\.[0-9]{1,8})?', text):
        raise DiagnosticError('INVALID_NUMBER')
    result = Decimal(text)
    if magnitude:
        result = abs(result)
    if abs(result) > (Decimal('1000000000000') if quantity else Decimal('1000000000')):
        raise DiagnosticError('NUMBER_OUT_OF_RANGE')
    if quantity and result != result.to_integral_value():
        raise DiagnosticError('INVALID_QUANTITY')
    return format(result, 'f')


def trade_time(value, received):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9]{6}', value):
        raise DiagnosticError('INVALID_TRADE_TIME')
    try:
        t = datetime.strptime(value, '%H%M%S').time()
        return datetime.combine(received.astimezone(KST).date(), t, KST).astimezone(UTC)
    except ValueError:
        raise DiagnosticError('INVALID_TRADE_TIME') from None


def ack_success(frame):
    return type(frame.get('return_code')) in (int, str) and str(frame['return_code']) == '0'


class Observer:
    def __init__(self, max_events=5000):
        if not 1 <= max_events <= 20000:
            raise DiagnosticError('INVALID_EVENT_LIMIT')
        self.rows = []
        self.max_events = max_events
        self.generation = 0
        self.login = self.reg = False
        self.truncated = False

    def add(self, row):
        if len(self.rows) >= self.max_events:
            self.truncated = True
            raise DiagnosticError('EVENT_LIMIT')
        self.rows.append(row)
        return row

    def begin(self, received):
        self.generation += 1
        self.login = self.reg = False
        return self.add({'kind':'CONNECTED','generation':self.generation,'receivedAt':stamp(received)})

    def frame(self, frame, received, generation=None):
        generation = self.generation if generation is None else generation
        if generation != self.generation:
            return []
        common = {'generation':generation,'receivedAt':stamp(received)}
        if not isinstance(frame, dict):
            return [self.add(dict(common,kind='REJECTED',reason='INVALID_FRAME'))]
        kind = frame.get('trnm')
        if kind in ('LOGIN','REG'):
            ok = ack_success(frame)
            if kind == 'LOGIN':
                self.login = ok
            else:
                ok = ok and self.login
                self.reg = ok
            return [self.add(dict(common,kind=kind,result='OK' if ok else 'REJECTED'))]
        if kind != 'REAL':
            return []  # PING and unknown messages have no persisted raw body
        if not self.reg:
            return [self.add(dict(common,kind='REJECTED',reason='BEFORE_REG'))]
        entries = frame.get('data')
        if not isinstance(entries,list) or len(entries)>500:
            return [self.add(dict(common,kind='REJECTED',reason='INVALID_DATA'))]
        result=[]
        for entry in entries:
            try:
                if not isinstance(entry,dict) or not isinstance(entry.get('values'),dict):
                    raise DiagnosticError('INVALID_ENTRY')
                values=entry['values']; typ=entry.get('type')
                if typ=='0s' and entry.get('item','')=='':
                    observed=trade_time(values.get('20'),received)
                    age=(received-observed).total_seconds()
                    row=dict(common,kind='MARKET_STATE',marketStatus=STATES.get(str(values.get('215')),'UNKNOWN'),
                        observedAt=stamp(observed),ageSeconds=age,productTimeCompatible=-2<=age<=5)
                elif typ=='0B' and entry.get('item')=='005930':
                    observed=trade_time(values.get('20'),received)
                    price=number(values.get('10'),True); volume=number(values.get('15'),True,True)
                    cumulative=number(values.get('13'),True,True)
                    if min(Decimal(price),Decimal(volume),Decimal(cumulative))<=0:
                        raise DiagnosticError('NONPOSITIVE_TRADE')
                    row=dict(common,kind='TRADE',symbol='005930',observedAt=stamp(observed),
                        price=price,volume=volume,cumulativeVolume=cumulative,
                        session='REGULAR' if values.get('290')=='2' else 'OTHER',
                        productTimeCompatible=-2<=(received-observed).total_seconds()<=5)
                else:
                    continue
                result.append(self.add(row))
            except DiagnosticError as error:
                if str(error)=='EVENT_LIMIT':
                    raise
                safe_type=entry.get('type') if isinstance(entry,dict) and entry.get('type') in ('0s','0B') else 'OTHER'
                result.append(self.add(dict(common,kind='REJECTED',reason='INVALID_MARKET_FIELDS',eventType=safe_type)))
        return result

    def candles(self, body, requested, received):
        if not isinstance(body,dict) or not ack_success(body):
            raise DiagnosticError('CHART_REJECTED')
        rows=body.get('stk_min_pole_chart_qry')
        if not isinstance(rows,list) or len(rows)>2000:
            raise DiagnosticError('INVALID_CHART')
        result=[]
        for entry in rows[:20]:  # one latest page, no implicit pagination or full history fetch
            try:
                t=entry.get('cntr_tm')
                if not isinstance(t,str) or not re.fullmatch(r'[0-9]{14}',t):
                    raise ValueError()
                native=datetime.strptime(t,'%Y%m%d%H%M%S').replace(tzinfo=KST).astimezone(UTC)
                prices={k:number(entry.get(v),True) for k,v in
                    [('open','open_pric'),('high','high_pric'),('low','low_pric'),('close','cur_prc')]}
                volume=number(entry.get('trde_qty'),quantity=True)
                if Decimal(volume)<0 or min(map(Decimal,prices.values()))<=0 or \
                        Decimal(prices['high'])<max(Decimal(prices['open']),Decimal(prices['close'])) or \
                        Decimal(prices['low'])>min(Decimal(prices['open']),Decimal(prices['close'])):
                    raise ValueError()
                result.append(self.add(dict(kind='CANDLE',generation=self.generation,receivedAt=stamp(received),
                    requestedAt=stamp(requested),asOfCandidate=stamp(requested),nativeAt=stamp(native),
                    intervalMinutes=1,symbol='005930',volume=volume,**prices)))
            except (ValueError,AttributeError,DiagnosticError):
                raise DiagnosticError('INVALID_CHART_FIELDS') from None
        return result


def unique_trades(rows,generation):
    trades=[r for r in rows if r['kind']=='TRADE' and r['generation']==generation]
    unique=[]; seen={}; gap=any(r['kind']=='REJECTED' and r.get('eventType')=='0B' and r['generation']==generation for r in rows)
    for t in trades:
        key=t['cumulativeVolume']
        if key in seen:
            if any(t[f]!=seen[key][f] for f in ('price','volume','observedAt')):
                gap=True
            continue
        if unique:
            previous=unique[-1]
            if instant(t['observedAt'])<instant(previous['observedAt']) or \
                    Decimal(t['cumulativeVolume'])-Decimal(previous['cumulativeVolume'])!=Decimal(t['volume']):
                gap=True
        seen[key]=t;unique.append(t)
    return unique,gap


def window(trades,start,end):
    # Need both boundary witnesses from one connection, not a guessed complete capture.
    if not any(instant(t['observedAt'])<start for t in trades) or not any(instant(t['observedAt'])>=end for t in trades):
        return None
    inside=[t for t in trades if start<=instant(t['observedAt'])<end]
    if not inside:
        return None
    prices=[Decimal(t['price']) for t in inside]
    return {'open':prices[0],'high':max(prices),'low':min(prices),'close':prices[-1],
            'volume':sum((Decimal(t['volume']) for t in inside),Decimal(0))}


def analyze(observer):
    rows=observer.rows; generations=[]; boundaries=[]; asof=[]
    for gen in range(1,observer.generation+1):
        own=[r for r in rows if r['generation']==gen]
        states=[r for r in own if r['kind']=='MARKET_STATE']
        state_frames=[r for r in own if r['kind']=='MARKET_STATE' or (r['kind']=='REJECTED' and r.get('eventType')=='0s')]
        connected=next((instant(r['receivedAt']) for r in own if r['kind']=='CONNECTED'),None)
        accepted=any(r['kind']=='REG' and r['result']=='OK' for r in own)
        latest_state=state_frames[-1] if state_frames else None
        opened=bool(latest_state and latest_state.get('marketStatus')=='OPEN' and latest_state.get('productTimeCompatible'))
        for record in own:
            if record['kind']=='MARKET_STATE' or (record['kind']=='REJECTED' and record.get('eventType')=='0s'):
                opened=record.get('marketStatus')=='OPEN' and record.get('productTimeCompatible',False)
            elif record['kind']=='TRADE' and record['session']=='OTHER':
                opened=False  # match the product's conservative close until a subsequent explicit 0s
        recorded_trades=[r for r in own if r['kind']=='TRADE']
        last_trade=recorded_trades[-1] if recorded_trades else None
        live=bool(last_trade and last_trade['session']=='REGULAR' and last_trade['productTimeCompatible'] and
            -2<=(instant(own[-1]['receivedAt'])-instant(last_trade['observedAt'])).total_seconds()<=5)
        first=state_frames[0] if state_frames else None
        generations.append({'generation':gen,'regAccepted':accepted,'stateReceived':bool(state_frames),
            'firstStateDelaySeconds':(instant(first['receivedAt'])-connected).total_seconds() if first and connected else None,
            'productEligibilityEvidence':'SUPPORTED' if accepted and opened and live else 'INCONCLUSIVE',
            'initialStateWithin60Seconds':bool(first and connected and (instant(first['receivedAt'])-connected).total_seconds()<=60)})
    for c in (r for r in rows if r['kind']=='CANDLE'):
        trades,gap=unique_trades(rows,c['generation']); native=instant(c['nativeAt'])
        start_result=window(trades,native,native+timedelta(minutes=1))
        end_result=window(trades,native-timedelta(minutes=1),native)
        matches=lambda candidate:candidate is not None and all(candidate[k]==Decimal(c[k]) for k in candidate)
        verdict='INCONCLUSIVE'
        if not gap and not observer.truncated and start_result and end_result and instant(c['receivedAt'])>=native+timedelta(minutes=1):
            a,b=matches(start_result),matches(end_result)
            verdict='START_SUPPORTED' if a and not b else 'END_SUPPORTED' if b and not a else 'INCONCLUSIVE' if a and b else 'MISMATCH'
        boundaries.append({'generation':c['generation'],'nativeAt':c['nativeAt'],'verdict':verdict,
            'captureGap':gap,'startObserved':start_result is not None,'endObserved':end_result is not None})
    # Determine the label only from complete bars; incomplete live bars cannot establish it.
    labels={gen:{b['verdict'] for b in boundaries if b['generation']==gen and b['verdict'] in ('START_SUPPORTED','END_SUPPORTED')}
            for gen in range(1,observer.generation+1)}
    for c in (r for r in rows if r['kind']=='CANDLE'):
        trades,gap=unique_trades(rows,c['generation']);native=instant(c['nativeAt'])
        known=labels[c['generation']]
        verdict=next(iter(known)) if len(known)==1 else 'INCONCLUSIVE'
        # asOf is a candidate, never claim the REST vendor supplied a watermark.
        asof_verdict='INCONCLUSIVE'; reason='BOUNDARY_OR_COVERAGE_UNKNOWN'
        if not gap and not observer.truncated and verdict in ('START_SUPPORTED','END_SUPPORTED'):
            start=native if verdict=='START_SUPPORTED' else native-timedelta(minutes=1)
            cutoff=instant(c['asOfCandidate']);response=instant(c['receivedAt'])
            if start<=cutoff<start+timedelta(minutes=1) and window(trades,start,response+timedelta(microseconds=1)):
                prefix=sum((Decimal(t['volume']) for t in trades if start<=instant(t['observedAt'])<=cutoff),Decimal(0))
                actual=Decimal(c['volume'])
                asof_verdict='SUPPORTED' if actual==prefix else 'MISMATCH'
                reason='PREFIX_MATCH' if actual==prefix else 'PRE_ASOF_MISSING' if actual<prefix else 'POST_ASOF_OVERLAP_OR_VENDOR_DIFFERENCE'
        asof.append({'generation':c['generation'],'nativeAt':c['nativeAt'],'verdict':asof_verdict,'reason':reason,
            'watermarkGuaranteed':False})
    return {'formatVersion':1,'mode':'READ_ONLY_DIAGNOSTIC','events':len(rows),'captureTruncated':observer.truncated,
        'generations':generations,'boundaryChecks':boundaries,'asOfChecks':asof,
        'realApiAcceptance':'NOT_ASSERTED','transactionExecution':'NOT_RUN'}
