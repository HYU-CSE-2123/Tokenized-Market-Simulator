---
id: settlement-debugging
name: 온체인 정산 조사
purpose: 주문 견적 전송 receipt와 정산 규칙의 관계 확인
version: 1
allowedRoles: [ADMIN]
preconditions: [ORDER_TARGET_REQUIRED, OWNED_OR_ADMIN_AUTHORIZED]
allowedTools: [getOrder, getQuote, getBlockchainTransaction, getReceiptSummary]
retrievalDomains: [trading, settlement, operations, support]
steps: [LOAD_ORDER, CHECK_ORDER, LOAD_LINKED_QUOTE, LOAD_TRANSACTION, LOAD_RECEIPT, CHECK_EVIDENCE, RETRIEVE_POLICY, SUMMARIZE]
stopConditions: [AUTHORIZATION_DENIED, INVALID_LINK, DEADLINE_EXCEEDED]
forbiddenActions: [WRITE_DB, SIGN, BROADCAST, FORCE_SETTLEMENT, CHANGE_BALANCE]
outputSchema: diagnostic-v1
---
# 온체인 정산 조사

인가된 주문을 재사용하고 연결 견적을 확인한 뒤 전송과 receipt를 읽는다.
연결 불일치에는 후속 조회를 중단한다. 현재 관측과 과거 원인 추론은 분리한다.
체인 성공과 DB 확정은 다르며 누락된 receipt나 오류 원인을 추측하지 않는다.
승인된 domain의 정책만 조회한다. 재전송, 서명, 정산 또는 자산 변경을 실행하지 않는다.
