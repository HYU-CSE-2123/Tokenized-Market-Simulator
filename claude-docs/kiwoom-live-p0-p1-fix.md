# 키움 첫 장중 증거 후속: P0 최소 수정 및 P1 오프라인 분석

> 2026-10-14. 기준 integration/additional-features `240cb398`, 작업 feat/kiwoom-live-fixes. 실제 새 broker 호출/인증정보 사용/DB/RPC/서버 변경 없음. 기존 사용자 미커밋 문서와 playground 변경을 보존한다.

## 1. 확인된 사실과 아직 확정할 수 없는 원인

기존 [실관찰 보고서](kiwoom-live-readonly-acceptance-2026-10-14.md) §5~8 및 보존된 정제 events.jsonl만 읽었다. credentials.json은 열지 않았다. 09:40:43 연결, LOGIN/REG 승인, 180초 동안 0B 1,163건·0s 0건은 그대로다. 과거 증거/판정을 수정하거나 새 PASS로 덮어쓰지 않는다.

### 구독 형식 — 확인된 구현 차이, 원인 확정 아님

- 공식 [async 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/국내주식/실시간시세/subscribe_domestic_market_open_time_async.py)와 [pubsub 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/국내주식/실시간시세/subscribe_domestic_market_open_time_pubsub.py)는 모두 0s 등록 `item: []`를 사용한다.
- 제품 KiwoomProtocol.subscribe 및 기존 진단 capture는 `item: [""]`였다. 빈 배열과 빈 문자열 요소 배열은 같지 않다. 공식 예제에 맞춰 빈 배열로 최소 수정했다. 0B는 기존 005930, LOGIN→REG 순서 및 generation 경계 유지.
- REG ACK는 요청 승인 근거이지만 0s 초기 delivery 보장은 아니다. 수정한 payload를 아직 실제 서버에 보내지 않았으므로 과거 미수신 원인을 구독 오류로 확정하거나 문제 해결 PASS를 선언하지 않는다.

### 이벤트 특성·초기 상태 — 불확실성 유지

공식 [API 가이드](https://openapi.kiwoom.com/m/guide/apiguide)와 [공식 전체 spec](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json)의 장시작시간(0s)은 장 전환 알림과 215 상태 코드/20 HHmmss를 설명한다. 구독 직후 당일 현재 상태 snapshot의 보장, 주기, 재연결 replay 계약은 확인하지 못했다. 09:40 늦은 연결에서 전환 이벤트를 기다리느라 0건일 가능성은 **가설**이다. '상태 변화 시에만 발행'으로 확정하지 않는다.

공식 REST 응답 필드 조사에서 명시적인 KRX 장운영구분 bootstrap 계약을 확보하지 못했다. ka10007 시각·현재가, ka10100 종목정보 등으로 OPEN을 추론하지 않는다. 0D에도 215 이름이 있으나 설명/초기 전달 계약이 부족해 대체 공급자로 추가하지 않았다. API catalog 검색 결과를 모든 대안의 부재 증명으로 쓰지 않는다.

### 제품 모델 — 확인된 동작과 결정 보류

- KiwoomProtocol.state는 0s에도 trade와 같은 time()을 호출해 수신 시 과거5초/미래2초를 적용한다.
- KiwoomPriceProvider는 받아들인 상태를 같은 connection generation·같은 KST 날짜에 유지한다. 매 current()마다 0s 나이가5초를 넘었다고 만료시키는 것은 아니다.
- 만약 bootstrap/replay의 20이 원래 장 전환 시각이라면 현재 parser가 거절할 수 있다. 하지만 그런 실제 프레임은 이번 capture에 없다. 상태 이벤트 시각과 체결 최신성은 개념적으로 다르지만, 날짜 없는 HHmmss에 더 긴 허용치를 적용할 공급자 근거는 미확보다.
- 상태 parser·5초/미래2초·Oracle TTL·OPEN+LIVE+fresh 정책은 변경하지 않았다. 같은 generation 명시적0s 없는 fresh0B는 UNKNOWN이다. 재연결은 이전 OPEN을 승계하지 않는다.

## 2. P1 저장 자료 오프라인 재현

원본 events 1,226, MARKET_STATE0. analyze 결과 START_SUPPORTED1/INCONCLUSIVE59, PRE_ASOF_MISSING2/경계·coverage불명58를 재현했다. 기존 report/events를 다시 쓰지 않았다.

- 09:42 완료봉 START_SUPPORTED 및 기존 OHLCV 239500/240000/239000/239750, volume67795·tick437 근거 유지. timestamp 이동 없음.
- 새 순수 함수 asof_timing_comparison은 두 시각 기준 합계를 **별도** 계산하고 항상 INCONCLUSIVE/watermarkGuaranteed=false로 표기한다. generation과 NATIVE_START 가정도 명시하며, 자체적으로 모든 봉의 시작 label을 증명하지 않는다. transport·키·제품 상태 변경 없음.

| 요청·봉 KST | REST volume | 체결시각≤요청 합 | REST−합 | 수신시각≤요청 합 | REST−합 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 09:42:08 /09:42봉 | 20195 | 20503 | -308 | 20194 | 1 |
| 09:43:33 /09:43봉 | 49645 | 49743 | -98 | 49645 | 0 |

09:40 bootstrap 봉은 capture 이전 구간이 없어 부분 표본이다. 새 비교의 큰 차이를 결함으로 해석하지 않는다. observedAt/receivedAt/요청 시간은 서로 다른 경계이며 요청 시간은 공식 snapshot watermark가 아니다. 도구가 같은 수집 thread에서 REST를 수행하여 WS 처리를 늦출 수 있는 한계도 유지한다.

웹 ChartController.load/tick 및 CandleLoadBuffer.resolve 재확인: asOf cutoff는 SIMULATED만 사용, KIWOOM/Toss는 null이다. 외부 buffered tick이 REST 봉에 이미 들어 있으면 중복될 가능성은 남지만 이번 자료에 실제 웹 요청/버퍼 증거는 없다. 전체 tick 폐기·timestamp 이동·차트 제품 변경은 하지 않았다.

## 3. 변경 위치

- backend/.../kiwoom/KiwoomProtocol.java: subscribe의 0s item만 []로 수정.
- backend/.../kiwoom/KiwoomProtocolTest.java: 정확한 배열·005930 유지 및 오래된 상태 거부 회귀.
- deployment/kiwoom_diagnostic_core.py: subscription() 공통 payload와 asof_timing_comparison() 오프라인 정제 집계.
- deployment/kiwoom-diagnostic.py: capture REG가 subscription() 사용. 실행 gate/상한/재시도 정책 유지.
- deployment/test_kiwoom_diagnostic.py: payload·실제 Mock capture 송신·시각별 합계·원본 불변성 테스트.

KiwoomRealtimeClient/PriceProvider/차트/거래/계약은 수정하지 않았다. 일반 로그에 provider 원문·token·key·fingerprint를 추가하지 않는다.

## 4. 이번 실행과 검토

- Python -S 진단 Mock/순수 테스트28/28 PASS, FAIL0/SKIP0.
- Java 키움 테스트20/20 PASS, FAIL0/ERROR0/SKIP0; 실제 broker/Anvil 테스트 아님.
- 웹84/84 PASS, FAIL0/SKIP0.
- 기존 private capture 읽기는 sandbox 권한 차단1회 후 승인된 read-only 실행으로 완료. 증거 내용 읽기 전후 동일 확인.
- 백엔드 전체 기본 회귀: `gradlew.bat test --offline --no-daemon --rerun-tasks`,487 total/452 PASS/35 SKIP/FAIL0/ERROR0, 3분18초. gated 실제DB/체인/외부AI 테스트의 SKIP은 PASS에 포함하지 않았다. 키움 선택 실행20개와 전체487개는 서로 중복되므로 합산하지 않는다.
- Java 선택/전체 실행 XML은 ignored deployment/runtime/kiwoom-offline-fixes-20261014의 kiwoom-tests/backend-full에 따로 보존했다. 원본 capture는 읽기만 했다.
- 독립 최초/첫 재검토 모두 발견된 필수 수정 없음. reviewer 직접 Python28/28 실행 및 공식 async 예제 확인; Java/웹/capture 집계는 구현자 결과로 구분했다. 선택 제안 generation/시작가정 표시를 반영하고 재검토 완료. 공식 pubsub 예제는 구현자가 확인했고 reviewer의 별도 조회는 실패했다.
- 새 실제 키움/Toss·유료 AI·PG/Anvil E2E·Foundry·웹 실서버 인수는 NOT_RUN. 기존 playground/backend 및 acceptance를 재시작·정지·초기화하지 않았다.

## 5. Spring Boot 검증으로 가기 위한 최소 다음 절차 — 사용자 승인 필요

1. 수정된 item=[]가 실제 장중 연결에서0s를 제공하는지 새 private run으로 확인한다. 제안 상한: OAuth1+1분봉3=REST4, WS1,180초,5000events,005930만. retry/reconnect0, 주문0, 실제 계정 기존 연결 경쟁 사전 확인. 아직 실행하지 않는다.
2. 0s의 수신시각/20/215/동일 generation을 판정한다. 미수신이면 OPEN을 만들어 서버 거래로 넘어가지 않고 공식 문의 또는 별도 승인된 장 전환 전 연결 관찰이 필요하다. 오래된20을 받으면 별도 상태 시각 계약 결정부터 한다.
3. 시장 증거를 확보하면 최신 소스의 신규 격리 Spring Boot+DB/Anvil 환경에서 먼저 읽기 REST/STOMP/차트 확인, 이어서 별도 승인된 모의BUY/SELL·quote/정산·개인WS 인수. 기존 환경·기준점 재사용 없음.

현재 판정: 공식 예제 차이 수정/오프라인 분석 PASS, 실제0s bootstrap 해결과 제품 OPEN/LIVE 인수는 BLOCKED(추가 근거 필요). 외부 API 동작을 Mock 성공으로 대체하지 않는다.

변경은 기능 작업 트리에 미커밋으로 유지한다. 기존 사용자 dirty 문서와 이번 변경을 함께 자동 커밋/통합하지 않았다. API 키·로그·DB/chain·build는 Git에 추가하지 않았다.

## 6. 수정 payload 재관찰 준비 — 외부 승인 대기

2026-10-14, feat/kiwoom-live-fixes/HEAD240cb398 확인. 기존 보존 XML에서 키움20/20 및 전체487 total/452PASS/35SKIP/실패0을 재확인했다. **이번 준비에서 테스트를 재실행한 결과가 아니다.** 기존 Python28/웹84 및 독립 검토 기록도 이전 실행으로 유지한다.

- 새 private run: `deployment/runtime/kiwoom-diagnostic-20261014022735-a84f1564`.
- 기존 private 설정은 로컬 ACL/구조 검사 후 새 run에만 복사해 준비했다. mode=real, 키 필드 존재, 사용자/SYSTEM 제한 ACL 확인. 실제 값·키 fingerprint·인증 원문은 출력/문서화하지 않았다. 기존 설정/증거는 쓰지 않았다.
- prepared.json에 허용된 REG payload, 상한, 소스 hash와 승인 대기 상태를 보존했다. 0B item=[005930], 0s item=[], LOGIN 후 REG를 사용한다. 이는 **준비된 payload**이며 실제 송신 증거가 아니다.
- REST/WS endpoint는 real 설정에서 각각 api.kiwoom.com과 wss://api.kiwoom.com:10000/api/dostk/websocket으로 결정된다. 서비스 신청/IP 등록은 이전 사용자 확인을 그대로 인정하고 반복 요청하지 않았다. 다른 WS 세션을 종료하거나 새로 접속하지 않았다.
- 새 events.jsonl/report.json은 아직 없다. OAuth/REST/WS/주문/AI/배포0, DB/Anvil 미접근. 외부 호출의 추가 승인을 받기 전에는 capture를 실행하지 않는다.

승인 요청 범위: OAuth1회, ka10080 1분봉최대3회(REST총4), WS1회,180초,5000이벤트,005930만. retry/reconnect/추가token 없음. 실패하면 중단하고 새 증거 보존.

실행 직전 prepared.json의 소스와 현재 코드를 대조한 뒤, 변경된 REG의 송신 경로·LOGIN/REG ACK·0s215/20·같은generation의0B·제품OPEN/LIVE/fresh를 각각 판정한다. 현재 도구는0s를 정제된 상태/observedAt으로 기록하므로 원래215의 정확한 CLOSED/UNKNOWN 코드까지 보존하는 것은 별도 허용 필드 증거가 필요하다. 준비 manifest만으로 실제 송신이나 수신을 PASS로 기록하지 않는다.

0s 미수신/과거시각/다른generation이면 OPEN을 추정하지 않는다. payload수정으로도 초기제공 불확실성이 남으면 공급자 문의 또는 별도 승인된 장 전환 전 연결 관찰을 다음 후보로 보고한다. SpringBoot·모의BUY/SELL·새환경배포는 이 capture 승인에 포함되지 않는다.

## 7. 승인된 실제 재관찰 결과

사용자별도승인후위새run에서1회capture실행. REST4/WS1/180초,LOGIN/REG성공,item=[]실제송신확인,0B807건(모두generation1·시간호환),0s0건.연결은COMPLETE로정상종료했으나OPEN인수BLOCKED유지,추가호출/재연결/주문없음. [새 실행 상세 보고서](kiwoom-live-recapture-2026-10-14.md)에시각/송신증거/판정/원인미확정과다음승인경계를기록했다. §6은실행전준비이력으로보존한다.
