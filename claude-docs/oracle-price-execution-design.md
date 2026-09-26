# 서명 가격 기반 원자적 Oracle·거래 설계 논의

> 작성: 2026-09-26
> 상태: Phase 5.1 가격 보고서 계약 확정
> 목적: Toss 시장 가격과 온체인 체결 가격의 시간차 문제, 해결 원리와 다음 Phase 범위를 공유한다.

## 1. 현재 구조

현재 가격과 거래는 서로 다른 트랜잭션으로 처리된다.

```text
Toss REST·WebSocket
        ↓
Spring Boot 가격 상태와 웹 표시
        ↓ 별도 updatePrice 트랜잭션
PriceOracle.priceE8
        ↓ 이후 별도 거래 트랜잭션
ExchangeVault.buy/sell
```

- Toss 가격은 현실 시장에서 관측한 최신 참고 가격이다.
- 백엔드는 운영자 키로 `PriceOracle.updatePrice()`를 호출한다.
- `PriceOracle`은 운영자가 등록한 온체인 공식 가격과 갱신 시각을 보관한다.
- `ExchangeVault`는 사용자가 제출한 가격이 아니라 `PriceOracle`의 가격으로 수량과 수수료를 계산한다.
- Oracle 갱신과 거래가 분리되어 있어 웹에 표시된 최신 Toss 가격과 Vault가 읽는 가격이 다를 수 있다.

## 2. 현재 PriceOracle이 제공하는 신뢰

현재 `PriceOracle`은 현실 가격의 진실성을 블록체인이 독립적으로 증명하는 장치가 아니다. 블록체인이 보장하는 것은 허가된 소유자가 특정 가격을 등록했고, 이후 Vault가 그 공개된 공통 상태로 결정론적으로 계산했다는 사실이다.

기존 역할은 다음과 같다.

- Vault가 사용할 하나의 온체인 공식 가격 제공
- 일반 사용자가 거래 가격을 임의로 전달하지 못하게 차단
- 가격 변경 권한을 Oracle 소유자로 제한
- 모든 노드가 동일한 값으로 거래 결과를 계산하도록 보장
- 가격 변경 트랜잭션과 `PriceUpdated` 이벤트를 통한 감사 근거 제공

따라서 정확한 표현은 `블록체인이 검증한 현실 가격`이 아니라 `권한 있는 운영자가 등록한 검증 가능한 온체인 공식 가격`이다.

## 3. 블록체인 지연에 대한 핵심 이해

블록체인은 외부 가격 관측부터 블록 포함까지의 네트워크 지연을 없앨 수 없다. 그러나 Oracle 갱신과 거래를 하나의 트랜잭션 내부 호출로 묶으면 두 온체인 작업 사이의 별도 확정 대기는 없앨 수 있다.

```text
기존: 가격 갱신 tx 확정 → 시간 경과 → 거래 tx 확정

개선: 하나의 거래 tx
      ├─ 가격 보고서 검증
      ├─ Oracle 가격 반영
      └─ 같은 가격으로 Vault 정산
```

한 트랜잭션 내부에서는 모든 작업이 함께 성공하거나 함께 되돌려진다. 이 원자성으로 `Oracle만 갱신되고 거래는 실행되지 않은 중간 상태`와 `갱신 후 거래 전 다른 Oracle 가격이 끼어드는 상태`를 제거할 수 있다.

## 4. Push Oracle과 Pull Oracle

현재는 가격을 미리 체인에 보내는 Push 방식이다.

```text
가격 관측 → Oracle에 미리 저장 → 나중에 거래가 저장값 사용
```

합의한 개선 방향은 거래할 때 서명 가격을 함께 제출하는 Pull 방식이다.

```text
가격 관측 → 가격 보고서 서명 → 거래 요청에 포함
         → 컨트랙트가 검증 → 동일 가격으로 즉시 정산
```

이 방식은 블록체인을 빠르게 만드는 것이 아니다. 사용자가 확인한 가격 보고서와 컨트랙트가 소비하는 가격을 동일한 데이터로 만들어, 그사이에 다른 저장 가격을 읽는 문제를 제거한다.

## 5. 합의한 목표 구조

```text
Toss 시장 데이터
        ↓
백엔드 가격 보고서 생성
├─ symbol
├─ priceE8
├─ observedAt
├─ validUntil
├─ quoteId
├─ side
└─ inputAmount
        ↓ 가격 전용 키 서명
서명된 가격 보고서
        ↓ 거래 트랜잭션에 포함
PriceOracle·ExchangeVault
├─ 허가된 서명자 검증
├─ 체인과 대상 컨트랙트 검증
├─ 관측 시각·만료 검증
├─ 주문 내용과 보고서 일치 검증
├─ 보고서 재사용 방지
├─ 검증된 가격 기록
└─ 동일 가격으로 자산 정산
```

현재 방향에서는 `PriceOracle`을 제거하지 않는다. 역할을 `미리 푸시된 최신 숫자 저장소`에서 다음을 담당하는 `가격 검증기 겸 마지막 승인 가격 기록소`로 발전시킨다.

- 허용된 가격 서명자 관리
- 서명된 외부 가격 보고서 검증
- 관측 시각과 만료 정책 강제
- 마지막으로 거래에 승인된 가격과 시각 기록
- `PriceUpdated` 이벤트를 통한 감사 근거 제공

`ExchangeVault`는 Oracle이 같은 트랜잭션에서 검증한 가격으로만 거래를 정산한다.

## 6. 해결되는 문제와 남는 문제

### 해결되는 문제

- 사용자가 확인한 서명 가격과 컨트랙트 체결 가격을 일치시킬 수 있다.
- Oracle 갱신과 거래 사이에 다른 가격이 끼어들지 않는다.
- 거래 실패 시 같은 트랜잭션의 Oracle 변경도 함께 되돌릴 수 있다.
- 만료된 견적은 다른 가격으로 조용히 체결하지 않고 실패시킬 수 있다.
- 가격 출처·관측 시각·거래 조건을 온체인 검증 대상으로 만들 수 있다.

### 남는 문제

- Toss 관측부터 블록 포함까지 현실 시간은 계속 흐른다.
- 블록체인은 Toss API 자체를 직접 검증하지 못하고 등록된 가격 서명자를 신뢰한다.
- 오래된 가격이 사용자에게 유리하면 Vault 준비금을 대상으로 차익 거래가 가능하므로 freshness와 만료가 필수다.
- 단일 가격 서명자는 중앙화된 신뢰 지점이다. 복수 공급자·다중 서명·중앙값은 MVP 이후 범위다.

따라서 목표는 `블록 포함 순간의 현실 가격과 항상 동일한 체결`이 아니다. 목표는 `사용자가 확인한 충분히 최신인 서명 가격으로 체결하거나, 조건이 맞지 않으면 실패`하는 것이다.

## 7. 필수 방어 조건

- 짧은 `validUntil`
- 허용 가능한 최대 관측 지연
- 미래 관측 시각과 0원 가격 거부
- 체인 ID와 검증 컨트랙트 주소를 포함한 EIP-712 도메인 분리
- 주문 방향·입력 금액을 가격 보고서에 결합
- 주문별 `quoteId`와 재사용 방지
- 매수 최대 가격·매도 최소 가격 또는 최소 수령량
- 가격 급변 시 거래 중단 정책
- 가격 서명 키와 거래 전송 키의 역할 분리
- 실패한 주문의 DB 자산 잠금 해제와 재견적 안내

### Phase 5.1 확정값

- EIP-712 domain name: `TokenizedMarketPriceOracle`
- EIP-712 domain version: `1`
- 가격 보고서 유효시간: 30초
- 보고서 발급 시 허용하는 최대 Toss 관측 지연: 5초
- 체인 검증 시 허용하는 미래 관측 시각 오차: 2초
- 가격 보고서는 주문별 일회용이며 `quoteId` 재사용을 금지한다.
- 가격 서명 키와 거래 트랜잭션 전송 키는 서로 다른 역할과 키로 분리한다.
- `priceE8`은 8 decimals, `inputAmount`와 `minimumOutput`은 각 ERC-20의 18 decimals 정수, 시각은 Unix seconds다.
- 매수는 `side=0`, 매도는 `side=1`이다.

확정한 EIP-712 struct 문자열은 다음과 같다. 필드 순서나 타입 변경은 서명 호환성을 깨뜨리므로 domain version을 올리는 별도 변경으로 취급한다.

```text
PriceReport(bytes32 quoteId,bytes32 symbolHash,uint256 priceE8,uint256 observedAt,uint256 validUntil,uint8 side,uint256 inputAmount,uint256 minimumOutput,address executor)
```

도메인은 `name`, `version`, `chainId`, `verifyingContract`를 사용한다.

### 필드별 위협 대응

| 필드 | 방어 대상 |
| --- | --- |
| `quoteId` | 동일 주문별 보고서의 재사용·중복 정산 |
| `symbolHash` | mSEC용 가격을 다른 상품에 사용하는 교차 상품 공격 |
| `priceE8` | 컨트랙트가 사용할 서명된 정확한 가격 |
| `observedAt` | 오래되거나 미래인 시장 관측값 |
| `validUntil` | 블록 포함이 늦어진 만료 견적 |
| `side` | 매수 견적을 매도에 사용하는 방향 변경 |
| `inputAmount` | 서명 후 주문 수량 변경 |
| `minimumOutput` | 가격이 같아도 수수료·반올림 변경으로 수령량이 줄어드는 경우 |
| `executor` | 보고서를 다른 운영자·호출자가 탈취해 사용하는 경우 |
| `chainId` | 다른 체인에서의 서명 재사용 |
| `verifyingContract` | 다른 Oracle·검증 컨트랙트에서의 서명 재사용 |

### Java·Solidity 공통 테스트 벡터

Phase 5.1은 체인 ID `31337`, 검증 컨트랙트 `0x1111...1111`과 고정 보고서 입력을 사용해 다음 값을 양쪽에서 동일하게 검증한다.

```text
digest: 0x17997e214c5c57f7030b1c2f588f97a3e4353a383135e96714018aafad246b3e
signer: 0xe05fcC23807536bEe418f142D19fa0d21BB0cfF7
```

테스트 키는 고정 벡터 재현만을 위한 값이며 운영 키로 사용하지 않는다.

## 8. 새 Phase 제안

이 변경은 컨트랙트, 백엔드 서명·web3j, 배포와 웹 거래 UX를 함께 바꾸므로 별도 Phase로 진행한다.

### Phase 5.1 — 가격 보고서 계약과 위협 모델 — 완료

- EIP-712 타입과 도메인 확정
- 일회용 주문별 보고서 정책 확정
- 시각·만료·가격 한도·재사용 규칙 확정
- 가격 서명자와 거래 운영자 권한 분리 설계
- 테스트 벡터로 Java와 Solidity가 동일 digest를 계산하는지 검증

### Phase 5.2 — 컨트랙트 검증과 원자적 정산 — 진행 중

- Phase 5.2-A 완료: `PriceOracle` 서명 검증·freshness·승인 가격 기록
- `ExchangeVault`의 검증 가격 기반 매수·매도
- 동일 트랜잭션 원자성, replay와 잘못된 서명 방어
- Foundry 단위·fuzz 테스트와 배포 스크립트 갱신

### Phase 5.3 — 백엔드 가격 보고서와 주문 연동

- Toss 스냅샷 기반 가격 보고서 생성·서명
- 견적 응답과 주문 요청 계약 확장
- web3j 인코딩·전송·receipt 정산과 실패 복구
- 서명 키 환경 설정과 비밀정보 관리

### Phase 5.4 — 웹 종단간 검증

- 시장 가격·서명 가격·관측/만료 시각·최종 온체인 가격 표시
- 정상 체결, 만료, 변조, replay, 가격 한도 실패 시연
- PostgreSQL·Toss·Anvil을 포함한 전체 흐름 검증과 문서화

Android 구현은 이 Phase의 완료 조건이 아니며, 검증된 REST·WebSocket 계약을 추후 담당자에게 전달한다.

## 9. 아직 확정하지 않은 세부 값

다음 값은 각 구현 마일스톤 승인 전에 사용자와 확정한다.

- 가격 한도를 시스템 고정값으로 둘지 사용자가 선택할지
- 가격 서명 키의 생성·보관·회전 방식
- `PriceOracle`이 서명 검증까지 수행할지 별도 검증 컨트랙트를 둘지
- 보고서가 정확한 출력량까지 고정할지 가격과 입력량만 고정할지
- 급격한 가격 변동을 판단하는 circuit breaker 기준

## 10. Phase 5.2-A 구현 결과

`PriceOracle`은 EIP-712 domain의 `verifyingContract`가 되며 다음 검증을 수행한다.

```text
validUntil == observedAt + 30
observedAt <= block.timestamp + 2
block.timestamp <= validUntil
```

- `authorizedConsumer`로 등록된 Vault만 보고서를 소비할 수 있다.
- `priceSigner`와 `authorizedConsumer`는 소유자만 0이 아닌 주소로 변경할 수 있다.
- `supportedSymbolHash`와 다른 상품, 0원, 0 `quoteId`, 잘못된 서명과 domain을 거부한다.
- 성공적으로 소비한 `quoteId`는 replay를 막기 위해 기록한다. 이후 같은 트랜잭션의 Vault 정산이 실패하면 EVM 원자성에 의해 이 기록도 rollback된다.
- `priceObservedAt`은 외부 시장 관측 시각, 기존 `updatedAt`은 온체인 반영 블록 시각으로 분리한다.
- 기존 소유자 `updatePrice()`는 Phase 5.3 백엔드 전환 전 호환을 위해 유지하며 블록 시각을 관측 시각으로 기록한다.
- Phase 5.2-A만으로는 거래가 원자화되지 않는다. Vault 연결은 Phase 5.2-B 범위다.
