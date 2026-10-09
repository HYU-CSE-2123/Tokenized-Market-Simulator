# 준비금 대사 — 코드 조사와 구현 제안

> 작성: 2026-10-08
> 상태: **최초 설계 기록 / 후속 구현 승인됨**
> 후속 요구사항·실제 구현·검증·한계는 [구현 보고서](reserve-reconciliation-implementation.md)가 정본이다. 아래 내용은 최초 제안 시점 기록이며, 특히 Order 실행 유형과 미정산 INCONCLUSIVE 정책은 후속 승인·구현을 따른다.
> 조사 기준: `b7cb0f0ed8fd936e73733a6d2f167efe4a951a63`
> 문서 저장 브랜치: `feat/reserve-reconciliation`

## 1. 결론과 승인 경계

준비금 대사를 두 영역으로 분리한다.

1. **정합성**: DB 사용자 자산, 정산된 체결, 실제 체인 잔고·공급량의 변화가 설명되는가?
2. **유동성**: operator/Vault가 현재 사용자 주문을 수행할 자산·allowance를 가지고 있는가?

DB faucet은 체인 준비금을 늘리지 않으므로 `사용자 mKRW 합계 = operator mKRW`를 정상 조건으로 삼지 않는다. 장부가 정확해도 준비금이 부족할 수 있다. 이 기능은 모의 시스템의 대사이며 실제 금융 준비금 감사·지급 보증·공개 proof-of-reserves가 아니다.

사용자 승인으로 이 문서만 별도 worktree에 저장했다. 아래 API/스키마/서비스/UI는 **아직 구현하지 않은 제안**이다.

- `acceptance-20261007153304-9bc0b3`와 runtime/DB/chain/설정, 장중 인수 전 제품 코드를 보존한다.
- 원래 worktree의 `melon-init`/`b7cb0f0`는 그대로 두고 기능 브랜치의 별도 worktree만 사용한다. merge/cherry-pick/배포는 하지 않는다.
- 새 worktree는 원래 저장소의 ignored `deployment/runtime/reserve-reconciliation-worktree`에 둔다. 이는 acceptance run의 하위 디렉터리가 아니며 기존 runtime 자료를 복사한 것도 아니다. 이 worktree 내부에서는 일반 Git 추적이 가능하다.
- 현재 저장소 소스·모델을 읽었으며 이번 설계 과정에서 기존/acceptance DB·RPC·Toss·AI 서비스는 조회하거나 변경하지 않았다.
- 이후 기능 테스트는 새 DB/Anvil/키/포트·fixture 시세만 사용한다. 실제 Toss 연결, AI 설정 및 유료 호출은 건드리지 않는다.

## 2. 현재 코드에서 확인된 사실

| 대상 | 현재 동작 | 관련 소스 |
|---|---|---|
| 사용자 자산 | `amount` 총수량, `lockedAmount` 잠금, 가용=`amount-lockedAmount` | [UserBalance](../backend/src/main/java/com/pricetrack/exchange/wallet/UserBalance.java) |
| DB faucet | 사용자 mKRW에 1,000,000 추가. RPC/온체인 mint 없음. 별도 지급 이력 없음 | [WalletService](../backend/src/main/java/com/pricetrack/exchange/wallet/WalletService.java) |
| 온체인 faucet | 호출자에게 mKRW mint. 무제한 호출 가능, 지급 이벤트 존재 | [MockKRW](../contracts/src/MockKRW.sol) |
| BUY | operator의 mKRW 전액 입력을 Vault로 이동하고 수수료 제외 가격 계산으로 mSEC를 operator에 mint | [ExchangeVault](../contracts/src/ExchangeVault.sol) |
| SELL | operator의 입력 mSEC를 burn하고 Vault가 수수료 차감 mKRW를 operator에 지급 | [ExchangeVault](../contracts/src/ExchangeVault.sol) |
| 발행/소각 권한 | 등록된 minter만 mint/burn. minter 주소는 소유자가 변경 가능 | [SamsungPriceTrackingToken](../contracts/src/SamsungPriceTrackingToken.sol) |
| 사용자 주문 준비 | quote 소비/주문 생성/입력량 잠금은 같은 DB 트랜잭션. 입력 총수량은 유지 | [OnchainOrderPreparationService](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java) |
| 성공 정산 | 검증된 이벤트로 총수량 변경/잠금 해제/Trade 저장/상태 확정을 원자적으로 수행 | [OnchainSettlementService](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java) |
| 실패/검토 | 실패 receipt는 잠금만 해제. REVIEW_REQUIRED는 자산 잠금 유지 | 위 서비스 및 [BlockchainReconciliationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java) |
| DB-only 거래 | blockchain 비활성일 때 DB만 변경하고 Trade 생성 | [OrderService](../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java) |
| 실제 체결 데이터 | BUY `quoteAmount`는 입력 mKRW 전액, SELL은 실제 순수령 mKRW. `baseAmount`는 mSEC, 수수료는 별도 기록 | [Trade](../backend/src/main/java/com/pricetrack/exchange/trade/Trade.java), 성공 정산 서비스 |
| 수량 변환 | 18 decimals로 정확히 변환, 초과 정밀도 거부 | [TokenUnits](../backend/src/main/java/com/pricetrack/exchange/blockchain/support/TokenUnits.java) |
| 현재 체인 조회 | ContractSnapshot은 operator 잔고/allowance·fee·Oracle만 포함. 일반 ABI 조회는 `latest` 사용 | [BlockchainService](../backend/src/main/java/com/pricetrack/exchange/blockchain/BlockchainService.java), [ContractGateway](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractGateway.java) |
| 모델 | 사용자 잔고, 주문, 체결, 체인 전송, 견적 테이블은 있지만 faucet 지급/준비금 기준점/대사 결과 테이블 없음 | [schema.sql](../backend/src/main/resources/schema.sql) |

가격 provider가 SIMULATED인 것과 DB-only 거래는 다르다. `BLOCKCHAIN_ENABLED`가 true라면 합성 시세도 서명 보고서로 온체인 거래할 수 있다. 대사에서 시세 provider로 거래 실행 유형을 추측하지 않는다.

## 3. 정본과 비교 단위

- 사용자별 소유 수량/잠금/귀속: DB `user_balances`가 정본이다. 온체인 operator 잔고만으로 사용자별 귀속을 재구성할 수 없다.
- 온체인 실행 결과: 해당 블록의 canonical receipt, 검증된 Vault/토큰 이벤트, ERC-20 상태가 정본이다. DB CONFIRMED 상태만으로 체인 상태 존재를 보증하지 않는다.
- 사용자 실제 체결 반영: DB Trade를 receipt/event와 대조한다. 주문의 예상 수령량이나 견적 최소수령량을 최종 수량으로 쓰지 않는다.
- 모든 수량 비교는 `BigInteger` 최소단위로 수행한다. decimal/float 오차 허용으로 불일치를 숨기지 않는다.
- 평균 매수가/평가손익은 준비금 수량 정합성의 기준이 아니다. 가격 기반 유동성 참고 지표에만 가격이 필요하다.
- 서로 다른 블록의 잔고·공급량·fee를 하나의 스냅샷으로 섞지 않는다. DB와 체인의 분산 트랜잭션은 만들지 않는다.

## 4. 계산식

### 4.1 기준점과 기호

기준점은 검증 범위의 시작이다. 신규 기능 테스트 환경에서는 DB 사용자 자산 0, 승인된 체인 초기 공급·유동성, 미완료 주문 0 상태에서 시작하는 것이 권장된다.

- `U_K`, `U_S`: DB 사용자 mKRW/mSEC `amount` 합계. ADMIN 계정도 사용자 자산을 보유하면 포함한다.
- `L_K`, `L_S`: 해당 자산의 `lockedAmount` 합계.
- `O_K`, `O_S`: operator의 실제 온체인 mKRW/mSEC.
- `V_K`, `V_S`: Vault의 실제 온체인 mKRW/mSEC.
- `T_S`: mSEC totalSupply. mKRW totalSupply도 별도로 관측한다.
- 아래 첨자 `0`: 시작 기준점 값.
- `F`: 기준점 이후 독립 지급 이력으로 증명된 DB faucet 지급 합계.
- `B_K`, `B_S`: 기준점 이후 정산된 BUY 실제 입력 mKRW/출력 mSEC 합계.
- `S_K`, `S_S`: 기준점 이후 정산된 SELL 실제 출력 mKRW/입력 mSEC 합계.

장부 식에는 모든 실제 체결을 포함한다. 체인 식에서는 **현재 chain/계약/operator에 속하는 온체인 체결만** 포함한다. 과거 타입 불명 기록은 불확실성을 명시한다.

### 4.2 사용자 장부

```text
U_K_expected = U_K0 + F - B_K + S_K
U_S_expected = U_S0 + B_S - S_S
```

이는 기준점 이후 별도 입출금/자산 조정/삭제가 없다는 현재 기능 조건의 식이다. 그런 변동을 추가하면 독립 근거가 있는 항목으로 확장해야 한다. 잔고에서 역산한 faucet 수량을 F로 사용해 같은 잔고를 재검증하지 않는다.

### 4.3 operator / Vault

미정산·외부 변동이 없을 때 온체인 정산된 체결만 이용하면:

```text
O_K_expected = O_K0 - B_K_onchain + S_K_onchain
V_K_expected = V_K0 + B_K_onchain - S_K_onchain
O_S_expected = O_S0 + B_S_onchain - S_S_onchain

O_K + V_K = O_K0 + V_K0    (거래만 발생했다면)
```

외부 전송, 온체인 faucet, 기타 발행/소각은 별도 chain flow로 관측한다. 승인된 초기 공급·유동성은 기준점에 포함하고, 그 이후 설명되지 않는 변동을 자동으로 정상 offset으로 받아들이지 않는다. 자동 충전/출금 승인/장부 보정은 첫 범위 밖이다.

DB와 operator의 mKRW 차이에는 초기 할당과 faucet이 반영된다. 온체인 거래만 있고 모든 체결이 정산됐다면:

```text
U_K - O_K = (U_K0 - O_K0) + F
```

DB-only 거래, 외부 변동, 미정산이 있으면 추가 보정이 필요하다. `U_K=O_K`를 강제하지 않는다.

### 4.4 mSEC / 총공급량

```text
T_S_expected = T_S0 + 전체 mint 합계 - 전체 burn 합계
```

전체 공급에는 operator 이외 보유자가 포함된다. `T_S = O_S`는 보편적인 조건이 아니다. `T_S-O_S`는 Vault·외부 주소 등의 보유량에 해당하며 이를 사용자 DB 자산으로 간주하지 않는다.

`U_S=O_S`를 기대할 수 있는 조건은 시작 operator 자체 재고 0/시작 사용자 0, 온체인 체결만 사용, 외부 전송 없음, 모든 체결 정산 완료이다. 그 외에는 기준점·DB-only 변동·미정산·외부 흐름을 분리한다. minter 변경/허용되지 않은 mint-burn도 조사 근거로 표시한다.

### 4.5 lockedAmount

```text
가용 수량 = amount - lockedAmount
총수량 = 가용 수량 + lockedAmount
0 <= lockedAmount <= amount
```

준비금 비교는 **총수량 amount**를 사용한다. 잠금은 총수량에 이미 들어 있으므로 더하지도, 사용자 자산 합계에서 빼지도 않는다.

잠금의 별도 정합성 검사는 사용자·입력 자산별 활성 주문과 대조한다. REQUESTED/PENDING_ONCHAIN 및 연결 tx의 REVIEW_REQUIRED를 고려하고, 같은 주문을 중복 합산하지 않는다. quote 발급만 된 경우에는 잠금이 없다. FILLED/FAILED인데 잠금이 남거나 활성 주문 입력량과 잠금이 다르면 근거와 함께 불일치로 표시한다.

### 4.6 수수료 / 반올림

- BUY는 입력 mKRW 전액이 operator에서 Vault로 이동한다. tokenOut만 수수료 차감 후 가격으로 산출된다.
- SELL은 gross에서 fee를 뺀 net mKRW만 Vault에서 operator로 이동한다.
- DB Trade의 BUY quoteAmount는 gross input, SELL quoteAmount는 net output이므로 fee를 다시 빼지 않는다.
- fee는 mKRW 단위로 별도 합산해서 참고 표시할 수 있지만 `Vault 증가분 = fee 합계`로 검사하지 않는다. 가격 변화에 따른 지급액 차이도 존재한다.
- Solidity 정수 나눗셈의 내림을 그대로 적용한다. DB-only 계산기의 반올림 정책을 온체인 체결 재검증에 대신 사용하지 않는다.

## 5. 비동기 정산과 조회 시점

체인 성공 후 DB 정산 전에는 정상적으로 차이가 발생한다. 실제 성공 receipt/event로 증명된 미반영 변동은 다음과 같다.

```text
미정산 BUY:  delta O_K=-input, delta V_K=+input, delta O_S=+output
미정산 SELL: delta O_K=+output, delta V_K=-output, delta O_S=-input
```

위 효과를 체인 예상값에 별도 반영하고 `SETTLEMENT_PENDING` 근거로 표시한다. 주문 예상 출력량으로 보정하지 않는다.

- SIGNED도 RPC 제출 여부가 불명확할 수 있어 상태명만으로 체결 효과를 0/성공으로 단정하지 않는다.
- receipt 실패는 ERC-20 변동 0이다. ETH gas 소모는 별도 운영 지표이지 mKRW 손실이 아니다.
- 이벤트 불일치/REVIEW_REQUIRED는 대사에서 해결하거나 기존 reconciliation을 호출하지 않는다. 확인할 수 없는 부분은 INCONCLUSIVE로 남긴다.
- 기존 Trade의 txHash·sender·계약·블록과 receipt/event를 대조한다. 누락/유실된 receipt나 주소 불일치를 DB 상태만으로 덮지 않는다.

조회 방식 제안:

1. 설정된 chain/계약/operator와 기준점 block hash/code를 확인하고, confirmation 정책에 맞는 블록 B를 선택한다.
2. 짧은 read-only repeatable-read DB snapshot에서 사용자 합계/Trade/주문/tx/지급 이력을 확보한다. 네트워크 호출 동안 잔고 write lock을 잡지 않는다.
3. 모든 체인 balance/totalSupply/fee/role 조회를 B에 고정하고 필요한 receipt/log를 bounded 범위로 확인한다.
4. DB에 B 이후 체결 반영이 포함되거나 블록 hash가 바뀌면 같은 시점의 비교라고 주장하지 않는다. 제한된 읽기 재시도 또는 INCONCLUSIVE로 종료한다.
5. 결과에 DB snapshot 시각, B의 번호/hash, 기준점, 미정산/미검증 범위를 저장한다. 단일 원자적 DB+chain snapshot이라고 표현하지 않는다.

RPC timeout, 로그 범위/처리량 상한, 동시에 실행 가능한 대사 수를 제한한다. 조회 범위를 완전히 읽지 못하면 MATCH를 내리지 않는다. 현재 `latest` gateway를 변경하는 대신 block 지정 가능한 별도 read-only reader를 제안한다.

## 6. 판정과 유동성

### 정합성

| 상태 | 의미 |
|---|---|
| MATCH | 필요한 이력·환경·조회 시점이 검증됐고 수량·잠금·공급량 비교가 정확히 일치 |
| SETTLEMENT_PENDING | 실제 미정산 성공 체결로 차이가 설명됨. 완료 정산과 동일한 정상 상태로 표현하지 않음 |
| MISMATCH | 확정 가능한 비교에 설명되지 않는 차이/잘못된 잠금/계약 연결/공급량 이상 존재 |
| INCONCLUSIVE | 과거 이력 부족, 검증 불가 이벤트, snapshot 충돌 등으로 결론 불가 |
| UNAVAILABLE | RPC/DB 등 필수 데이터 접근 실패 |

항목별 결과를 유지해 일부 오류/불확실성이 다른 검사의 확정적 불일치를 숨기지 않도록 한다. 전체 MATCH는 모든 필수 검사 통과 시에만 표시한다.

### 유동성 (별도 지표)

- BUY: operator mKRW 잔고와 operator→Vault allowance.
- SELL: operator mSEC 잔고와 Vault mKRW 지급 여력.
- 참고 할당 충당률: operator mKRW / 사용자 mKRW 합계. 분모 0은 비율 없음으로 표시한다.
- 사용자 mSEC 전체 매도 가정은 가격/fee/시각/가정을 명시한 시나리오다. 주문 분할·정수 반올림·pending 변동·가격 변화로 실제 결과가 달라지므로 현재 견적/체결/지급 보증이 아니다.
- 평가 가격은 기존 시장 snapshot을 읽을 뿐 provider API를 대사가 직접 호출하지 않는다. 가격 없음/오래됨이면 해당 평가만 unavailable이다. Oracle 마지막 가격을 실시간 주문 가격으로 사용하지 않는다.
- Vault의 mSEC는 현재 SELL이 burn하는 operator 보유량을 대신하지 못한다. operator mKRW와 Vault mKRW를 합쳐 모든 자산이 동시에 담보된다고 주장하지 않는다.

무제한 DB faucet으로 사용자 mKRW가 operator 보유량을 초과해도 지급 이력과 장부 식이 일치할 수 있다. 이때 정합성 MATCH와 유동성 부족 경고가 동시에 가능하다.

## 7. 최소 기록 보완 제안 (미구현)

1. **DB faucet 지급 이력**: 기존 잔고 증가와 같은 DB transaction에 수량/user/시각/고유 event ID를 저장한다. 기존 지급량/API 응답/RPC 정책은 변경하지 않는다. 잔고 증가 rollback 시 지급 이력도 rollback한다.
2. **Trade 실행 유형**: 신규 Trade에 ONCHAIN/DB_ONLY를 명시한다. 기존 `txHash=null`을 일괄 DB_ONLY로 backfill하지 않고 UNKNOWN/검증 한계를 남긴다. 해당 유형은 실제 주문 경로에서 정해진다.
3. **대사 기준점**: DB 시작 자산과 체인 시작 자산/공급량·chainId·operator/계약·블록 hash·근거 범위를 보존한다. 사전에 불일치가 있는 장부를 현재 값으로 승인 없이 정규화하지 않는다.
4. **대사 결과 이력**: 계산 버전, 실행자·시각, 기준점, 입력 합계/실제값/차이, 항목별 상태/근거, 제한·실패 사유 저장.

구체 테이블/필드 확정은 설계 승인 후 구현 단계에서 한다. 기존 Trade/정산 정보를 재사용하고 전 거래를 복제하는 별도 full ledger는 첫 범위에서 만들지 않는다. 과거 faucet 이벤트는 생성하지 않으며 기준점 이전은 독립 검증 불가임을 표시한다.

## 8. API / 화면 / 권한 제안 (미구현)

```text
POST /api/admin/reserve-reconciliations        수동 조회·계산 및 결과 저장
GET  /api/admin/reserve-reconciliations        실행 이력
GET  /api/admin/reserve-reconciliations/{id}   상세·근거
```

JWT의 ADMIN role을 서버에서 강제한다. 기존 SecurityConfig의 authenticated fallback만으로 ADMIN 권한이 보장되지 않으므로 새 경로의 명시적인 ADMIN 규칙과 테스트가 필요하다.

ADMIN 화면: 사용자 총/가용/잠금, operator/Vault 실제 자산, 토큰 공급량, 예상·실제·차이, 미정산 및 예상 밖 변동, 유동성 경고, 기준점/조회 블록/불확실성. 긴 결과/근거는 상세 페이지와 제한된 페이지 조회로 제공한다.

USER는 기존 개인 자산 화면을 유지한다. 전체 사용자 잔고·운영자 자산·다른 사용자 주문을 노출하지 않는다. 비밀키/JWT/signature/rawTransaction/RPC·DB 비밀 URL/원본 예외는 결과·UI·로그에 넣지 않는다.

## 9. 기존 거래 영향과 제외 범위

- 대사 자체는 read-only RPC/DB 조회와 새 결과 저장만 한다. 기존 사용자 자산·주문·전송 nonce·가격·토큰 상태를 바꾸지 않는다.
- faucet 기록과 Trade 유형 저장은 기능 브랜치에서 승인받을 최소 persistence 변경이다. 거래 수량/서명/잠금/정산/WS 계약을 바꾸지 않는다.
- 기존 receipt reconciliation과 준비금 reconciliation은 목적이 다르다. 기존 scheduler를 수정/호출하거나 REVIEW_REQUIRED를 해제하지 않는다.
- 대사 실패로 거래를 중단하지 않는다. 거래와 대사 사이의 분산 transaction/긴 write lock/외부 API 의존은 만들지 않는다.
- 첫 범위 제외: 자동 실행, AI Tool/Skill, 자동 충전/출금, 자동 장부 수정, 거래 제한 정책, 사용자별 온체인 지갑, 공개 준비금 증명, acceptance 반영/실제 Toss 인수.

## 10. 구현 승인 후 테스트 계획

새 DB/Anvil/독립 키·포트를 명시한 전용 테스트만 사용한다. 새 worktree에는 기존 `.env`·runtime·의존성·빌드 산출물을 복사하지 않는다. 기존 서비스 주소로 fallback하지 않도록 설정 검사를 둔다.

| 범위 | 시나리오 |
|---|---|
| 단위 계산 | 초기자금/사용자 합계 차이, faucet, BUY/SELL/수수료, 최소단위 반올림, 0/큰 수량 |
| 잠금 | 총수량 이중 계산 방지, 활성 주문별 잠금, 완료 주문 잔여 잠금 |
| 정산 | 성공 체인/DB 미반영, 실패 receipt, SIGNED 불확실성, REVIEW_REQUIRED, 중복 receipt |
| 체인 | 외부 전송, 예상 밖 mint/burn/minter 변경, totalSupply와 operator 차이, state 유실/잘못된 계약 |
| 이력 | DB-only 체결, legacy UNKNOWN, 지급 이력 없음, 기준점 이후 faucet rollback |
| snapshot | 동일 블록 조회, DB 정산 경쟁, B 이후 Trade, block hash 변화, RPC 오류/timeout/조회 범위 초과 |
| 권한/UI | 미인증401/USER403/ADMIN허용, 민감정보 미노출, 정상·대기·불일치·불확실·장애 구분 |
| 불변성 | 대사 전후 사용자 잔고·주문/체결/tx 상태·nonce·블록·토큰 상태 동일. 대사 결과만 저장 |
| 회귀 | 별도 worktree 전체 backend/contract/web 테스트 및 독립 검토. acceptance 환경 미사용 |

현재 단계에서 제품/통합 테스트를 실행한 것은 아니다. 문서 작성 후 Git 변경 범위와 소스 링크를 검증한다. 기능 구현 완료 뒤 별도 검토를 수행한다.

## 11. 다음 승인

추천 첫 구현 범위는 **ADMIN 수동 대사 + DB faucet 지급 이력 + Trade 실행 유형 + 기준점/결과 이력 + ADMIN UI**이다. 승인 전에는 문서 외 기능 코드를 작성하거나 테스트 환경을 기동하지 않는다.

이 설계는 앞선 답변의 내용을 문서화한 것이다. 추가 의사결정이나 기능 구현 승인을 받은 것으로 취급하지 않는다.
