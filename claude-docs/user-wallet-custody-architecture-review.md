# 사용자별 온체인 지갑·자산 보관 모델 기술 검토

> 후속 결정(2026-10-12): 사용자가 A 모델을 공식 유지 모델로 승인했다. B/C·신규 CustodyVault·사용자 직접 서명·사용자 간 호가 거래소는 구현하지 않는다. 아래 제안/미승인 표시는 최초 검토 당시의 이력이며 [최종 점검](asset-custody-final-check.md)이 현재 결정과 후속 결과다.

> 2026-10-12 / 기준 `integration/additional-features` **0b5d35feee90487ba6fdd9a3441004bf45a15c22**. **설계 제안이며 구현 승인이 아니다.** 이번 변경은 이 보고서 하나뿐이다. 제품 코드·계약·기존 DB/Anvil/runtime·acceptance·melon-init은 변경하지 않았다. 외부 서비스/RPC/유료 AI를 호출하거나 테스트를 재실행하지 않았다.

## 1. 결론

**현재 졸업프로젝트 목표에는 A(운영자 통합 지갑 + DB 사용자 장부 유지)를 추천한다.** 사용자별 지갑 없이는 현재 요구를 구현할 수 없다는 근거는 확인되지 않았다. Oracle 기준 모의 거래, 사용자별 포트폴리오, 조건부 실행, 서명 가격 검증, 실패 복구, 장부·거래 감사는 이미 이 구조로 구현돼 있다.

다만 A는 **사용자가 ERC-20을 직접 보유·통제하는 서비스가 아니다.** DB 모의 자산 할당과 운영자 온체인 정산을 연결하는 하이브리드 플랫폼이라고 설명해야 한다. DB 전체 합계와 operator 잔고가 우연히 같아도 개별 사용자 온체인 소유권, 사용자 동의, 상환 가능성은 증명되지 않는다.

B는 사용자별 배분의 온체인 검증이 명시적인 목표가 될 때의 별도 연구 범위다. C는 키 기반 자산 통제·외부 지갑 사용이 필수 요구일 때 선택할 범위다. 두 대안 모두 기술적 의미가 있지만 현재 모의 플랫폼의 필수 보완으로 간주하지 않는다. **개별 주소 생성만 하는 중간안은 추천하지 않는다.**

## 2. 조사 근거와 사실/제안 구분

이 문서에서 **현재 사실**은 아래 소스와 테스트 assertion을 읽어 확인한 내용이다. **제안**은 아직 존재하지 않는 설계다. 코드만으로 현재 기동 환경의 주소별 잔고·권한 설정을 확인한 것은 아니며 DB/체인 실측도 하지 않았다. 배포 스크립트의 역할 설정은 의도된 구성이지 모든 실행 환경에서 동일하다는 증거가 아니다.

| 근거 파일 | 확인한 역할 |
|---|---|
| [MockKRW.sol](../contracts/src/MockKRW.sol) | 호출자에게 제한 없는 faucet mint, owner의 지급액 설정 |
| [SamsungPriceTrackingToken.sol](../contracts/src/SamsungPriceTrackingToken.sol) | 지정 minter만 mint/burn, owner의 minter 교체 |
| [ExchangeVault.sol](../contracts/src/ExchangeVault.sol) | signed BUY/SELL, msg.sender 자산 이동·mint/burn, 수수료·유동성 |
| [PriceReportTypes.sol](../contracts/src/PriceReportTypes.sol), [PriceOracle.sol](../contracts/src/PriceOracle.sol) | executor 바인딩, 가격 서명·domain·TTL·일회 소비 |
| [PrepareOperator.s.sol](../contracts/script/PrepareOperator.s.sol) | operator용 온체인 faucet·Vault approve; DB Faucet 아님 |
| [User.java](../backend/src/main/java/com/pricetrack/exchange/user/User.java), [AuthService.java](../backend/src/main/java/com/pricetrack/exchange/auth/AuthService.java) | nullable walletAddress, 가입 시 DB 잔고 초기화, JWT 인증 |
| [UserBalance.java](../backend/src/main/java/com/pricetrack/exchange/wallet/UserBalance.java), [WalletService.java](../backend/src/main/java/com/pricetrack/exchange/wallet/WalletService.java) | amount/lockedAmount/평균 매수가와 DB 자산 접근 |
| [FaucetService.java](../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetService.java), [FaucetStore.java](../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetStore.java) | 지급 정본·quota/budget·멱등성·DB transaction |
| [PriceQuoteService.java](../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java), [PriceReportIssuer.java](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java) | DB 사용자 귀속, operator executor의 가격 보고서 발급 |
| [OnchainOrderPreparationService.java](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java), [OnchainOrderService.java](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java) | quote 소비·입력 잠금·운영자 전송 연결 |
| [BlockchainTransactionSender.java](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java) | operator 키·nonce·Gas·SIGNED 저장·동일 raw 복구 |
| [BlockchainReconciliationService.java](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java), [ContractEventParser.java](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java), [OnchainSettlementService.java](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java) | receipt/event 검증과 사용자 DB 정산·REVIEW_REQUIRED |
| [ConditionalOrderService.java](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderService.java), [ConditionalOrderWorker.java](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderWorker.java) | 장기 의도·fresh quote·실행 연결·취소/복구 |
| [ReserveCalculator.java](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveCalculator.java), [ReserveService.java](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveService.java), [ReserveStore.java](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveStore.java) | 수량 보존식·immutable 기준점·consistent-cut |
| [AuditValidator.java](../backend/src/main/java/com/pricetrack/exchange/tradeaudit/AuditValidator.java), [AuditService.java](../backend/src/main/java/com/pricetrack/exchange/tradeaudit/AuditService.java), [RpcAuditChain.java](../backend/src/main/java/com/pricetrack/exchange/tradeaudit/RpcAuditChain.java) | 주문별 증거 비교·역방향 event scan·과거 설정·coverage |
| [schema.sql](../backend/src/main/resources/schema.sql), [conditional-schema.sql](../backend/src/main/resources/conditional-schema.sql) | 사용자·잔고·주문·Trade·quote·tx·지급/대사·의도 저장 모델 |

## 3. 현재 구조도와 자산 보관

```text
사용자 브라우저 ─ JWT / quoteId / 조건부 의도 ─→ Spring Boot
  │                                               │
  │                              PostgreSQL 사용자 장부
  │                              userId → amount / lockedAmount
  │                              Order → Quote → BlockchainTransaction → Trade
  │                                               │
  │                              price signer: 짧은 EIP-712 가격 서명
  │                              operator: 거래 raw 서명 + nonce + ETH Gas
  │                                               │ RPC
  │                                         Anvil / 체인
  │                                               │
  │                  BUY: operator mKRW ─→ ExchangeVault mKRW
  │                       mSEC mint ─→ operator
  │                  SELL: operator mSEC burn
  │                        ExchangeVault mKRW ─→ operator
  │                                               │
  └─ 개인 WS / 포트폴리오 ← DB 정산 ← 검증된 receipt / Vault·Oracle event

사용자 개별 주소·키 ── 현재 거래 경로에는 없음
PriceOracle ── 가격 승인/일회 소비; 자산 보관소가 아님
```

### 3.1 토큰·Vault·operator의 권한

- **mKRW**: ERC-20 잔고는 주소별 온체인 상태다. `faucet()`은 누구나 자신의 주소에 반복 mint할 수 있다. DB의 일일/누적/전체 예산 정책이 온체인 faucet에는 적용되지 않는다. 현재 MockKRW에는 공개 burn 기능이 없다. owner는 faucetAmount를 설정한다. 보유자는 transfer, 승인받은 spender는 transferFrom을 사용할 수 있다.
- **mSEC**: ERC-20이고 주소 간 이전도 가능하다. 지정 minter만 `mint(to, amount)`와 `burn(from, amount)`를 호출한다. burn은 이 구현에서 ERC-20 allowance를 사용하지 않는다. owner는 minter를 변경할 수 있으므로 “운영자가 어떤 방법으로도 발행 권한을 바꿀 수 없다”는 설명은 틀리다. 의도된 minter는 ExchangeVault다.
- **ExchangeVault**: krw/token/oracle 연결 주소는 immutable. BUY는 caller의 총 mKRW를 받고 caller에게 새 mSEC를 발행한다. SELL은 caller의 mSEC를 소각하고 Vault mKRW를 지급한다. 보통 mSEC 재고를 쌓아 사용자에게 분배하는 구조가 아니다. SELL에는 Vault mKRW 유동성이 필요하다.
- **operator**: 현재 백엔드의 실제 거래 caller이자 mKRW/mSEC 보유 주소다. operator가 키로 거래하고 ETH Gas를 낸다. Vault의 Bought/Sold 이벤트 이름 `user`도 실제로는 operator다. 컨트랙트는 특정 operator 주소를 하드코딩해 전용 거래자로 제한하는 대신 `report.executor == msg.sender`를 검사한다. 현재 발급기가 operator를 executor로 선택한다.
- **price signer**: 기준 시세/방향/입력량/최소 수령량/유효기간/executor를 승인하는 별도 키다. 사용자 자산 통제 서명이나 사용자 주문 동의 서명이 아니다. 토큰 보관 주체도 아니다.
- **owner와 operator는 논리적으로 다른 권한**이다. 배포 환경에서 같은 주소일 수 있지만 코드상 반드시 같아야 하지는 않는다. 현재 Vault에 별도 owner 출금 함수는 없으며, 외부에서 Vault 주소로 토큰을 보낼 수 있다.
- `PriceOracle.updatePrice`는 코드에 남아 있지만 Vault 거래를 대체하지 않는다. 실제 BUY/SELL은 `PriceReport + signature`만 받고 승인 가격을 같은 거래에서 소비한다.

### 3.2 DB 잔고의 정본

현재 사용자에게 보여 주고 사용을 허용하는 자산은 `user_balances(user_id, symbol)`의 **모의 장부 할당**이다. `amount`는 잠긴 수량을 포함한 총량, `available = amount - lockedAmount`다. lockedAmount를 총량에 더하거나 총량 대사에서 다시 차감하면 이중 계산이다. DB 잠금은 온체인 escrow가 아니다.

| 상황 | 사용자 DB 변화 | 실제 체인 변화 |
|---|---|---|
| 가입 | mKRW/mSEC 잔고0 생성 | 지갑 생성·mint 없음 |
| DB Faucet | mKRW amount 증가 + FaucetGrant·사용량·성공 claim; locked 불변 | 없음 |
| 온체인 BUY 준비 | 사용자 mKRW locked 증가, Order 생성, quote 소비 | 아직 없음 |
| BUY 성공 정산 | mKRW amount/locked 감소, mSEC 증가, 평균 매수가 갱신, Trade 생성 | operator mKRW 감소/Vault 증가; operator mSEC·totalSupply 증가 |
| SELL 준비·성공 | mSEC 잠금 후 amount/locked 감소; mKRW 증가 | operator mSEC·totalSupply 감소; Vault mKRW 감소/operator 증가 |
| 확정 실패 receipt | 입력 잠금 해제, amount 불변, FAILED | 자산 변경 revert; Gas 지출은 가능 |
| SIGNED/불확실/REVIEW_REQUIRED | 잠금 유지; 근거 없는 지급·재주문 없음 | 성공 여부 미확정 또는 DB 반영 대기 |
| DB_ONLY 즉시 거래 | DB에서 잔고·Trade 변화 | 없음; 실행 유형으로 구분 |
| 조건부 WAITING/취소/실행 전 만료 | 의도 상태만 변화; 자산 미예약 | 없음 |

온체인 정산 결과는 해당 DB Order.userId에 귀속된다. **이 귀속을 체인이 직접 검증하지는 않는다.** 현재 PriceReport에 userId/beneficiary가 없고 PriceQuote.userId는 DB에만 있다. DB 행을 일관되게 재귀속하는 악의적 관리자를 현재 가격 서명만으로 막을 수는 없다. 감사는 저장된 증거의 정합성을 검사하며 독립적인 사용자 서명 동의 증명은 아니다.

`users.wallet_address`는 이미 nullable 컬럼이며 `me` 응답에서 읽는다. 검색 결과 현재 가입/quote/전송/정산 경로는 이를 설정하거나 자산 보관 주소로 사용하지 않는다. “컬럼이 없다”가 아니라 **키 소유 증명·자산 배분·서명 실행이 구현돼 있지 않다**가 정확하다.

## 4. 두 대사가 증명하는 것과 증명하지 않는 것

### 4.1 장부-체인 자산 대사

현재 ReserveCalculator의 수량식(토큰 최소단위 정수):

```text
DB mKRW(u) = FaucetGrant(u) 합 - BUY.quoteAmount 합 + SELL.quoteAmount 합
DB mSEC(u) = BUY.baseAmount 합 - SELL.baseAmount 합
  위 DB 식에는 ONCHAIN과 DB_ONLY Trade 모두 포함

ΔK = ONCHAIN SELL.quoteAmount 합 - ONCHAIN BUY.quoteAmount 합
ΔS = ONCHAIN BUY.baseAmount 합 - ONCHAIN SELL.baseAmount 합

operator K = 기준 operator K + ΔK
Vault K    = 기준 Vault K    - ΔK
operator S = 기준 operator S + ΔS
Vault S    = 기준 Vault S
mSEC totalSupply = 기준 supply + ΔS
mKRW totalSupply = 기준 supply
```

BUY.quoteAmount는 수수료 포함 총 입력, SELL.quoteAmount는 수수료 제외 순출력이다. 수수료를 별도로 다시 차감하지 않는다. BUY fee는 Vault에 들어간 총 입력에 포함되고 SELL fee는 지급하지 않은 금액으로 Vault에 남는다. DB Faucet은 chain ΔK에 넣지 않는다. Faucet 정책 counter도 자산 계산에 더하지 않는다.

이 식은 **신규 0원 DB에서 설정한 불변 기준점과 그 이후 지원된 흐름**에 대한 것이다. 운영 중 별도 온체인 faucet/mint/입출금/외부 transfer는 현재 식으로 자동 정당화되지 않는다. 관측된 예상 밖 흐름은 MISMATCH 또는 근거 부족 시 판정 불가가 될 수 있다. 이를 정상화하려고 기준점을 재설정해서는 안 된다.

DB는 짧은 read-only REPEATABLE_READ snapshot이고, 체인은 blockNumber/hash로 조회한다. 마지막 DB fingerprint·head·canonical 검증을 통과하지 못하면 INCONCLUSIVE다. 미정산·REVIEW_REQUIRED·UNKNOWN 과거 유형은 정상/불일치로 단정하지 않는다. 안정된 정산 완료 구간의 잔여 lockedAmount는 불일치 대상이다.

**MATCH ≠ 다음 주문 가능 ≠ 준비금 보증 ≠ 사용자별 온체인 소유권.** 유동성은 별도 operator mKRW/allowance/mSEC, Vault mKRW, operator ETH 참고다. ETH 조회는 Gas 추정이나 모든 미래 거래 가능성 보증이 아니다. `operator K / 사용자 DB K`는 참고값이며 Proof of Reserves가 아니다.

### 4.2 거래 전수 대사

Order/Trade/PriceQuote/BlockchainTransaction의 userId·방향·수량·해시·상태를 비교하고 실제 transaction/receipt의 sender·destination·nonce·block·calldata, Vault 이벤트, Oracle quoteId/서명/TTL, 과거 signer/fee 근거를 확인한다. DB 대상 → 체인 비교와 불변 baseline 이후 해당 Vault/Oracle 이벤트 → DB 연결을 모두 검사한다. 모든 블록의 모든 ERC-20 전송이나 baseline 이전 전체 체인 역사를 포괄하는 감사는 아니다.

개별 MATCH/MISMATCH/INCONCLUSIVE와 전체 coverage가 별개다. 조회/scan/환경/cut이 불완전하면 전수 MATCH가 아니다. 과거 설정을 조회할 수 없으면 현재 signer/fee로 추측하지 않는다. 기존 정산/복구 호출이나 잔고 보정은 하지 않는다.

**전수 MATCH도 DB 사용자 배분이 사용자의 암호학적 동의를 받았음을 증명하지 않는다.** 현재 온체인 거래 주체가 operator이고 사용자 식별자는 DB 연결에 있기 때문이다.

### 4.3 읽어 확인한 테스트 근거

- [ReserveServiceTest](../backend/src/test/java/com/pricetrack/exchange/reserve/ReserveServiceTest.java): `faucetAndUnequalOperatorBalanceAreNormal`, `matchCanHaveInsufficientOperatorFunds`, `lockedAmountIsNotAddedToTotalAndTerminalLockIsMismatch`, `exactBuySellFeeAndSupplyConservation`, `dbOnlyTradeChangesLedgerButNotChainExpectations`, cut/UNKNOWN/REVIEW_REQUIRED 검증.
- [AuditReceiptTest](../backend/src/test/java/com/pricetrack/exchange/tradeaudit/AuditReceiptTest.java): DB 사용자·quote 소유·ABI/nonce/fee/Oracle event·과거 signer 부족 검증. 구성한 cryptographic/Mock 증거이며 실제 EVM 실행으로 오인하지 않는다.
- [ConditionalPostgresAnvilE2ETest](../backend/src/test/java/com/pricetrack/exchange/conditional/ConditionalPostgresAnvilE2ETest.java): 격리 BUY/SELL·동시 실행·취소·응답 유실/raw 복구·두 대사 확인 코드.
- 직전 [통합 검증 기록](oracle-conditional-orders-implementation.md)은 backend418PASS/34SKIP, 웹77/Foundry36PASS, 별도 H214+실제 E2E1PASS다. **이번 조사에서 재실행한 결과는 아니다.** B/C 구현·테스트는 존재하지 않는다.

## 5. A/B/C의 정확한 의미

### A — 운영자 통합 지갑 + DB 장부 유지 (추천)

지금 모델 그대로다. 사용자 인증은 JWT, 사용자 자산 할당은 DB, 실제 토큰은 operator/Vault 주소에 있다. 체인은 가격 승인과 operator 정산 증거를 제공한다. 새 지갑·새 계약·추가 자산 정본은 필요 없다. 사용자 자산 직접 통제라는 목표는 달성하지 않는다고 명시한다.

### B — 위탁 보관 + 온체인 사용자별 배분 (제안, 미구현)

단순 `userId → 숫자` 기록이나 token을 보유하지 않는 주소만 생성해서는 자산 보관 구현이 아니다. **실제 보관 자산과 사용자별 할당을 연결하고 이중 할당을 막는 계약**이 필요하다. 예를 들어 별도 CustodyVault가 실제 mKRW/mSEC를 보유하고 `accountId → balance`를 관리하며 기존 ExchangeVault와 교환하고 같은 체인 transaction에서 해당 계정을 차감/증가시킨다. ExchangeVault의 매도 유동성과 CustodyVault의 사용자 보관 자산은 별도 풀이다.

이 adapter안에서는 ExchangeVault의 caller/executor가 **CustodyVault**가 되고 외부 transaction sender/Gas payer는 operator다. 현재 발급기·parser·대사가 두 주소를 같다고 보는 가정을 모두 변경해야 한다. 대안으로 ExchangeVault 자체에 위탁 장부를 추가할 수도 있지만 사용자 보관 풀과 거래 상대방 유동성을 섞지 않는 별도 회계가 필요하며 계약 변경은 더 크다.

현재 PriceReport는 accountId를 서명하지 않는다. 사용자 귀속을 서명과 체인에서 검증하려면 **accountId/beneficiary를 포함한 V2 보고서 또는 quoteId와 계정·방향·입력을 결합한 별도 실행 승인 서명**이 필요하다. 후자의 키·역할·domain은 구현 전 별도 결정한다. 단순 이벤트에 계정 번호만 추가하는 것은 사용자 권한 검증과 다르다. 기존 가격 서명 형식/TTL/일회 소비를 유지할 부분과 새 승인 envelope를 명확히 버전 분리해야 한다.

사용자 키 없이 operator가 집행한다면 이는 **온체인에 검증 가능한 위탁 배분**이지 self-custody가 아니다. accountId 변경·재할당·출금·업그레이드 권한을 어떻게 제한할지에 따라 운영자 신뢰가 여전히 남는다. 사용자별 잔고는 체인 정본, DB 잔고는 검증된 이벤트 projection으로 취급하는 방향을 제안한다. 평균 매수가·손익·서비스 인증은 DB에 남는다.

DB Faucet 성공만으로 이 보관 자산이 늘지 않는다. 새 방식의 지급은 실제 토큰 공급/보관 credit 이벤트와 연결해야 하며 DB 정책 승인→체인 지급→확정 후 projection의 실패·재시도 모델이 필요하다. DB/체인 분산 transaction을 가정하지 않는다. 기존 Faucet의 즉시 DB 지급 의미를 그대로 두면서 이를 완전한 온체인 보유량이라고 표시할 수 없다.

### C — 사용자 키/지갑 + 사용자 서명 (제안, 미구현)

사용자가 키를 통제하고 자신의 주소가 실제 token을 보유해야 한다. 서버가 사용자마다 키를 만들고 전부 보관하는 방식은 per-address custody이지 사용자 직접 통제가 아니며 C의 목표를 만족하지 않는다.

직접 호출 방식은 Vault caller와 report.executor가 사용자 주소가 되면 현재 Vault의 교환 로직을 재사용할 여지가 있다. 그러나 발급기가 operator executor를 고정하고 서버가 operator로 전송하므로 **현재 백엔드를 그대로 두고 주소만 바꾸어 사용할 수는 없다.** 사용자 mKRW approve와 ETH Gas·사용자 nonce·서명 tx 수집/검증이 추가된다. 현재 SELL burn에는 allowance 검사가 없다는 점도 그대로 이해해야 한다.

operator가 Gas를 내는 relay/위임 방식에서는 원래 Vault의 msg.sender가 relayer이므로 사용자 토큰을 직접 소비할 수 없다. beneficiary/payer와 executor 분리, 사용자 승인·위임 검증 계약 또는 안전한 adapter가 필요하다. 기존 report의 executor만 relayer로 바꾸면 relayer 자산을 거래할 뿐 사용자 자산 모델은 해결되지 않는다.

JWT와 지갑 소유 증명은 별개다. challenge의 domain/유효기간/nonce, 서명 검증, 계정 연결·해제·주소 교체·키 유실 정책이 필요하다. 현재 price signer 서명은 사용자 거래 승인 서명을 대신하지 않는다.

조건부 자동 실행에는 사용자가 등록 때 **장기 조건 의도**를 서명하거나 제한된 권한을 위임해야 한다. 이는 미리 저장한 짧은 PriceQuote 서명이 아니다. 목표 가격·방향·입력 상한·수혜 주소·만료·intent nonce·체인/계약을 결합한 사용자 승인과 트리거 시 fresh 가격 서명을 함께 검증한다. 위임하지 않으면 트리거 시 사용자의 온라인 재서명이 필요해 현재 무인 실행 UX를 유지할 수 없다. 취소/위임 철회와 제출된 tx의 경합도 온체인 nonce·취소 상태를 기준으로 다시 설계해야 한다.

외부 transfer/별도 mKRW faucet으로 잔고가 변할 수 있으므로 DB는 체인 balance의 projection이고 lock은 내부 예약 참고값일 뿐 외부 지출을 막지 못한다. 온체인 escrow를 추가하지 않는다면 실행 시 자산 부족을 허용하고 체인 잔고를 다시 검증해야 한다. 공개 전송 자체를 사용자 간 거래소/호가 매칭 기능으로 확대하는 것은 이번 방향과 다르다.

## 6. 비교표

| 판단 항목 | A 현재 모델 | B 위탁 온체인 배분 | C 사용자 키 기반 |
|---|---|---|---|
| 자산 의미 | DB 모의 할당 / operator 실제 token | 보관 계약 자산에 연결된 계정별 배분; 운영자 집행 | 사용자 키로 제어되는 실제 주소 잔고; 위임 시 권한 범위 의존 |
| 가격·경제 구조 | 기존 Oracle/Vault 유지 | 동일 Oracle 가격 유지; 매칭 불필요 | 동일 Oracle 가격 유지; 매칭 불필요 |
| 컨트랙트 | 변경 불필요 | Custody 계약+계정 바인딩 승인 또는 Vault/Report V2 | 직접 호출은 Vault 재사용 가능; relay/자동 조건부는 사용자 승인·취소/위임 계약 필요 |
| Spring/DB | 기존 서비스·정본 유지 | 계정 mapping, custody 이벤트 projection, 지급 workflow, 모델 버전 추가 | 지갑 binding/proof, 사용자 tx 또는 relay, 잔고/전송 인덱싱, 사용자 nonce/위임 상태 |
| 인증 | 기존 JWT가 서비스 승인 | JWT+operator 권한; 사용자 자체 서명 증명은 없음 | JWT와 별도 키 소유/거래·위임 서명 |
| Gas/nonce | operator ETH·단일 프로세스 직렬화 | operator 부담 유지; caller와 sender 분리 | 직접은 사용자, relay는 operator; 사용자 nonce와 intent nonce 별도 |
| 대기·취소 | DB row와 실행 FK 경계 유지 | DB 의도+온체인 계정 실행/취소 경계 검증 추가 | 온라인 서명 또는 제한 위임; 온체인 철회·제출 경합·Gas 가능 |
| 실패 복구 | 동일 raw/receipt/REVIEW_REQUIRED 유지 | 원칙 재사용; 새 custody 이벤트까지 검증 후 projection | 외부 wallet tx 교체·직접 전송·위임 철회까지 추적; 기존 raw 저장만으로 부족 |
| migration | 없음 | 실제 담보/배분·cutover·새 기준점 필요 | 실제 token 이동·주소 소유 증명·과거/신규 실행 주체 분리 필요 |
| 보안 | 운영자 키·DB 관리자 신뢰 집중 | 키 신뢰+custody 코드/역할 위험; 사용자 배분 관측 강화 | 키 유실/피싱/무제한승인/주소 연결/서명 replay/위임 위험 |
| 개인정보 | 체인에 operator 중심 기록 | accountId는 공개 가명; 실명/이메일 직접 기록 금지 | 주소·거래 공개성과 서비스 계정 연계 고려 |
| 테스트 복잡도 | 기존 범위 보호 | 높은 수준: 보관량/내부장부·계정격리·cross-system 지급 | 가장 높음: 지갑별 자산/외부변경·서명·위임·교체·Gas/nonce |
| 졸업프로젝트 실익 | 하이브리드 정산·복구·감사에 집중, 완료 범위 선명 | 온체인 사용자 할당 검증·이중 정본 projection 설계 경험 | 실제 self-custody·wallet UX·권한 위임 경험; 주제 범위 크게 증가 |

모든 방식에서 원화/주식 상환권, 실물 담보, 경제적 가치나 지급 능력 보장은 생기지 않는다. 개별 지갑도 가격 신뢰·Vault SELL 유동성·operator Gas·분산 nonce 문제를 자동 해결하지 않는다.

## 7. 파일·DB·대사 영향 범위

아래 B/C 파일명은 **후보 설계**이며 새 파일을 생성/구현한 것이 아니다.

| 영역/현재 파일 | A | B의 예상 변경 | C의 예상 변경 |
|---|---|---|---|
| contracts/src/{ExchangeVault,PriceReportTypes,PriceOracle}.sol | 그대로 | 새 CustodyVault/계정 승인 envelope 또는 V2; 역할·asset caller 변경 | 직접 거래 ABI 유지 가능, delegated 거래/intent 승인·취소는 별도 계약/ABI |
| contracts/script/Deploy.s.sol, PrepareOperator.s.sol | 그대로 | custody 배포/역할·공급·allowance 설정 | 지갑별 funding/approve; dev 자동충전과 운영 기능 분리 |
| user/User, auth/AuthService | 그대로; walletAddress에 의미 부여 안 함 | accountId mapping/등록 권한; 실명정보 chain 금지 | wallet binding·소유서명·재연결/교체; JWT 신원과 구분 |
| quote/PriceQuoteService, blockchain/oracle/* | 그대로 | executor=custody, accountId 승인/버전 | executor=user 또는relay, beneficiary/사용자승인 저장·검증 |
| order/OnchainOrderPreparationService, OnchainOrderService | 그대로 | custody 계정의 confirmed/available snapshot+projection·연결 원자성 | chain 소유잔고/사용자 authorization 검사; 제출 주체 선택 |
| blockchain/transaction/{Sender,Persistence,BlockchainTransaction} | 그대로 | tx sender(operator)와실제 Vault caller(custody) 분리 기록 | sender/nonce scope 변경·signed user tx/relay evidence·교체/복구 |
| blockchain/contract/{Gateway,EventParser}, reconciliation/*, settlement/* | 그대로 | 계정별 debit/credit event·Vault event 결합, DB projection 멱등성 | beneficiary/owner 이벤트·외부 tx/transfer·잔고 projection 검증 |
| wallet/{UserBalance,WalletService}, wallet/faucet/* | 그대로 | custody balance projection·지급 상태/실제 보관자산 journal | 지갑잔고 projection·실제 온체인 Faucet/지급 확정; quota 재설계 |
| conditional/* | 그대로 | userId↔custodyAccount 및 새 실행증거 연결 | 사용자 서명 의도·권한/취소/만료/nonce/escrow 또는온라인 재서명 |
| reserve/* | 현재 공식 유지 | 사용자별 chain allocation ↔ DB projection, pooled token backing, exchange liquidity 분리 | wallet별실제잔고·projection·주소 소유증명과 외부흐름 분리 |
| tradeaudit/* | 현재검사/coverage 유지 | caller/sender/account 이벤트·계정 승인·버전별 ABI 검사 | user/beneficiary/relay/approval·서명 intent·tx교체·버전별 과거근거 |
| schema.sql/Order/Trade/PriceQuote | 그대로 | 새custody_accounts/credit workflow+chain event cursor; custody model/version/execution provenance | 새wallet bindings/authorizations/event cursor+chain/token/address/sender 모델; DB 모의모드와 분리 |
| tools/websocket-test-client/src/{api,main,conditional-panel}.js | 표시 개선 필요 시 별도 승인 | 위탁/온체인 배분확정/지급대기 표시 | wallet connect/sign/approve/Gas/위임·취소/키복구 UX |
| Foundry/backend/web 테스트, Android 계약 | 기존회귀 유지 | 새 ABI/이벤트/계정격리/실제PG+Anvil·기존회귀 | 직접/relay별서명·권한·외부전송·nonce/교체·조건부·기존회귀 |

### migration 원칙 (B/C를 선택할 경우에만)

1. 기존 사용자별 token 배분은 체인 기록에서 역산할 수 없다. 현 체인 이벤트는 operator이며 DB 귀속에 대한 별도 동의 증거가 없다. 과거 기록을 사용자 서명 거래로 재분류하지 않는다.
2. 실행 중/REVIEW_REQUIRED 및 lockedAmount가 남은 주문을 먼저 해결해야 cutover를 승인할 수 있다. 현재 보존 환경에서 임의 해소/보정하지 않는다.
3. 모델·ABI·chainId/주소·executor/sender/beneficiary·적용시점을 이력으로 구분하고 구형 검증기를 보존한다. 현재 설정으로 과거 지갑·수수료·signer를 추론하지 않는다.
4. 새 보관자산/실제 지갑으로 지급하거나 이전할 수량과 공급 출처를 승인해야 한다. DB 금액을 단순 copy한 것은 token 배분이 아니다. mKRW/mSEC mint 권한·기존 operator 재고·Vault 유동성의 이동도 분리한다.
5. 신규 격리 0원 환경 검증이 우선이다. 기존 데이터를 복사·계약 재배포·기준점 초기화하는 migration은 별도 사용자 승인이다. 이미 설정된 불변 reserve baseline은 바꾸지 않으며 새 환경/모델의 독립 기준점을 사용한다.

## 8. 요구사항을 네 부류로 구분

| 부류 | 요구사항/판단 |
|---|---|
| 이미 충족 | 모의자산 사용자장부, 즉시/조건부 Oracle 가격 거래, quote 사용자 검증·일회 소비, operator 정산·raw 복구, receipt/event 감사, DB journal 수량과 chain 변화 대사 |
| 사용자 키가 있어야 충족 | 사용자가 서비스와 독립적으로 자신의 주소 자산을 전송/승인, 사용자 개인 키 기반 거래 동의·위임/철회. 주소 숫자만 추가해선 불가능 |
| 지갑 없이도 강화 가능 | DB 배분 감사·주문 증거 추적·cut/coverage·권한·Gas/유동성 관측·수동 공급 이력. 개인 배분을 체인에서 조회하는 요구는 B로도 가능하지만 이는 감사만의 소규모 수정이 아니라 새 custody 모델 |
| 실익 작은 변경 | 자산 없는 per-user 주소, 서버가 키를 모두보관하며self-custody라고 부르는 변경, 모의 거래에필수 요구없이relay/복구/승인UX를 전면 추가하는 변경 |

A가 해결하지 못하는 잔여 문제는 operator 키 탈취/관리자 신뢰, 체인만으로 개인 귀속 증명 불가, 외부입출금 provenance 범위, Gas/유동성 부족, 다중 서버 nonce다. B/C도 각각의 일부만 해결하며 지갑 도입 하나로 동시에 해결하지 못한다.

## 9. 추천 A를 선택했을 때 단계별 계획

**현재 구현을 유지하는 결정 자체에 신규 코드 구현은 필요 없다.** 다음은 승인 후 진행할 계획이며 이번 조사에서 실행하지 않았다.

1. **범위 결정**: “DB 모의 할당 + 운영자 통합 온체인 정산”을 공식 보관 모델로 확정한다. 사용자별 온체인 지갑은 필수 기능이 아니라 미래 선택지로 재분류한다. B/C를 완료했다고 쓰지 않는다.
2. **표시·문서 점검**: 사용자 UI/인수 문서에서 모의자산·위탁장부·가격과 정산증거·비상환권을 명확히 표현한다. walletAddress나 대사비율을 사용자 token 소유/준비금 보장이라고 표시하는 곳이 있는지 확인하고 필요한 작은 표시 변경은 별도 승인한다.
3. **기존 통제의 회귀 유지**: Faucet과 BUY/SELL/조건부·잠금/REVIEW_REQUIRED·불변 기준점·cut·coverage를 기능별 및 최종 격리 환경에서 검증한다. 이미 통과한 검증을 새로 통과한 것처럼 보고하지 않는다.
4. **실제 잔여 요구만 설계**: Google/이메일 인증은 지갑 여부와독립적으로 설계한다. 다중 서버가 필요한 경우에만 분산 nonce를 별도 설계한다. 기준점 이후 공급/입출금을 지원해야 한다면 자산 공급 journal과 대사식 확장을 먼저 승인받는다. 자동충전이나 새로운 reserve 보증을 묵시적으로 추가하지 않는다.
5. **최종 인수**: 추가 기능 완료 후 최신 통합 버전의 신규 acceptance에서 실제 Toss 장중·실제 AI 인수를 별도 승인된 절차/비용으로 수행한다. 운영 부하/장애·Sepolia/AWS는 각각 별도 승인·격리 실행한다. 기존 보존된 acceptance를 덮어쓰지 않는다.

### 이후 검증 계획 (승인된 변경이 생길 때)

- A: 기존즉시·조건부 BUY/SELL, Faucet 멱등/한도, raw응답유실/실패receipt/REVIEW_REQUIRED, 사용자 격리WS, 두 대사 MATCH/MISMATCH/INCONCLUSIVE·coverage·Gas 한계 및 웹/Foundry/전체 회귀. 환경은 신규 PG/Anvil/Synthetic, 실제 Toss/AI는최종별도 승인.
- B: 위 항목+계정간중복할당/다른계정출금/서명바인딩/내부잔고≤실제보관량/보관풀과거래유동성분리/지급중복·체인성공DB실패/projection재시작·reorg/oldABI감사.
- C: 위 항목+지갑소유challenge replay/계정연결공격/주소교체/허용체인·계약/사용자직접·relay각각Gas·nonce/외부transfer/approve범위/위임변조·철회/조건부 취소경합/tx교체·유실/키유실과종료정책.

## 10. 구현 전에 필요한 사용자 결정

1. **A를 공식 모델로 유지할지**, B/C의 어떤 추가 요구를 졸업프로젝트 필수 목표로 삼을지. 본 문서는 추천이지 기존 계획의 자동 변경이 아니다.
2. B를 선택하면 실제 보관 풀·accountId·정본/DB projection·가입 및 Faucet의 지급 의미·계정 승인 서명·관리자 권한·토큰 이전/출금 허용 여부.
3. C를 선택하면 사용자 직접 키인지 서버위탁 키인지, 직접거래/relay, Gas 부담, wallet연결과키유실/주소교체, 조건부 장기위임/escrow·취소의 의미.
4. 기존 DB/체인 migration 여부, 새 contract/domain/ABI·새 기준점과 기존감사 보존, 온체인공급·전송에 필요한 네트워크/비용/키관리 범위.
5. A의 표시 보완·공급journal·분산nonce 등의 실제 구현 범위. 이번 보고서로 새서비스·지갑생성·계약배포·잔고변경을 승인받은 것으로 간주하지 않는다.

**이번 결과:** read-only 코드/테스트 조사와 이 문서 작성만 완료했다. 신규 기능·migration·계약·지갑·검증 환경을 구현/실행하지 않았다. 설계 조사이므로 별도 구현 검토자는 호출하지 않았으며 구현 승인 후에는 공통 독립 검토 절차를 따른다.
