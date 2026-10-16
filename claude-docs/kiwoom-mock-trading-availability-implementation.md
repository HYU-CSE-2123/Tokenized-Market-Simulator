# 키움 모의 거래 가용성 구현 및 검증

> 2026-10-14. 사용자 승인 설계: `kiwoom-mock-trading-availability-design.md`. 실제 키움 재호출 없이 구현·격리 검증 완료. 실제 서버 연결 인수는 미실행이다.

## 변경한 경계

- `backend/src/main/java/com/pricetrack/exchange/market/TradingPriceContext.java`: 내부 시세 스냅샷과 거래 가용성, 제한 사유, 연결 generation을 함께 전달한다. 공개 MarketPriceSnapshot/DB에는 필드를 추가하지 않는다.
- `market/provider/MarketPriceProvider.java`, `market/MarketPriceService.java`: 공급자의 거래 근거를 읽는 경로를 추가한다. 기존 DB-only `requireTradableSnapshot`은 유지한다.
- `market/provider/kiwoom/KiwoomPriceProvider.java`: 현재 연결의 유효한 0B 정규 체결(290=2), LIVE, 과거 5초/미래 2초를 모두 요구한다. REST bootstrap, 이전 generation, 연결 종료, 시간외 체결은 허용 근거가 아니다. 명시적 CLOSED/지원하지 않는 상태는 체결보다 우선하며 같은 날짜 재연결로 지워지지 않는다.
- `blockchain/oracle/PriceReportIssuer.java`: 계산 직전과 RPC 이후의 거래 근거를 검사하고 공급자 또는 generation 변경 시 서명을 거절한다. 계산한 가격과 30초 TTL, executor, EIP-712 형식은 유지한다.
- `conditional/ConditionalOrderService.java`: 등록·감시·실행 준비의 시세 검사에 같은 거래 근거를 적용한다. 조건 충족 후 신규 Quote와 가격 조건 재검사는 그대로 유지한다.
- `market/MarketController.java`: REST 응답에 `mockQuoteAllowed`, `mockQuoteReason`만 추가한다. generation은 공개하지 않는다. 이 값은 시세 조건이며 잔고·유동성·최종 체결 보장이 아니다.
- 웹 `src/state.js`, `src/main.js`: UNKNOWN과 모의 견적 시세 가용성을 구분한다. 키움 모드에서 캐시된 백엔드 REST를 2초마다 조회한다. WS tick만으로 허용 근거의 시각을 갱신하지 않으며 오래된 허용 표시는 만료시킨다. 공개 STOMP 계약은 변경하지 않는다.

## 유지한 계약

MarketStatus는 0s 근거가 없으면 UNKNOWN이다. 0B는 시장 OPEN 또는 휴장 여부의 확정 증거가 아니다. Toss/Synthetic의 기존 공급자 정책, DB-only 거래, Oracle/Vault, quote 소비, nonce/raw transaction, receipt 정산과 복구, 사용자 원장 및 스키마는 변경하지 않는다. Android 코드는 수정하거나 빌드하지 않았다. REST의 추가 필드는 선택적 안내이며 Android에서도 서버 Quote 결과를 최종 기준으로 사용해야 한다.

## 검증 기록

- 최초 선택 테스트: 42개 중 40 PASS/2 FAIL. 신규 Mockito stub을 재설정할 때 기존 Answer가 실행되어 null 스냅샷을 참조한 테스트 설정 오류였다. `doReturn`으로 수정했으며 제품 정책 오류로 분류하지 않는다.
- 웹 단위 테스트 최초 실행: 87/87 PASS. UNKNOWN 표시, CLOSED 차단 안내, 오래된 허용 근거의 WS 갱신 방지, 기존 공급자 표시를 확인했다.
- 1차 전체 회귀: 백엔드 총 494개 중 459 PASS/35 SKIP/0 FAIL. 웹 87 PASS, Chrome desktop/mobile fixture smoke PASS, Foundry 36 PASS. 이후 추가한 경계·REST 응답 테스트와 UI 경합 수정은 최종 재실행 결과로 별도 기록한다.
- 신규 `phase-a-20261014033111-1c4e6ce8` session: source hash, 전용 loopback 포트, 소유권 및 시각 preflight를 검증한다. 이전 fixture를 재사용하지 않는다. `conditional-3e2b1384`의 실제 PostgreSQL/Anvil E2E 1/1 PASS, 별도 JVM snapshot restart 1/1 PASS. 이 실제 체인 검증의 시세는 Synthetic이며 실제 키움 서버 검증이 아니다.
- Faucet `faucet-cc9296eb`, 자산 대사 `reserve-9627a2a7`, 거래 전수 대사 `audit-3784d7c2`: 서로 다른 신규 PostgreSQL/Anvil fixture에서 각각 1/1 PASS. 조건부/관측 및 별도 JVM 재시작과 합쳐 필수 E2E 5/5 PASS, SKIP 0이다. 모든 테스트는 신규 키·계약·포트·네트워크·DB를 사용했다. 소유권 검사 후 이번 실행이 만든 컨테이너만 정지하고 상태·로그·결과를 보존한다.
- 최종 전체 회귀: 백엔드 총 498개 중 **463 PASS / 35 SKIP / 0 FAIL / 0 ERROR**, 웹 **88/88 PASS**, Chrome desktop/mobile fixture smoke PASS, Foundry **36/36 PASS**. SKIP은 통과 수에 포함하지 않는다. 신규 키움 정책은 Mock/단위 및 H2 통합 테스트로 검증하고, 실제 PG/Anvil의 시세는 Synthetic으로 구분했다.
- 실행 명령: `python deployment/phase-a.py init`, `fixture --session <신규 session> --case conditional|faucet|reserve|audit`, `regression --session <신규 session>`. 조건부 fixture는 별도 JVM 재시작을 이어 실행한다. 기존 harness/제품 정책/시각 허용치를 바꾸지 않았다.
- 동일 session에서 2차 `regression`을 호출했을 때 백엔드 실행은 완료됐지만 고정 `evidence/backend-full` 경로의 덮어쓰기 방지로 `FileExistsError`가 발생했다. 제품/테스트 실패가 아니라 증거 수집 경로 충돌이다. 기존 증거는 그대로 보존하고, 최신 실행의 XML을 새 `evidence/backend-full-final`에 복사·집계했다. 기존 session source guard와 clean environment를 적용한 harness `execute`로 웹 단위/브라우저/Foundry를 실제 새로 실행했다. 최초 결과를 재실행 결과로 대체하지 않았다.

## 독립 검토

별도 검토자가 서버 정책과 웹·테스트·문서 초안을 읽기 전용으로 검토했다. 최초 검토에서 고빈도 WS tick이 REST 응답보다 먼저 도착하면 가용성 필드까지 폐기되는 UI 경합을 지적했다. 최신 WS 가격을 보존하면서 REST 가용성 필드와 그 근거 시각만 반영하는 `mergeMarketRead`와 경합 회귀 테스트로 수정했다. 첫 재검토 결과는 발견된 필수 수정 없음이며 검토자가 직접 웹 88/88 PASS를 확인했다. 검토자는 실제 API·키·DB/RPC·private runtime에 접근하지 않았다. 이는 실환경 검증 완료 또는 무결함 보장이 아니다.

마지막 재검토도 **발견된 필수 수정 없음**이다. 추가 REST 응답·generation 변경 Java 테스트 및 완료 문서를 정적으로 확인했다. 최종 백엔드/Foundry/E2E 수치는 구현자가 실제 실행한 결과이며 검토자가 직접 재실행한 결과로 표현하지 않는다. 모든 신규 fixture 컨테이너 8개의 exited 상태도 마지막에 읽기 전용으로 확인했다.

## 현재 소스 및 인수 판정

작업 브랜치는 `feat/kiwoom-live-fixes`, 기준 HEAD는 `240cb3982d6c05724d6a89e99aed66788d77d697`이다. 변경사항은 미커밋이며 이전 사용자/진단/학습/로컬 실행 도구 변경도 보존한다. `melon-init` HEAD `f069a1085fd7cfe1d8d75f7bb454fc20feb50ede`는 그대로다. 새 runtime은 Git ignore 대상이고 stage는 비어 있다. 기존 DB/Anvil/acceptance와 실제 키움/OpenAI/Toss/배포에는 접근하지 않는다.

**로컬 구현·회귀는 완료이며 실제 키움 통합 검증에 진입할 수 있다. 최종 실환경 인수 PASS는 아니다.** 다음에는 별도 승인한 신규 격리 환경에 최신 소스를 반영하고 실제 키움 current-generation 정규 체결 → UNKNOWN 유지·가용성 안내 → 신규 Quote → Anvil BUY/SELL → receipt/event → DB/개인 WS/포트폴리오를 확인해야 한다. 0s 수신은 더 이상 완료 필수 조건이 아니다. 추가 외부 호출이나 기존 환경 반영은 이번 작업에서 수행하지 않는다.

## 실제 연동 미검증과 한계

실제 키움 API 호출은 이번 작업에서 실행하지 않는다. 보존된 capture와 0s-only 준비 증거도 변경하지 않는다. Spring Boot의 실제 키움 연결 → 서명 Quote → Anvil BUY/SELL → DB 정산 → 개인 WS/포트폴리오는 아직 실환경 인수가 필요하다.

0s 없이 실제 장 마감/휴장을 직접 확정할 수 없다. 마지막 정상 체결이 최대 5초 동안 가용성 근거가 될 수 있으며 이미 발급된 보고서는 기존 30초 TTL을 유지한다. 연결 변경이나 시세 상실은 신규 발급을 차단하지만 기존 제출 거래의 복구·정산을 막지 않는다. 클라이언트 안내는 참고 정보이고 실제 견적 발급 시 서버가 다시 검증한다.
