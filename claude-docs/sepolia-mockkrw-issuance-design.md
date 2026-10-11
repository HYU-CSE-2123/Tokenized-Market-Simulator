# Sepolia용 모의 mKRW 발행 정책 설계 — 구현·배포 미승인

> 사용자 후속 결정(2026-10-12): **A안 방향 확정**. 로컬 Anvil의 기존 공개 MockKRW Faucet을 유지하고 Sepolia는 별도 최초 고정 공급 ERC-20 호환 variant를 사용한다. 최초 공급 이후 추가 mint는 없다. S0/O0/V0는 미확정이며 계약 구현·배포·ETH 사용·Sepolia RPC 호출은 별도 승인 전 금지한다. 신규 환경/배포/자산 대사 기준점은 별도로 생성하고 기존 계약·DB·acceptance는 보존한다. 아래 대안 비교는 결정 전 검토 이력이다.

> 2026-10-12 / 기준 `integration/additional-features` `c7bf4e83ede98fbcfb661f7e5ab7bfe35607ab9b`. 로컬 Anvil의 MockKRW/공개 Faucet/테스트는 유지한다. 아래 정책은 제안이며 신규 contract·배포 script·환경·기준점은 아직 만들지 않았다. Sepolia/RPC/외부 서비스 호출은 없다.

## 1. 실제 현재 계약과 보존할 경계

- [MockKRW](../contracts/src/MockKRW.sol): 누구나 반복 `faucet()`으로 자기 주소에 기본 100만 mKRW mint. owner는 faucetAmount 변경 가능, 누적 supply cap/발행자 제한 없음.
- [PrepareOperator](../contracts/script/PrepareOperator.s.sol): operator가 faucet 후 Vault에 allowance를 승인. DB Faucet와 별개다.
- [Deploy](../contracts/script/Deploy.s.sol): mKRW/mSEC/Oracle/Vault 배포, Vault를 mSEC minter 및 Oracle authorizedConsumer로 등록. 기본 초기 가격 75,000원은 배포 초기값이지 공급 규모 산정 근거가 아니다.
- [ExchangeVault](../contracts/src/ExchangeVault.sol): BUY는 operator mKRW 입력량 전체를 Vault로 이동하고 operator에 mSEC mint, SELL은 operator mSEC burn 후 수수료 차감 mKRW를 지급. mKRW mint를 수행하지 않는다. 주소 immutable이므로 새 mKRW variant에는 새 Vault가 필요하다.
- [mSEC](../contracts/src/SamsungPriceTrackingToken.sol): 정상 거래 mint/burn는 Vault만, owner는 minter 교체 가능. 공개 mKRW 제한을 넣어도 mSEC 권한/소유자 위험까지 자동 해결되지 않는다.
- [DB Faucet](../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetService.java): 사용자 장부 지급만 수행. 현재 100만/KST 하루1회/누적5회/전역5천만 제한을 바꾸지 않으며 **이 예산이 onchain 초기 자금과 같아야 한다는 가정은 하지 않는다.**

사용자는 실제 token 소유/상환권자가 아니다. 테스트 토큰 공급 통제는 모의 실험의 재현성과 감사 근거를 위한 것이며 실물 담보·Proof of Reserves 도입이 아니다.

## 2. 대안 비교

| 대안 | 발행 권한·추가 공급 | 장점 | 위험/대사 영향 |
|---|---|---|---|
| A. 로컬 MockKRW + 공개용 최초 고정 공급 variant | constructor에서 승인 초기 총량을 발행/초기 배분, 이후 mint 함수 없음 | 권한 오설정 없이 추가 무제한 mint 차단, 현재 mKRW totalSupply 불변 대사와 가장 잘 맞음 | 초기 예산 부족 시 자동 증액 못 함. 승인 새 실험 또는 별도 추가 공급 기능 설계 필요 |
| B. 로컬 MockKRW + 공개용 owner-only capped mint | owner만 전체 hard cap 내 mint | 테스트 공급 조절 가능, 역할 단순 | owner 키 탈취/운영 실수, 기준점 이후 mint는 현재 대사 MISMATCH. cap·공급 journal·승인 정책 추가 필요 |
| C. 공개용 allowlisted minter + cap | owner가 지정 minter에 제한 발행 권한 | 배포 admin/운영 발행자 분리 가능 | 역할 추가·권한 회수·키 운영 복잡성. 지급 계정별 한도와는 다름. 추가 공급 대사 설계는 B와 동일 |
| D. 단일 MockKRW에 faucet 권한/모드 추가 | 환경 설정으로 공개 faucet 비활성 | 소스 종류 감소 | 기존 로컬 계약·회귀에도 영향을 주고 공개 활성화 실수 위험. 이번 로컬 유지 요구에는 A/B/C 분리보다 불리함 |

**첫 Sepolia 실험 추천은 A: 별도 제한 variant + 최초 고정 공급, 추가 공급 없음.** owner/허용 minter 방식이 꼭 필요하면 B가 최소 권한 대안이며 C는 별도 발행 담당 프로세스가 있을 때만 검토한다. A에서는 초기 발행이 constructor로 끝나므로 owner/minter 운영 권한을 새로 만드는 것이 필수가 아니다. B/C에서도 operator와 price signer를 자동 minter로 삼지 않는다. chainId 하드코딩만으로 공개 발행 권한을 통제하는 방식은 권장하지 않는다.

후속 contract 이름·constructor/API는 미정이며 ERC-20 18 decimals 및 balanceOf/transfer/approve/allowance/transferFrom/totalSupply 호환성을 유지한다. 공개 variant에 기존 무제한 faucet selector를 남길 필요는 없다. 로컬 MockKRW와 기존 테스트는 그대로 두고 공개 variant 전용 테스트·준비 script를 추가하는 방안이다.

## 3. 초기 발행/자금 배분 — 값은 승인 필요

입력값은 `S0`(총 초기 mKRW), `O0`(operator BUY용), `V0`(Vault SELL 완충 유동성)이다. 첫 범위에서는 **S0=O0+V0**, 초기 잔여 미배분 계정을 만들지 않는 구성을 제안한다. 값은 이번에 임의 확정하지 않는다. 초기 발행을 배포 전용 계정에 받고 새 Vault 배포 후 operator/Vault로 transfer하는 방식이면 주소의 순환 의존 없이 구성할 수 있다. 모든 배분을 끝낸 후 baseline을 캡처한다.

- O0는 사용자 DB 지급 합계와 같다는 이유로 정하지 않고 실험에서 예상하는 BUY 입력량·동시 주문·SELL 반환 자금을 기준으로 산정한다. BUY에는 allowance도 필요하다.
- V0는 초기 mSEC가 없더라도 이후 가격 상승 SELL의 초과 지급을 위한 완충 재원이다. 순지급액은 `floor(S*P/1e8)-floor(gross*feeBps/10000)`이며 가격 상승이 Vault mKRW를 만들어주지 않는다.
- 어떤 유한 V0도 무제한 가격 상승·주문량의 모든 SELL을 보장하지 않는다. 목표 실험의 최대 주문량/가격 범위·중단 시점과 ‘유동성 부족이면 기존 거래 거부’를 명시한다. 새로운 가격 상한·자동 조정·충전 메커니즘을 넣자는 결정은 아니다.
- mSEC 초기 공급은 **0**으로 시작하는 것을 추천한다. 사용자 DB 신규 0원 장부 및 Faucet 지급 후 실제 BUY로만 생성하면 기존 journal·대사와 연결이 명확하다. 사전 mSEC 지급이 필요하면 근거/회계 모델부터 별도 승인한다.
- operator ETH는 별도 Gas 자금이다. mKRW 초기 공급과 섞지 않는다. 가격 signer는 offchain EIP-712 서명 키이므로 가격 보고서 서명 자체에 ETH가 필요하지 않다. owner의 onchain 역할 설정 tx와 operator 거래에는 ETH가 필요하다.

현재 Vault 수수료를 바꾸거나 사용자 DB 잔고에 초기 onchain 배분을 자동 반영하지 않는다. 새 사용자 입출금/키 생성도 없다. 부족 자금에는 기존 estimate/revert/receipt 복구와 정체 관측을 사용하고 자동 mint/ETH 충전은 넣지 않는다.

## 4. 대사와 불변 기준점

[ReserveCalculator](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveCalculator.java)에서 ONCHAIN Trade로부터 `ΔK=SELL 순지급-BUY 입력`, `ΔS=BUY mSEC-SELL mSEC`를 계산한다.

```text
operatorKRW = O0 + ΔK
VaultKRW    = V0 - ΔK
operatorSEC = SEC0 + ΔS
mSEC supply = totalSEC0 + ΔS
mKRW supply = S0 (불변)
```

위 식에는 DB Faucet 지급 counter를 더하지 않는다. 사용자 DB 장부는 FaucetGrant와 Trade로 별도로 계산한다. A가 totalSupply 불변과 자연스럽게 맞지만 unrestricted ERC-20 transfer/외부 Vault donation 등 설명되지 않은 자금 이동은 여전히 불일치를 만들 수 있다. 발행 제한이 모든 수량 변화의 설명을 보장하지는 않는다.

- 초기 mint·배분·allowance·계약 권한 설정을 **기준점 생성 전에** 끝낸다.
- 새 chainId·contract 주소·code hash·signer/roles·명시적 blockNumber/blockHash·DB 기준 시각·실행 식별자를 저장한다. [RpcReserveChainReader](../backend/src/main/java/com/pricetrack/exchange/reserve/RpcReserveChainReader.java)의 기존 roles 검사를 그대로 만족해야 한다. 특히 현재 Vault owner=operator 요구가 있으므로 MockKRW 발행자 분리와 Vault owner 분리를 혼동하지 않는다.
- Sepolia는 새 DB/chain provenance를 가진 별도 환경이다. Anvil의 주소/quote/raw/nonce/txHash/기준점을 복사해서 이어 쓰지 않는다. 기존 baseline을 초기화·덮어쓰지 않는다.
- B/C의 기준점 이후 mint나 외부 계정의 자금 유입은 현행 식에 포함되지 않는다. 이를 정상으로 인정하려면 별도 공급 journal·양방향 이벤트 검증·consistent cut·승인 경계와 거래 전수 대사 coverage를 설계해야 한다. 공급을 증가시킨 뒤 baseline을 재설정해서 MATCH로 만드는 방법은 금지한다.
- [거래 전수 대사](trade-full-audit-implementation.md)는 Vault/Oracle 체결 증거와 DB 거래를 검증하며 발행 허가/자금 지급 보증을 대신하지 않는다. 최초 공급 tx provenance는 배포 기록으로 별도 보존한다.

## 5. 승인 후 배포·키 절차 (이번에 실행하지 않음)

1. 추천 대안 A/B/C, S0/O0/V0, 추가 공급 여부, 발행 권한 주체를 승인한다. operator/price signer는 분리하고 배포·발행 admin 분리 여부를 결정한다. 현재 Vault owner 요구 변경이 필요하면 별도 설계 승인을 받는다.
2. 로컬 fixture 전용 restricted variant 단위/Anvil 통합 테스트를 먼저 작성한다. Solidity import·bytecode·IERC20 호환성을 검증한다. 기존 public MockKRW script를 Sepolia에 재사용하지 못하도록 환경/variant guard를 설계한다.
3. 새 isolated configuration으로 MockKRW variant→mSEC→Oracle→Vault 배포. chainId/코드/연결 주소·mSEC minter·authorizedConsumer·priceSigner 분리 확인. Oracle EIP-712 domain은 새 chainId/Oracle 주소를 사용한다.
4. 초기 token 배분·operator allowance·ETH를 준비하고 신규 빈 거래 DB/AI 설정 경계를 마련한다. 키는 전용 runtime secret 파일로 주입하고 소스/일반 로그에 넣지 않는다. deploy key·operator key·price signer의 복구/접근 권한을 문서화한다. 기존 .env/acceptance를 복사·덮어쓰지 않는다.
5. baseline 생성 전 기동 guard·최초 supply/잔고·owner/roles·block provenance를 검증한다. 첫 scope A에서는 추가 mint 경로가 없음을 bytecode/API/테스트로 확인한다.
6. Sepolia는 confirmation/reorg/tx propagation/Gas·nonce 복구를 별도 검증한다. confirmation 설정·test ETH/RPC 자격증명/네트워크 사용은 승인 필요. 실제 Toss 온체인 가격 데이터 사용권 문제는 별도이며 이번 선택으로 승인되지 않는다.

owner/minter 권한을 없애려고 기존 Vault/mSEC/Oracle owner를 무조건 renounce하지 않는다. 이들은 signer/fee/역할 변경과 현행 roles 검사에 영향을 준다. 고정 공급 variant의 발행 권한과 기존 계약 admin 권한은 서로 다른 결정이다.

## 6. 승인 후 테스트와 남은 결정

- 로컬 기존 Foundry/Faucet 회귀 유지, restricted variant 비인가 mint 차단·초기 공급/배분·cap/역할 테스트(B/C 선택 시).
- 새 PostgreSQL/Anvil에서 즉시/조건부 BUY·SELL, Vault 부족/가격 상승·Gas/RPC 유실, Faucet가 supply/nonce를 변경하지 않음, 두 대사 MATCH 및 외부 자금 이동/발행 불일치.
- 준비 script의 wrong chain/잘못된 variant/역할/배분 누락 차단, secret 비노출, baseline 불변·신구 환경 분리.
- 이후 별도 승인된 Sepolia에서만 실제 deployment/receipt/confirmation/reorg 한계를 검증한다. 로컬 결과를 Sepolia 완료로 보고하지 않는다.

현재 확정: A안 별도 최초 고정 공급 variant, 최초 공급 이후 추가 mint 없음, ERC-20 호환성과 로컬 공개 Faucet 유지. 미확정/추가 승인 필요: 초기 총공급 S0, operator 배분 O0, Vault 배분 V0 및 나머지 배분/보관 주체, 배포 키 역할, 공개 RPC/test ETH 확보, confirmation/reorg 정책, 기존 거래 자료의 공개 범위. 스마트 컨트랙트 구현·배포·ETH 사용·Sepolia RPC는 아직 미승인이며 이번 작업에서 실행하지 않는다.
