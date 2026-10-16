# SIGNED/SUBMITTED 정체 거래 관측 설계 — 구현 승인 대기

> 사용자 후속 승인(2026-10-12): **①+② 구현 승인**. 신규 ADMIN 조회/최신 관측 snapshot/300초 LONG_WAIT를 기능 브랜치에서 구현·격리 검증·별도 검토 후 통합한다. 외부 Grafana/Prometheus는 제외한다. 기존 REVIEW_REQUIRED·복구·raw/nonce·잔고·계약은 유지한다. 아래 미승인/미실행 표시는 설계 제출 당시 이력이며 완료 여부는 후속 구현 기록에서 확인한다.

> 2026-10-12 / 기준 `integration/additional-features` `c7bf4e83ede98fbcfb661f7e5ab7bfe35607ab9b`. A 모델 안내·문서·표시 테스트 13개 파일을 이 커밋으로 확정했다. 이 설계는 코드/테스트 읽기 전용 조사이며 구현, DB migration, RPC 호출, 기존 환경 변경은 하지 않았다.

## 1. 현재 구현으로 이미 되는 것

| 근거 | 실제 구현 |
|---|---|
| [BlockchainTransactionSender](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java) | 단일 프로세스 synchronized 전송/복구. operator의 pending nonce와 gasPrice, 실제 calldata Gas 추정, 120% gasLimit, raw 서명, 계산 txHash 저장 후 전송 |
| [BlockchainTransactionPersistence](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionPersistence.java) | SIGNED는 RPC보다 먼저 REQUIRES_NEW 커밋. 제출 확인 시 SUBMITTED/Order PENDING_ONCHAIN 및 submittedAt 저장 |
| [BlockchainReconciliationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java) | SIGNED 조회/동일 원문 재전송, SUBMITTED receipt polling, 확인 수 대기, receipt 실패/성공 정산, 이벤트·정산 불일치 격리 |
| [Reconciliation 설정](../backend/src/main/java/com/pricetrack/exchange/blockchain/config/BlockchainReconciliationProperties.java), [application.yml](../backend/src/main/resources/application.yml) | 기본 initial delay 1초, fixedDelay 1초, requiredConfirmations 1. 설정 가능. 한 배치 실행이 끝난 뒤 지연이 시작되므로 거래마다 정확히 1초 재시도를 보장하지 않음 |
| [OnchainSettlementService](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java) | 확인된 실패 receipt는 입력 잠금 해제, 검증된 성공은 DB 정산. REVIEW_REQUIRED는 거래 상태/원문 증거를 보존하고 잠금 유지 |
| [ConditionalOrderWorker](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderWorker.java), [서비스](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderService.java) | 기본 poll 1초/초기 5초, 별도 단일 worker. 실행 Order 연결 후 새 주문을 만들지 않고 refresh. SIGNED/SUBMITTED이면 EXECUTING/AWAITING_RECEIPT, 실제 tx 검토 상태면 REVIEW_REQUIRED |

```text
DB 준비(입력 잠금) → SIGNED(raw/nonce/hash 보존) → SUBMITTED → CONFIRMED / FAILED
                          ↳ 동일 raw 조회/재전송       ↳ receipt 없음: 대기
                                                     ↳ 증거 불일치: REVIEW_REQUIRED
```

SIGNED 복구는 저장된 txHash로 `eth_getTransactionByHash` 조회 후 없으면 **같은 raw tx**를 재전송한다. 이미 존재하거나 `already known`이면 제출로 기록한다. 새로운 nonce·주문·서명을 만들지 않는다. SUBMITTED는 receipt가 없거나 확인 수가 모자라면 대기한다. 일반 RPC/RuntimeException은 경고 로그 후 다음 배치에서 재시도한다. 현재 최대 정체 시간·종결 retry budget은 없다.

REVIEW_REQUIRED는 이벤트/정산 증거가 일치하지 않아 자동 정산에서 제외하는 의미다. polling 대상이 SIGNED/SUBMITTED이므로 이를 단순 시간 초과에 사용하면 복구가 멈추고 기존 AI 자동 진단 대상도 바뀐다. **이번 제안은 REVIEW_REQUIRED를 변경하지 않는다.** Order가 항상 REVIEW_REQUIRED로 동시 전환되는 것으로 가정하지 않는다: 현재 `markReviewRequired`는 BlockchainTransaction을 변경하고 조건부 주문은 refresh에서 이를 반영한다.

## 2. 기존 시간과 ADMIN 조회의 재사용 범위

[BlockchainTransaction](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransaction.java)에 `createdAt`, `submittedAt`, `confirmedAt`이 있다. 마지막 복구 시도·재시도 횟수·최근 RPC 실패 분류는 없다. `errorMessage`는 정산 실패/검토 정보이며 일반 polling 실패의 최신 기록이 아니다. 마지막 시도 시각을 submittedAt이나 조건부 주문 updatedAt으로 대체하면 잘못된 표시가 된다.

이미 [ToolRegistry](../backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolRegistry.java)에 `listAbnormalOrders(filter=WAITING_LONG)`이 있다. [ToolReadFacade.abnormal](../backend/src/main/java/com/pricetrack/exchange/ai/tool/read/ToolReadFacade.java)는 SIGNED/SUBMITTED 중 `coalesce(submittedAt,createdAt) <= now - waitingSeconds`를 조회한다. 기본 300초, 허용 60~3600초, limit 1~20, 생성 시각/id keyset cursor. ADMIN 전용이며 목록은 txHash를 빼고, 상세 `getBlockchainTransaction(orderId)`는 created/submitted/confirmed 시각을 제공한다.

[ToolController](../backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolController.java)의 `POST /api/ai/tools/listAbnormalOrders` 예시 계약은 `{"arguments":{"filter":"WAITING_LONG","waitingSeconds":300,"limit":10}}`이다. 이는 조회 POST이며 거래 실행이나 LLM 호출이 아니다. [ToolProperties](../backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolProperties.java)의 tools enabled 경계는 있지만 RAG/AI DB/provider key와 독립이다. 기존 USER 주문 API는 본인 주문만 조회하고 ADMIN 전체 거래 대시보드를 제공하지 않는다.

기존 Tool 목록은 Order inner join이므로 orderId 없는 시스템 거래나 연결이 손상된 거래를 놓칠 수 있다. 처음 보완 대상은 BUY/SELL이지만 ADMIN 관측 전체 coverage에는 **고아 거래를 숨기지 않아야 한다.** 기존 API 의미를 바꾸지 않고 별도 거래 관측 query에서 전체 SIGNED/SUBMITTED와 optional Order/조건부 주문 연결을 조회하는 편이 낫다. 시스템 타입은 구분 표시하고 거래로 오인하지 않는다.

조건부 주문의 실행 연결 키는 `executionOrderId`다. 정체 시간은 장기 의도 등록일/updatedAt이 아니라 연결 BlockchainTransaction 기준이다. EXECUTING의 expiry 도달만으로 실패·취소·잠금 해제를 하지 않는다.

## 3. 원인 분류: 사실과 가설을 구분

| 관측 | 표시 가능한 사실 | 단정하면 안 되는 것 |
|---|---|---|
| 최근 send 응답이 insufficient funds | 해당 시도의 노드가 자금 부족을 이유로 거절했다고 보고 | tx가 다른 노드/이전 시도에 절대 제출되지 않았다는 결론 |
| operator ETH 현재 잔고/서명 원문의 gasLimit·gasPrice 조회 | 동일 관측 시각의 잔고와 legacy tx 최대 비용 비교 참고 | 미래 Gas 충분, 모든 주문 실행 가능, 실패 확정 |
| RPC timeout/연결 실패 | 해당 조회/전송 결과 확인 불가 | 미채굴·미전송·ETH 부족 중 하나로 확정 |
| tx 조회 존재 + blockNumber 없음 + receipt 없음 | 해당 RPC가 pending tx를 알고 있음 | 최종 실패 또는 언제 채굴될지 보장 |
| tx 조회 없음 + receipt 없음 | 해당 RPC에서 거래를 찾지 못함 | 영구 유실/안전한 nonce 재사용 가능 |
| receipt 존재, 확인 수 부족 | 채굴 관측 및 confirmation 대기 | 최종 성공 정산 완료 |
| latest/pending nonce 변화 | 해당 노드의 sender nonce 관측 참고 | 저장 tx의 성공/대체 여부 확정; tx/receipt 증거 필요 |

현재 polling은 RPC 오류 텍스트를 로그에 남기지만 typed 원인/관측 timestamp 이력이 없다. 기존 [ReadOnlyReceiptClient](../backend/src/main/java/com/pricetrack/exchange/ai/tool/receipt/ReadOnlyReceiptClient.java)는 bounded RPC로 receipt·확인 수·이벤트를 조회할 수 있으나 NOT_FOUND만으로 원인은 분류하지 않는다. [operator ETH 관측](../backend/src/main/java/com/pricetrack/exchange/reserve/RpcReserveChainReader.java)은 이미 존재하며 Gas 미추정 한계를 표시한다. 위 ETH/nonce 추가 상세 진단은 선택 확장으로 분리하고 첫 관측 구현에서 새로운 자동 RPC polling을 늘리지 않는 것을 추천한다.

## 4. 세 가지 개선안과 추천

| 안 | 변경 규모·재사용 | 한계·추천 |
|---|---|---|
| ① ADMIN 정체 시간/마지막 시도 | 기존 WAITING_LONG 조건·ADMIN UI/API/조회 접근 제어 패턴 재사용. 시간은 DB 조회만으로 계산, 마지막 시도는 작은 영속 관측 보완 필요 | 가장 작은 유효 범위. 거래 상태·서명·정산 그대로 유지 |
| ② 시간 초과 경고/확인 필요 표시 | ① 위에 파생 `attention=LONG_WAIT`/배지 추가 | 추천. **관측 상태이지 BlockchainTransactionStatus/OrderStatus가 아님**, 자동 복구 계속. 시간 초과만으로 AI/Skill 자동 실행하지 않음 |
| ③ Grafana/모니터링 경고 | bounded 집계·구조화 로그 패턴 재사용 가능 | 현재 Grafana/Prometheus 설정·Actuator/Prometheus 의존성은 확인되지 않음. 기존 외부 모니터링이 있는 것처럼 가정하지 않음. 새 서버/수집기·alert 전송 도입은 별도 승인 |

**추천: ①+②의 최소 관측 패널. ③은 현재 애플리케이션의 집계/안전 로그까지만 활용 가능하고, Grafana 구축은 후속 선택.** [AiObservability](../backend/src/main/java/com/pricetrack/exchange/ai/observability/AiObservability.java)는 bounded label·프로세스 counters·관측 오류의 업무 비영향 패턴을 참고할 수 있지만 AI 전용 counters에 거래 원장을 섞지 않는다. `/api/ai/observability`가 거래 정체 모니터링을 이미 제공하는 것은 아니다.

### 제안하는 첫 구현 범위 (미구현)

- ADMIN 전용 `GET /api/admin/transaction-observations`와 `GET /api/admin/transaction-observations/{id}`. status/attention/limit/cursor allowlist·keyset 조회. explicit ADMIN security matcher와 서비스 권한 확인을 함께 적용한다. USER 접근 금지. 기존 Tool/API 계약은 유지한다.
- DB `createdAt`부터 totalAgeSeconds, `submittedAt`부터 submittedAgeSeconds, DB 읽기 시각/서버 Clock과 5분 기준 LONG_WAIT 표시. **5분은 기존 Tool 기본값 재사용 제안**이지 체인 실패 timeout 확정이 아니다. threshold는 설정하고 화면에 적용값을 보여준다.
- 선택 필드 `conditionalOrderId`, 실행 Order 상태, 거래 상태, 마지막 기록된 시도 시각/phase/resultCode, 관측 freshness. nonce/txHash는 인증된 ADMIN 상세에만, raw tx·private key·서명·RPC URL/원문 오류는 노출하지 않는다.
- 별도 `blockchain_transaction_observations` 최신 snapshot 한 행/tx: transactionId(unique), lastAttemptStartedAt, lastAttemptFinishedAt, phase(SIGNED_RECOVERY/RECEIPT_CHECK), resultCode, attemptCount, recordedAt. 기존 행은 **미기록(null/UNRECORDED)**이지 실패 횟수 0으로 추정하지 않는다. 확정 거래 원장/잔고/Trade에 컬럼이나 상태 의미 변경을 넣지 않는다.
- 기존 recovery/polling 결과 관측 hook만 추가. RPC 구간 밖에서 시도 시작 시각을 기억하고 **완료 시 snapshot을 독립적·bounded best-effort 방식으로 기록**한다. 이것은 마지막 기록된 완료 시도이며 진행 중/프로세스 종료 직전 시도까지 완전하게 기록한 감사 로그가 아니다. 관측 실패·queue 포화는 업무 예외를 대체하지 않고 freshness/관측 실패 집계로 표시한다. 새 try/catch가 기존 복구·정산 예외를 삼키지 않도록 테스트한다.
- 성공 정상 polling은 compact snapshot 갱신, RPC 원문 대신 고정 resultCode allowlist. 서버 재시작 후 보존, 병렬 기록의 역순 완료는 더 오래된 시각으로 덮어쓰지 않도록 monotonic update. 최초 제출 실패의 마지막 복구 시도는 null일 수 있고 이를 ‘복구 실패’로 꾸미지 않는다.
- ADMIN 화면은 manual refresh(또는 탭 활성 시 제한된 polling), 오래된 거래 배지, ‘자동 복구 진행 중/확인 필요’ 안내와 연결 조건부 주문/기존 진단 링크만 제공한다. 강제 실패·unlock·nonce 폐기·raw 재서명·재주문 버튼은 없다. 유료 AI 자동 호출도 없다.
- 같은 거래의 경고 반복은 상태 변화/정체 진입 중심으로 억제하고 txHash/userId/nonce를 metric label로 사용하지 않는다. first scope는 기존 로그와 ADMIN 화면이며 외부 알림 채널은 구현하지 않는다.

규모 예상: 신규 관측 DTO/query/controller/recorder/config 및 schema 5~7개, 기존 recovery hook·security·웹 API/패널 4~6개, 관련 테스트/문서. 이는 구현 계획 추정이며 실제 변경 파일 수나 작업 시간 보장이 아니다. 추가 표는 업무 snapshot 대사의 자산 계산에 넣지 않고 AI DB와도 분산 transaction을 만들지 않는다. 거래 DB를 공유하므로 관측 쓰기에도 resource budget이 필요하며 ‘절대 장애 영향 없음’이라고 보증하지 않는다.

## 5. 승인 후 검증 계획

1. Clock 경계: 299/300/301초, created/submitted 두 나이, null 관측, 중복/역순 기록, restart 보존.
2. 기존 WAITING_LONG·ADMIN/USER 권한·고아/시스템 거래 표시, raw/key/오류 payload 비노출, pagination.
3. 격리 PostgreSQL/Anvil: SIGNED 유실 응답·동일 raw 재전송, SUBMITTED 미채굴/confirmation 대기, failed receipt·성공 정산, RPC 장애·ETH 부족 관측을 구분. 테스트로 확인하지 못한 cause는 UNKNOWN 유지.
4. LONG_WAIT 진입/해소 전후 Order/Trade/잔고·lockedAmount·nonce·raw/hash 불변(정상 recovery/receipt 정산에 의한 변화와 관측 쓰기를 분리). REVIEW_REQUIRED의 자동 재시도 제외 의미 유지.
5. 조건부 EXECUTING 경고가 expiry/cancel/새 quote·재주문을 유발하지 않음. 기존 Faucet/두 대사/AFTER_COMMIT WS 회귀.
6. 관측 DB 실패·queue 포화·역순 기록·모바일/로그아웃 응답 경합, backend/web/Foundry 회귀 및 독립 검토.

위 테스트는 **미실행 계획**이다. 이번 조사는 코드/기존 assertion을 읽은 것으로 실제 RPC 장애·ETH 부족을 새 재현한 것이 아니다. 현재 일반 send 실패 후 SIGNED가 계속 남는 위험을 관측하게 할 뿐 자동 해소하지 않는다. 구현 승인 대상은 ①+②/API·작은 snapshot·5분 초기 경고값/관측 자원 경계이며, ③의 외부 모니터링과 거래 종결 정책은 별도 승인 사항이다.
