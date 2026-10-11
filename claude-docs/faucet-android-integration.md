# Android Faucet 연동 계약

> 2026-10-11. 서버 기능·격리 PostgreSQL/Anvil 검증·통합 회귀를 완료했다. Android 소스는 접근했지만 수정하거나 테스트하지 않았다. 기존 ApiServices.kt에는 Faucet API가 없다. 실제 Android 연동은 다음 계약을 적용하고 검증해야 한다. 상세 [구현 기록](faucet-policy-implementation.md).

1. 인증된 사용자의 POST `/api/wallet/faucet`에 `Authorization: Bearer ...`와 `Idempotency-Key`를 보낸다. 키는 UUID 등 영숫자/하이픈/밑줄16..128자. body는 없음 또는 `{}`. 금액/userId를 보내지 않는다.
2. 서버 정책은1회1,000,000 mKRW, KST 하루1회, 누적5회, 전체50,000,000 예산이다. ADMIN도 우회하지 못한다. 현재 잔고·이메일 인증을 새 조건으로 쓰지 않는다.
3. 사용자가 지급을 선택했을 때 계정별 pending key를 로컬 안전한 저장소에 보존한다. 네트워크 오류/앱 종료/로그아웃으로 결과가 불확실하면 새 키로 자동 재지급하지 않는다. 명시적 재시도도 동일 key를 사용한다.
4. GET `/api/wallet/faucet/claims`에 같은 header로 결과를 확인한다.200이면 기존 성공,404이면 아직 성공 근거가 없음(즉시 새 key 발급의 근거 아님). 계정 전환 시 다른 계정의 pending key를 보내지 않는다.
5. 성공 응답은 `symbol`, `receivedAmount`, `balance`, `grantId`, `grantedAt`, `policyVersion`, `replayed`. 금액을 Double로 회계 처리하지 않는다. replay balance는 최초 지급 당시 값이므로 최신 잔고는 GET `/api/portfolio`로 다시 읽는다.
6. GET `/api/wallet/faucet/status`의 eligible/reason/remainingCount/decisionDate/nextEligibleAt를 표시한다. 지급 가능 안내는 POST 결과를 보장하지 않는다. 다음 날짜는 Asia/Seoul 자정이고 별도 cooldown은 없다.
7. 본인 지급 이력 GET `/api/wallet/faucet/grants?before=<nextBefore>`는20개 페이지. raw key를 URL·분석 이벤트·일반 로그에 남기지 않는다. JWT도 로그에 남기지 않는다.
8.400 잘못된 키/요청,429 당일 제한,409 누적/예산/키 충돌,503 정책/회계/DB 장애.401이면 세션을 갱신하되 성공 여부가 불확실한 지급 intent는 보존한다. 늦게 온 응답은 요청 시작 시 계정/session generation이 현재와 같을 때만 반영한다.

개인 WS는 새 지급 commit 후만 전달하며 replay에서는 새 알림이 없다. WS 미수신만으로 지급 실패라고 판단하지 않는다. DB 지급은 온체인 토큰 공급/ETH 충전을 하지 않는다. Android의 다른 기존 DTO·계약 스캐폴딩 문제는 이번 Faucet 변경으로 수정 완료했다고 주장하지 않는다.
