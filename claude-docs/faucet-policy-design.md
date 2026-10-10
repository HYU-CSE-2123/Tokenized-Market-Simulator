# Faucet 정책 고도화 설계안

> 2026-10-10 / integration/additional-features / 조사·설계만 수행. Faucet 제품 코드·정책값·DB migration은 아직 구현하지 않았다. 아래 선택안과 모든 숫자 정책값은 사용자 승인 대상이다.

## 1. 확인한 현재 동작

기존 개발/acceptance DB는 조회하지 않았다. 다음은 현재 통합 소스·스키마·테스트에서 확인한 사실이며 실제 운영 계정의 지급 횟수를 조사한 결과가 아니다.

| 실제 코드 | 현재 역할/계약 |
|---|---|
| `backend/src/main/java/com/pricetrack/exchange/wallet/WalletController.java` | 인증된 본인의 POST `/api/wallet/faucet`. 입력 금액·다른 userId를 받지 않는다. 현재 idempotency key도 받지 않는다. |
| 같은 폴더 `WalletService.java` | 매 호출마다 고정 **1,000,000 mKRW**를 DB amount에 더하고 FaucetGrant 저장. 한 DB transaction에서 처리한다. 횟수·누적액·기간·전역 예산·이메일 확인 조건이 없다. 이 숫자는 현행 상수이지 새 정책 승인값이 아니다. |
| `FaucetGrant.java`, `FaucetGrantRepository.java` | UUID, userId, amount, createdAt 지급 정본. 매번 새 UUID이므로 HTTP 재시도는 별도 지급이다. `@Immutable`. 정책버전·멱등키·지급원인 metadata는 없다. |
| `UserBalance.java`, `UserBalanceRepository.java` | 사용자·종목 unique. amount=총량, lockedAmount=그 중 주문 잠금, available=amount-lockedAmount. `PESSIMISTIC_WRITE`로 같은 사용자 mKRW 변경을 직렬화한다. 동시 중복 요청을 **모두 순차 지급**하지 횟수를 제한하지는 않는다. |
| `auth/AuthService.java` | 회원가입 시 mKRW/mSEC 0원 행만 만든다. 회원가입 자동 지급은 없다. USER/ADMIN 모두 인증 후 같은 faucet 경로를 쓸 수 있다. |
| `websocket/publisher/UserWebSocketPublisher.java`, `WebSocketDeliveryListener.java` | portfolio snapshot 요청 후 AFTER_COMMIT 개인 이벤트 전달. rollback이면 전달하지 않는다. WS 미수신이 DB 지급 실패를 뜻하지 않는다. |
| `reserve/ReserveStore.java` | reserve 기능 활성 시 faucet_grants·baseline·reserve_runs에 UPDATE/DELETE/TRUNCATE 금지 trigger를 설치한다. 기능 비활성일 때도 같은 DB trigger가 반드시 존재한다고 가정할 수는 없다. |
| `reserve/ReserveCalculator.java` | 사용자 mKRW 기대량=해당 사용자 grant 합계 - BUY 입력 합계 + SELL 순수령 합계. lockedAmount는 amount의 부분집합이라 부채에 다시 더하지 않는다. |
| `tools/websocket-test-client/src/main.js`, `api.js` | 버튼의 동시 클릭 차단·불확실한 결과 자동 재요청 금지·portfolio 재조회. 서버의 멱등성/지급 정책을 대체하지 않는다. |

현재 `FaucetResult`는 symbol/receivedAmount/balance이고 HTTP200이다. grant와 잔고의 동시 commit/rollback은 기존 `ReserveIntegrationTest.faucetEvidenceCommitsAndRollsBackWithBalance`가 H2로 검증한다. 실제 DB 동시 요청 정책 검증은 이번 **설계**에서 실행하지 않았다.

## 2. DB 지급과 온체인 공급은 다르다

DB faucet은 ERC-20을 발행하거나 전송하지 않는다. 반면 `contracts/src/MockKRW.sol`의 faucet은 호출자에게 토큰을 mint하고 totalSupply를 늘리는 별도 함수이며 현재 반복 호출에 제한이 없다. `contracts/script/PrepareOperator.s.sol`은 운영자 mKRW 공급과 Vault allowance 설정용이다. 이번 정책에서 이 두 코드를 변경하거나 자동 호출하지 않는다.

DB 지급 G가 성공하면 사용자 amount와 grant 합계가 G 증가하고, operator/Vault balance·allowance·mKRW supply·ETH는 변하지 않는다. 준비금 대사 MATCH가 될 수 있어도 실제 거래 가능 자원이 증가한 것은 아니다. 온체인 BUY/SELL은 기존 운영자 executor와 서명 quoteId 경로를 그대로 사용한다. 운영자 부족이면 기존 거래 경계에서 실패할 수 있다.

전역 지급 예산은 **새로 배분할 모의 DB mKRW의 한도**다. 은행 예금·실제 원화 담보·운영자 온체인 잔고·AWS/AI 요금 예산이 아니다. 예산을 사용자의 현재 잔고 합계나 operator balance로 대신 계산하지 않는다. 매수·매도·수수료·손실로 잔고가 줄어도 이전 faucet 예산 사용량을 되돌리지 않는다. 지급 G 때문에 operator 자동충전·Vault 공급·ETH 지급·대사 baseline 재설정은 없다.

## 3. 정책 선택지와 추천

| 선택지 | 장점 | 한계 |
|---|---|---|
| 신규 계정에 한 번 지급 | 가장 단순하고 계정별 발행량 예측이 쉽다 | 사용 후 체험 재개가 어렵고 신규 계정 반복 생성은 막지 못한다 |
| **기간별 정액 지급 + 계정 누적 한도 + 전역 예산** | 반복 체험과 발행량 제어를 함께 지원한다. 거래 손익과 지급 정본을 분리하기 쉽다 | 기간 경계·전역 경쟁·잔여량 처리가 필요하다 |
| 직전 지급 이후 rolling window/cooldown | 자정 직전/직후 연속 지급을 막기 쉽다 | 각 사용자마다 다음 지급 시각이 다르고 기간별 집계와는 별도 제약이다 |
| 잔고 부족 시 목표 잔고까지 보충 | 계속 거래 체험하기 편하다 | 손실/매수/잠금 상태가 보상 기준에 영향을 줘 무한 보충·다중 계정 전략을 유도할 수 있다. 첫 범위 비추천 |

추천은 **기간별 정액 지급 + 명시적 계정/전역 한도**다. rolling cooldown은 꼭 필요한지 승인받아 선택한다. 기간은 KST 달력일이 사용자 안내와 ADMIN 집계에 단순하지만 자정 연속 지급이 가능하다. UTC 달력일·rolling 기간도 선택지이며 timezone/window를 확정하지 않았다.

정액 P라면 같은 기간의 횟수 N과 금액 U는 유효 허용 횟수 `min(N, floor(U/P))`로 겹친다. 둘을 둘 수 있으나 모순되는 숫자를 임의로 배치하지 않고 하나를 주 제약으로 정하거나 각각 목적을 설명한다.

### 승인할 정책값

| 항목 | 선택/산정 근거 — 미확정 |
|---|---|
| 1회 지급 P | 현행1,000,000 유지 또는 축소. 체험 BUY/SELL에 필요한 주문 크기·왕복 횟수로 결정. Synthetic 가격은 변하므로 보장 거래 횟수라고 표시하지 않는다. |
| 계정 기간 횟수 N / 금액 U | 체험 재시도 횟수와 위 정액 중복 관계를 기준으로 결정 |
| 계정 누적 횟수 L / 누적 금액 C | 장기 반복/계정 악용 억제용. 유한값 또는 특정 제약 미사용을 명시적으로 승인 |
| 전역 누적 지급 예산 B / 기간 예산 Bp | 예상 참여 계정 A × P × 허용 지급 횟수를 참고해 정한다. 자산 담보가 아니라 DB 발행 정책 |
| 기간·timezone / cooldown | 달력 기간 안내의 단순성 대 rolling 공정성. KST 제안이나 아직 결정 아님 |
| 대상 계정 | 현재 인증 계정 유지 제안. 이메일 인증 강제는 후속 인증 기능 전에는 사실상 기존 계정을 막으므로 이번에 몰래 도입하지 않는다. ADMIN 우회 지급도 기본 제안에서 제외 |

기간·누적 잔여 금액이 P보다 작으면 **부분 지급하지 않고 거절**하는 방향을 제안한다. 지급 금액에 client 입력은 허용하지 않는다. limit값은 양수·정확한 18자리 이하 단위·DB 표현 범위·상호 제약을 검증하며 잘못된 설정을 무제한으로 해석하지 않는다.

## 4. 계산식과 성공 불변식

정본 S는 commit된 실제 `faucet_grants`다. `C_u=사용자 성공 grant 수`, `A_u=사용자 성공 grant 합`, `C_{u,t}/A_{u,t}=동일 기간의 값`, `A_all/A_t=전체 누적/기간 성공 지급 합`이다. 실패·거절·같은 멱등키 replay는 여기에 포함하지 않는다.

새 지급 P는 사용 중인 모든 조건을 동시에 만족해야 한다:

```text
C_u + 1 <= L           A_u + P <= C
C_{u,t} + 1 <= N       A_{u,t} + P <= U
A_all + P <= B         A_t + P <= Bp
사용자/설정/회계 범위가 유효하고 DB 금액 표현 범위 내임
```

성공 시 amount+=P, lockedAmount 유지, available+=P, 성공 grant1개, 성공 멱등 결과1개, 계정·전역 사용량+=P/1이 **같은 DB transaction**으로 확정된다. 거절/rollback에서는 잔고·잠금·grant·성공 사용량이 변하지 않는다. 서버가 응답 전에 죽어도 DB commit 여부로 성공을 판단하고 같은 키 조회/재시도로 복구한다.

준비금 대사 사용자 grant 합계 정본은 그대로다. 정책 counters는 제한을 위한 파생값이지 사용자 자산 정본이나 대사 baseline이 아니다. counter와 grant 집계의 차이는 별도 `FAUCET_ACCOUNTING_INCONSISTENT`로 관측하고 자동 counter/잔고 보정이나 임의 baseline 재설정으로 정상화하지 않는다.

## 5. 제안 데이터 모델·동시성

기존 immutable faucet_grants 원문은 수정·삭제하지 않는다. 추가 테이블 이름/세부 DDL은 구현 승인 후 확정한다.

- `faucet_policy_versions`: 실제 적용한 정책값·contentHash·활성 시점. immutable version snapshot. 재기동/설정 변경만으로 지급 이력이나 누적 예산을 초기화하지 않는다. 노드 설정과 DB version hash 불일치는 새 지급 fail-closed.
- `faucet_account_usage`: userId PK, 누적 count/amount·기간 상태. `faucet_budget_usage`: 단일 DB 예산 식별자의 누적/기간 사용량. 별도 기간 이력/usage 행은 필요에 따라 구체화한다. 기간 사용량은 policyVersion이 바뀌어도 동일 기간의 기존 성공 지급을 포함한다. 정책을 낮추어 이미 초과한 경우 새 지급을 막고 ADMIN에 초과 표시한다.
- `faucet_claims`: `(userId, keyFingerprint)` unique, request fingerprint, grantId unique 연결, 적용 policyVersion, 지급 시점의 비민감 응답. 성공 결과 append-only. raw key·JWT·개인키·시그니처를 저장하지 않는다.
- grant 검색용 `(user_id,created_at,id)`/기간 조회 index. 새 성공 metadata는 claims에서 grantId로 연결하므로 기존 immutable journal UPDATE backfill이 필요 없다.

**최소 동시성 제안:** READ_COMMITTED의 짧은 transaction에서 사용자 quota 행 → 전역 budget 행 → 본인 mKRW balance 순서로 잠근다. 사용자 quota는 insert-on-conflict-do-nothing 후 lock하여 최초 동시 요청도 직렬화한다. quota lock 후 동일 key의 이미 commit된 성공을 재조회한다. 전역 행은 서로 다른 사용자도 남은 예산의 마지막 한 지급을 동시에 넘지 않게 한다. 그 뒤 서버의 결정 시점과 모든 한도를 재검증하고 변경·grant·claim·WS 요청을 함께 commit한다.

기존 주문은 quota/budget 행을 잡지 않으므로 faucet에서 mKRW balance를 잡은 뒤 quota를 역으로 잠그는 경로를 만들지 않는다. 전역 lock 동안 RPC·시세 API·LLM·파일 I/O는 호출하지 않는다. DB lock/transaction timeout은 별도 설정으로 bounded하고 값은 테스트 후 승인한다. 전역 row 직렬화는 첫 규모의 정확성을 위한 tradeoff이며 고부하 확장 성능을 보장하지 않는다. Redis·분산 transaction은 이번 제안에 필요 없다.

시간은 client clock을 사용하지 않는다. 제안은 quota/budget lock 획득 후 PostgreSQL `clock_timestamp()`를 한 번 읽어 결정 시점·bucket·grant createdAt에 함께 사용한다. 앱 Clock 기반 순수 정책 테스트와 실제PG 경계 테스트를 구분한다. 자정/대기 중 기간 전환은 이 시점으로 다시 계산한다. 시계가 역행해 저장된 기간보다 과거 bucket이 나오면 counter를 뒤로 초기화하지 않고 거절/관측한다.

## 6. 멱등성과 오류 계약

- 기존 POST 주소/기본 성공 필드는 유지하되 `Idempotency-Key`를 **필수로 추가하는 방향**을 제안한다. 길이/형식을 제한한다. userId는 JWT만 사용하며 key는 계정·faucet endpoint scope로 묶는다. 이전 무키 client는400이므로 웹/테스트/Android 전달 계약을 같은 release에 갱신해야 한다. 무키 호환을 유지하는 선택안은 서버 멱등성 보장을 못 하므로 첫 추천이 아니다.
- 같은 사용자·같은 키의 성공은 원래 지급 결과를 반환하고 새 지급·예산 차감·grant·WS를 만들지 않는다. 새 policy 적용 이후에도 먼저 성공 replay를 찾아 이전 결과를 유지한다. request fingerprint가 다르면409. 현재 client가 금액을 선택하지 않더라도 향후 계약 확장을 위한 경계다.
- 성공만 immutable claim으로 보존한다. **정책 거절/인프라 rollback은 성공 claim을 생성하지 않으며**, 동일 키의 나중 요청이 조건 개선 후 처음 성공할 수 있다. 실패 응답까지 영구 replay하는 정책과 다르다는 점을 API에 명시한다. grant commit 이후만 at-most-once credit을 보장하며 서로 다른 키는 정책 안에서 별도 지급이다.
- 응답 유실/WS 실패: 새 키를 자동 발급하여 재지급하지 않는다. 같은 키 결과 조회·재시도와 portfolio REST 재조회. replay의 balance는 원래 지급 시점 값이며 이후 거래를 반영한 최신 잔고가 아니다. `grantId/grantedAt/replayed` 등 추가 metadata로 표시한다.
- 성공 fingerprint를 TTL로 지우면 같은 키로 두 번 지급할 수 있다. 첫 범위에서 임의 삭제/TTL은 넣지 않고 별도 retention 승인 시 영구 dedup marker 또는 명시적 만료 키 거절을 설계한다.

| 오류 | 제안 HTTP/표현 |
|---|---|
| 인증 없음 / 남의 지급 정보 | 401 / 403 또는 사용자 소유권404 |
| 키/요청 형식 오류 | 400, 사용자 금액 입력 금지 |
| 계정 기간 제한/cooldown | 429 + 계산 가능한 nextEligibleAt/Retry-After. 별도 제약이 더 막으면 시각만으로 지급 보장 안 함 |
| 누적 계정 한도 / 전역 예산 종료 / 정책 비활성 | 409 + 안정된 사유 코드. 일정 시간 후 반드시 가능하다고 표시하지 않음 |
| 설정/회계 근거 부족, DB/lock timeout | 503 + 비민감 코드. rollback, 같은 키로 확인/재시도; SQL/secret/raw 예외 미노출 |

거절 사유별 ADMIN 집계는 bounded 비민감 metrics/관측으로 제안한다. 개별 거절 원문·새 키마다 무제한 request journal을 쌓지 않으며 이 집계는 지급 정본이 아니다. 과도 요청에 대한 전송 rate limit과 여러 계정 생성(Sybil) 방어는 quota와 별개다. rate값·인증 자격 강화를 몰래 확정하거나 이미 해결됐다고 주장하지 않는다.

## 7. API/UI·ADMIN 범위

모두 **추가 예정 계약**이며 현재 존재하는 API가 아니다.

- USER `GET /api/wallet/faucet/status`: 본인 지급 가능 여부·사유·선택 정책·기간·다음 시각·본인 잔여 한도. 응답은 안내용이며 POST transaction에서 다시 판정한다. 다른 사용자나 전체 운영자 자산 정보 노출 없음.
- USER POST 기존주소 + `Idempotency-Key` header. 본인 결과 조회는 `GET /api/wallet/faucet/claims` + 같은 header로 제안한다. raw key를 URL에 넣지 않고 access log/trace에서도 이 header와 JWT를 기록하지 않는다. 본인 성공 이력은 `GET /api/wallet/faucet/grants`(cursor)로 구분한다. 금액/회계 상태는 decimal string로 정확히 표시한다.
- ADMIN `GET /api/admin/faucet`: 정책 version, 누적/기간 지급 count·amount·잔여 budget·coverage/provenance·counter/journal 비교 상태·거절 reason집계. `GET /api/admin/faucet/grants`: user/기간/정책 필터·bounded cursor 지급 이력. 전체 key 원문은 노출하지 않는다.
- 웹 portfolio의 지급 버튼은 상태 조회·정책 안내·남은 지급액·불확실 결과 복구에 연결한다. UI disabled만으로 제한을 맡기지 않는다. ADMIN의 **DB 모의자금 지급 예산**과 기존 **장부-체인 자산 대사 / 거래 가능 유동성**은 별도 카드로 표현한다.
- 첫 범위 ADMIN은 읽기 전용이다. 임의 사용자 대신 지급/한도 우회/예산 리셋/기존 journal 삭제/준비금 baseline 재생성/자동 operator 충전은 넣지 않는다. 정책 변경은 승인한 version rollout로 다루며 UI의 임의 변경 기능은 별도 설계 대상이다.

## 8. 도입·회귀와 검증 계획

현재 journal 도입 이전 지급은 기록이 없을 수 있다. 현재 잔고나 주문을 역산해 과거 faucet 횟수를 추정하지 않는다. **새 0원 DB fixture와 최종 신규 acceptance**에 정책을 도입하는 것이 안전한 우선안이다. 기존 환경에 적용하려면 실제 journal coverage 확인, grant/사용량 일관된 snapshot·짧은 쓰기 경계, verified backfill 또는 명시적 activation 이후 한도라는 별도 migration 설계가 필요하다. 과거가 불명확한데 실제 평생 누적 한도로 표시하지 않는다. 기존 acceptance/개발DB를 이 작업에서 migration하지 않는다.

승인 후 `feat/faucet-policy`에서 구현·검증하여 통합한다. 예상 수정은 wallet 정책·quota/claim repository, schema, 오류·권한, 웹·API 계약·테스트·문서이며 Order/Trade/Vault·서명/EIP-712·nonce·정산 로직은 유지한다. 새 metadata는 reserve 계산의 기존 grant 금액에 덧셈으로 중복 반영하지 않는다. faucet grant가 증가하는 중 reserve 관측은 기존 CUT_CHANGED/INCONCLUSIVE가 되어야 한다.

예정 테스트:

- 순수 정책: 각 한도 미만/같음/초과, 고정P에서 count·amount 중복 제한, 부분 지급 거절, 기간/KST·UTC·rolling 선택 경계/clock역행, precision·overflow·잘못된 설정.
- 실제 새PostgreSQL: 같은/다른키 동시 요청, 다른사용자 마지막 global budget 경쟁, quota 최초row 생성 경쟁, locktimeout, 자정에 lock대기, journal/claim/amount/counters 원자commit·rollback, 응답 유실 후 replay, version변경에도 old key replay/누적한도 미리셋, source/counter차이 failclosed.
- 주문 경합: 동일 mKRW faucet vs BUY잠금·정산 및 SELL수령; lockedAmount 보존·잔고 증가 유실 없음·lock순서/timeout. pending/review상태를 faucet이 해제/수정하지 않음.
- 새Anvil/Synthetic: DB 지급 시 chain assets/supply/allowance/nonce/block **무변경**, 지급 후 기존quoteId EIP-712 BUY/SELL, 두 대사 MATCH와 concurrent grant 시asset대사 INCONCLUSIVE, operator 부족을 지급정책이 해결했다고 표시하지 않음.
- HTTP/웹: USER 소유권·ADMIN읽기·무키/중복/거절·다중탭·logout/stale상태·원래 응답 balance시점 안내·모바일. 기존전체회귀+Foundry+독립검토. 실제Toss/유료AI/운영부하는 최종단계/별도승인.

## 9. 이번에 승인받을 것

같은 quota 회계 범위에서는 기간 정의와 timezone을 고정한다. 정책 version 변경으로 P/횟수/금액 한도를 바꿔도 기존 지급을 새 bucket으로 재분류하거나 사용량을 초기화하지 않는다. 기간 정의/timezone 변경은 별도 migration·snapshot·재분류 설계 승인이 필요하며 첫 범위의 일반 설정 변경에 넣지 않는다.

1. 정책 유형: 추천 기간별 정액+계정/전역 한도, 기간 종류/timezone 및 cooldown 필요 여부.
2. P, N/U, L/C, B/Bp 실제 값과 어떤 제약을 사용할지. 금액·횟수는 아직 설정하지 않았다.
3. 성공 key 필수/성공만 멱등 보존/거절은 재판정, ADMIN읽기 전용, 전체 예산 all-or-nothing, 새fixture 우선 도입 경계.

현재 완료된 것은 코드 조사와 이 **설계 제안**뿐이다. 위 정책·API·테이블·동시성 테스트는 구현/실행 완료 상태가 아니다.
