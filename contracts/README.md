# contracts — 스마트 컨트랙트 (Foundry)

삼성전자 가격 추종 토큰(mSEC) 현물 거래소의 온체인 정산 레이어. 기획서 §9 참고.

## 컨트랙트

| 파일 | 역할 | 핵심 함수 |
| --- | --- | --- |
| `MockKRW.sol` | 모의 원화 ERC-20 (mKRW) | `faucet()` |
| `SamsungPriceTrackingToken.sol` | 삼성 가격 추종 ERC-20 (mSEC) | `mint`, `burn`, `setMinter` |
| `PriceOracle.sol` | 기준 가격 저장 (1e8 정밀도) | `updatePrice`, `getPrice` |
| `ExchangeVault.sol` | 매수/매도 정산 | `buy`, `sell`, `quoteBuy`, `quoteSell` |

> mSEC 의 `mint`/`burn` 은 `ExchangeVault`(minter)만 호출할 수 있다.

## 사전 준비

```bash
# Foundry 설치 (이 환경에서는 foundry.paradigm.sh DNS 차단 → brew 사용)
brew install foundry

# 의존성 (lib/ 는 gitignore 됨)
git clone --depth 1 -b v5.1.0 https://github.com/OpenZeppelin/openzeppelin-contracts lib/openzeppelin-contracts
git clone --depth 1 https://github.com/foundry-rs/forge-std lib/forge-std
# 또는: forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 foundry-rs/forge-std
```

## 빌드 & 테스트

```bash
forge build
forge test -vv   # 기획서 §0.5 매수→가격변경→매도→잔고 시나리오 포함 6개 통과
```

## 로컬 배포

```bash
anvil                       # 별도 터미널, 로컬체인 실행
forge script script/Deploy.s.sol \
  --rpc-url local --broadcast \
  --private-key <ANVIL_PRIVATE_KEY>
```

배포 시 초기 가격 75,000원으로 설정되고 `ExchangeVault` 가 mSEC minter 로 등록된다.
배포 로그에 출력된 컨트랙트 주소는 백엔드의 `MOCK_KRW_ADDRESS`, `MSEC_ADDRESS`, `PRICE_ORACLE_ADDRESS`, `EXCHANGE_VAULT_ADDRESS` 환경 변수로 연결한다.

## 로컬 시나리오 검증

```bash
anvil                       # 별도 터미널, 로컬체인 실행
forge script script/Scenario.s.sol --rpc-url local --broadcast
```

`Deploy.s.sol` 은 컨트랙트 배포와 주소 출력만 수행한다. `Scenario.s.sol` 은 기획서 §0.5 검증용으로, Anvil 기본 테스트 계정을 사용해 배포→매수→가격변경→매도 흐름을 한 번에 실행한다.

## 면책

본 컨트랙트의 토큰은 실제 삼성전자 주식·배당권·의결권·상환권을 나타내지 않으며, 학습/포트폴리오 목적의 모의 거래 전용이다.
