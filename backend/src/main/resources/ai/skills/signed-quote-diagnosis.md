---
id: signed-quote-diagnosis
name: 서명 견적 진단
purpose: 견적의 만료 소비와 연결 주문 정산 관측 분리
version: 1
allowedRoles: [ADMIN, USER]
preconditions: [QUOTE_TARGET_REQUIRED, OWNED_OR_ADMIN_AUTHORIZED]
allowedTools: [getQuote, getOrder, getBlockchainTransaction, getReceiptSummary]
retrievalDomains: [trading, settlement, support]
steps: [LOAD_QUOTE, LOAD_LINKED_ORDER, LOAD_TRANSACTION, LOAD_RECEIPT, CHECK_EVIDENCE, RETRIEVE_POLICY, SUMMARIZE]
stopConditions: [AUTHORIZATION_DENIED, INVALID_LINK, DEADLINE_EXCEEDED]
forbiddenActions: [WRITE_DB, SIGN, BROADCAST, FORCE_SETTLEMENT, CHANGE_BALANCE]
outputSchema: diagnostic-v1
---
# 서명 견적 진단

인가된 견적의 storedStatus와 expiredByTime을 독립 관측으로 기록한다.
서버가 검증한 연결 주문만 조회하고 역방향 연결과 종목 방향 입력 단위를 확인한다.
현재 만료는 과거 거래 실패 원인이 아니며 CONSUMED는 체결 완료를 뜻하지 않는다.
서명 원문과 키를 조회하지 않고 한 번의 정책 검색과 근거 종합만 수행한다.
