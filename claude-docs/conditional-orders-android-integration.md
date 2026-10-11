# 조건부 주문 클라이언트 계약

> 진행 중인 기능 브랜치 계약. 기존 즉시 주문 API는 변경하지 않는다. Android `android/app/src/main/java/com/pricetrack/exchange/data/remote/ApiServices.kt`를 읽어 접근 가능함을 확인했지만 이번에 수정·빌드·실기기 검증하지 않았다.

- 모든 사용자 경로는 JWT가 필요하다. 대상 사용자 ID는 받지 않는다. 금액·가격은 정확한 decimal 문자열을 권장한다.
- `GET /api/conditional-orders/capabilities`: `registrationEnabled`. 비활성이면 등록 UI를 닫되 이력 조회는 유지한다.
- `POST /api/conditional-orders`, `Idempotency-Key` 필수(영문/숫자/underscore/hyphen16~128자). body 예: `{"symbol":"mSEC","side":"BUY","inputAmount":"100000","targetPrice":"75000","expiresAt":"2026-11-01T01:00:00Z"}`. 시간은 초 단위 UTC ISO8601이며 실행 개시 만료 기한이다. 응답201, 성공 키 재전송도 동일 의도 현재 상태를 반환한다. 같은 키·다른 내용409. 키 원문은 서버 DB에 저장하지 않는다.
- BUY는 수수료 포함 총 mKRW, SELL은 mSEC 수량이다. 토큰 최대18/가격 최대8 decimals. 등록 시 견적과 자산 잠금이 없다. 실행 시 잔고 부족으로 실패할 수 있다.
- `GET /api/conditional-orders`, `GET /api/conditional-orders/{id}`: 본인 이력/상세. 목록 `{items,nextBefore}`, 다음 페이지 `?before=nextBefore`(40개/keyset). `GET /api/conditional-orders/requests`에 원래 멱등키 헤더로 응답 유실 등록을 확인한다. 404는 새 키 자동 재등록 근거가 아니다. 원래 body·키를 보존하고 명시적으로 재시도한다.
- POST가 확정 validation400(`INVALID_CONDITIONAL_ORDER`/`INVALID_EXPIRY`/`INVALID_IDEMPOTENCY_KEY`)으로 거절되면 해당 요청은 지급/주문 등록되지 않았으므로 입력을 수정해 새 키로 등록할 수 있다. 네트워크/5xx/알 수 없는 proxy400은 확정 거절로 취급하지 않는다. 세션 교체 후 도착한 응답으로 이전 요청 키나 현재 화면을 변경하지 않는다.
- `POST /api/conditional-orders/{id}/cancel`: 실행 연결 전 대기/준비 중만 가능. 이미 실행됐으면409. 성공 여부 불확실한 거래를 취소/실패로 단정하지 않는다.
- 응답 `state`: WAITING/PREPARING/EXECUTING/SUCCEEDED/FAILED/CANCELLED/EXPIRED/REVIEW_REQUIRED. `reasonCode`는 안전한 고정 코드다. `executionOrderId`가 있으면 기존 주문/체결 상세를 조회한다. 조건부 ID는 UUID이며 기존 실행 Order 숫자 ID와 다르다.
- STOMP `/user/queue/conditional-orders`, `type=CONDITIONAL_ORDER_UPDATED`가 commit 후 상태를 통지한다. 재연결 시 REST 이력을 다시 조회한다. 다른 사용자의 결과/로그아웃 이후 늦은 응답을 적용하지 않는다.
- ADMIN `GET /api/admin/conditional-orders`는 읽기 전용 동일 페이지 계약. 강제 실행·취소 우회·잔고 보정 API는 없다.

목표 조건은 서버의 최신 기준 시세 및 새 EIP-712 서명 가격에 대한 조건이다. 조건 충족은 실행 성공 보장이 아니다. 서명 보고서의 짧은 유효기간 중 실제 기초자산 시세는 변할 수 있다. 주문장·부분 체결·시간 지정 예약 주문은 없다. public Synthetic에서는 비활성이고 Synthetic 조건부 실행은 신규 격리 fixture 전용이다.
