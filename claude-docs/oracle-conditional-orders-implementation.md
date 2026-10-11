# Oracle 가격 조건부 주문 — 구현·검증 기록

> 2026-10-12 / feat/oracle-conditional-orders / 기준 integration/additional-features 0a3b67e. 기능 구현·전체 회귀·독립 재검토 완료, 통합 병합/병합 회귀 대기. 기존 acceptance/dev 환경은 보존한다.

## 실행 의미

BUY는 기준 시세 및 새 서명 가격이 목표 이하, SELL은 목표 이상일 때 기존 Vault 거래를 시도한다. 토큰18/가격8 decimals를 정확한 정수로 비교한다. BUY 입력은 수수료 포함 총 mKRW, SELL 입력은 mSEC 수량이다. 대기 중 견적·서명·자산 예약이 없다. 조건 충족 후 새 견적을 발급하며 실제 서명 가격을 다시 검사한다. 따라서 트리거≠체결 보장이며 짧은 보고서 유효기간 중 기초자산 시세 변화도 보증하지 않는다.

## 코드와 데이터

- `backend/src/main/java/com/pricetrack/exchange/conditional/`: 의도 엔터티/행 잠금 repository, 신규 schema 설치, lifecycle service, 단일 전용 worker, USER/ADMIN controller.
- `backend/src/main/resources/conditional-schema.sql`: 신규 `conditional_orders`만 추가. 사용자/실행 Order FK, 실행 Order unique, 계정+키 fingerprint unique, optimistic version, due/owner indexes. 시각은 TIMESTAMP WITH TIME ZONE이다. 기존 거래 테이블 migration/과거 주문 추론 없음.
- `order/OnchainOrderPreparationService`: 기존 즉시 REQUIRES_NEW 진입은 유지하며 같은 준비 본문의 MANDATORY 진입점을 추가했다. 조건부 의도 연결·Order·quote 소비·잔고 잠금은 한 짧은 transaction이다. 신규 adapter 실패는 전부 rollback한다. 기존 즉시 잔고 부족 FAILED 이력 계약은 그대로다.
- `order/OnchainOrderService.submitPrepared`: 이미 commit된 준비 결과를 기존 전송/보상 경로로 전달한다. 이때 사용자 잔고 transaction은 끝나 있다. 기존 Operator/price signer·Vault/nonce/raw/receipt/event/정산 계산은 변경하지 않는다.
- 기존 SecurityConfig/개인 STOMP whitelist에 새 읽기 ADMIN 경로 및 `/user/queue/conditional-orders`를 추가했다. 새 type은 CONDITIONAL_ORDER_UPDATED이며 기존 AFTER_COMMIT listener를 사용한다.
- `tools/websocket-test-client/src/conditional-panel.js`: 등록/본인 목록·페이지·취소·이유·실행 Order 상세 연결과 ADMIN 읽기 이력. decimal 문자열·계정별 미확정 요청 키/body 유지, 자동 재등록 없음, 로그아웃/화면 교체의 늦은 응답 차단, reconnect REST 갱신. 시장 화면에 조건 충족≠성공/대기 중 미예약 안내가 있다.

## 상태·복구

WAITING → PREPARING → EXECUTING → SUCCEEDED / FAILED / REVIEW_REQUIRED. 실행 FK 전만 취소·만료 가능. 시장 비가용/조건 변화/견적 만료는 WAITING으로5초 뒤 재평가하고 의도 기한이 지나면 EXPIRED. 자산 부족·확정 실행 실패는 FAILED이며 새 주문을 자동 재시도하지 않는다.

취소와 prepare는 의도 row를 함께 잠근다. prepare는 quote·기존 available balance를 검증/잠그고 마지막 시장/만료 검사를 거쳐 함께 commit한다. RPC를 보내는 동안 잔고 row lock을 유지하지 않는다. 전송 이후 취소나 의도 만료로 잔고를 풀지 않는다. SIGNED/SUBMITTED는 기존 동일 raw 복구만 사용한다. 실행 연결 후 transaction 기록을 찾을 수 없는 interruption은 REVIEW_REQUIRED로 자산을 유지한다. 자동 수동확인 해소/보정 기능은 없다.

한 프로세스 gate/최대 한 작업 enqueue와 DB claim을 사용하며 due batch20개를 확인한다. PREPARING+실행 없음은 재시작 후 새 quote부터 복구할 수 있으나 실행 FK가 있으면 새 Order/nonce를 만들지 않는다. 여러 backend/worker 프로세스와 분산 nonce는 지원하지 않는다.

## 환경·API

`CONDITIONAL_ORDERS_ENABLED=false` 기본. blockchain+price-report enabled도 필요하다. Toss OPEN/LIVE 및 관측5초/미래2초 경계를 통과할 때만 실행한다. public profile은 비활성이다. Synthetic는 conditional-fixture profile+명시 fixture flag+localhost RPC+격리 DB 이름 검사에서만 허용한다. 공개 합성 데모의 즉시 거래 경로는 유지한다.

[Android API 계약](conditional-orders-android-integration.md)에 경로/헤더/상태/페이지/WS를 기록했다. 실제 Android 소스는 읽었으나 수정·빌드·실기기 테스트하지 않았다. 기존 즉시 API와 Faucet 정책은 바꾸지 않았다.

## 실행별 검증

1. 초기 H2/Mock10개 및 기존 즉시 온체인 테스트는 통과했다. 새 E2E 테스트 작성 중 Java import 및 HttpService close API 컴파일 오류는 수정 후 재컴파일했다. 이를 E2E 성공으로 표시하지 않는다.
2. 첫 fresh PG/Anvil 실행은 테스트 프로필 미적용으로 Synthetic 등록을409로 차단했다. 해당 fixture/실패 XML은 보존하고 두 컨테이너만 정지했다. `@ActiveProfiles`로 테스트 설정을 수정했다.
3. 다음 fresh PG/Anvil은 BUY/SELL·same-key 동시 등록·worker 중복·취소 경합·실제 전송 응답 유실 복구까지 통과했으나 만료 assertion에서 실패했다. JDBC 연결 시간대와 TIMESTAMP 없는 시간대의 SQL cast가9시간 이동한 증거를 확인했다. **신규 테이블** 시각 컬럼을 WITH TIME ZONE으로 통일하고 기존 테이블은 바꾸지 않았다. 실패 fixture를 초기화/재사용하지 않았다.
4. 세 번째 fresh PG/Anvil에서 **H212/12 + 실제 E2E1/1 PASS**. 단일 실제 E2E 안에서 별개 시나리오들을 검증했으며 다수 JUnit 테스트처럼 합산하지 않는다. 실제 EIP-712 BUY/SELL 정산, same-key 동시 등록1건, 같은 의도 worker 동시 실행1회, cancel/prepare 경합, HTTP RPC proxy가 raw 전송을 실제 Anvil에 넘긴 뒤 응답을 끊은 SIGNED 복구(실행/Trade 추가 생성 없음), 이전 worker PREPARING 재복구, DB 만료 EXPIRED, 실제 만료 이후 채굴한 status0 receipt→FAILED/잠금 해제/Trade0을 검증했다. Faucet 이후 두 대사 MATCH/전수 chain coverage true도 확인했다.
5. 웹71/71 PASS·Vite94 modules·Chrome desktop/mobile fixture PASS. 모바일 조건부 주문 overflow assertion을 추가한 최종 재실행도 PASS였다. Chrome은 REST/STOMP fixture이며 실제 Toss 또는 실제 브라우저→Anvil 인수로 표시하지 않는다.
6. Foundry `forge test --offline -vv` **36/36 PASS**, 실패/skip0.
7. 위 실제 E2E 이후 H2 commit-only WS test와 실제 STOMP 개인 conditional queue owner isolation test를 추가했다. 최종 기능 소스 전체 backend 회귀 XML은 **451개/417 PASS/34 SKIP/실패·오류0**이다. opt-in fixture를 켜지 않은 전체 실행의 skip은 별도 실제 E2E 성공과 구분한다. 기존 opt-in31개 및 Faucet/Reserve/Conditional 실제 fixture 테스트의 기본 skip을 성공으로 합산하지 않는다.

## 검토·남은 범위

최초 독립 검토는 **필수 수정 있음(2건)**이었다. prepare가 새 견적 가격만 비교하고 최신 snapshot 가격 조건은 비교하지 않던 문제를 초기/자산 lock 이후 두 번 모두 priceE8 조건 검사하도록 보완했다. BUY/SELL의1e-8 경계 이탈을 초기·최종 네 경우로 검증하며 rollback/WAITING을 확인하는 회귀를 추가했다.

확정 validation400도 pending으로 유지해 정상 재입력을 막던 웹 문제는 알려진 backend validation400에서만 같은 키를 폐기하도록 보완했다. 미확정 네트워크/5xx/알 수 없는400/결과 확인404는 계속 보존한다. 정수12자리 입력 검사, 실제 패널에서 거절 후 정상 등록/유실·동시 클릭/세션 교체/늦은 이력 회귀를 추가했다. 조건부 WS도 기존 eventId 중복 제거 후 이력·portfolio를 REST 재동기화한다. 수정 후 신규 PG/Anvil·전체 회귀와 첫 재검토는 진행 중이다.

### 첫 검토 보완 후 재실행 (2026-10-12)

- 새 격리 PostgreSQL/Anvil 실제 E2E **1/1 PASS**. 같은 실행의 H2는14개 중13개가 Faucet fixture 준비에서 실패했다. UTC/KST 날짜가 다른 시각에 H2 자동 생성 시간대 컬럼과 JDBC journal 날짜 비교가 어긋난 것으로 확인했다. 이 실패를 실제 E2E 성공으로 대체하지 않는다.
- H2에 기존 PostgreSQL schema.sql을 그대로 적용하는 시도는 H2가 기존 ON CONFLICT 구문을 지원하지 않아 기동 실패했다. 해당 설정은 철회했다. **H2 테스트 연결에만** `SET TIME ZONE 'UTC'`를 적용한 후 **14/14 PASS**. Faucet 제품 코드·기존 PostgreSQL schema/시간대는 변경하지 않았다. 실패 및 재실행 XML과 새 fixture state는 비공개 runtime에 보존했다.
- 웹 보완 후 **77/77 PASS**, Vite94 modules 및 Chrome desktop/mobile REST/STOMP fixture PASS. Foundry 재실행 **36/36 PASS**. 최신 snapshot BUY/SELL 초기·최종 가격 경계 네 경우와 웹 확정 거절/미확정 응답·세션 교체 회귀를 포함한다.
- 이어진 전체 회귀는 **452개/414 PASS/4 실패/34 SKIP**였다. 기존 H2 Faucet/거래 테스트 네 건도 같은 UTC/KST 날짜 경계에서 회계 검사로 실패했다. 실패 XML을 보존하고 테스트 공통 `src/test/resources/application.yml`의 Hikari 연결 초기 SQL에도 UTC를 명시했다. 이는 테스트 설정이며 제품 설정·운영 DB를 변경하지 않는다. PostgreSQL opt-in fixture에도 유효한 SET TIME ZONE 구문으로 적용되므로 최종 격리 PG 실행에서 함께 재검증한다. 전체 재실행 및 첫 재검토 중이다.

공통 UTC 보완 후 기능 소스 전체 재실행은 **452개/418 PASS/34 SKIP/실패·오류0**, Gradle BUILD SUCCESSFUL이다. 첫 독립 재검토 결론은 **발견된 필수 수정 없음**. 검토자가 패널11/11을 직접 실행하고 전체 XML106파일/452개 및 실제 PG/Anvil 성공·H2 실패 보존 XML과 수정 소스를 읽었다. 검토자는 DB/RPC/외부 호출이나 파일 변경을 하지 않았다. 공통 UTC 설정의 PostgreSQL fixture 상속은 최종 fresh PG 실행에서 확인할 항목으로 유지한다.

공통 UTC 설정 이후 또 다른 새 격리 PG/Anvil에서 **H214/14 + 실제 E2E1/1 PASS(15개/skip0)**, BUILD SUCCESSFUL을 확인했다. 해당 fixture의 PostgreSQL 세션 설정과 실제 서명/채굴/정산·두 대사 호환을 최종 제품 소스에서 재검증한 결과다. 전체452 회귀 XML과 이 E2E XML은 별도 비공개 경로로 보존했고 해당 새 컨테이너 두 개만 정지했다. 데이터·chain state는 보존했다.

병합 회귀 및 기능/통합 커밋은 아직 남아 있다. 실제 Toss 장중·유료 AI·AWS·Sepolia·Android·장시간 중단/다중 프로세스·운영 부하는 실행하지 않는다. 기존 acceptance DB/chain/runtime을 변경/배포하지 않는다. 최종 Toss/AI 인수는 전체 추가 기능 개발 이후 신규 acceptance에서 진행한다.
