# 키움 실제 시세 인수 사전 준비 및 판정 절차

> 2026-10-14 후속: 기존진단으로최초실전장중관찰1회완료(REST4/WS1/180초). 인증/구독/0B성공,0s미수신으로제품OPEN인수는BLOCKED. native1m시작가설1표본과asOf차이를확보했으며현재웹은KIWOOM에asOf폐기cutoff를적용하지않으므로아래누락가설을그대로현재웹결함으로해석하지않는다. [실행결과·현재코드대조·다음검증](kiwoom-live-readonly-acceptance-2026-10-14.md). 실제서버/주문/재연결/유료AI는NOT_RUN. 아래계획·미실행표현은이전기록이다.

> 2026-10-14. 계획만 작성. 실제 키움/Toss/유료 AI 호출, 계정 생성, 키 주입, 신규 실환경 구축은 NOT_RUN이다. 제품 변경이나 실환경 인수 승인 문서가 아니다.

## 최신 착수 판단

통합a317f67의 새 Synthetic PostgreSQL/Anvil 필수E2E5/5가 실제 PASS했다. [실행 증거와 범위](phase-a-subnet-retry-validation.md). 격리 기반 검증 차단은 해소됐다. 후속으로 [읽기 전용 진단 도구](kiwoom-readonly-diagnostics.md)를 구현·Mock 검증했으며 실제 App Key/Secret·mode/endpoint·접근/IP 확인과외부호출승인이남아있다. 초기profile은도구안내의token1+1분봉3/WS1·180초로축소하여제안한다. 장중최초0s bootstrap·분봉timestamp·asOf를먼저확인하고통과후에만실데이터BUY/SELL 인수를검토한다. 기존Synthetic5PASS나진단Mock는실제키움정상동작증거가아니다.

## 전제와 승인

사용자가 키움 REST 서비스의 이용 자격·시세 권한·접근/IP 등록 조건을 공식 계정에서 확인하고 App Key/Secret을 전용 runtime에 준비해야 한다. 실제/모의 키와 endpoint를 혼용하지 않는다. 필수 설정 이름은 PRICE_PROVIDER=kiwoom, KIWOOM_APP_KEY, KIWOOM_SECRET_KEY, KIWOOM_API_BASE_URL, KIWOOM_WEBSOCKET_URL이다. 실제 값은 문서/일반 로그/Git에 남기지 않는다. 현재 기본 endpoint는 api.kiwoom.com의 HTTPS 및10000포트 WSS이며 모의는 mockapi.kiwoom.com의 짝으로 설정한다. `.env.example`은 이름 안내만 제공한다.

실제 시세는 별도 승인된 신규 DB/Anvil/runtime에서만 인수한다. 기존 acceptance/devDB/chain/.env는 재사용하지 않는다. 실제 주식 주문·계좌 API·Sepolia 거래는 없다. AI 기능은 비활성화하고 유료 색인을 하지 않는다. 거래를 검증할 경우 신규 로컬 Anvil 전용 operator/price signer, Vault 유동성·불변 대사 기준점 준비는 별도 실행 승인에 포함해야 한다.

공식 근거: [키움 REST 문서](https://openapi.kiwoom.com/m/guide/apiguide), [공식 spec](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json). API 지원과 실제 계정/IP/장중 전달 동작은 다르며 등록 조건·요금은 실행 전 공식 계정에서 재확인한다.

## 1. 장중 최초 연결의0s 검증

개장 전에 연결해 상태 전이를 기다리는 테스트와 **이미 장중인 시점의 최초 연결**을 분리한다. 현재 코드가 최초 REG 직후 시장 snapshot을 반드시 받는다고 가정하지 않는다.

1. 호스트/JVM/RPC 시각을 기록하고 신규 실행 generation=G를 식별한다. LOGIN 성공 ACK(return_code=0) → REG 성공 ACK 순서를 확인한다. 신규 서명 견적은 이 단계에서 아직 발급하지 않는다.
2. 실제 발신 구독은 KRX005930의0B와 item빈문자열의0s다. 최대60초 관찰하는 것을 초기 인수 상한으로 제안한다(제품 timeout 변경 아님). 성공 REG만 보고 OPEN으로 판단하지 않는다.
3. 허용필드만수집한다:수신UTC/generation·고정이벤트종류,검증된시장상태/관측시각/시간호환,005930가격·체결량·누적량·안전한OHLCV/native시각/asOf후보다. **LOGIN token/App Key/Secret/Authorization/header·원문오류는기록하지않는다.** 현재공급자는원문frame을영속로그로남기지않으므로별도[진단도구](kiwoom-readonly-diagnostics.md)를사용한다. 원본필드전체가아닌정제값만기록하며외부실행은별도승인이필요하다. REST시장응답만으로0s수신을증명하지않는다.
4. 0s가 왔는지, item이 현재 parser와 일치하는지,215=3인지,20이 현재 시각인지 또는 개장 전이 시각인지 확인한다. 현재 `KiwoomProtocol.time`은0s와0B 모두 수신일KST로 해석하고 나이≤5초/미래≤2초를 요구한다. 예를 들어 오전에 연결했는데0s/20이개장시각을 그대로 반환하면 현재 parser는 거절할 수 있다. payload가 없거나 오래되었으면 장중 최초 연결 지원은 **BLOCKED/FAIL**로 기록하고 기준을 완화하지 않는다.
5. 같은 generation에서 fresh0s=OPEN과0B 체결·연결을 모두 확인한 뒤 서버 시장 API의 providerKIWOOM/OPEN/LIVE/observedAt을 대조한다.0B의290=2 또는 실제 주가가 보인다는 이유만으로 OPEN을 대신 확정하지 않는다.
6. 새로운 연결 G+1에서도0s 확인 전 UNKNOWN, fresh0B만 받아도UNKNOWN, G의 지연0s가G+1에 적용되지 않는지 확인한다. 새 연결의 유효0s 이후에만 정상화해야 한다. 재연결/토큰 갱신 검증은 별도 횟수 상한 승인을 받는다.

**통과:** 장중 최초 구독·재연결 모두 같은 연결의 명시적 시장 확인과 fresh tick을 증거로 제시하고 실제 서버 상태와 일치한다. **실행불가/증거부족:** 시세 접근·수집 도구·OPEN 확인이 없으면 BLOCKED, 실제 실행하지 않았다면 NOT_RUN. 등록 응답만으로 PASS하지 않는다. 상태 bootstrap가 지원되지 않으면 공급자의 공식 현재 상태 조회 대안 등을 조사하고 제품 수정은 다시 승인받는다.

코드: `market/provider/kiwoom/KiwoomProtocol.java`, `KiwoomRealtimeClient.java`, `KiwoomPriceProvider.java`, `blockchain/oracle/PriceReportIssuer.java`.

## 2. 분봉 timestamp의 시작/종료 경계 검증

현재 `KiwoomCandleProvider`는 ka10080 cntr_tm을 KST→UTC로 변환해 그대로 startedAt에 넣는다. Toss처럼 주기를 빼지 않는다. 문서의 체결시간이라는 이름만으로 시작/종료 경계를 확정하지 않는다. 가격은 비조정 OHLC이며 volume은 주식수량이다.

1. 별도 승인된 read-only 수집으로 **완전한1분 구간과 양쪽 인접 구간**의0B tick을 누적한다. 구간 경계 직전/직후 수신을 포함하고, KST표시시각·수신UTC·0B20·누적량13·개별량15·가격10을 기록한다. 현재 parser가 쓸 UTC와 rawHHmmss를 함께 대조한다.
2. 완료된 구간을 같은 종목/비조정 정책의 ka10080으로 조회한다. interval=1의 cntr_tm·OHLC·trde_qty와 REST 시장 캔들 응답 startedAt을 비교한다. 인접 봉 포함 기준으로 다음 두 가설을 비교한다: Hstart=[T,T+1m), Hend=[T-1m,T). 체결 가격의 open/high/low/close와 개별 체결량합·누적량 차이를 검증한다.
3. 같은 초 복수 체결은 누적량으로 구분하며 수신 누락·역순·거래 없는 구간·가격이 우연히 동일한 구간은 판정 근거가 부족할 수 있다. 일반 화면의 시간 라벨만으로 확정하지 않는다. tick자료가 불완전하면 INCONCLUSIVE로 기록하며 REST누락과 체결량 차이를 무조건 제품 버그로 단정하지 않는다.
4. 분 경계 직전·직후 같은 REST 봉을 재조회하여 현재 미완성 봉의 labeling/거래량 누적 방식을 확인한다. 이후5m/15m/30m/60m도 native주기와 웹bucket을 비교한다. 현재 코드의 주기정렬 검사나 future봉 거절이 실제 규격과 충돌하면 증거를 보존하고 별도 수정 승인으로 넘긴다.
5. `before` 배타적·nextBefore·cont-yn/next-key로 과거 페이지를 확인하며 중복·구간 누락을 검증한다. 일봉 dt의 KST날짜와 비조정OHLC·주식수량을 별도로 확인한다. 하루 종료와 closed flag도 코드의 시간bucket 기준이 vendor표시와 같은지 구분한다.
6. **asOf도 별도 검증한다.** 현재 키움 캔들 asOf는 조회 시각이며 vendor가 그 시각까지 모든 체결을 포함했다는 watermark 보장이 아니다. 웹이 asOf 이전 tick을 버리는 경계에서 거래량/가격 누락이 발생하는지 수집 증거로 확인한다. 문제 발생 시 이번에 임의 보정하지 않고 REST-asOf·live tick 결합의 최소 수정안을 제안한다.

**통과:** 여러 변동 구간의 전체tick과 native OHLCV가 특정 경계 가설을 일관되게 지지하고 서버startedAt/웹현재봉/과거page와 맞는다. **실패:** 반복된 정확히 한 주기 밀림·정렬거부·완전tick 대비OHLCV/중복 누락을 재현한다. **불확실:** 제한된tick/다른조정정책/무거래 때문에 두 가설을 구분 못하면 INCONCLUSIVE이며 임의shift 하지 않는다.

코드: `KiwoomRestClient.chart`, `KiwoomCandleProvider`, 웹 `chart-time.js`, `candles.js`, `chart-controller.js`. 실환경 준비 문서는 미검증 가능성을 드러내는 것이며 발견된 제품 결함을 확정한 문서가 아니다.

## 호출과 비용 승인 경계

초기 read-only 후보는 token발급1, 현재가1, 분봉1m 전후3, 다른분주기4, 일봉1의 **REST10회**와 WS접속1/구독1·60초 관찰이다. pagination/재연결/토큰 만료 추가 호출은 별도 상한으로 승인받으며 자동 전체history 스캔하지 않는다. 위 짧은 관찰만으로 모든 봉을 검증할 수 없고 이후 완전5/15/30/60분 관찰은 필요한 기간·호출수를 다시 승인받는다. 정확한 키움 이용 비용과 접근 권한은 미확정이며 무료라고 가정하지 않는다. 유료AI는0회다. 실행 전 계정/키·전용runtime·수집도구·관찰구간·외부호출수를 사용자가 승인해야 한다.
