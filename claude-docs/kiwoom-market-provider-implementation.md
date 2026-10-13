# 키움 읽기 전용 시세 공급자 — 구현·검증 기록

> 2026-10-13. 현재 진행 중. 실제 App Key/외부 키움 호출 없음. 완료/실환경 인수를 의미하지 않는다.

## 범위와 근거

최신 통합 `a626d7a`에서 `feat/kiwoom-market-provider`를 생성했다. Toss/Synthetic는 유지하며 신규 `PRICE_PROVIDER=kiwoom` bean만 선택한다. 키움 주식 주문·계좌 API는 없다. 공식 [REST 문서](https://openapi.kiwoom.com/m/guide/apiguide), [공식 API spec](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json), [인증](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/core/auth.py), [WS](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/core/ws_client.py)를 조사했다. 공식 문서 조회와 실제 broker 호출은 구분한다.

## 구현 위치와 계약

- `backend/.../market/provider/kiwoom/`: 설정, 인증/REST, 프로토콜, 단일 WS 연결, 가격·차트 공급자. REST는 ka10001/ka10080/ka10081 고정 조회만 제공한다.
- JSON client_credentials, 메모리 토큰, expires_dt KST→UTC, 만료30초 전 갱신. HTTP401/공식 만료 코드8005·8031·8103에 한 번만 조회 재시도. 원문 응답/토큰/키는 공개 예외와 로그에 넣지 않는다.
- KRX `005930`만 수신한다. NXT/SOR 종목·미지원 시장 상태는 추정하지 않는다. 가격 부호는 크기, 등락금액은 부호, 체결량은 크기, 누적량은 중복/역순 판정에 사용한다.
- ka10001에는 체결 시각이 없어 REST bootstrap은 INITIALIZING/UNKNOWN이다. LIVE는 실시간 체결과 연결 확인 후에만 가능하다. HHmmss는 수신일 KST 기준 변환하되 지연5초/미래2초를 초과하거나 날짜가 모호한 프레임은 거절한다.
- OPEN은 명시적인 당일0s/215=3에만 의존한다. 초기 상태 메시지가 제공되지 않거나 검증할 수 없으면 UNKNOWN으로 거래를 차단한다. 임의 개장 시간이나 체결 수신만으로 OPEN을 추정하지 않는다.
- LOGIN 성공 후 REG(0B,0s), PING echo, 연결 generation과 종료 후 늦은 프레임 차단, 1~30초 재연결, token 갱신 재연결. 연결 단절 DEGRADED/노후 STALE이며 신규 견적의 OPEN+LIVE+fresh 경계는 공통 issuer가 유지한다.
- 차트는 native1/5/15/30/60분 및 일봉, 비조정 OHLC/주식 수량을 변환한다. cont-yn/next-key를 최대8페이지 조회하고 반복·불완전 cursor는 오류 처리한다. `before`는 배타적이며 중복/잘못된 OHLC를 거절한다.
- **분봉 경계 한계:** 공식 spec의 cntr_tm을 그대로 보존하며 Toss의 종료→시작 보정을 임의 적용하지 않는다. 실제 vendor 봉 labeling/미완성 봉과 웹 tick bucket 일치 여부는 App Key 연결 후 확인할 항목이다. 미정렬 timestamp는 추측 보정하지 않고 거절한다.
- 조건부 주문의 TOSS 이름 제한을 실제 공급자 공통 경계로 대체했다. 기존 Synthetic 격리 fixture/public 차단·fresh/잠금·복구/정산 계약은 유지한다. AI 시세 Tool은 기존 MarketPriceService를 그대로 조회한다. 웹은 KIWOOM 출처만 표시한다.

## 검증 상태

컴파일 PASS. 신규 REST/프로토콜/가격/차트/WS Mock 테스트와 기존 회귀는 실행 후 수치와 검토 결과를 추가한다. 실제 키움 인증, IP 허용, token 만료 응답, live WS, 장중0s 초기 전달, 차트 경계, 외부 rate limit은 NOT_RUN이다. 실제 Toss·유료AI·Sepolia·AWS 및 기존 acceptance/DB/chain은 접근하지 않는다.

## 최초 실행 및 검토

최초 구현 session `phase-a-20261013141811-33461756`: backend485 total /450 PASS /35 SKIP /0 FAIL·ERROR, 웹84·Chrome desktop/mobile·Foundry36 PASS。실제 신규 Synthetic PG/Anvil `conditional-3941a775`(DB33721/RPC48644) 1 PASS, 별도 JVM restart1 PASS. 키움 신규 Mock18개와 KIWOOM 조건부 H2/Mock1개 포함이며 실제 키움 E2E가 아니다.

독립 최초 검토 `review_kiwoom_market`는 재연결 중 누락된 시장 상태를 이전 OPEN으로 대체할 수 있는 **필수 결함1개**를 지적했다. 거래 통합을 보류하고 WS frame에 연결 generation을 포함하며 현재 연결과 같은 generation의0s만 시장 확인으로 사용하도록 수정했다. 짧은 재연결의 기존 fresh tick, 새 tick만 수신, 과거 generation 늦은0s, 새0s 정상 복원을 검증하는 회귀1개를 추가했다. 최초 PASS는 수정 소스 PASS로 덮어쓰지 않는다. 수정 후 신규 session `phase-a-20261013142757-26bb2975` 결과와 재검토는 완료 후 기록한다.

## 수정 후 최종 기능 검증

- session `phase-a-20261013142757-26bb2975`: backend **486 total /451 PASS /35 SKIP /0 FAIL·ERROR**. 신규 키움 Mock19개와 KIWOOM 조건부 H2/Mock1개 포함. 웹84/Chrome desktop·mobile fixture/Foundry36 PASS.
- 신규 실제 Synthetic PG/Anvil `conditional-e36e45b2`(DB44473/RPC35721) **1 PASS**, 별도 JVM snapshot restart **1 PASS**. 기존 즉시·조건부/EIP712·raw 복구/receipt/review·Faucet/두대사/관측 경계의 호환 E2E이며 실제 키움 broker E2E가 아니다.
- 같은 session에서9개 지식 문서/manifest 갱신 후 **별도 `backend-knowledge-refresh` 전체 회귀486 total /451 PASS /35 SKIP /0 FAIL·ERROR**. 이전 XML을 재사용하지 않고 새 증거 디렉터리에 보존했다. KnowledgeLoader의9문서/역할/hash/크기 검증 포함. 실제 embedding/index 발행·retrieval 재평가·LLM 호출은 NOT_RUN이며 과거 검색 수치를 새 corpus 평가로 쓰지 않는다.
- 독립 첫 재검토 **발견된 필수 수정 없음**. reviewer는 generation 수정/추가 회귀,9문서 및 normalized hash/version9/9·USER8/ADMIN1·diff check를 직접 확인했다. 테스트를 직접 재실행하거나 private runtime을 열지 않았으며 위 실행 수치는 구현자의 새 runner 결과다.
- 이번 작업의 새 격리 컨테이너18개는 정확한 fixture label/이름으로 정지·소유권을 확인했다. 상태/로그는 보존하며 기존 acceptance/devDB/Anvil/runtime은 조회·변경하지 않았다. 기능 커밋/통합과 병합 소스 결과는 후속 기록한다.

## 실제 연결 준비

사용자가 발급한 적절한 키움 REST App Key/Secret, 실제/모의 endpoint 조합, 공식 서비스의 계정·접근/IP 조건 확인과 별도 외부 호출 승인 필요. 실계좌/모의 키를 혼용하지 않는다. 실제 값은 전용 runtime secret으로만 준비하며 기존 .env를 수정하거나 자동 import하지 않는다. 시세 read-only라 해도 실환경 검증 전 LIVE 인수 완료라고 표시하지 않는다.
