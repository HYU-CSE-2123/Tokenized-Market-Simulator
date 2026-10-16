# 장부·체인 자산 대사 — 구현·검증 보고서

> 2026-10-08 / `feat/reserve-reconciliation` 별도 worktree
> 상태: 구현·격리 E2E·조건부 전체 회귀·독립 재검토 완료 (아래 검증 한계 유지)
> 후속 보완(2026-10-09)의 실제 PostgreSQL 동시성·ETH/Gas·CRLF 일반 회귀는 [최종 검증 보고서](reserve-reconciliation-final-validation.md)를 따른다. 이 문서의 실행 수치/미커밋 상태는 10-08 당시 기록이다.

## 승인 경계

원본 `melon-init` 제품 코드, acceptance baseline/runtime, 기존 DB/Anvil/.env/Toss/AI 설정은 변경하거나 참조하여 실행하지 않는다. merge·commit도 하지 않았다. 전용 fixture는 신규 DB `exchange_reserve_e2e`, PostgreSQL 25542, persistent Anvil 25545, 독립 operator/price signer를 생성한다. SIMULATED 시세만 사용하며 AI는 모두 비활성이다. 테스트를 위한 키·state·로그는 ignored runtime에 제한된 Windows ACL로 보존한다.

이 기능은 ADMIN 수동 관측 및 결과 저장이다. 장부 보정·자동 충전·거래 차단·정산 호출·공개 Proof of Reserves는 아니다.

## 데이터 모델과 변경 위치

| 변경 | 구현 |
|---|---|
| Order 실행 경로 정본 | `order/ExecutionMode.java`, `Order.java`: UNKNOWN/ONCHAIN/DB_ONLY. 실제 주문 생성 경로에서 지정하므로 실패·미정산 주문에도 남음 |
| Trade 실행 경로 | `Trade.java`: Order 값을 복사. 과거 orders/trades는 UNKNOWN 기본값, txHash로 추론/backfill하지 않음 |
| faucet 지급 근거 | `wallet/FaucetGrant.java`: 고유 UUID/user/amount/createdAt. `WalletService.faucet`에서 기존 잔고 증가와 같은 transaction으로 저장 |
| 불변 기준점 | `reserve_baselines`: PK=1, payload/actor/DB 시각. 생성 UUID/executionId/chainId/4개 계약/operator/signer/code hash/blockNumber/hash/초기 잔고·공급량 포함 |
| 결과 이력 | `reserve_runs`: UUID/actor/시각 및 기준점·DB 기준 시각·체인 snapshot·검사별 예상/실제/차이·판정/사유·유동성 |
| 스키마/설정 | `backend/src/main/resources/schema.sql`, `application.yml` |
| 계산/관측/API | `backend/src/main/java/com/pricetrack/exchange/reserve/` |
| ADMIN 제품 UI | `tools/websocket-test-client/src/reserve-panel.js`, `index.html`, `api.js`, `main.js` |

PostgreSQL에서는 기준점·지급 이력·결과 테이블의 UPDATE/DELETE/TRUNCATE를 trigger로 차단한다. H2는 ORM/API 불변성만 검증한다. DB owner가 trigger를 제거할 수 있으므로 악의적인 DBA에 대한 변조 불가능 증명이 아니다. 초기 trigger 설치에 DDL 권한이 필요하며 본 범위는 신규 fixture DB에서 확인했다.

기준점은 **장부·주문·체결·지급 이력이 없는 신규 0원 환경에서만** 한 번 생성한다. 0원 회원은 있어도 된다. 기존 유잔고 개발 DB에 소급 기준점을 만들거나 불일치 후 재설정하는 기능은 없다. PK 단일행 + INSERT만 허용하며 별도 불변 식별자를 저장한다.

## 정본과 계산

사용자 귀속/총수량은 DB, 체인 실행은 canonical receipt/Vault event/토큰 상태가 정본이다. amount에 lockedAmount가 포함되므로 잠금을 더하거나 빼서 총수량을 왜곡하지 않는다. 모든 계산은 18자리 최소단위 BigInteger, 예상/실제/차이는 문자열이다.

```text
사용자별 mKRW 예상 = 지급 journal 합 - 모든 BUY quoteAmount + 모든 SELL quoteAmount
사용자별 mSEC 예상 = 모든 BUY baseAmount - 모든 SELL baseAmount

dk = - ONCHAIN BUY quoteAmount + ONCHAIN SELL quoteAmount
ds = + ONCHAIN BUY baseAmount - ONCHAIN SELL baseAmount
operator KRW 예상 = 기준점 operator KRW + dk
Vault KRW 예상    = 기준점 Vault KRW - dk
operator SEC 예상 = 기준점 operator SEC + ds
Vault SEC 예상    = 기준점 Vault SEC
SEC supply 예상   = 기준점 SEC supply + ds
KRW supply 예상   = 기준점 KRW supply
```

BUY quoteAmount는 입력 전액, SELL quoteAmount는 순수령액이다. 수수료를 한 번 더 차감하지 않는다. DB_ONLY 체결은 사용자 식에만 반영한다. fee/price/output은 온체인 receipt event와 Trade를 직접 대조한다. minter/consumer/Vault 연결/owner/18 decimals도 검사한다.

기준점 이후 두 토큰의 Transfer 로그에서 정산된 거래 txHash로 설명되지 않는 흐름은 MISMATCH 근거다. 외부 faucet/mint/burn/전송을 새로운 정상 offset으로 흡수하지 않는다. 전체 토큰 외부 보유자를 사용자 자산으로 취급하지 않는다.

## consistent-cut 절차

1. 체인 latest 블록 B의 number/hash를 선택한다.
2. `ReserveStore.cut`의 독립 read-only REPEATABLE_READ DB transaction에서 잔고/주문/체결/BUY·SELL tx/지급 이력을 읽고 DB CURRENT_TIMESTAMP를 저장한다. RPC 호출 전에 transaction을 닫는다. FOR UPDATE 없음.
3. 별도 read-only RPC reader에서 모든 eth_call/code 조회를 **B.number**에 고정한다. B.hash와 기준점 hash를 canonical 조회로 검증한다.
4. 실행 환경 동일성, Order/Trade/tx 관계, CONFIRMED 블록<=B, canonical receipt/from/to/input/output/fee/price와 Transfer 범위를 검증한다.
5. 두 번째 짧은 DB snapshot의 관련 행 전체 fingerprint가 처음과 동일하고, 현재 head number/hash가 B와 동일하며 B도 canonical인지 확인한다.
6. 위 조건을 모두 확보한 뒤에만 MATCH/MISMATCH를 산정한다. 조회 중 DB/블록 변경은 잠정 수량 차이가 있더라도 INCONCLUSIVE다.

이는 분산 원자적 snapshot이 아니라 **검증 가능한 정지 구간을 보수적으로 확인**하는 방식이다. 읽기 전용 SELECT의 짧은 기본 DB lock은 존재하지만 사용자 잔고 write lock이나 거래 중단은 없다. 지속적인 새 블록·거래에서는 INCONCLUSIVE가 자주 나올 수 있다.

미정산 REQUESTED/PENDING 또는 tx CREATED/SIGNED/SUBMITTED/REVIEW_REQUIRED가 있으면 이번 범위에서는 INCONCLUSIVE로 종료한다. 미정산 수령량을 추정하거나 정산을 호출하지 않는다. 최초 설계의 SETTLEMENT_PENDING 보정/별도 판정은 구현하지 않았다.

상한: 테이블별 10,000행, 기준점 이후 10,000블록/Transfer 로그 10,000개, DB transaction 5초/query timeout 3초, RPC 호출별 2초, receipt 반복 검사 15초 budget, 동시 수동 실행 1개(초과429). 전체 RPC 작업의 엄격한 15초 wall-clock 보장은 아니다. 읽기 상한 초과는 INCONCLUSIVE, 조회 장애는 UNAVAILABLE. 결과 DB 자체가 장애라면 결과 저장/API도 실패할 수 있다.

## 유동성·노출 범위

유동성은 operator 매수 mKRW/allowance, operator 매도 mSEC, Vault 매도 지급 mKRW를 별도로 표시한다. 사용자 mKRW 대비 operator 비율은 참고 할당량 비율일 뿐 보증·지급 능력·담보 증명이 아니다. 현재 가격으로 전체 청산을 보장하는 계산은 없다.

consistent cut 미확보/장애에서는 서로 다른 시점의 비율을 조합하지 않도록 liquidity=null이다. MATCH여도 사용자 합계보다 operator 자금이 적을 수 있으며 화면은 두 의미를 구분한다.

모든 API는 JWT ADMIN만 허용한다. USER는 개인 포트폴리오만 유지한다. 결과에 private key/signature/raw transaction/비밀 URL/원본 RPC 예외는 없다. UI는 safe text DOM, 정확한 최소단위 표현, 로그아웃 응답 폐기, 자동 POST 재시도 없음, 모바일 overflow 회귀를 유지한다.

```text
POST /api/admin/reserve-reconciliations/baseline  최초 생성 (201, 중복/유잔고409)
GET  /api/admin/reserve-reconciliations/baseline  기준점 (없으면404)
POST /api/admin/reserve-reconciliations           수동 실행·저장
GET  /api/admin/reserve-reconciliations           최근20개
GET  /api/admin/reserve-reconciliations/{UUID}    상세
```

## 대표 사례

| 사례 | 판정 |
|---|---|
| DB faucet 100만 지급, operator 잔고와 사용자 합계 다름 | 지급 journal·장부식이 일치하면 MATCH |
| 75,000원 BUY 750,000 → 9.99 SEC, 80,000원 SELL → 798,400.8 KRW | BUY fee750/SELL fee799.2 포함, 장부/operator/Vault/공급량 MATCH |
| journal 없는 사용자 잔고 +1, unexplained operator 자금 감소/외부 Transfer | 안정 cut 확보 후 MISMATCH |
| 잠정 잔고 차이가 있지만 조회 중 새 블록 또는 DB 변경 | INCONCLUSIVE CUT_CHANGED |
| UNKNOWN 과거 주문, 미정산/REVIEW, receipt 검증 불가 | INCONCLUSIVE (자산·주문 상태 변경 안 함) |
| RPC 장애 | UNAVAILABLE (민감 오류 원문 없음) |

## 실행한 검증 및 진행 상태

- backend 단위/격리 H2 대사 최초22개 PASS, 독립 검토 후6개 보완하여 현재28개 PASS. fixture unit에서 블록·DB 경합, UNKNOWN/REVIEW, 수량·fee/잠금, 권한 및 지급 rollback, tx 방향 오류·DB_ONLY 연결·정상 DB_ONLY·receipt 실패·role/hash 변경을 확인했다.
- 신규 PostgreSQL+Anvil 서명 BUY/SELL E2E 1개 PASS: 지급→MATCH, 미정산→INCONCLUSIVE, BUY/SELL 정산→MATCH, 대사 전후 잔고/주문/블록 불변, 고의 잔고+1→MISMATCH, anchor UPDATE/DELETE/TRUNCATE·journal DELETE 거부. 검토 수정 후에도 **두 번째 신규 fixture에서 같은 E2E 1/1 PASS**, XML tests1/failures0/skipped0 확인. 각 변조 fixture는 정상화/재설정하지 않고 증거로 보존했다.
- Foundry 36/36 PASS (fuzz 포함). Solidity 서비스 코드는 변경하지 않았다.
- 웹 54/54 PASS (기존48+신규6), Vite build/Chrome desktop+360px 모바일 fixture smoke PASS. 신규 ADMIN 수동 대사/이력/INCONCLUSIVE 상세도 실제 Chrome에서 검증. 브라우저 REST/STOMP는 fixture이며 신규 PostgreSQL/Anvil E2E와는 별도다.
- 첫 전체 backend: 370개 중 338PASS/1FAIL/31SKIP. FAIL은 수정하지 않은 기존 SkillRegistryTest의 CRLF fixture 이중 변환. Git HEAD의 LF source와 worktree의 CRLF checkout, 테스트의 LF→CRLF 치환을 확인했다. 기존 AI 소스/설정을 수정하지 않고 build/resources/main/ai/skills/*.md만 LF로 정규화한 조건에서 전체 회귀 BUILD SUCCESSFUL(339PASS/31SKIP). 검토 수정·6개 보완 후 같은 LF 조건 전체376개 **345PASS/0FAIL/31SKIP**, XML 집계 확인. 일반 checkout 명령의 실패를 숨기거나 무조건 전 테스트 통과로 표기하지 않는다.
- 실제 PG 동시 writer로 snapshot race를 재현하는 부하 검증, 실제 Toss/유료 AI, 원래 acceptance 환경 인수, EC2 운영 검증은 이번 실행 범위 밖이다. cut 선언 + fixture race + 실제PG 정상E2E를 구분한다.
- 최초 독립 검토 `review_reserve`: ONCHAIN tx 방향 불일치 및 DB_ONLY 연결 tx를 정상으로 허용하는 2건 지적. 신규 unit에서 실제 false-MATCH를 재현(24개 중 해당2FAIL)하고 Order.side=tx.type, DB_ONLY 주문/체결 hash 및 연결 tx 없음 검증을 보완했다. 수정 후 전체 회귀376개345PASS/31SKIP 및 새PG/Anvil E2E1/1 PASS. **재검토 결론: 발견된 필수 수정 없음.** 두 연결 검사와 신규6개 테스트·보고서를 확인했고 현재 E2E XML 1개/failure0/error0/skip0을 읽기 전용으로 확인했다. 최초 검토에서 웹 신규6개를 직접 실행해 PASS. 전체backend·PG/Anvil/Foundry·Chrome 실행은 구현자 검증으로 구분하며 검토자는 DB/Anvil/Toss/AI에 접속하지 않았다. 실제PG 동시 writer와 일반CRLF checkout 실패는 독립 미검증이다. 선택 제안인 로그 블록별 canonical 캐시/전체 wall-clock deadline은 이번 범위에서 추가하지 않고 장시간 gate 점유 가능성을 한계로 유지한다.

## 사용·재현

`RESERVE_RECONCILIATION_ENABLED=true`, `RESERVE_EXECUTION_ID=<새 환경 식별자>`가 필요하다. 기본 disabled. feature worktree에서 `python deployment/reserve-e2e.py`는 기존 .env/runtime을 import하지 않고 새 키·DB·chain/컨트랙트만 준비한다. 출력된 새 root를 RESERVE_E2E_RUN에 넣고 RESERVE_E2E_TESTS=true로 `gradlew test --tests '*ReservePostgresAnvilE2ETest'` 실행한다. 성공 테스트는 의도적 MISMATCH를 남기므로 동일 DB 위에 전체 시나리오를 반복하지 않는다. 기존 fixture 초기화/재배포로 반복하지 말고 승인된 신규 fixture를 사용한다.

runner의 최초 실패는 새 runtime 상위 경로 부재로 컨테이너 생성 전에 발생했고 parents=True 보완 후 준비에 성공했다. Windows Docker Desktop만 검증했다. 기존 runtime/컨테이너를 자동 정리하지 않는다. 본 기능을 acceptance에 반영하려면 장중 인수 보존 해제·merge가 별도로 승인되어야 한다.

완료 시 두 신규 fixture의 컨테이너는 각각 라벨·이름 확인 후 정지했다. DB/chain/키/불일치 증거는 각 ignored runtime에 보존하고 삭제·초기화하지 않았다. feature worktree의 변경은 미커밋이며 원본 worktree Git clean을 확인했다.
