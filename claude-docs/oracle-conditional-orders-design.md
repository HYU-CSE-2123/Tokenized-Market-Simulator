# Oracle 가격 조건부 주문 — 코드 조사와 설계

> 2026-10-11 / feat/oracle-conditional-orders / 입력 integration/additional-features `0a3b67e`. 이 문서는 설계이며 구현·테스트 완료 기록이 아니다. acceptance/dev DB·Anvil·runtime은 보존한다. 실제 Toss·AI·AWS·Sepolia 호출 없음.

## 1. 기존 계약과 가격 의미

- `order/OrderController.java`, `OnchainOrderPreparationService.java`: BUY input은 수수료 포함 **총 mKRW**, SELL input은 **mSEC 수량**. BUY 수수료를 별도 추가 청구하거나 BUY를 지정 토큰 수량 주문으로 바꾸지 않는다.
- `blockchain/support/TokenUnits.java`: 양쪽 토큰18 decimals, 초과 정밀도를 반올림하지 않고 거부한다. 기존 DB amount는 numeric(30,18)이다.
- `blockchain/support/PriceUnits.java`: 가격8 decimals. 목표 가격과 시세·견적을 priceE8 정수로 비교한다. BUY <=, SELL >=, 경계 포함. float 비교 없음.
- `blockchain/oracle/PriceReportIssuer.java`: 관측5초 이내/미래오차2초, 관측 시각부터30초 유효 보고서. Vault의 명시 가격 견적으로 fee/minimumOutput 산정. 온체인 수수료는 Vault 설정을 사용하며 DB_ONLY TradeCalculator의0.1% 상수를 온체인 가격 계산으로 대체하지 않는다.
- 여기서 Oracle 기준은 최신 외부 기준 시세와 그에 서명한 보고서 가격이다. PriceOracle에 마지막 저장된 가격을 트리거 정본으로 사용하지 않는다. 조건을 만족한 **서명 가격**으로 전체 입력을 실행 시도하며 실제 기초자산 가격이 제출·채굴 사이에도 목표 범위에 머문다는 보장이 아니다.

## 2. 자산 확보와 정본

등록 시 잠금은 자금을 미리 확보하지만 긴 대기로 잔고를 묶고 취소·기간 만료마다 별도 자산 예약 정산이 필요하다. **조건 충족 후 실행 준비 시 잠금**을 선택한다. 기존 getAvailableAmount=amount-lockedAmount와 기존 입력 자산 lock을 재사용하고 대기 중 잔고를 바꾸지 않는다. 따라서 등록 성공은 자금 확보/체결 보장이 아니며 실행 당시 부족하면 FAILED다.

새 conditional_orders는 사용자 의도/조건/만료/취소의 정본이다. 기존 Order는 실제 실행의 정본, Trade는 검증 정산 결과, BlockchainTransaction은 raw/nonce/hash/receipt 복구의 정본이다. 새 테이블의 execution_order_id unique FK로 최대1개 실행 Order를 연결한다. 과거 Order/Trade는 변환·추정하지 않는다. 신규 테이블만 추가하며 기존 행 migration 없음.

등록 body는 symbol=mSEC, side, inputAmount, targetPrice, expiresAt. userId/서명/quoteId는 받지 않는다. expiresAt은 실행 개시 기한이며 시간 지정 예약이 아니다. 날짜가 지나도 이미 실행 준비를 commit한 거래를 EXPIRED로 바꾸지 않는다. 사용자 JWT의 수명과 별개로 승인한 의도는 지정 만료/취소까지 남는다.

## 3. 상태와 원자적 실행 연결

WAITING → PREPARING → EXECUTING → SUCCEEDED / FAILED / REVIEW_REQUIRED.
WAITING/PREPARING에서 실제 실행 연결 전에는 CANCELLED/EXPIRED 가능. PREPARING에서 조건 변화·만료 견적·시장 비가용이면 새 실행을 만들지 않고 WAITING(의도 자체 만료면 EXPIRED). EXECUTING 이후 취소/의도 만료로 잠금 해제 금지.

1. 짧은 DB transaction으로 WAITING을 claim하고 PREPARING과 실행자 인스턴스 식별자를 저장한다. 잔고 lock 없음.
2. transaction 밖에서 최신 시장을 확인하고 새로운 PriceQuoteService.issue를 호출한다. 등록 때 quote를 만들지 않는다. Quote 발급 자체의 transaction에는 사용자 잔고 lock이 없다.
3. 짧은 prepare transaction에서 conditional row lock → 새 quote lock/사용자·방향·input/만료 검증 → 최신 시장/조건 재검증 → 기존 입력 잔고 lock → Order 생성·quote 소비·잔고 lock·conditional 실행 FK/EXECUTING을 **같이 commit**한다.
4. 모든 DB 자산 lock을 놓은 뒤 기존 BlockchainTransactionSender를 호출한다. receipt/event 검증·DB 정산·AFTER_COMMIT WS는 기존 경로 그대로 사용한다.

현재 `OnchainOrderPreparationService.prepare`는 REQUIRES_NEW다. 바깥 transaction에서 기존 메서드 호출 후 conditional link를 저장하면 중간 crash에 Order와 연결이 분리된다. 따라서 기존 prepare 본문을 재사용하는 **MANDATORY 내부 준비 진입점**을 추가하고, 기존 즉시 prepare의 REQUIRES_NEW/noRollbackFor 의미는 유지한다. 신규 adapter가 자체 transaction에서 준비 진입점과 conditional link를 함께 저장한다. 기존 즉시 주문·정산 금액/수수료/실패 의미는 바꾸지 않는다.

## 4. 장애·취소·복구 경계

- 조건 row를 잠그는 cancel과 최종 prepare가 경쟁한다. cancel 선행이면 prepare가 중단되고, 실행 연결 commit 선행이면 cancel409다. 외부 RPC 중 잔고 row lock은 유지하지 않는다.
- 한 프로세스 내부 실행 gate와 DB claim 상태를 함께 사용한다. 병렬 worker가 같은 의도를 두 번 실행하지 않는다. 기존 synchronized operator nonce는 그대로이며 **다중 서버 실행은 지원하지 않는다**. lease timeout만으로 살아 있는 worker를 다시 실행하지 않는다.
- restart: PREPARING+실행FK 없음은 기존 claim을 복구해 fresh quote부터 다시 시작 가능. 실행 FK가 있으면 **새 quote/새 Order/새 nonce 재시도 금지**. SIGNED/SUBMITTED는 기존 동일 raw 복구에 맡긴다. CONFIRMED+FILLED이면 성공, 검증 실패 receipt+FAILED이면 실패, REVIEW_REQUIRED이면 잠금 유지/수동 확인이다.
- 실행 연결 후 tx 부재 상태의 interruption은 신규 실행을 만들지 않고 안전한 미서명 보상 여부를 확인한다. 살아 있는 전송 worker/서명 유무를 확정하지 못하면 REVIEW_REQUIRED로 남긴다. RPC 오류 문자열/키/서명 원문을 사용자 결과에 노출하지 않는다.
- 견적 발급 전 시장 비가용은 대기한다. 조건 변화·짧은 quote 만료는 재평가 대상으로 대기한다. 자산 부족/확정 pre-SIGNED 전송 실패/실패 receipt는 실패이며 자동 새 주문 재시도하지 않는다. 원문이 저장됐거나 전송 성공 여부가 불확실하면 실패/취소로 단정하지 않는다.
- Toss는 OPEN+LIVE+신선도 통과 때만 새 실행한다. UNKNOWN/INITIALIZING/DEGRADED/CLOSED/STALE 및 공급자 오류는 fail-closed. Synthetic는 명시적인 격리 fixture 모드에서만 허용하며 public profile에서는 비활성이다. 공개 합성 데모의 기존 즉시 거래는 유지한다.

## 5. API/UI와 테스트 계획

USER: POST/GET `/api/conditional-orders`, GET `/{id}`, POST `/{id}/cancel`. ADMIN 읽기 전용 이력·상태·실행Order 연결 조회. 대기 주문을 기존 Order 성공·Trade·온체인 이벤트로 기록하지 않는다. 웹 거래 영역에 별도 조건부 주문 입력·대기 이력·취소·이유·실행 결과 연결을 추가한다. 가격/입력/수수료 의미와 실행 시 잔고 확보, 조건 충족≠성공, 서명 유효기간 중 실제 시장 변동 한계를 표시한다. 세션 교체 후 늦은 응답 차단/자동 주문 재등록 금지. Android 소스 접근 여부와 미수정/미빌드를 연동 문서에 명시한다.

단위/H2: 정수 경계, 요청검증/owner·ADMIN, 상태 전이·취소경합·준비 rollback, 시세조건·quote 재검증·만료, 공급자/모드 제한, 재시작과 raw 복구, 실패/수동확인. 실제 신규 PG/Anvil/Synthetic: 동일 의도 worker/cancel 동시성, 잠금 원자성, BUY/SELL EIP-712 실행·정산, 실패/응답 유실·REVIEW_REQUIRED, Faucet/두 대사 호환. backend/web/Foundry 전체 회귀·독립 검토 → 안전 feature commit → 통합 merge → 병합 source 신규 회귀. 실제와 fixture 실행 및 skip을 별도 기록한다.

Faucet 이전 통합436/403PASS/33SKIP, 웹66·Foundry36 및 PG9PASS/대사79PASS는 **이전 기능 완료 결과**다. 이번 기능 검증으로 재표시하지 않는다.
