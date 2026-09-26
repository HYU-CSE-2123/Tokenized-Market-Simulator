# AI Phase 1 — 지식 문서 작성 결과

> 작성: 2026-09-27 / 코드 기준: `30105a4`
> 상태: 작성·자체 검증 완료, 사용자 리뷰 대기

## 범위와 결정

사용자가 Phase 0 감사 결과를 승인하고 Phase 1 진행을 승인했다. ADR-001은 제안 상태로 유지한다. Spring AI 사용 여부, LLM/Embedding 공급자, pgvector 실제 도입 방식은 Phase 2 진입 전에 최종 결정한다. 이번 작업은 문서만 추가·수정하며 서버·DB·의존성·환경·ADR의 추천안을 변경하지 않는다.

## 지식 문서 목록

| 문서 | 용도 | minimum_role |
|---|---|---|
| [system-overview](../ai-knowledge/system-overview.md) | 시스템 책임, 운영자 지갑과 사용자 원장 | USER |
| [signed-quote-policy](../ai-knowledge/signed-quote-policy.md) | 보고서 필드·소유권·만료·최소 수령량 | USER |
| [order-lifecycle](../ai-knowledge/order-lifecycle.md) | 주문과 트랜잭션 상태 구분 | USER |
| [onchain-settlement-policy](../ai-knowledge/onchain-settlement-policy.md) | receipt·이벤트 검증과 DB 정산 | USER |
| [transaction-recovery-runbook](../ai-knowledge/transaction-recovery-runbook.md) | 전송 장애의 읽기 전용 운영 점검 | ADMIN |
| [market-data-policy](../ai-knowledge/market-data-policy.md) | 공급자·장 상태·신선도·차트 | USER |
| [authorization-policy](../ai-knowledge/authorization-policy.md) | 본인 조회와 향후 AI 인가 경계 | USER |
| [websocket-consistency-policy](../ai-knowledge/websocket-consistency-policy.md) | 알림과 REST 복구의 역할 | USER |
| [error-and-status-guide](../ai-knowledge/error-and-status-guide.md) | 실제 HTTP·상태·Solidity 오류 구분 | USER |

모든 문서에 title, domain, type, version, status, minimum_role, updated_at을 기록했다. version은 1이며 사용자 리뷰 전이므로 status는 draft다. active 전환 및 ingest 승인은 리뷰 이후 별도 반영한다. metadata 자체는 런타임 접근 제어가 아니며 검색 권한 필터는 미구현이다.

이 보고서·ADR·감사 보고서는 지식 문서 9개와 별개다. 저장소 전체나 이력·진행 보고서를 통째로 임베딩하지 않는다. 코드 링크는 근거 추적용이지 자동 코드 ingest 지시가 아니다.

## 대표 질문 점검

문서를 직접 읽고 아래 답변이 가능한지 자체 대조했다. LLM/RAG 평가를 실행한 결과가 아니다.

| 질문 | 문서에 기록한 답변과 확인 경계 |
|---|---|
| 왜 PENDING_ONCHAIN이 필요한가? | HTTP, 체인 실행, 확인 수, DB 정산의 시점이 다르기 때문이다. 대기는 성공·실패 확정이 아니다. |
| REVIEW_REQUIRED는 언제 생기는가? | 이벤트/정산 조건 불일치로 트랜잭션을 격리한다. 주문 상태·잠금은 유지하며 단순 RPC 지연과 다르다. |
| Oracle과 Vault가 각각 검증하는 것은? | Oracle은 서명·소비자·종목·시간·재사용, Vault는 방향·executor·입력·최소 출력과 유동성을 검증한다. |
| 견적은 왜 30초 뒤 만료되는가? | 오래된 관측 가격 사용을 제한하는 정책이다. 기준은 발급 시각이 아니라 observedAt이며 전송 완료를 보장하지 않는다. |
| WebSocket을 최종 상태로 신뢰하면 안 되는 이유는? | 영속 이벤트/replay 저장소가 아니므로 유실·재연결 뒤 개인 상태는 REST로 복구한다. |
| RPC 응답 유실 때 새 nonce로 재서명하지 않는 이유는? | 기존 거래가 이미 실행됐을 수 있어 별도 중복 거래가 될 수 있다. 기존 raw와 txHash로 복구한다. 이 답변의 상세 근거는 ADMIN runbook이다. |
| CLOSED인데 LIVE일 수 있는가? | Toss 휴장 시 구현상 가능하다. 시장 개장 여부와 가격 상태는 별개다. |
| 지금 내 주문이 왜 대기인가? | 문서만으로 특정 주문의 원인을 확정할 수 없다. 인가된 최신 조회가 필요하며 AI Tool은 아직 없다. |

## 코드·테스트 대조

- 견적 만료 경계·타인 견적·방향/입력·일회성: [PriceQuoteServiceTest](../../backend/src/test/java/com/pricetrack/exchange/quote/PriceQuoteServiceTest.java), 실제 PriceQuoteService·PriceReportIssuer 및 Oracle/Vault 코드.
- 신규 주문의 장/신선도 차단: [OrderMarketPolicyTest](../../backend/src/test/java/com/pricetrack/exchange/order/OrderMarketPolicyTest.java), MarketPriceService·TossPriceProvider.
- 정산 멱등성·실패 잠금 해제: [OnchainSettlementServiceTest](../../backend/src/test/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementServiceTest.java), 실제 reconciliation·event parser·settlement 코드.
- 소켓 인가: [WebSocketAuthInterceptorTest](../../backend/src/test/java/com/pricetrack/exchange/websocket/config/WebSocketAuthInterceptorTest.java), 실제 interceptor·delivery listener.
- 컨트랙트 보고서 검증: [PriceOracleSignedReport.t.sol](../../contracts/test/PriceOracleSignedReport.t.sol), [Exchange.t.sol](../../contracts/test/Exchange.t.sol).
- 웹 복구: [recovery.test.js](../../tools/websocket-test-client/test/recovery.test.js), [websocket.test.js](../../tools/websocket-test-client/test/websocket.test.js), 실제 복구·연결 코드.
- 현재 career-project-brief의 통합 지갑·서명 견적·미완료 범위와 대조했다. 과거 updatePrice 주석을 현재 체결 경로로 옮기지 않았다.

## 실행한 문서 검증

- PowerShell 검사: 지식 문서 정확히 9개, 필수 metadata 7개 존재, draft 상태, 문서 내 로컬 링크 50개 대상 존재, 줄 끝 공백·충돌 마커 없음.
- 실제 GlobalExceptionHandler의 오류 코드 16개가 표에 있는지 검사했고, 지식 문서와 보고서의 로컬 링크 총 67개가 존재함을 확인했다. HTTP 상태 매핑·상태 enum은 코드와 대조했다. 대표 질문 표는 사람 관점의 자체 검토 결과다.
- `git diff --check` 통과. 신규 지식 문서는 별도 공백·충돌 마커 검사로 확인했다.
- 제품 코드 변경이 없는 Phase 1이므로 지침에 따라 문서 검증을 수행한다. 애플리케이션 테스트·빌드·실DB·Anvil·LLM 호출은 이번에 재실행하지 않는다. Phase 0 통과 기록은 이번 검증 결과와 구분한다.
- 동작과 에이전트 작업 절차를 바꾸지 않는 문서 작성이므로 공통 지침의 생략 허용에 따라 별도 검토 에이전트는 호출하지 않았다.

## 남은 사항

사용자 리뷰가 완료돼야 Phase 1 완료 기준을 충족한다. Phase 2는 아직 시작하지 않았다. 다음에는 기술 선택·비용·DB 보존 방식을 합의한 뒤 embedding/검색 구현 범위와 검증 계획을 승인받는다.
