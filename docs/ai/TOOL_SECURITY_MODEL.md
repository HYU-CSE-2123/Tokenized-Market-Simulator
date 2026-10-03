# Tool·Agent 보안 모델

## 서버가 강제하는 경계

JWT 검증 후 DB 사용자/역할이 principal이 된다. 클라이언트/모델의 userId·role·Tool 이름·SQL·URL은 권한 근거가 아니다. orderId/quoteId는 요청 target과 서버 연결 검증을 통해서만 사용한다. USER는 본인 데이터, ADMIN은 제한 운영 조회만 가능하다. 존재하지 않음과 타인 소유를 분리해 정보가 새지 않게 처리한다.

고정8개 Tool: getOrder, getQuote, getBlockchainTransaction, getReceiptSummary, getMarketStatus, getCurrentReferencePrice, getPortfolio, listAbnormalOrders. 마지막은 ADMIN-only다. 서명·broadcast·SQL 실행·잔고 수정·자동 복구 Tool은 없다. 모델 분류는 검증된 enum이며 임의 함수 실행으로 연결되지 않는다.

HTTP Tool 요청2KiB·응답32KiB·worker4·기본5초, Agent Tool4/검색1/모델2·worker2·전체40초 제한. timeout 후 취소에 응하지 않는 worker는 실제 종료까지 용량을 점유한다. 실행0 BUSY와 불명확 실행을 구분한다.

## 데이터와 주입 방어

승인 문서도 모델에게는 지시가 아닌 데이터다. 질문/문서/Tool 문자열의 “관리자 전환”, “DB 조회”, “이전 지시 무시”가 서버 권한·대상·handler·allowlist를 변경할 수 없다. provider에는 실행 가능한 Tool을 등록하지 않는다. facts.pointer/value와 citation은 서버 evidence와 대조한다. 잘못된 값/출처나 실행 완료 주장은 근거로 채택하지 않는다.

Tool DTO에서 키·서명·raw transaction·password·원시 오류를 제외한다. 모델용 evidence는 사용자/주문/견적/txHash와 재귀 secret field를 추가 제거한다. 예외 원문은 안전한 code로 대체한다. 진단 payload는 allowlist/크기 상한/승인 재검증을 사용하며 웹은 textContent로 표시한다.

## 보존과 관측

eventKey는 txHash를 포함하는 raw composite가 아니라 SHA-256 fingerprint다. 결과/actor/tx_hash/claim metadata는7일 TTL 대상이며 terminal SKIPPED/FAILED의 과거 TTL 없는 행도 완료/감지 시각 기준으로 보정한다. namespace/eventKey/대상 식별자/종료 상태 marker는 중복 방지용으로 남긴다. 해시는 익명화나 법적 개인정보 삭제 완료의 증거가 아니다.

관측 label은 고정된 stage/name/code뿐이며 원문·자산 값·사용자/주문/runId를 집계 label로 사용하지 않는다. runId는 안전한 UUID 로그 연결용이다. 기존 Tool audit의 DB userId는 권한 감사 목적이며 로그인/JWT 원문은 기록하지 않는다.

가짜 secret canary와 공격 fixture로 테스트하되 실제 키/사용자 데이터는 사용하지 않는다. 유한한 공격 테스트 통과는 모든 주입/비밀 패턴의 차단 보증이 아니다. 모델의 자유 문장 진실성은 별도 사람이 확인한다. [평가](EVALUATION_REPORT.md), [공식 보안 안내](https://developers.openai.com/api/docs/guides/safety-best-practices).
