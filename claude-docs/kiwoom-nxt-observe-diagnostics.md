# NXT 읽기 전용 관측 프로필 — 구현 및 오프라인 검증

> 2026-10-15. 사용자 승인: 기존 진단 도구에 `nxt-observe` 추가. 실제 외부 실행은 별도 승인 전 금지. Spring Boot·웹·KRX E2E 이미지·DB·Anvil·baseline은 변경하지 않는다.

## 구현

- `deployment/kiwoom_diagnostic_core.py`: `subscription('nxt-observe')`는 `005930_NX`의0B와빈item=[]의0s를한REG로등록한다. NXT Observer는별도wire경로로item허용값/형식,290/9081숫자코드,215허용코드,20시각,수신시각/generation,가격·체결량·누적량만보존한다. 거래소/세션의의미는UNVERIFIED,제품거래가용성은NOT_RUN이다. UNKNOWN/누락/비정상값을OPEN으로바꾸지않으며기존KRX analyze결과를NXT에적용하지않는다.
- `deployment/kiwoom-diagnostic.py`:명시적nxt프로필·preparedmanifest/소스해시/구독/한도/secret참조검사,기존E2E전용 `secrets/kiwoom.properties`의명시적절대경로read-only참조.파일소유권/ACL과두키구조검사후메모리에서만사용한다.환경변수/.env탐색,인증정보복사·fingerprint출력없음.실전endpoint만사용하며주문API는없다.
- 같은파일의supervisor는WS를보유하는worker하나만생성한다.전체210초중종료여유2초를남긴시점에worker를kill/wait하며,정상관찰은최대180초다.프로세스가블로킹되어도부모가종료하며재시작하지않는다.실행run을exclusive하게선점하고기존capture가있으면거절한다.
- `deployment/test_kiwoom_nxt_diagnostic.py`:NXT전용오프라인Mock/비밀필드/호출예산/부모프로세스종료검증.기존진단테스트는수정하지않았다.

## 강제 제한 및 증거

| 항목 | 제한 |
|---|---|
| OAuth | 최대1회;REST총예산1회에포함 |
| 시세/차트REST | 0회;transport에서도차단 |
| WS연결 | 최대1회 |
| LOGIN/REG | 각각최대1회;PING응답외추가packet금지 |
| 관찰 | 최대180초 |
| 전체실행 | 210초;supervisor는종료여유2초확보 |
| 정제이벤트 | 최대5000개;수신frame예산도적용 |
| 재시도/재연결/토큰갱신 | 없음 |

`events.jsonl`은전체원문이아닌허용필드만저장한다.알수없는item은값대신형식/NULL로기록하고,기본005930·NXT·SOR item은서로분리한다.290/9081은1~3자리숫자외값을그대로남기지않는다.미허용형식은redacted/missing으로구분되므로그값의의미를이번관측으로확인했다고주장하지않는다.

`observedAt`의날짜는수신일KST기반의가정이며vendor날짜가아니다.원래HHmmss·age와함께명시한다.오래된0s도필터링해서없애지않고관측하되시장OPEN/정규체결을추정하지않는다.다른generation은섞지않는다.

`attempts.jsonl`은OAuth/WS/LOGIN/REG전송전의시도횟수만저장한다.카운트는서버도달·성공횟수가아니라시도횟수다.강제종료시partial evidence와 `supervisor-report.json`을보존한다.종료는COMPLETE여도실환경인수PASS나NXT계약확정과동일하지않다.

supervisor는정상·비정상worker종료에도별도보고서를남긴다.원래 `report.json`을덮어쓰지않고,worker종료코드와COMPLETE가충돌하면WORKER_FAILED로기록한다.강제종료중journal의부분줄은그대로보존하며마지막완전기록과횟수불확실성을함께표시한다.수동중단도worker를종료하고USER_INTERRUPTED로보존한다.

## 실제 수행한 오프라인 검증 — 최초 실행

`python -S -B -m unittest discover -s deployment -p 'test_kiwoom*diagnostic.py'`

- 57/57 PASS,FAIL/ERROR/SKIP0. 기존진단39개+신규18개다.180초관찰루프는Mock monotonic clock으로진행했다.실제180초외부수신시험이아니다.
- 전체제한은상수210초를테스트에서2.4초로축소하고,실제신규로컬Python대기프로세스를종료했는지확인했다.실제210초장시간시험이나키움WS종료시험으로표현하지않는다.
- 실제키움OAuth/REST/WS·유료AI0회.실제계정키검증/전용run준비와독립검토결과는후속기록한다.

### 필수 보완 후 재실행

- 독립 최초검토에서강제종료중journal마지막부분줄을JSON으로읽다가종료보고서가누락될수있다는필수지적을확인했다.부분줄보존/마지막완전기록/INCONCLUSIVE처리로수정했다.추가제안인worker비정상종료와COMPLETE충돌검사도반영했다.
- run동시선점,비정상종료,부분journal,LOGIN/REG거절,사용자중단회귀를보강했다.62개확장테스트의최초실행은61PASS/1ERROR였다.부분journal을worker시작전부터만든잘못된fixture가정상적인CAPTURE_ALREADY_EXISTS가드에걸린테스트설정문제였다.생성시점을실행중으로수정한후 **62/62 PASS,FAIL/ERROR/SKIP0**이다(기존39+신규23).
- `python -S -B -m unittest discover -s deployment -p 'test_phase_a*.py'`: **15/15 PASS**,실제Docker가아닌기존오프라인guard/clock단위검증이다.앞선 `test_phase_a.py`만의12/12와구분한다.
- 기존CLI Mock replay도실행했고기존generation1 SUPPORTED/generation2 INCONCLUSIVE결과를확인했다.이것은NXT의거래가용성판정이아니다.

첫재검토에서는부분 `report.json`을읽을때도같은종료보고누락위험이남았다는필수지적을받았다.읽기/JSON/최소스키마실패를WORKER_FAILED로수렴하고원본report를보존했다.부분report·정상worker완료·journal읽기실패회귀를추가한최종소스에서 **65/65 PASS(기존39+신규26),기존Phase A guard/clock15/15 PASS,FAIL/ERROR/SKIP0**을다시확인했다.실제210초대기나실서버시험은실행하지않았다.

## 준비 및 향후 실행 절차

1. 오프라인테스트·보안검사·독립검토를마친다.
2. `prepare-nxt --credentials-file <기존E2E전용kiwoom.properties의절대경로>`로새private진단run을만든다.실제키는복사하지않고private prepared manifest에파일참조와코드해시/한도/REG만저장한다.이준비명령은네트워크를사용하지않는다.
3. 새run이AWAITING_EXTERNAL_APPROVAL인지확인하고,사용자에게실행명령을제출한다.기존KRX E2E환경은기동/재빌드/수정하지않는다.
4. 별도승인후에만아래capture를실행한다.인증정보경로는CLI에서명시하며키값은인자로전달하지않는다.

```powershell
python -B deployment/kiwoom-diagnostic.py capture `
  --run <새진단run의절대경로> `
  --profile nxt-observe `
  --credentials-file <기존E2E전용kiwoom.properties의절대경로> `
  --allow-external `
  --max-rest-requests 1 --max-ws-connections 1 `
  --seconds 180 --max-events 5000 --chart-snapshots 0
```

210초제한은위capture명령의supervisor가자동적용한다.숨겨진worker옵션은수동실행하지않는다.인증실패/등록거절/통신실패/예산초과시추가호출없이중단하며0s미수신은초기스냅샷미지원으로단정하지않는다.프리/애프터는별도관측·승인이필요하다.

## 보존 및 한계

- 기존mixed/0s-only의기본구독·의미분석은유지한다.코드해시가바뀌었으므로과거prepared파일을새소스에맞춰덮어쓰지않는다.새승인run만만든다.
- KRX실환경E2E,제품NXT모드,분봉·서명견적·BUY/SELL의NXT통합인수는이번범위가아니다.
- Git commit/merge/push,기존capture·E2E환경·DB·Anvil·baseline변경없음.

## 최종 검토 및 실제 private 준비 결과

- 독립검토:최초1회+재검토2회.최종2차재검토에서 **발견된 필수 수정 없음**.reviewer가직접65/65오프라인테스트를실행했다.실제키/private run/Docker/외부API에는접근하지않았으며그부분은구현자의아래로컬준비결과와구분한다.
- `prepare-nxt`를실제로실행하여새run `deployment/runtime/kiwoom-diagnostic-20261015112753-02b328b4`를생성했다.기존E2E전용properties파일은명시적read-only참조로검사했으며두값을출력/복사/해시하지않았다.
- 준비후read-only검증:status=AWAITING_EXTERNAL_APPROVAL,소스해시/REG/호출한도/secret참조manifest검증PASS,private ACL검증PASS.새run의파일은 **prepared.json/run.json 두개뿐**이다.credential복사·events/report/attempts생성없음.실제capture실행0.
- 별도검증용 `python -c`의최초명령은deployment모듈검색경로누락으로ModuleNotFoundError였다.검색경로만명시해다시읽기전용검증했고PASS했다.제품/도구수정이나외부재시도는없었다.
- 외부승인없는NXT capture CLI는 `EXTERNAL_DISABLED`로차단되는것도실제로확인했다.이차단은의도한안전검증이며NXT실환경실패가아니다.

### 별도 외부 승인 후에만 실행할 명령

다음명령은**통합작업worktree** `deployment/runtime/integration-worktree`를현재디렉터리로둔상태의명령이다.이번작업에서는실행하지않았다.

```powershell
$nxtRun = (Resolve-Path 'deployment/runtime/kiwoom-diagnostic-20261015112753-02b328b4').Path
$nxtSecret = (Resolve-Path 'deployment/runtime/kiwoom-e2e-20261014171302-662c5ba0/secrets/kiwoom.properties').Path
python -B deployment/kiwoom-diagnostic.py capture `
  --run $nxtRun --profile nxt-observe --credentials-file $nxtSecret `
  --allow-external --max-rest-requests 1 --max-ws-connections 1 `
  --seconds 180 --max-events 5000 --chart-snapshots 0
```

소스가 바뀌거나 run에 기존 capture가 있으면 실행을 거절한다. 기존 manifest/capture를 덮어써서 일치시키지 않는다.

## 실제 관측 승인 후 시각 점검 — BLOCKED / NOT_RUN

사용자가 기존 `kiwoom-diagnostic-20261015112753-02b328b4`에서 NXT 거래 시간에 관측 1회를 실행하도록 승인했다. OAuth최대1/시세·차트REST0/WS최대1/LOGIN·REG각최대1/재시도·재연결·토큰재발급0/관찰180초/전체210초/정제이벤트5000개의 동일 한도다.

실행 전 가장 먼저 확인한 Windows 시각은 **2026-10-15T20:56:48+09:00**, UTC 시각은11:56:48이며 별도clock 조회도일치했다. NXT 애프터마켓 종료시각20:00 이후이므로 사용자가 지정한 거래시간 조건을 충족하지 못해 외부 연결 전에 중단했다. 이것은 공급자가CLOSED를전송했다는 관측 결과가 아니다.

| 이번 실제 수행 항목 | 결과 |
|---|---|
| 호스트 KST/UTC 확인 | PASS |
| NXT 거래시간 실행 전제 | BLOCKED |
| 현재 prepared/source/secret 재대조 | NOT_RUN · 시간 전제에서 먼저 중단 |
| OAuth/시세·차트REST/WS/LOGIN/REG | 전부0회 |
| 구독 승인 및 item/290/9081/215 수신 | NOT_RUN · 관측값 없음 |
| NXT/KRX 실제 체결 식별 가능 여부 | 미확인 · 실제 증거 없음 |

이번에는진단capture명령·private run 접근/수정·새run생성·제품수정·테스트·Docker/DB/Anvil조작·Git명령을실행하지않았다. 기존prepared/capture/인증정보와KRX E2E환경을보존했다.문서에이번시간차단기록만추가한다.승인된1회관측을시도한것으로계수하지않으며,다음NXT거래시간에같은run의manifest/소스/참조/한도를새로확인한후재개해야한다.자동예약이나백그라운드대기는설정하지않았다.

## 2026-10-16 승인된 실제 관측 완료

같은run으로승인된관측1회를실행했다. LOGIN/REG승인,005930_NX체결742건/290=2,같은generation1. 0s0건,9081은정제null이어서필드부재/타입/필터링원인미확정이다. OAuth1/시세REST0/WS1/LOGIN·REG각1,재연결·재시도0. worker정상종료·파일시각기준181.54초. 상세근거와구분한미검증범위는 [실제 관측 보고서](kiwoom-nxt-live-observation-2026-10-16.md)를따른다. 이run은이미사용됐으므로같은capture를다시실행하지않는다. 제품NXT지원/프리·애프터/모의거래인수PASS가아니다.

## 9081 형식 보완 및 애프터마켓 관측 준비 — 2026-10-16

`Observer.nxt_wire()`의 NXT 체결 정제 행에만 다음 메타데이터를 추가했다. 제품 코드와 기존 mixed/0s-only 행은 변경하지 않았다.

| 필드 | 의미 |
|---|---|
| exchangeFieldPresent | values 객체에 9081 키가 존재하는지 |
| exchangeJsonType | MISSING / NULL / BOOLEAN / NUMBER / STRING / ARRAY / OBJECT |
| exchangeFormatAllowed | 기존 exchangeCode 허용 형식인 ASCII 숫자 1~3자리 문자열인지 |

기존 exchangeCode 필터는 그대로다. 허용되지 않은 문자열·객체·배열의 원문, 길이, fingerprint는 기록하지 않는다. 숫자 JSON 값도 문자열로 강제 변환하지 않는다. 값의 거래소·세션 의미는 해석하지 않는다. values 자체가 객체가 아니면 기존 처리대로 빈 객체로 정규화되어 MISSING으로 기록된다. 과거 capture의 null 원인은 소급 복원할 수 없다.

### 이번 오프라인 검증

- `python -S -B -m unittest discover -s deployment -p 'test_kiwoom*diagnostic.py'`: 68/68 PASS, skip 0. 기존 65개와 신규 3개 테스트(타입별 subtest 포함). 누락/null/boolean/숫자/허용·미허용 문자열/배열/객체, 비밀 canary 비노출, legacy 행 불변 및 기존 호출·시간 제한 검증.
- `python -S -B -m unittest discover -s deployment -p 'test_phase_a*.py'`: 15/15 PASS, skip 0.
- 실제 외부 OAuth/REST/WS: 모두 0회. 실제 애프터마켓 응답 검증은 NOT_RUN.
- 별도 검토자 `review_acceptance_baseline`: 발견된 필수 수정 없음. 직접 진단 테스트 68/68 PASS 재확인. private run/credentials/외부 API는 검토 범위에서 제외했다.

### 신규 private run 및 향후 승인 후 실행 명령

`kiwoom-diagnostic-20261016033307-3aee8495`를 외부 연결 없이 준비했다. 기존 secret은 명시적 경로로 읽기 전용 참조하며 값을 출력·복사하지 않았다. 과거 사용된 run은 보존하고 재사용하지 않는다.

아래 명령은 **실제 애프터마켓 관측의 별도 승인 후에만** 실행한다. 실행 위치는 `deployment/runtime/integration-worktree` 작업 트리다. 이번 작업에서는 실행하지 않았다.

```powershell
$nxtRun = (Resolve-Path 'deployment/runtime/kiwoom-diagnostic-20261016033307-3aee8495').Path
$nxtSecret = (Resolve-Path 'deployment/runtime/kiwoom-e2e-20261014171302-662c5ba0/secrets/kiwoom.properties').Path
python -B deployment/kiwoom-diagnostic.py capture `
  --run $nxtRun --profile nxt-observe --credentials-file $nxtSecret `
  --allow-external --max-rest-requests 1 --max-ws-connections 1 `
  --seconds 180 --max-events 5000 --chart-snapshots 0
```

OAuth 최대1, 시세·차트 REST0, WS1, LOGIN/REG 각1, 재시도·재연결·토큰 재발급0, 관찰180초/전체210초/정제 이벤트5000개의 기존 제한을 유지한다. 소스 변경 또는 capture 존재 시 manifest/재사용 guard가 차단한다. 0s 부재로 재호출하지 않으며 의미가 확인되지 않으면 INCONCLUSIVE로 남긴다.

### 애프터마켓 관측 승인 후 시각 확인 — BLOCKED (2026-10-16)

사용자가 신규 run `kiwoom-diagnostic-20261016033307-3aee8495`의 애프터마켓 관측 1회를 승인했다. 먼저 읽은 Windows 시각은 `2026-10-16T12:39:11+09:00`, 별도 clock은 `2026-10-16 03:39:11 UTC`로 일치했다. 오후 애프터마켓이 아닌 시간대이므로 실행 전제 BLOCKED로 중단했다. 공급자 시장 상태를 조회하거나 OPEN/CLOSED로 판정한 결과가 아니다.

- OAuth/시세 REST/WS/LOGIN/REG: 모두 0회. 실제 관측 항목은 전부 NOT_RUN.
- 시간 전제에서 먼저 중단했으므로 이번 요청에서 prepared/source/secret/private run 재검증과 capture는 실행하지 않았다. 신규 run은 이번 요청에서 사용하지 않았다.
- 기존 증거·제품·DB·Anvil·baseline·인증정보 변경 없음. 예약 실행이나 백그라운드 대기 없음. 애프터마켓에 재개할 때 manifest/소스/경로/한도/미사용 상태를 다시 확인해야 한다.
- NXT 고정 모드의 새 실측 근거는 추가되지 않았다. item/290/9081 및 세션 의미에 관한 기존 미확정 사항을 유지한다.
