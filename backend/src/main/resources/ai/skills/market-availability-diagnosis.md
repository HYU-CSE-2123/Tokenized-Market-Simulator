---
id: market-availability-diagnosis
name: 시장 가용성 진단
purpose: 단일 가격 스냅샷의 시장 상태 신선도와 발급 규칙 설명
version: 1
allowedRoles: [ADMIN, USER]
preconditions: [NONE_TARGET_REQUIRED, OWNED_OR_ADMIN_AUTHORIZED]
allowedTools: [getCurrentReferencePrice]
retrievalDomains: [market, trading]
steps: [LOAD_REFERENCE, CHECK_EVIDENCE, RETRIEVE_POLICY, SUMMARIZE]
stopConditions: [AUTHORIZATION_DENIED, INVALID_LINK, DEADLINE_EXCEEDED]
forbiddenActions: [WRITE_DB, SIGN, BROADCAST, FORCE_SETTLEMENT, CHANGE_BALANCE]
outputSchema: diagnostic-v1
---
# 시장 가용성 진단

한 번의 가격 조회에 담긴 시장 상태 가격 상태 관측 시각과 공급자를 함께 사용한다.
SIMULATED와 실시간 공급자를 구분하고 LIVE만으로 견적 발급 성공을 보장하지 않는다.
현재 CLOSED 또는 STALE을 과거 주문 거부의 확정 원인으로 설명하지 않는다.
새 견적이나 주문을 발급하지 않고 허용된 시장 거래 정책만 검색한다.
