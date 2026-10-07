# 현재 프로젝트로 이해하는 백엔드·블록체인과 거래 흐름

> 작성: 2026-10-08 KST  
> 대상: 블록체인 기초 없이 현재 프로젝트의 연결 구조부터 이해하려는 개발자  
> 읽는 순서: 1~4절 구조 → 5~8절 BUY/SELL → 9~13절 용어·장애 → 14절 코드 탐색

이 문서는 일반적인 코인 거래소가 아니라 **현재 저장소의 실제 구현**을 설명한다. 문서를 작성하기 위해 거래·배포·복원하지 않았다. 실제 환경에서 확인한 내용은 [개발 DB/체인 조사 보고서](development-chain-state-audit.md)에 별도로 기록했다.

설명의 기준:

- **[코드 사실]**: 현재 코드에서 확인한 동작. 이번 실제 Toss 거래가 성공했다는 뜻은 아니다.
- **[조사 사실]**: 이번 DB/RPC/보존 파일 조회로 확인한 결과.
- **[개념 설명]**: 코드를 이해하기 위한 일반적인 뜻이나 예시.
- **[해석/한계]**: 사실로부터의 판단 또는 아직 구현·검증하지 않은 부분.

## 1. 먼저 전체 구조

**[코드 사실]** 사용자는 브라우저에서 계정으로 로그인한다. 브라우저가 사용자 지갑으로 블록체인 거래를 서명하는 서비스가 아니다. 백엔드가 사용자 권한과 DB 잔고를 확인한 뒤 **운영자 지갑으로** 컨트랙트 거래를 실행한다.

```text
브라우저: 화면·로그인·차트·견적 확인·주문 버튼
   │ REST: 견적 발급 / 주문 / 포트폴리오 조회
   │ STOMP: 공개 시세·체결 / 로그인 사용자의 주문·포트폴리오 수신
   ▼
Spring Boot 백엔드
   ├─ Toss 공급자: 실제 시세·운영 시간 → 현재 시장 스냅샷
   ├─ 거래 PostgreSQL: 계정 / 사용자 잔고 / 견적 / 주문 / 체결 / 전송 복구 기록
   ├─ price signer: 가격 보고서에 EIP-712 서명 (이 서명 자체는 RPC 전송 아님)
   ├─ operator: Vault 호출용 Ethereum 트랜잭션 서명
   │     │ HTTP JSON-RPC (web3j)
   │     ▼
   │   Anvil: 로컬 Ethereum 노드, EVM 실행·블록·계정·receipt 보관
   │     ├─ MockKRW: 온체인 mKRW 잔고·전송
   │     ├─ mSEC: 온체인 mSEC 잔고·발행·소각
   │     ├─ PriceOracle: 가격 보고서 서명·만료·재사용 검증
   │     └─ ExchangeVault: 보고서 가격으로 토큰 교환
   │
   └─ reconciliation: receipt/event 조회 → 검증 → 거래 DB 정산
              └─ DB commit 이후 WebSocket 통지 → 브라우저 갱신

AI 모듈 → 별도 AI PostgreSQL+pgvector / 외부 AI provider
         거래 상태는 Read-only Tool로 관측하며, 거래 실행·정산을 대신하지 않음
```

여기서 PostgreSQL과 Anvil은 서로 다른 저장소다. DB에 `FILLED`라고 써 있다고 체인 거래가 자동으로 생기지 않으며, 체인 거래가 성공했다고 사용자별 DB 잔고가 자동으로 바뀌지도 않는다. **두 결과를 연결하는 것이 백엔드의 역할**이다.

실제 위치:

- [웹 main.js](../tools/websocket-test-client/src/main.js): 화면에서 요청/이벤트를 연결한다.
- [웹 api.js](../tools/websocket-test-client/src/api.js): REST 요청과 JWT를 전달한다.
- [웹 websocket.js](../tools/websocket-test-client/src/websocket.js): 실제 STOMP 구독을 연결한다.
- [BlockchainConfig](../backend/src/main/java/com/pricetrack/exchange/blockchain/config/BlockchainConfig.java): RPC URL로 web3j 클라이언트를 만든다.
- [개발 Docker Compose](../docker-compose.yml): 개발 PostgreSQL과 Anvil 실행 구성.
- [application.yml](../backend/src/main/resources/application.yml): DB/RPC/계약 주소/공급자 설정.

## 2. 사용자가 가진 자산과 운영자 지갑의 자산은 다르다

**[코드 사실]** 현재 사용자별 자산은 거래 DB의 `user_balances`에 있다. 웹 사용자마다 온체인 지갑을 생성해서 각각의 ERC-20을 관리하지 않는다.

예를 들어 사용자 A의 DB mSEC가 2, 사용자 B가 3일 수 있다. 체인에는 A/B 로그인 ID 대신 **operator 주소의 ERC-20 잔고**가 기록된다. 온체인 `Bought/Sold` event의 `user`도 현재 경로에서는 operator다. 어느 웹 사용자의 주문인지 백엔드가 `orderId → userId`로 연결한다.

| 영역 | 기록 단위 | 누가 변경하는가 |
|---|---|---|
| 거래 DB | userId별 mKRW/mSEC·잠금·평균 매수가 | WalletService / 주문 정산 서비스 |
| 온체인 | address별 ERC-20 잔고·allowance·발행량 | 토큰/Vault 컨트랙트 |
| 연결 기록 | orderId / txHash / sender / nonce | BlockchainTransactionPersistence |

**[코드 사실]** faucet도 두 종류다.

1. 웹의 모의 자금 지급: [WalletService.faucet](../backend/src/main/java/com/pricetrack/exchange/wallet/WalletService.java)가 사용자 DB mKRW를 1,000,000 증가시킨다. **MockKRW 컨트랙트를 호출하지 않는다.**
2. 운영자 온체인 자금 준비: [PrepareOperator.s.sol](../contracts/script/PrepareOperator.s.sol)이 `MockKRW.faucet()`을 호출해 operator에게 실제 테스트 ERC-20 mKRW를 발행하고 Vault 사용 승인을 설정한다.

DB faucet을 여러 번 받았다고 운영자 온체인 자금이 같이 증가하는 것은 아니다. 따라서 DB 잔고가 있어도 operator의 토큰/ETH 또는 Vault 유동성이 부족하면 온체인 거래를 할 수 없다.

**[해석/한계]** 현재 구조는 중앙 운영자 지갑을 사용하는 모의 하이브리드 거래소다. 사용자별 지갑/자산 소유의 탈중앙 증명, 자동 준비금 충족, 은행 입금 담보를 구현한 것으로 해석하면 안 된다. 사용자 DB 지급액과 온체인 준비금의 전체 대사는 별도 보강 과제다.

관련 코드: [UserBalance](../backend/src/main/java/com/pricetrack/exchange/wallet/UserBalance.java), [BlockchainTransaction](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransaction.java), [ContractEventParser](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java).

## 3. 네 컨트랙트는 각각 무엇인가?

**[개념 설명]** `.sol`은 Solidity 소스 파일이고, 배포된 컨트랙트는 Anvil의 EVM이 실행하는 프로그램이다. Java 클래스와 비슷하게 상태/함수를 묶지만, 배포 후에는 체인 안의 **주소를 가진 프로그램**으로 호출한다. 소스 파일 하나가 블록체인 하나인 것은 아니다.

**[코드 사실]** 실제 주요 컨트랙트는 다음 네 개다. `PriceReportTypes.sol`은 보고서 형식을 공유하는 library이며 별도 사용자 토큰이 아니다.

### MockKRW: 결제용 모의 원화 토큰

[MockKRW.sol](../contracts/src/MockKRW.sol)은 ERC-20이다. `balanceOf`, `transfer`, `approve`, `allowance`, `transferFrom`으로 잔고와 전송을 관리한다. `faucet()`은 호출한 주소에 mKRW를 신규 발행한다. 현재 faucet은 횟수 제한이 없어 반복 발행할 수 있다. 실제 은행 원화나 상환 보증은 아니다.

### mSEC: 사용자가 사고파는 모의 토큰

[SamsungPriceTrackingToken.sol](../contracts/src/SamsungPriceTrackingToken.sol)도 ERC-20이다. 가격 자체를 결정하지 않고 address별 수량과 총 발행량을 관리한다.

- `setMinter(vault)`로 발행/소각 권한을 Vault에 준다.
- `mint(operator, amount)`는 매수 결과의 토큰을 만든다.
- `burn(operator, amount)`는 매도 입력 토큰을 없앤다.
- 실제 삼성전자 주식/배당/의결권을 제공하지 않는다.

### PriceOracle: 현재는 가격 보고서 검증기

[PriceOracle.sol](../contracts/src/PriceOracle.sol)은 토큰이 아니다. 핵심 역할은 **승인된 price signer가 서명한 거래 가격을 검증**하는 것이다.

- `priceSigner`: 누구의 가격 서명을 믿을지 등록한 주소.
- `authorizedConsumer`: 보고서를 소비할 수 있는 Vault 주소.
- `usedQuoteIds`: 이미 체인에서 사용한 견적인지 기록.
- `consumePriceReport`: 종목·가격·유효시간·서명·재사용을 검증하고 승인 가격을 기록.
- `priceE8`, `priceObservedAt`, `updatedAt`: 마지막 승인 가격, 시장 관측 시각, 블록 갱신 시각.

**중요:** 현재 거래는 주기적으로 저장된 Oracle 가격을 기다렸다가 체결하지 않는다. **주문별 서명 보고서의 가격**을 같은 거래 안에서 검증하고 사용한다. `updatePrice()`와 참고용 `quoteBuy/quoteSell` 함수는 코드에 남아 있지만 신규 백엔드 거래 가격 결정 경로가 아니다. 파일 상단의 과거 updatePrice 중심 주석보다 실제 함수와 호출 경로를 기준으로 읽는다.

### ExchangeVault: 교환·온체인 정산 프로그램

[ExchangeVault.sol](../contracts/src/ExchangeVault.sol)은 토큰이 아니라 교환 프로그램이다. 배포 시 mKRW/mSEC/Oracle 주소를 immutable로 연결한다.

- BUY: operator mKRW를 Vault로 받고 operator에게 mSEC를 발행한다.
- SELL: operator mSEC를 소각하고 Vault mKRW를 operator에게 지급한다.
- 기본 수수료 `10 bps = 0.1%`.
- `buy/sell`은 **PriceReport + signature만** 받는다. 예전 금액만 받는 거래 함수는 없다.
- 방향, executor, 입력량, 최소 출력량과 SELL 유동성을 검사한다.

컨트랙트는 외부 Toss API를 직접 호출하지 않는다. 가격을 가져오는 백엔드와 그 가격을 보증하는 signer를 신뢰하는 구조다. 서명 검증이 가능하다고 외부 시세의 진실성까지 자동으로 보증되는 것은 아니다.

## 4. operator와 price signer를 왜 나누는가?

**[코드 사실]** 둘은 서로 다른 용도의 키 설정이다.

| 역할 | 하는 일 | 설정 / 코드 |
|---|---|---|
| price signer | “이 가격·주문 조건을 승인한다”는 보고서 서명 | `PRICE_SIGNER_PRIVATE_KEY`, [PriceReportSigner](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportSigner.java) |
| operator | “내 지갑으로 Vault 거래를 실행한다”는 Ethereum transaction 서명·전송 | `OPERATOR_PRIVATE_KEY`, [BlockchainTransactionSender](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java) |
| 컨트랙트 owner | signer/consumer/minter/수수료 등 관리 함수 실행 권한 | Deploy 시 `msg.sender`, 각 컨트랙트 `onlyOwner` |

price signer는 보고서에 서명할 때 gas를 쓰지 않는다. operator는 트랜잭션을 체인에 보내므로 ETH gas와 거래 자산이 필요하다. owner는 별도의 관리 권한 개념이며 반드시 웹 ADMIN 계정과 같거나 반드시 operator와 달라야 하는 것은 아니다. **웹 ADMIN은 DB 역할이고, 컨트랙트 owner는 체인 주소**다.

**[설계 결정]** 가격 승인 키와 거래 전송 키는 분리해서 운영한다. 이 역할 분리는 가격 승인과 자산 실행의 권한을 나누지만, 가격 서명 키 탈취/서버 침해 위험을 없애는 것은 아니다. `PriceReportStartupValidator`는 signer 등록 일치를 검사하며 키 두 개가 서로 다른지까지 확인하는 코드로 읽으면 안 된다.

배포 연결은 [Deploy.s.sol](../contracts/script/Deploy.s.sol)에 있다. Oracle 생성 시 signer를 등록하고, mSEC minter와 Oracle consumer를 Vault로 지정한다. 배포 주소만 있는 것과 이 연결이 올바르게 설정된 것은 다른 조건이다.

## 5. BUY ① 버튼부터 서명 견적까지

**[코드 사실]** 사용자가 시장 화면에서 금액을 입력하고 견적을 요청한다. 웹은 가격 서명 원문을 만들거나 사용자 지갑으로 서명하지 않는다.

1. **웹 → REST:** `POST /api/quotes/buy`에 `symbol=mSEC`, `krwAmount`를 전송한다. JWT로 사용자 신원이 전달된다. [api.js](../tools/websocket-test-client/src/api.js), [QuoteController](../backend/src/main/java/com/pricetrack/exchange/quote/QuoteController.java).
2. **시장 스냅샷:** [PriceReportIssuer](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java)가 [MarketPriceService](../backend/src/main/java/com/pricetrack/exchange/market/MarketPriceService.java)를 통해 현재 공급자 가격을 읽는다. Toss 모드의 실제 공급자는 [TossPriceProvider](../backend/src/main/java/com/pricetrack/exchange/market/provider/toss/TossPriceProvider.java)다.
3. **거래 가능성:** 시장 CLOSED 또는 가격 STALE을 거부한다. 서명 발급기는 관측 후 5초를 넘거나 허용 2초보다 미래인 가격도 거부한다. 공급자 화면의 LIVE/DEGRADED 구분만으로 발급을 보장하지 않는다.
4. **정확한 출력량 조회:** `quoteBuyAtPrice(input, priceE8)`를 Vault에 `eth_call`로 조회한다. 이 조회는 거래 전송/블록 생성이 아니다. [BlockchainService](../backend/src/main/java/com/pricetrack/exchange/blockchain/BlockchainService.java), [ContractGateway](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractGateway.java).
5. **보고서 생성:** quoteId, symbolHash, priceE8, observedAt, validUntil, BUY 방향, 입력량, 최소 수령량, executor=operator를 결합한다. [PriceReportTypes.sol](../contracts/src/PriceReportTypes.sol).
6. **EIP-712 서명:** 실제 RPC chainId와 PriceOracle 주소를 domain에 넣고 전용 signer 키로 digest에 서명한다. [PriceReportEip712](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportEip712.java), [PriceReportSigner](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportSigner.java).
7. **DB 저장:** [PriceQuoteService.issue](../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java)가 원본 보고서 필드·서명·userId를 `price_quotes`에 저장한다.
8. **웹 응답:** quoteId, 가격, 수수료, 예상/최소 수령량, 관측/만료 시각을 보여준다. signature와 executor는 사용자 응답에 내보내지 않는다.

**[개념 설명]** EIP-712는 “아무 문자열”이 아니라 정해진 필드와 사용 대상(domain)을 포함해 서명하는 규칙이다. 이 프로젝트에서는 chainId와 Oracle 주소도 결합하므로 다른 chainId/다른 Oracle 주소에 그대로 쓸 수 없다. 단, **같은 chainId와 같은 주소로 빈 체인을 다시 만들면 과거 체인 이력까지 구별해 주는 것은 아니다.**

유효시간은 **관측 시각 + 30초**다. “응답을 받은 순간부터 언제나 30초”가 아니다. 가격이 바뀌면 새 견적 가격도 바뀌지만 발급된 보고서는 그 보고서의 가격과 조건을 유지한다. 유효기간·수수료 변경·유동성 등으로 실행이 거부될 수도 있다.

**[코드 사실]** 현재 minimumOutput은 서버가 Vault에서 조회한 정확한 출력량이다. 사용자 지정 허용 가격 편차를 설정하는 기능과 동일하지 않다.

## 6. BUY ② 주문 확정부터 RPC 전송까지

1. **사용자 확정:** 웹 [main.js](../tools/websocket-test-client/src/main.js)가 견적 조건/만료를 확인하고 `POST /api/orders/buy`에 `symbol`, `krwAmount`, `quoteId`만 전달한다. **사용자가 signature나 PriceReport 원문을 보내지 않는다.**
2. **인증과 분기:** [OrderController](../backend/src/main/java/com/pricetrack/exchange/order/OrderController.java) → [OrderService](../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java). 시장 상태를 다시 확인하고 blockchain enabled이면 온체인 경로로 들어간다.
3. **독립 DB 준비:** [OnchainOrderPreparationService.prepare](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java)가 새 DB 트랜잭션을 연다. 견적 행을 잠가 소유자, ISSUED 상태, 만료, BUY 방향, 입력량을 검사한다.
4. **주문·자산 잠금:** 사용자 mKRW 잔고 행을 잠근다. 주문을 REQUESTED로 만들고 사용 가능 mKRW가 충분하면 입력액을 lockedAmount로 예약한다. `available = amount - lockedAmount`이며 아직 최종 차감하지 않는다.
5. **견적 소비:** 같은 DB 준비 트랜잭션에서 quote를 CONSUMED로 바꾸고 orderId에 연결해 커밋한다. DB에 저장된 보고서와 서명만 복원한다. 다른 사용자는 같은 quoteId를 사용할 수 없다.
6. **함수 인코딩:** [OnchainOrderService](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java)가 `Vault.buy(report, signature)`의 ABI calldata를 만든다. [ContractGateway](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractGateway.java)는 calldata 생성까지만 담당한다.
7. **체인 전송 준비:** [BlockchainTransactionSender](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java)가 chainId, operator의 pending nonce, gasPrice를 읽고 `eth_estimateGas`로 실행 가능성과 gas를 확인한다. gas limit에는 추정값의 120% 버퍼를 붙인다.
8. **raw transaction 서명:** nonce·gasPrice·gasLimit·to=Vault·value=0·calldata로 transaction을 만들고 operator 키로 서명한다. value=0이어도 calldata가 mKRW 토큰 전송을 실행할 수 있다. ETH 전송량과 ERC-20 입력량은 다른 값이다.
9. **txHash 선계산·DB 커밋:** signed raw bytes의 hash를 미리 계산한다. [BlockchainTransactionPersistence.saveSigned](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionPersistence.java)가 orderId, sender, nonce, raw transaction, txHash를 **RPC보다 먼저** SIGNED 상태로 저장한다.
10. **RPC 제출:** `eth_sendRawTransaction(rawHex)`를 호출한다. 반환 hash가 사전 계산 hash와 같아야 한다. [BlockchainTransactionPersistence.markSubmitted](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionPersistence.java)가 전송 기록 SUBMITTED와 주문 PENDING_ONCHAIN을 저장한다.
11. **웹 응답:** PENDING_ONCHAIN이면 HTTP 202를 반환할 수 있다. 이것은 접수/전송 대기 상태이지 최종 체결 확정이 아니다. 개인 주문 대기 WS 알림도 발행된다.

**[코드 사실]** nonce 전송은 `synchronized`로 **단일 백엔드 프로세스 안에서** 직렬화한다. 다중 서버에서도 자동 안전한 분산 nonce 관리가 구현됐다는 뜻은 아니다.

잔고 부족이면 FAILED 주문을 남기고 자산을 잠그지 않는다. SIGNED 기록 생성 전에 실패한 주문은 `failIfNotSigned`가 잠금 해제/FAILED로 보상한다. 이미 CONSUMED로 커밋한 견적을 자동으로 다시 ISSUED로 되돌리는 코드는 아니다. SIGNED 이후에는 함부로 실패 확정/재주문하지 않고 같은 원문의 복구에 맡긴다.

## 7. BUY ③ 컨트랙트 실행부터 DB 정산·화면 반영까지

**[코드 사실]** 다음은 노드가 거래를 실행한 뒤 백엔드가 결과를 처리하는 흐름이다. Anvil automining이 빠르면 RPC 응답 시점에 이미 채굴돼 있을 수 있다. 그래도 백엔드는 receipt 검증 전 DB 체결을 확정하지 않는다.

1. **Vault 검사:** BUY 방향, `report.executor == msg.sender`, 입력량/최소 출력량이 양수인지 확인한다. 여기서 msg.sender는 operator다.
2. **Oracle 보고서 소비:** Vault가 `consumePriceReport`를 호출한다. Oracle은 호출자가 승인된 Vault인지, 종목 hash, 양수 가격, 유효시간, 미래 시각, quoteId 미사용, EIP-712 signer를 확인한다. 성공하면 quoteId 사용 표시와 마지막 승인 가격을 기록한다.
3. **수량 산정:** Vault가 보고서 가격과 현재 feeBps로 출력량을 계산하고 minimumOutput을 충족하는지 확인한다.
4. **온체인 자산 교환:** mKRW `transferFrom(operator, vault, input)` 실행 → mSEC `mint(operator, output)` 실행 → `Bought` event 기록.
5. **체인 원자성:** 위 과정 중 allowance/잔고/권한 등으로 실패하면 이 Ethereum transaction 안의 토큰 변경·Oracle quoteId 소비·가격 기록도 rollback된다. gas 비용까지 없던 일이 되는 것은 아니다.
6. **receipt 조회:** [BlockchainReconciliationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java)가 SIGNED/SUBMITTED 기록을 주기적으로 조사한다. 기본 1초, 기본 confirmation 1개다. receipt 없음/confirmation 부족이면 기다린다.
7. **성공 event 검증:** receipt status가 성공이어야 하며 [ContractEventParser](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java)가 예상 Vault 주소에서 `Bought`가 정확히 1개인지, indexed user가 저장된 operator인지, 입력량이 DB 주문과 같은지 검사한다.
8. **DB 원자적 정산:** [OnchainSettlementService.settleSuccess](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)가 관련 행을 잠근다. 사용자 DB mKRW 차감/잠금 해제, mSEC 증가/평균 매수가 갱신, Trade 생성, 주문 FILLED, 전송 CONFIRMED·blockNumber 저장을 한 DB 트랜잭션으로 확정한다.
9. **commit 후 알림:** [UserWebSocketPublisher](../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/UserWebSocketPublisher.java)가 주문/포트폴리오 알림을 준비하고 [WebSocketDeliveryListener](../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/WebSocketDeliveryListener.java)가 AFTER_COMMIT에 실제 STOMP로 전송한다. rollback된 정산을 완료 알림으로 보내지 않는다.
10. **브라우저 갱신:** `/user/queue/orders`, `/user/queue/portfolio`를 받아 완료 상태와 자산을 표시한다. 공개 체결 topic에는 사용자 개인정보 없이 체결을 알린다. 재연결 시 REST로 주문·체결·포트폴리오를 다시 조회한다.

**[확인 범위]** 현재 receipt parser는 Bought/Sold의 발생 주소·개수·operator·입력량을 검사한다. 별도의 `PriceReportConsumed` event를 DB quoteId와 다시 연결해 검증하거나 모든 report 필드를 receipt와 재비교하는 것으로 확대 해석하지 않는다. 보고서 서명·방향·최소 출력량 검증은 컨트랙트 실행 단계가 담당한다.

**[코드 사실]** 이미 CONFIRMED/FILLED이면 재정산하지 않고, orderId의 체결 unique/행 잠금 등으로 반복 receipt polling에 대비한다. 입력 자산은 실패 receipt일 때 잠금만 풀고 FAILED로 확정한다. 결과가 모순되면 자동으로 자산을 바꾸지 않는다.

## 8. SELL: 같은 안전 흐름에서 무엇이 달라지는가?

**[코드 사실]** SELL도 견적 발급 → EIP-712 → DB quote 보관 → quoteId만 확정 → 자산 잠금 → raw 서명·보존 → RPC → receipt/event → DB 정산 → WS 순서를 모두 거친다.

1. 웹은 `POST /api/quotes/sell`에 tokenAmount를 보낸다.
2. 발급기는 SELL 보고서에 입력 mSEC와 `quoteSellAtPrice`가 계산한 최소 수령 mKRW를 넣어 서명한다.
3. `POST /api/orders/sell`에는 symbol/tokenAmount/quoteId만 보낸다.
4. DB가 견적 소유권·SELL 방향·입력량·만료·상태를 검사하고 **사용자 DB mSEC를 잠근다.**
5. operator가 `Vault.sell(report, signature)` 거래를 서명·전송한다. nonce·gas·raw 저장/복구는 BUY와 같다.
6. Vault/Oracle이 서명과 조건을 검사한다. **Vault의 mKRW가 지급액보다 부족하면 실패**한다.
7. Vault는 `mSEC.burn(operator, input)`으로 operator 토큰을 소각하고 `mKRW.transfer(operator, output)`으로 지급한다. `Sold` event를 기록한다.
8. 백엔드는 Sold의 Vault/operator/입력량을 검증한다.
9. DB 정산은 사용자 mSEC 차감/잠금 해제, mKRW 증가, Trade 생성, FILLED/CONFIRMED를 한 번 반영한다. mSEC를 전량 매도했다면 평균 매수가를 0으로 만든다.
10. 개인 주문·포트폴리오 WS로 화면이 갱신된다.

**SELL에 mSEC approve가 없는 이유:** 이 토큰은 Vault가 지정 minter이며 `burn(from, amount)`를 직접 호출하는 설계다. 반면 BUY는 mKRW의 일반 `transferFrom`이므로 operator → Vault allowance가 필요하다. 어떤 ERC-20 매도에도 approve가 불필요하다는 일반 규칙이 아니다.

### 수치 예시 — 실제 이번 체결 결과가 아님

가격 75,000, 입력 mKRW 750,000, 수수료 0.1%라면:

- BUY 수수료 750 mKRW → 순입력 749,250 → 수령 mSEC 9.99.
- 이후 가격 80,000에서 mSEC 1을 SELL → 총액 80,000 → 수수료 80 → 수령 79,920 mKRW.

현재 BUY UI는 **수수료를 포함한 지출 금액**을 입력한다. “1토큰을 지정하면 추가 수수료를 별도로 청구하는 주문”과 다르다. 토큰 수량은 온체인 정수 계산으로 내림 처리할 수 있다.

## 9. 두 서명, 세 가지 ID를 구별하자

| 구분 | 언제 생기는가 | 의미 / 저장 위치 |
|---|---|---|
| 가격 서명 | quote 발급 | price signer가 보고서 조건을 승인; `price_quotes.signature` |
| transaction 서명 | 주문 전송 준비 | operator가 nonce·gas·Vault 호출을 승인; `blockchain_transactions.raw_transaction` |
| quoteId | 보고서 발급 | 특정 가격/조건 견적의 bytes32 식별자; userId에 귀속 |
| orderId | DB 주문 준비 | 사용자 주문 행의 ID; BUY/SELL/입력량·사용자 연결 |
| txHash | raw transaction 서명 | 정확히 그 서명 거래의 해시; DB와 체인 조회를 연결 |

가격에 서명했다고 블록에 기록된 것은 아니다. txHash가 있다고 체결 성공도 아니다. orderId만으로 체인 조회를 할 수도 없다.

**[코드 사실]** 같은 raw transaction을 재전송하면 같은 txHash다. nonce나 gas 등 내용을 바꿔 다시 서명하면 다른 거래/hash가 된다. 그래서 전송 결과가 불확실할 때 새 주문/새 nonce를 만드는 것이 아니라 저장된 원문을 조사한다.

개인키는 비밀이며 이 문서에 넣지 않는다. raw transaction도 개인키는 아니지만 이미 서명된 실행 가능 데이터이므로 공개 로그/AI evidence에 내보내면 안 된다. 가격 signature도 임의 사용자에게 원문 제출 권한을 주지 않도록 현재 API에서 숨긴다.

## 10. 용어를 현재 프로젝트 사용처로 이해하기

| 용어 | 여기서의 뜻 | 실제 사용처 |
|---|---|---|
| Anvil / EVM | 로컬 Ethereum 노드 / Solidity 프로그램을 실행하는 환경 | 개발 Compose `anvil`, contracts 배포 대상 |
| RPC / JSON-RPC | 백엔드가 노드에 조회·전송을 요청하는 통신 규약 | BlockchainConfig/web3j, eth_call / eth_sendRawTransaction |
| chainId | 서명에 쓰는 체인 번호; 현재 로컬은 31337 | PriceReportEip712 domain, TransactionEncoder.signMessage |
| address | 지갑 또는 배포 프로그램을 찾는 공개 주소 | `.env` 네 계약 주소, operator/executor, signer 등록 |
| private key | 주소 주체가 승인했다는 서명을 만드는 비밀키 | 가격용 PriceReportSigner / 거래용 BlockchainTransactionSender |
| nonce | 같은 발신자 거래의 순번; quoteId와 다름 | ethGetTransactionCount(PENDING), DB sender+nonce unique |
| gas / gasPrice / gasLimit | 실행 연산량 / 단가 / 허용 최대 연산량 | ethEstimateGas, ethGasPrice, operator ETH 비용 |
| ABI / calldata | Solidity 함수를 호출하고 값을 읽는 인코딩 규칙 / 호출 데이터 | ContractGateway FunctionEncoder/Decoder |
| raw transaction | operator 서명이 붙은 직렬화된 거래 원문 | saveSigned → eth_sendRawTransaction → recoverSigned |
| txHash | 서명 원문의 hash; 거래 조회 식별자 | orders/trades/blockchain_transactions, receipt polling |
| block number | 해당 거래가 포함된 블록의 위치 | receipt.blockNumber, confirmation 계산 |
| confirmation | 포함 블록부터 최신 블록까지의 확인 수 | latest - receiptBlock + 1, 기본 요구 1 |
| receipt | 거래가 실제 실행된 결과 영수증 | status·block·logs를 조회해 정산 여부 결정 |
| event / log | 컨트랙트가 실행 결과를 남긴 기록 | Bought/Sold → ContractEventParser; PriceReportConsumed도 체인에 emit |
| allowance / approve | 소유자가 spender에게 허용한 토큰 사용량 / 설정 함수 | operator의 mKRW를 Vault.transferFrom 할 때 필요 |
| mint / burn | 토큰 신규 발행 / 보유 토큰 소각 | MockKRW faucet mint; Vault BUY mSEC mint / SELL burn |
| EIP-712 / digest | 타입/domain을 포함하는 서명 규칙 / 서명할 hash | PriceReportEip712 + Solidity EIP712 |
| priceE8 / wei 단위 | 가격 × 1e8 / 토큰 수량 × 1e18 | PriceUnits / TokenUnits; 토큰 단위는 ETH와 별개 |
| settlement | 검증된 체결 결과를 사용자 원장에 확정 | OnchainSettlementService |
| reconciliation | DB 전송 상태와 체인 실행 결과를 맞추는 재조회/복구 | BlockchainReconciliationService |
| WebSocket / STOMP | 브라우저와 서버의 실시간 연결 / 구독 메시지 규약 | MarketSocket, WebSocketConfig, 개인 queue/공개 topic |
| checkpoint | 같은 시점의 DB·chain·배포 주소·버전을 묶은 복원 기준 | deployment/ops.py backup / checkpoint.json |

gas는 mKRW 수수료와 다르다. 사용자 거래 수수료는 Vault가 mKRW 계산에 적용하는 0.1%이고, gas는 operator가 테스트 ETH로 부담한다. Anvil의 ETH/mKRW 모두 이번 프로젝트의 로컬 모의 자산이다.

## 11. 백엔드가 켜지려면 무엇이 먼저 준비돼야 하나?

**[코드 사실]** 현재 Toss + blockchain + signed quote 모드 기준이다. blockchain disabled인 DB 모의 거래 경로까지 체인 배포를 요구하는 것은 아니다.

1. 거래 PostgreSQL에 연결할 수 있고 schema 초기화 SQL이 정상이어야 한다. [application.yml](../backend/src/main/resources/application.yml)은 JPA `ddl-auto=none`, SQL init `always`를 사용한다.
2. RPC가 올바른 Anvil에 연결돼야 한다. 포트가 열린 것만으로 계약이 존재하는 것은 아니다.
3. 올바른 버전의 네 컨트랙트가 **그 체인에** 배포돼 있고 `.env`의 주소가 가리켜야 한다.
4. signed-report 기능이 켜지면 [PriceReportStartupValidator](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportStartupValidator.java)가 로컬 signer 키로 파생한 주소와 `PriceOracle.priceSigner`를 비교한다. 비어 있거나 불일치하면 기동을 중단한다.
5. Toss 모드의 [TossPriceProvider](../backend/src/main/java/com/pricetrack/exchange/market/provider/toss/TossPriceProvider.java)는 ApplicationReadyEvent에서 초기 REST 가격/시장 참조를 얻고 실시간 연결을 시작한다. 외부 API/IP/자격 증명 준비도 필요하다.

**기동과 거래 준비를 구별해야 한다.** signer 검증이 통과했다고 모든 Vault 연결·운영자 자금·allowance까지 자동 보증되는 것은 아니다. 실제 거래에는 추가로 다음이 필요하다.

- Vault의 krw/token/oracle 주소가 목표 계약과 일치.
- mSEC.minter=Vault, Oracle.authorizedConsumer=Vault.
- operator에 gas용 ETH, BUY용 mKRW 또는 SELL용 mSEC.
- BUY에서 operator mKRW의 Vault allowance가 입력량 이상.
- SELL에서 Vault mKRW가 지급액 이상.
- 최근 관측 가격과 유효한 보고서, 실제 거래 가능한 시장 시간.

이를 읽는 도구는 [BlockchainService.connectionStatus/contractSnapshot](../backend/src/main/java/com/pricetrack/exchange/blockchain/BlockchainService.java)이고, 배포/일부 자금 준비 예시는 [Deploy](../contracts/script/Deploy.s.sol), [PrepareOperator](../contracts/script/PrepareOperator.s.sol)다. PrepareOperator는 Vault 유동성까지 모두 자동 보증하는 스크립트가 아니다.

**[조사 사실]** 이번에는 DB 연결 뒤 `priceSigner()` 결과가 비어 시작 실패했다. 현재 chainId=31337/block=0이며 네 `.env` 주소의 code가 모두 없었다. “키를 적지 않았다”와 “해당 주소에 계약이 없다”는 다른 문제다. 자세한 증거는 [조사 보고서](development-chain-state-audit.md)에 있다.

## 12. `.env` 주소와 Anvil state가 왜 일치해야 하나?

**[개념 설명]** `.env`는 “이 주소로 찾아가라”는 설정이고, state는 “그 주소에 실제 어떤 코드/잔고/저장값/이력이 있나”다. 지도에 건물 주소가 남아 있어도 건물이 없어지면 사용할 수 없는 것과 비슷하다.

**[코드 사실]** 이 프로젝트에서 주소 불일치 영향은 여러 층에 나타난다.

- ContractGateway는 `.env` 주소의 함수 반환값을 기대한다. code가 없으면 필요한 값을 읽을 수 없다.
- EIP-712 digest는 Oracle 주소를 포함한다. 새 주소의 Oracle에는 옛 domain 서명을 그대로 쓸 수 없다.
- Vault constructor의 연결은 immutable이다. 환경 변수만 고쳐 Vault 내부 연결을 바꿀 수 없다.
- event 검증은 `.env` Vault 주소를 기준으로 한다. 다른 주소의 Bought/Sold는 정산 근거가 아니다.

**같은 주소로 재배포해도 과거 체인 복원은 아니다.** 개발 계정과 배포 순서/nonce가 같으면 주소가 다시 같아질 수 있지만, 이전 receipt·토큰 잔고·allowance·사용한 quoteId·블록 이력은 없어진 상태일 수 있다. chainId도 로컬 체인을 여러 번 만들 때 반복할 수 있다.

**[조사 사실]** 보존 checkpoint는 다른 계약 주소의 격리 검증 환경이었다. state 25개 모두 개발 DB txHash와 교집합이 0이었다. Foundry broadcast에는 같은 개발 주소의 옛 배포 기록이 있지만 전체 체인 state가 아니다.

**[해석]** 따라서 지금은 `.env`에 주소 네 개를 다시 적는 것만으로 개발 DB의 과거 이력을 회복할 수 없다.

## 13. DB와 체인의 정본 역할, REVIEW_REQUIRED와 복구

### 각 저장소는 자기 영역의 기준이다

**[코드 사실]** 사용자 신원·userId별 잔고/잠금·견적 소유권·주문 상태·체결 이력은 거래 DB가 관리한다. 체인에서 실제 실행됐는지, 어떤 ERC-20 전송/발행/소각이 일어났는지, 어떤 signer의 보고서가 소비됐는지는 체인의 receipt/log/state가 근거다.

“체인이 정본이니 DB를 버려도 모든 사용자 자산을 복원할 수 있다”는 구조가 아니다. 현재 체인에는 operator가 보이므로 **DB가 없으면 개별 웹 사용자의 귀속을 충분히 재구성할 수 없다.** 반대로 DB만으로 원래 체인의 receipt/contract state를 복구할 수도 없다.

**[해석/한계]** 로컬 Anvil은 운영자가 reset/조작할 수 있는 테스트 체인이다. 공용 블록체인의 독립적 합의/외부 검증 가능성을 현재 로컬 운용이 그대로 제공한다고 주장하지 않는다.

### DB와 RPC를 한 번에 commit할 수 없기 때문에 reconciliation이 필요하다

실제 가능한 중단 지점:

| 중단 지점 | 현재 처리 원칙 |
|---|---|
| 준비/잠금 후 SIGNED 저장 전 실패 | failIfNotSigned로 FAILED·잠금 해제 |
| SIGNED 저장 후 RPC 응답 유실 | 저장 원문과 hash로 체인 존재 조회; 없으면 같은 raw 재전송 |
| RPC 성공 후 SUBMITTED 기록 전 종료 | SIGNED 복구가 존재 거래를 확인하고 제출 상태 복원 |
| SUBMITTED인데 receipt 없음 | 실패로 단정하지 않고 대기 |
| receipt 실패 status | 입력 자산 잠금 해제, FAILED |
| receipt 성공인데 event/DB 불일치 | REVIEW_REQUIRED로 자동 정산 중지 |
| 정산 완료 후 같은 receipt 재조회 | 완료 상태/unique/잠금으로 중복 반영 방지 |

**[코드 사실]** 체인과 DB 사이에 분산 트랜잭션을 만들지 않고, DB 커밋 단계를 나눠 복구할 증거를 남긴다. 이는 단순히 “RPC를 다시 요청하면 된다”보다 엄격하다. **가격 보고서의 만료 때문에 아직 채굴되지 않은 거래의 복구 성공이 항상 보장되는 것도 아니다.**

### REVIEW_REQUIRED는 자동 실패 처리와 다르다

[OnchainSettlementService.markReviewRequired](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)는 **blockchain_transactions.status**를 REVIEW_REQUIRED로 바꾼다. 주문에 같은 이름의 상태를 넣는 것으로 설명하면 안 된다. 자산 잠금은 의도적으로 유지한다.

receipt가 성공인데 다른 Vault 로그만 있거나 입력량/연결/DB 잠금이 맞지 않는다면, 이미 실제 자산이 움직였을 가능성이 있다. 여기서 잠금을 풀고 새 주문을 허용하면 이중 사용 위험이 생기므로 검토가 필요하다. 현재 reconciliation은 REVIEW_REQUIRED를 일반 pending처럼 계속 자동 정산하지 않는다.

[DiagnosisSourceReader](../backend/src/main/java/com/pricetrack/exchange/ai/diagnosis/DiagnosisSourceReader.java)는 REVIEW_REQUIRED인 BUY/SELL 중 주문/txHash 연결이 유효한 대상만 AI 자동 진단에 공급한다. [ReadOnlyReceiptClient](../backend/src/main/java/com/pricetrack/exchange/ai/tool/receipt/ReadOnlyReceiptClient.java)는 receipt/event를 읽어 관측 근거를 제공한다. **AI는 잠금 해제·주문 수정·거래 재전송을 실행하는 복구 관리자 아니다.** 성공 MATCH는 진단의 근거 일치이며 DB 정산 완료와 별개의 사실이다.

### 이번처럼 state가 없어진 경우

**[조사 사실]** DB는 과거 거래를 CONFIRMED로 기억하지만 현재 체인에는 receipt가 없다. 이 불일치를 일반 일시적 RPC 장애와 같다고 보면 안 된다.

- 기존 CONFIRMED 기록은 pending reconciliation 대상이 아니다. 전체 과거 원장을 자동 감사/복원하는 기능으로 확대 해석하지 않는다.
- 같은 체인 번호로 다시 만든 빈 체인은 과거 확정 기록의 증거가 아니다.
- 새 체인의 operator nonce가 낮은 값부터 시작하면 DB의 과거 sender+nonce unique와 충돌할 수 있다.
- DB의 raw 원문을 모두 재전송하는 것은 체인 백업 복원과 다르다.

따라서 현재 제안은 기존 개발 DB/체인을 보존하고, **새 DB + 새 지속형 Anvil + 전용 operator + 동일 실행의 주소**로 실제 Toss/AI 인수 환경을 따로 만드는 것이다. 아직 생성하지 않았다.

올바른 장기 보존 단위는 같은 시점의 거래 DB/AI DB/chain state/계약 주소/실행 코드·지식 버전이다. [deployment/ops.py](../deployment/ops.py)는 ingress와 backend를 멈추고 미완료/잠금 0을 확인한 뒤 Anvil을 멈춰 checkpoint를 만든다. 이 절차를 기존 개발 DB에 적용하는 것은 별도 승인 작업이다.

## 14. 코드 읽는 순서

전체 클래스를 한 번에 이해하려고 하지 말고 한 주문을 아래 순서로 따라가면 된다.

1. 웹 [api.js](../tools/websocket-test-client/src/api.js): 사용자가 보내는 값은 무엇인가?
2. [QuoteController](../backend/src/main/java/com/pricetrack/exchange/quote/QuoteController.java) → [PriceReportIssuer](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java) → [PriceQuoteService](../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java): 누가 가격을 읽고 서명하며 어디에 저장하나?
3. [OrderController](../backend/src/main/java/com/pricetrack/exchange/order/OrderController.java) → [OnchainOrderPreparationService](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java): 누가 quote를 사용할 수 있고 무엇을 먼저 잠그나?
4. [OnchainOrderService](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java) → [BlockchainTransactionSender](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java): 언제 원문을 저장하고 실제 전송하나?
5. [ExchangeVault.sol](../contracts/src/ExchangeVault.sol) → [PriceOracle.sol](../contracts/src/PriceOracle.sol): 체인이 승인하는 가격/조건은 무엇인가?
6. [BlockchainReconciliationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java) → [ContractEventParser](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java) → [OnchainSettlementService](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java): 성공 receipt가 어떤 검증을 거쳐 사용자 자산이 되나?
7. [UserWebSocketPublisher](../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/UserWebSocketPublisher.java) → [WebSocketDeliveryListener](../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/WebSocketDeliveryListener.java) → 웹 [websocket.js](../tools/websocket-test-client/src/websocket.js): DB 완료가 어떻게 화면에 나타나나?
8. [PortfolioService](../backend/src/main/java/com/pricetrack/exchange/portfolio/PortfolioService.java): 현재 가격은 평가용이고, 평균 매수가/체결가는 과거 결과라는 차이를 확인한다.

포트폴리오는 DB 보유량 × 현재 시장 가격으로 토큰 평가액을 계산한다. 미실현 손익은 평가액 − 보유량 × 평균 매수가, 총 평가액은 DB mKRW + 토큰 평가액이다. **현재 평가 가격은 새 tick마다 변해도 기존 체결 가격이 다시 바뀌는 것은 아니다.** BUY 평균 매수가 갱신에는 실제 지출 입력액이 사용돼 수수료가 비용에 포함된다.

## 15. 문서의 검증 한계

현재 소스/설정과 읽기 전용 조사에 근거한 설명이다. 이번 실제 Toss 웹 BUY/SELL 전체 인수와 실제 AI 성공 MATCH 자동 진단은 아직 완료하지 않았다. 모의 거래/Anvil 통합 테스트/과거 checkpoint 복원 테스트가 존재하는 것과 **현재 개발 환경에서 장중 전체 흐름이 인수된 것**을 구분한다.

문서 작성으로 제품 코드·계약·기존 DB/chain/.env를 바꾸지 않았다. 기능 변경이 없는 학습/조사 문서이므로 별도 구현 검토는 생략하고, 소스·링크·조사 수치 자체 대조를 수행했다.
