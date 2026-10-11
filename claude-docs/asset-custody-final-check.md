# 자산 보관 모델 확정·거래 가능성·온체인 Faucet 최종 점검

> 2026-10-12 / 기준 `integration/additional-features` HEAD `0b5d35feee90487ba6fdd9a3441004bf45a15c22`. 사용자 승인으로 A 모델을 확정했다. 이번 변경은 표시 문구·문서·표시 회귀 테스트뿐이다. 계약, API payload, 거래/정산/조건부 주문 코드와 기존 DB/Anvil/runtime은 변경하지 않았다. 커밋·merge·배포도 수행하지 않았다.

## 1. 공식 모델과 소규모 수정

**A: 운영자 통합 지갑 + PostgreSQL 사용자별 모의자산 장부.** 사용자별 온체인 지갑, CustodyVault, 사용자 직접 서명, 사용자 간 호가 거래소는 구현하지 않는다. [A/B/C 기술 검토](user-wallet-custody-architecture-review.md)는 당시 제안을 보존하고 후속 승인 표시만 추가했다.

- 사용자 `mKRW/mSEC`, `amount`, `lockedAmount`, portfolio는 DB의 모의자산 할당량이다. 실제 ERC-20을 해당 사용자가 직접 보유·통제한다는 의미가 아니다.
- 실제 거래의 executor/호출자는 operator이며 실제 토큰은 operator/Vault 주소에 있다. EIP-712 가격 서명자는 별도 price signer이고 사용자 서명을 대신하는 소유권 증명은 아니다.
- 장부-체인 자산 대사 MATCH는 기준점부터 설명 가능한 수량 변화의 일치다. 사용자별 온체인 소유권·사용자 동의·준비금 보증·모든 주문의 실행 가능성을 증명하지 않는다. 거래 전수 대사는 DB/호출/receipt/event의 대응과 coverage를 검증한다.
- 실제 원화·삼성전자 주식의 담보 또는 상환권은 없다.

변경 위치: [웹 공통 안내](../tools/websocket-test-client/index.html)의 `custody-notice`, [루트 설명](../README.md), [API 설명](../backend/README.md), [프로젝트 개요](project-overview.md), [기획서](../구현%20계획.md), [공통 문서 입구](README.md), [통합 계획](additional-features-integration-plan.md), [career brief](career-project-brief.md)의 소유권 표현/지갑 후보. 기존 잔고 필드·API 이름은 유지한다. 안내는 route 밖 정적 HTML이므로 최초 API 실패나 JS 미실행에도 존재한다. 기존 ADMIN 유동성 패널의 PoR/지급 보증 부인 및 ETH 한계 표시는 그대로 사용한다.

career brief 전체 기능 목록을 재작성하거나 AI 지식 manifest/index를 변경한 작업은 아니다. 이번 승인과 다른 과거 완료/미완료 이력은 기존 기능 문서로 확인한다. AI 지식의 DB/operator 구분은 이미 되어 있으며 이번 유료 재색인·AI 호출은 없다.

## 2. 현재 실행 구조와 판단 근거

```text
사용자 JWT + quoteId → DB 사용자 잔고 확인/입력량 잠금
                     → operator가 Vault 호출을 Gas 추정
                     → raw transaction 서명·DB SIGNED 저장 → RPC 전송
                     → receipt/Event 검증 → 사용자 DB 정산 → commit 후 개인 WS
```

등록/견적 성공은 온체인 실행 성공이 아니다. [PriceReportIssuer](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java)의 `issue`는 최신 시세·보고서와 Vault 가격 계산을 사용하나 실제 지급 자원을 예약하지 않는다. [OnchainOrderPreparationService](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java)는 사용자 DB 가용량과 견적을 검증/소비하고 입력량을 잠근다. RPC 전송 중 사용자 잔고 행 잠금을 길게 유지하는 구조로 바꾸지 않았다.

[BlockchainTransactionSender](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java)의 `submitInternal` 순서는 nonce/gasPrice → 실제 calldata `estimateGas` → 120% Gas limit → `saveSigned` → `ethSendRawTransaction`이다. `estimateGas` 오류는 `OperatorNotReadyException`이다. [OnchainOrderService](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java)의 `submitPrepared` 실패 보상은 `failIfNotSigned`를 사용한다. **저장된 BlockchainTransaction이 없는 경우만** 주문 실패/잠금 해제를 허용한다.

| 상황 | 이미 구현된 처리 | 남는 한계 |
|---|---|---|
| operator mKRW 부족 BUY | [Vault](../contracts/src/ExchangeVault.sol) `buy`의 `SafeERC20.safeTransferFrom`이 잔고/allowance 부족을 거부한다. 실제 호출의 Gas 추정에서 확인되면 SIGNED 저장 전 FAILED/잠금 해제 | 견적 시점에 지급 자원을 보장/예약하지 않는다. 추정 이후 잔고 변화로 실제 receipt가 실패할 수 있다 |
| Vault mKRW 부족 SELL | `sell`에서 계산된 **수수료 차감 후 지급액**과 Vault 잔고 비교, `InsufficientLiquidity` revert. Gas 추정 또는 실패 receipt로 처리 | 현재 SELL 수량과 서명 가격을 기준으로만 확인하며 미래 SELL 전부를 보장하지 않는다 |
| operator ETH 부족 | ADMIN 대사 유동성 정보에 `operatorEthWei` 표시, `gasAssessment=READ_ONLY_NOT_ESTIMATED` 또는 `BALANCE_UNAVAILABLE_NOT_ESTIMATED`. 전송은 Gas 추정/노드 검증을 거침 | 명시적인 `ETH >= gasLimit*gasPrice` 사전 확인·Gas 예산 예약은 없다. 추정에서 부족을 검출하는지는 노드에 따라 달라질 수 있으며 미래 거래 가능성을 보증하지 않는다 |
| DB 충분·체인 실행 불가 | 사용자 DB 자격과 실제 온체인 실행을 분리한다. 서명 전 실패는 보상, 저장 후 불확실성은 원문 복구, 실패 receipt는 잠금 해제 | 아래 SIGNED 장기 정체 위험이 남는다. DB Faucet 성공은 chain 공급/Gas를 만들지 않는다 |
| 기준 가격 상승으로 SELL 지급액 증가 | `gross=floor(tokenWei*priceE8/1e8)`, `fee=floor(gross*feeBps/10000)`, `net=gross-fee`를 사용한다. 부족하면 기존 `InsufficientLiquidity` | 가격 상승이 Vault 자금을 증가시키지 않는다. 다른 사용자 유입·자동 발행·보증은 없다 |
| 조건부 주문에서 위 실패 | 조건 충족 때 새 견적 발급·조건 재검사 후 동일 실행 경로. 서명 전 실패/실패 receipt는 `FAILED/EXECUTION_FAILED`, 미확정 거래는 `EXECUTING/AWAITING_RECEIPT`, 검증 불일치는 REVIEW_REQUIRED | 조건 충족이나 충분한 사용자 DB 잔고는 체결 보장이 아니다. 실행 Order 실패 후 자동 재주문하지 않는다. 미확정 건을 취소/만료로 잠금 해제하지 않는다 |

SELL에는 operator의 mSEC도 필요하다. `SamsungPriceTrackingToken.burn(msg.sender,input)`의 잔고 검사 역시 실제 호출 시 강제된다. 수수료는 BUY 입력액 전체 이동, SELL 순지급액 이동에 이미 포함되며 대사에서 중복 차감하지 않는다. Vault의 공개 quote 함수는 수량 계산이지 유동성 보증이 아니다.

추가 근거: [ConditionalOrderWorker](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderWorker.java), [ConditionalOrderService](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderService.java)의 `refresh`, [BlockchainReconciliationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java), [OnchainSettlementService](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java), [ReserveCalculator](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveCalculator.java).

## 3. 처리하지 못하는 실제 위험과 수정 후보 — 구현하지 않음

**서명 저장 후 RPC 거절/장애가 계속되면 자동 종결 없이 잠금이 장기간 유지될 수 있다.** `saveSigned` 후 RPC가 부족 ETH 등을 이유로 거절하거나 응답을 잃으면 `SIGNED` 증거가 있으므로 보상 경로는 잠금을 풀지 않는다. `recoverSigned`는 체인 조회 후 같은 raw tx를 재전송하고, polling의 일반 RuntimeException은 다음 주기에 재시도한다. 이 경로에 재시도 상한·정체 시간 기반 수동 검토 전환은 없다. 이는 코드를 따라 확인한 상태 전이이며 **이번에 실제 ETH 부족을 일으켜 재현한 결과는 아니다**.

보고서 TTL이 지났다고 raw tx의 성공 여부가 확정되는 것은 아니다. 제출 후 재시도가 뒤늦게 채굴되어 만료 revert receipt가 나오면 기존 실패 정산이 가능하지만, receipt가 계속 없으면 잠금이 남을 수 있다. 조건부 주문도 연결된 거래가 SIGNED/SUBMITTED인 동안 EXECUTING이며 별도의 안전한 종결을 제공하지 않는다.

후속 변경 후보는 (1) 잔액/Gas 추정 기반 **참고 사전 진단**과 (2) 기존 ADMIN 상태 조회에 오래 정체된 SIGNED/SUBMITTED의 정체 시간·경보·검토 기준을 보완하는 정책이다. 시간 경과나 한 번의 RPC 오류만으로 잠금 해제·새 nonce 재주문을 해서는 안 된다. 안전한 종료 정책은 nonce/receipt/동일 원문 증거와 대사 영향 설계를 먼저 승인받아야 한다. 자동 자금 충전, 새 mint 정책, 거래 보증은 이번 범위 밖이다.

추정과 실행 사이의 자원 변화, 기준 가격 상승의 유동성 소진은 이미 contract revert/receipt 실패로 보호되는 **거래 실패 가능성**이다. 이를 처리하지 못하는 잔고 손실로 분류하거나 새로운 경제 모델이 필요하다고 단정하지 않는다.

## 4. 공개 MockKRW Faucet와 Sepolia 선택지

### 현재 확인된 사실

[MockKRW.sol](../contracts/src/MockKRW.sol)의 `faucet()`은 누구나 반복 호출해 자기 주소에 기본 1,000,000 mKRW를 mint할 수 있다. DB의 KST/누적/전역 예산 정책과 무관하다. owner의 `setFaucetAmount`도 총량/일일 예산 제한이 아니다. [PrepareOperator](../contracts/script/PrepareOperator.s.sol)는 operator 테스트 잔고와 Vault allowance를 준비한다. 로컬 시나리오/테스트에서 외부 자금 공급 없이 BUY·Vault 유동성을 만들기 위한 편의 기능이지 **공개 무제한 권한 자체가 필수 요구**는 아니다.

Sepolia 공개 배포 시 누구나 DB Faucet quota 밖에서 mint 가능하며 총발행량이 통제되지 않는다. 타인이 mint해도 operator 잔고가 자동으로 증가하지는 않는다. 타인의 직접 Vault 입금 등도 기준점 밖 변화를 만든다. 가격 보고서 서명/executor 권한까지 얻는 것은 아니므로 공개 mint만으로 남의 토큰을 소비하거나 백엔드 사용자 잔고를 늘릴 수 있다고 해석하지 않는다. 호출자 자신의 Gas를 지불하며 모든 비용이 operator에 청구되는 구조가 아니다.

현재 자산 대사는 mKRW `totalSupply`의 기준점 대비 불변을 검사하므로 **관리 대상 외 주소의 공개 mint도 수량 MISMATCH**를 만들 수 있다. 이것이 사용자 장부 횡령을 곧바로 증명하는 것은 아니다. 정상 운영 중 추가 공급을 허용하려면 발행 provenance와 공식 대사 식을 별도로 설계해야 하며 기준점을 덮어써 없애서는 안 된다. 거래 전수 대사는 거래 기록을 비교하므로 이러한 별도 mint 전체의 적법성/공급 보증을 대신하지 않는다.

### 대안 (아직 선택·구현·배포하지 않음)

| 대안 | 로컬 테스트 유지 | 공개 환경·대사 영향 |
|---|---|---|
| 로컬 전용 공개 MockKRW + Sepolia 제한 발행 variant | 기존 fixture는 현행 공개 faucet 사용 | 공개 variant는 기본 fail-closed owner/허용 minter만 발행. ERC-20 인터페이스 유지 시 Vault 경제식은 유지 가능하지만 배포/준비 스크립트·권한·코드 provenance·신규 기준점 검증 필요 |
| 단일 계약의 허용 minter/발행 enable 권한 | 테스트 배포에서 명시적으로 허용 | 모든 환경에서 권한·실수로 공개 활성화 방지·키 관리 검증 필요. chainId 하드코딩만으로 발행 보안을 대체하지 않음 |
| 최초 고정 발행 후 추가 mint 없음 | fixture가 최초 배분을 준비 | 단순하고 총량 추적 용이. 지급 유동성 소진은 여전히 발생하며 고정 공급량/배분을 별도 승인해야 함 |
| 현행 무제한 faucet 유지 + 명시적 테스트 표기 | 변경 최소 | 통제 발행/준비금 주장을 할 수 없음. 별도 외부 mint 인식 없이는 현재 totalSupply 대사의 지속 MATCH와 양립하지 않음 |

**추천 제안:** 공개 Sepolia가 필요해질 때 로컬용 mock과 공개용 제한 발행 variant를 분리하는 방안을 우선 검토한다. 발행자·초기 공급·추가 공급 여부·소유자 키/권한, 표준 ERC-20과 기존 Vault 호환성, 공급 이력과 대사 정책을 사용자 승인 후 확정한다. 신규 배포 주소와 Oracle EIP-712 domain에 맞는 **새 격리 환경/기준점**을 구성하고 기존 DB/quote/txHash를 새 체인에 이어 붙이지 않는다. 이번에는 MockKRW나 다른 계약을 수정·재배포하지 않았다.

## 5. 이번 검증과 보존 경계

- 웹 `npm test`: **78/78 PASS, skip 0**. 정적 안내 존재/route 외 배치/소유권·보증·상환권 부인을 새 assertion으로 검증했다.
- `npm run test:browser`: **PASS**. Vite 94 modules build, Chromium REST/STOMP fixture에서 기존 흐름과 desktop/360px mobile 5 routes, 공통 안내 표시 및 가로 overflow 없음 확인. 실제 backend/chain 인수나 실제 Toss 결과가 아니다.
- Foundry `forge test --offline`: **36/36 PASS, skip 0**. Vault 유동성 부족 revert의 burn/Oracle 소비 rollback, 가격 상승 roundtrip, 서명·min-output·fee 변경 등 기존 회귀를 이번에 재실행했다. Foundry 테스트 EVM이며 실제 Anvil 거래가 아니다.
- backend `.\gradlew.bat test --offline --no-daemon --rerun-tasks`: **452개 / 418 PASS / 34 SKIP / 실패·오류 0**, BUILD SUCCESSFUL. H2/Mock 기반 기본 회귀이며 실제 환경 opt-in 34개는 실행하지 않았다. 수동 build resource 정규화는 하지 않았다. 최초 sandbox 실행은 Gradle cache lock 경로 권한 때문에 시작하지 못했고 사용자 소유 캐시 접근 승인 후 새 실행했다. 이를 테스트 실패/조건부 통과로 혼동하지 않는다.
- 34 skip은 실제 AI provider/색인·검색 평가, AI pgvector/Tool PG·Anvil, blockchain Anvil, Quote PG concurrency, Synthetic/Faucet/조건부 주문/자산 대사/거래 전수 대사의 opt-in E2E다. 이전 실제 E2E 결과를 이번에 재실행한 것으로 합산하지 않았다.
- 단순 문구·문서 변경으로 거래 동작/API 계약은 바꾸지 않았다. 공통 지침의 동작 변경 대상이 아니므로 별도 검토자를 호출하지 않았으며 독립 검토 통과를 주장하지 않는다. 코드 근거·문서 링크·변경 범위는 구현자가 점검했다.

실제 부족 ETH 전송, 실제 PostgreSQL/Anvil E2E, Toss 장중, 유료 AI, Android 빌드, 다중 프로세스 nonce, Sepolia/AWS/운영 부하는 이번 실행 범위 밖이다. 이 한계는 소규모 문구 회귀 통과로 제거되지 않는다. 정상 API/거래 로직/컨트랙트 변경이 없으므로 새로운 격리 DB/체인을 생성할 필요도 없었다. 기존 acceptance·개발 DB/Anvil/실제 .env/runtime에는 접속·변경하지 않았다.

Git 읽기 전용 점검: 현재 브랜치는 `integration/additional-features`, HEAD는 위 기준 그대로이고 변경은 설명 문구·문서·표시 테스트만이다. 원본 `melon-init`은 `b7cb0f0ed8fd936e73733a6d2f167efe4a951a63`, root `git status --short`는 빈 출력(clean)이었다. `git diff --check` 통과(Windows LF→CRLF 알림만 있음), 두 자산 보관 보고서의 로컬 링크 유효. 비밀키·실제 .env·DB/chain state·runtime/로그·빌드 산출물은 변경 목록에 없으며 커밋하지 않았다.
