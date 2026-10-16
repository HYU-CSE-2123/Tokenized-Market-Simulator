# 키움 첫 장중 읽기 전용 인수 — 사전 점검과 실제 관찰 결과

> 최신 결과: 사용자 서비스/IP 확인 사실을 받아 기존 capture를 1회 실행했다. REST4/WS1/180초, 정상 종료. OAuth·LOGIN·REG·0B PASS, 0s 미수신으로 신규 거래의 OPEN/LIVE 호환 인수는 BLOCKED. 실제 결과는 5절 이후에 기록한다. 아래 사전 BLOCKED는 실행 전 이력으로 보존한다.

> 2026-10-14 KST. 기준 integration/additional-features `240cb39`.
> 사전 점검 당시 판정: **BLOCKED — 계정/API 이용 및 WS 경쟁 조건 확인 필요**.
> 사전 점검 당시 OAuth/시세 REST 0회, WebSocket 연결 0회, 실시간 이벤트 0개. 다음 실관찰 결과를 대체하지 않는다.

## 1. 이번 사전 점검

기존 준비 문서·진단 도구 소스와 최신 Git 상태를 읽고, 기존 진단 private 설정을 로컬에서 확인했다. 키/Secret/Token/fingerprint/인증 원문은 출력하거나 문서에 기록하지 않았다.

| 항목 | 결과 | 근거 및 한계 |
| --- | --- | --- |
| 코드·실행 브랜치 | PASS | 최신 통합 HEAD 확인. 기존 학습 문서 미추적 변경은 보존 |
| mode | PASS | real |
| App Key/Secret 존재·파일 구조 | PASS | 기존 private_credentials 검사 통과. 실제 인증 유효성은 미검증 |
| private credential 접근 권한 | PASS | 도구의 현재 사용자/SYSTEM 외 Allow 차단 검사 통과 |
| REST endpoint | PASS | https://api.kiwoom.com |
| WS endpoint | PASS | wss://api.kiwoom.com:10000/api/dostk/websocket |
| 의존성 | PASS | 설치된 websockets16.0 확인. 새 설치 없음 |
| 기존 진단 run 재사용 자격 | PASS | events.jsonl 없음. 최초 capture 증거를 덮어쓰지 않음 |
| 로컬 시각 | PASS(관측만) | Windows 표시 KST 09:19:10. 시장 OPEN이나 UTC 오차2초 이내를 이것만으로 확정하지 않음 |
| 계정 API 서비스 활성/실전 키 일치 | BLOCKED | 로컬 설정만으로 공식 계정 등록 상태를 확인할 수 없음 |
| 현재 접속 공인 IP 등록 | BLOCKED | 로그인된 공식 관리 페이지 및 사용자가 확인해야 함. 공인 IP 조회 서비스 호출 안 함 |
| 조회 이용·비용·private 증거 보관 조건 | BLOCKED | 공개 요금 안내를 무료로 추정하지 않음. 계정 적용 조건 사용자 확인 필요 |
| 동일 계정 다른 WS 연결 경쟁 | BLOCKED | 다른 PC/프로세스·토큰 사용을 로컬 파일로 확인할 수 없음. 기존 서비스는 조회/종료/변경하지 않음 |

공식 조건의 참고 출처는 [서비스 이용안내](https://openapi.kiwoom.com/intro/serviceInfo), [사용안내](https://openapi.kiwoom.com/intro?dummyVal=0)다. 이 문서에서 계정별 서비스 상태나 등록 IP를 확인했다고 주장하지 않는다. 기존 문서의 토큰별 세션 제한을 참고하되 다른 연결을 강제로 종료하지 않는다.

## 2. 승인된 관찰 범위 — 필수 조건 확인 후에만 실행

- 기존 deployment/kiwoom-diagnostic.py capture만 사용.
- mode=real, 종목 KRX005930, 비조정1분봉.
- OAuth 최대1 + ka10080 최대3 = REST 최대4.
- WS 최대1, 관찰 최대180초, 저장 이벤트 최대5000.
- 추가 토큰/조회 retry/재연결 없음. 실패하면 이번 증거 보존 및 중단.
- 기존 backend/DB/Anvil/acceptance 접속·변경, 주식 주문/로컬 모의 주문/유료AI/배포 없음.

실제 호출 실행은 사용자 지침으로 조건부 승인됐지만, 누락된 필수 조건을 임의 충족으로 간주하지 않는다. 사용자에게 실전 서비스·IP·이용 조건 및 다른 WS 사용 여부 확인을 요청한다.

## 3. 필수 검증 상태

| 대상 | 현재 판정 |
| --- | --- |
| OAuth 인증 | NOT_RUN |
| WS LOGIN/REG | NOT_RUN |
| 장중 최초0s 전달 | NOT_RUN |
| 0s215=3 및20 시각의 제품5초/미래2초 호환성 | NOT_RUN |
| 0B 정상 수신 | NOT_RUN |
| 0s/0B 같은 connection generation | NOT_RUN |
| 진단 기반 OPEN/LIVE 조건 호환성 | NOT_RUN |
| native1m 시작/종료 가설·OHLCV 대조 | NOT_RUN |
| REST asOf/tick 누락·중복 위험 | NOT_RUN |

초기0s 미제공/과거시각을 OPEN으로 추정하거나 native봉 시각을 보정하지 않는다. 자료 부족은 INCONCLUSIVE로 남길 예정이다. 이번에는 실제 제품 서버를 실행하지 않으므로 서버 OPEN/LIVE 동작·주문 성공을 실검증한 결과로 기록하지 않는다.

## 4. Spring Boot 연결 판단과 다음 단계

**현재 연결 판단은 보류**다. 사전 조건 확인 후 첫 capture 결과를 바탕으로 다음 우선순위를 판단한다.

1. 인증/LOGIN/REG 성공 및 같은 generation의0s·fresh0B를 확보할 수 있는지.
2. 0s 시간/장상태가 현재 제품 정책에 호환되는지. 미호환이면 근거와 별도 수정 후보를 먼저 보고하고 제품 정책은 변경하지 않음.
3. native1m labeling/asOf 위험의 판정 가능한 증거가 있는지. 부족하면 더 긴 관찰/다른 시점 재확인에 별도 호출 승인 필요.
4. 실제 재연결/토큰 만료·장상태 복원은 WS1 제한으로 이번 NOT_RUN 범위이며 별도 승인 후 검증.
5. 제품 서버/웹/서명 견적/Anvil 거래 인수는 이번 읽기 진단과 별도 단계.

이번 단계에서 수정 필요성이 확정된 제품 결함은 없다. 관찰 전 가설을 수정 작업으로 진행하지 않는다.

## 5. 사용자 확인과 실제 실행

사용자는 실전 서비스 신청과 IP 등록이 완료됐고 공식 화면에서 확인했다고 명시했으며, 같은 확인을 반복하지 말고 기존 상한으로 진행하라고 승인했다. **이는 사용자 확인 사실**이며 에이전트가 계정 관리 화면을 직접 조회한 것은 아니다. 별도 중복 서비스 신청은 요구하지 않았다. 다른 PC/동일 계정의 모든 WS 연결 부재를 에이전트가 독립 확인한 것도 아니며 이번 연결에서 충돌 오류는 관찰되지 않았다. 계정 요금/이용 약관을 에이전트가 독립 확인했다는 주장은 하지 않는다.

실제 source HEAD `240cb39`, 기존 도구·코드 변경 없이 다음 명령을 딱 한 번 실행했다.

```powershell
python deployment/kiwoom-diagnostic.py capture --run deployment/runtime/kiwoom-diagnostic-20261013184802-2ab7ce66 --allow-external --max-rest-requests 4 --max-ws-connections 1 --seconds 180 --max-events 5000 --chart-snapshots 3
```

출력과 evidence는 정제된 허용 필드만이다. credential 값·fingerprint·Token·Authorization·원문 인증/시세 응답은 출력하거나 Git에 포함하지 않았다. capture는 프로세스 exit0, `captureResult=COMPLETE`, `captureTruncated=false`로 종료했다. 추가 API 재시도·토큰 갱신·재연결 0회. 종료 후 추가 조사는 저장 이벤트와 제품 소스의 읽기뿐이다.

### 호출·이벤트 수

| 항목 | 실제 값 |
| --- | --- |
| OAuth REST | 1회 성공. 도구 token() 검증을 통과해 이후 WS 로그인/차트로 진행한 실행 흐름으로 확인 |
| ka10080 REST | 3회 성공, 각각 최신20개 봉을 저장. pagination/다른 TR 없음 |
| REST 합계 | 4회, 실패0 |
| WS 연결 | 1회, generation1. LOGIN1/REG1 승인 |
| 관찰 상한 | 180초. 첫 연결 기록 KST09:40:43.223316, 마지막 이벤트09:43:43.137141 |
| 수신 WS frame | 906개. 한 frame에 여러 체결이 있을 수 있어 이벤트 건수와 다름 |
| 송신 WS frame | 19개. LOGIN/REG 및 PING 응답 포함, 주식 주문 아님 |
| 저장 이벤트 | 1,226개: CONNECTED1 + LOGIN1 + REG1 + CANDLE60 + TRADE1,163 |
| MARKET_STATE / REJECTED | 각각0개. 미수신0s를 malformed0s나 stale0s로 해석하지 않음 |
| 0B | 005930 체결1,163개, REGULAR1,163/OTHER0, 시간 호환1,163/비호환0 |
| 표본 가격 범위 | 239,000~240,000, 공급자 가격 필드 정규화 관측. 투자 평가·진위의 독립 거래소 대조 아님 |
| 데이터 절단 | 없음. 저장 상한5000 미도달, 누적량 기반 captureGap=false |

LOGIN ACK는 KST09:40:43.255256, REG ACK는09:40:43.261915. 첫 체결 수신09:40:43.431100, 마지막09:43:43.137141. 시각 원본 정제 필드는 UTC로 저장하고 보고서 표시는 KST로 변환했다.

차트 요청 시각(asOfCandidate)은09:40:43.262941 /09:42:08.236478 /09:43:33.341353. 각각20봉 저장이며 **60개의 독립 완료 구간 검증을 뜻하지 않는다**. 같은/과거 봉이 반복 포함된다.

## 6. 필수 검증 판정

| 번호 | 검증 | 판정 | 근거/한계 |
| --- | --- | --- | --- |
| 1 | OAuth 인증 | PASS | 1회 token 검증 후 WS LOGIN·차트 조회 성공. token 값/응답 원문은 보존 안 함 |
| 2 | LOGIN/REG | PASS | generation1에서 둘 다 result=OK |
| 3 | 장중 최초0s 제공 | FAIL(관찰 요구 미충족) | REG 승인 후180초 안에0s가 한 건도 없음. 공급자의 규격 위반/영구 미지원 원인까지 확정한 판정 아님 |
| 4 | 0s215=3 및20 시간 호환 | INCONCLUSIVE | 0s 자체가 없어 값·나이 비교 불가. 오래된0s 수신 사례도 이번에는 없음 |
| 5 | 0B 정상 수신 | PASS | 1,163개, 가격·수량·누적량·regular/time 호환. 실제 주식 거래 실행 아님 |
| 6 | 0s/0B 같은 generation | INCONCLUSIVE | 0B는 모두generation1, 0s는 없음. 두 종류가 함께 있음을 검증하지 못함 |
| 7 | 제품 OPEN/LIVE 데이터 조건 | BLOCKED | 진단 `productEligibilityEvidence=INCONCLUSIVE`, `stateReceived=false`. fresh0B만으로 명시적 시장 OPEN을 대신하지 않음 |
| 8 | native1m 시작/종료 | PASS(표본1개 시작 가설 지지) | 완전09:42봉의 Hstart OHLCV 일치. 전주기/전시점 보장 아님, 나머지59개 boundaryChecks는INCONCLUSIVE |
| 9 | asOf/tick 결합 누락·중복 | INCONCLUSIVE(차이/위험 조건 관찰) | 진단기 두 PRE_ASOF_MISSING 표시와 아래 수치 확보. 실제 웹 실행 안 했으므로 UI 누락/중복 FAIL을 확정하지 않음 |

**최초 읽기 연결 자체는 성공했지만, 제품의 장중 거래 가용성 인수는 통과하지 않았다.** 현재 `KiwoomPriceProvider.marketStatus`는 같은 generation의 명시적0s가 없으면UNKNOWN, 신규서명 발급은OPEN+LIVE+fresh를 요구한다. 이 자료는 INITIALIZING 이후 LIVE tick 수신 근거가 될 수 있어도 OPEN을 보장하지 못한다. 실제 Spring Boot 프로세스를 시작하거나 서버 응답을 검증하지 않았다.

### native1m 완전 구간의 근거

KST09:43:33.341353에 요청한09:42:00 label 봉을09:42:00~09:43:00의437개 tick과 비교했다. 구간 전후 witness가 있고 전체 captureGap=false다.

| 항목 | REST 봉 | Hstart tick 집계 |
| --- | --- | --- |
| Open | 239,500 | 239,500 |
| High | 240,000 | 240,000 |
| Low | 239,000 | 239,000 |
| Close | 239,750 | 239,750 |
| 거래량 | 67,795 | 67,795 |

Hend=[09:41,09:42) 가설도 관측 범위에 있었지만 같은 OHLCV와 일치하지 않아 도구가START_SUPPORTED로 판정했다. 현재1분봉 label을 임의로1분 빼야 한다는 근거는 없다. 코드의 native timestamp 유지가 이 표본에 맞는다. 새 시각 보정은 하지 않았다.

### asOf 차이 — 진단 원결과와 해석을 분리

기존 진단기의 `asOfChecks`는 MISMATCH2/INCONCLUSIVE58, MISMATCH reason은 두 번 모두PRE_ASOF_MISSING였다. 이 결과는 수정/덮어쓰기하지 않고 private report.json에 그대로 보존한다.

| 봉 label / 요청 KST | REST volume | observedAt<=요청시각 tick 합 | REST-합 | 요청 전에 실제 수신한 tick 합 |
| --- | --- | --- | --- | --- |
| 09:42 /09:42:08.236478 | 20,195 | 20,503 | -308 | 20,194 |
| 09:43 /09:43:33.341353 | 49,645 | 49,743 | -98 | 49,645 |

초 단위 거래시각의 모호성도 별도 비교했다. 요청 초보다 엄격히 이전인 tick 합은 각각20,245/49,712로 REST보다50/67 많았다. 따라서 같은 초에 나중에 발생한 tick이 포함됐다는 설명만으로 전체 차이를 해소하지 못한다. 반면 **수신시각** 기준 요청 전 합과 REST는1/0 차이이므로 전송/버퍼 지연·응답 snapshot 시점/체결시각과 수신시각 차이를 함께 고려해야 한다. 원인을 vendor 지연/제품 버그 중 하나로 확정하지 않는다.

asOfCandidate는 도구의 요청 시각이며 공식 vendor watermark가 아니다. 위 차이는 "요청 시각 이전으로 표시된 모든 tick이 REST에 이미 포함됐다"는 가정이 안전하지 않을 수 있음을 보여준다. 원문 전체 저장이나 추가 조회 없이는 더 강한 원인 판정을 하지 않는다.

**현재 웹 소스 재확인:** `ChartController.load`는 `provider==='SIMULATED'`일 때만 asOf를 `CandleLoadBuffer.resolve`에 전달한다. KIWOOM/Toss는null이며 asOf 이전 tick을 일괄 버리는 경로가 아니다. 따라서 과거 계획의 "키움 웹에서asOf이전tick을버려누락" 설명은 현재 코드에 그대로 적용되지 않는다. 외부 공급자의 REST에 이미 포함된 buffered tick을 모두 다시 더할 때의 중복 가능성도 별도 검증 대상이다. 이번 캡처는 실제 웹/REST/STOMP와 동시에 실행하지 않았고, 도구의 REST 호출은 같은 수집 thread에서 WS 처리를 잠시 늦출 수 있어 브라우저 비동기 실행과 동일하지 않다.

코드 근거: `tools/websocket-test-client/src/chart-controller.js`의 load/tick, `src/candles.js`의 resolve; `KiwoomCandleProvider.candles`는 요청 시작의now를asOf로 반환한다. 실행 결과를 이유로 현재 웹·서버 코드를 수정하지 않았다.

## 7. 서버 연결 판단과 우선순위

**판단:** 인증과 읽기 transport 연결은 실제 확인됐다. 그러나 최신 Spring Boot에 연결해 **신규 온체인/조건부 거래를 활성화하는 인수는 보류**한다. 0s 없이 OPEN을 추정하지 않는 현재 정책을 유지한다. 읽기 전용 서버/화면 연결 검증도 별도 실행 단계이며 이번에 backend를 켜지 않았다.

1. **P0 — 장중0s bootstrap 원인 조사:** 공식 구독 규격과 현재 subscription payload, 0s가 초기 snapshot인지 상태 변화 이벤트인지 확인. 이번 REG 승인과0B만으로0s 등록/초기전달을 입증하지 못한다. 초기 상태를 확인할 공식 대안이 필요하다면 근거·설계·최소수정안을 먼저 보고한다. 사용자 시각을 OPEN 판정으로 대신하거나5초/미래2초 정책을 완화하지 않음.
2. **P1 — 외부 봉/tick 결합 계약 검증:** 요청/응답/체결/수신 시각과 누적량을 함께 대조해 snapshot 중복·누락을 검증. 현재 외부 asOf를 버림 기준으로 쓰지 않는 웹 경로를 기준으로 평가. 가정으로 timestamp shift나asOf 버림을 추가하지 않음. 이번 단일 완료봉 START_SUPPORTED는 보존하고 여러 완료봉·웹 버퍼 구간은 추가 승인된 관찰에서 확인.
3. **P2 — 실제 재연결·token/장상태 복원:** 이번 WS1 상한 때문에NOT_RUN. 새로운 private run과 별도 호출 상한 승인 후 진행. 기존run은capture가 있으므로재실행/로그덮어쓰기금지.
4. **P3 — 새 격리 서버/웹·서명견적·Anvil 인수:**0s시장확인방향과차트검증계획을닫은뒤 별도 승인. 기존DB/Anvil/acceptance를재사용·초기화하지 않음. 이번자료만으로BUY/SELL/Oracle/receipt/개인WS/포트폴리오PASS를선언하지않음.

예상 수정은 **후보**이지 이번에 확정/구현한 사항이 아니다.0s초기확인과외부REST/tick중복경계가우선조사대상이며,인증·실시간체결·native1m파서를통째로교체할필요가확인된것은아니다.

## 8. 증거와 보존

- 기존 진단 run의 private `events.jsonl`·`report.json`에 정제자료를 보존했다. credential과원문응답은공유하지않는다. 같은run capture 재실행안함.
- DB/Anvil/backend/acceptance와기존실패·테스트증거는접속·변경하지않았다. 사용자학습문서미추적파일도보존한다.
- 이번새실행은진단1회뿐이며제품코드/정책/테스트·컨테이너/키를변경하지않았다. 실제주식주문·로컬모의BUY/SELL·유료AI·외부배포0회.
- 보고서는허용field와집계만기록한다. private runtime/로그/state/실제.env/secret을Git에추가하지않는다.
- 이번관찰·보고는코드동작변경이없어새독립구현검토를실행하지않았다. 이전도구독립검토완료와이번실관찰결과를구분한다.
