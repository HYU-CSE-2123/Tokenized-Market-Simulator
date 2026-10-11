# Faucet 정책 구현·검증 기록

> 2026-10-11 / feat/faucet-policy / **기능 구현·격리 PostgreSQL/Anvil 검증 완료, 최종 재검토·통합 진행 중**. 입력 integration/additional-features HEAD `6720b4a`. 거래 전수 대사/고아 Quote 최종 통합 검증은 이전 기록의 신규 실행 결과로 확인했고 이번 Faucet 검증으로 재표시하지 않는다. 아래 최초 실행 이력과 후속 결과를 구분한다.

## 승인 정책과 구현 위치

`backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetPolicy.java`: 지급1,000,000 mKRW, Asia/Seoul 달력일1회, 누적5회(정액이므로5,000,000), 서비스 누적50,000,000. 기간 예산/cooldown/잔고 기준 금액 변경/이메일 인증 조건/ADMIN 우회 없음. 한 지급분보다 예산이 작으면 거절한다.

- `FaucetService`: READ_COMMITTED 단일 transaction, account quota → global budget → JPA mKRW balance. DB 결정 시각은 모든 잠금 대기 **후** `clock_timestamp()`에서 읽는다. 지급·grant·claim·quota·budget을 함께 commit/rollback하고 lockedAmount는 변경하지 않는다. transaction8초, SQL3초, PG lock2초는 무한 대기를 방지하는 초기 기술 상한이며 정책 금액/횟수와 무관하다.
- `FaucetStore`: 기존 grants 정본과 전체 account counter/global sum/claims 및 마지막 지급 날짜를 비교한다. 차이는 fail-closed, 자동 보정 없음. 버전·contentHash·정책 원문 불일치도 새 지급 차단. 기존 성공 키 replay는 현재 정책/한도 확인 전에 반환한다.
- `WalletController`, `FaucetAdminController`: owner는 JWT에서만 결정, 서버는 client amount/userId를 수용하지 않는다. no body와 빈 JSON object만 유효하다. 동일 성공 키에 다른 body는409, 신규 키의 다른 body는400. DAO/transaction 오류는 비민감503으로 처리한다.
- `WalletService`의 무키 지급 메서드는 제거했다. 기존 테스트 호출도 명시적인 키 계약으로 변경했다. 기존 정산/서명/nonce/Vault/온체인 Faucet 코드는 변경하지 않는다.

## DB와 활성화 경계

`backend/src/main/resources/faucet-schema.sql` 및 `FaucetEntities`:

| 신규 테이블 | 역할 |
|---|---|
| faucet_policy_versions | 불변 version/contentHash/실제 정책값 |
| faucet_account_usage | 계정 누적 count/마지막 KST 날짜 |
| faucet_budget_usage | id1 전역 누적 count/spent, active version, 신규 원장 활성화 여부 |
| faucet_claims | grant 연결, 계정별 key fingerprint unique, request fingerprint, 원래 응답·시각·정책 버전 |

PostgreSQL에서는 grants/claims/policy_versions UPDATE·DELETE·TRUNCATE를 기능 gate와 무관하게 차단한다. counter는 지급 transaction에서만 갱신한다. 성공 claim의 TTL/자동 삭제 없음. SHA-256 fingerprint만 저장하고 raw key/JWT는 저장하지 않는다. grant/claim 시각은 UTC Calendar를 명시해 naive timestamp의 KST 오해를 막는다.

정책 초기 활성화는 신규 빈 users/grants/주문/유잔고 없는 PostgreSQL에서만 가능하다. 기존 budget row를 재설정하지 않는다. 기존 기록이 있는데 새 사용량 row가 없으면 초기값을 근거로 과거 지급을 추정하지 않고 initialized=false로 차단한다. 기존 환경 migration/정책 version rollout은 별도 승인 사항이다. ADMIN에 쓰기·reset API는 없다. `FAUCET_POLICY_VERSION` 기본 `kst-v1`은 식별자만 제어하고 승인 정책 숫자는 코드 상수다.

## 계약·웹·Android

USER API:

- POST `/api/wallet/faucet`: `Idempotency-Key` 필수, 영숫자/`_`/`-`16..128자. 같은 사용자 성공 replay200, 새 grant/예산/WS 없음. 성공 필드 symbol/receivedAmount/balance 유지 및 grantId/grantedAt/policyVersion/replayed 추가.
- GET `/api/wallet/faucet/status`: eligibility/reason/remainingCount/decisionDate/nextEligibleAt. 안내용, POST에서 재검증.
- GET `/api/wallet/faucet/claims`: 동일 header로 본인 이전 성공 조회, 미존재404. key를 URL에 넣지 않는다.
- GET `/api/wallet/faucet/grants?before=`: 본인 지급 이력20개·날짜+id keyset.

ADMIN API: GET `/api/admin/faucet`, `/api/admin/faucet/accounts/{userId}`, `/api/admin/faucet/grants?userId=&before=`. 정책·총량·remaining budget·전체 journal/counter 일치·본인 아닌 계정 quota와 이력 조회. 거절 코드 집계는 bounded 현재 프로세스 메모리이며 재시작하면 초기화된다. 지급 성공 정본이나 영구 거절 이력이 아니다.

HTTP400 무키/형식/비어 있지 않은 새 요청,429 같은 KST일,409 누적/예산/키 충돌,503 설정/회계/DB/lock 불가. daily status의 다음 날짜는 KST 자정이며 누적/예산 제한까지 해결된다는 보장은 아니다.

웹 `src/faucet-panel.js`, `api.js`, `main.js`, `index.html`: 정책/본인 이력·결과 확인/ADMIN 읽기 전용 패널. 브라우저 저장소에 계정별 pending intent(키만, JWT 없음)를 보존한다. Web Locks 사용 가능한 환경에서는 탭 간 키 선택을 직렬화한다. 응답 유실 시 자동 새 키/자동 재지급 없음. 결과 확인이나 명시적 같은 키 재시도로 복구하고 portfolio를 별도 조회한다. 늦은 응답은 session generation과 조회 revision으로 배제한다. 구형 브라우저 fallback에서도 지급 한도는 서버가 보장한다.

Android 소스는 접근 가능하며 `android/app/src/main/java/com/pricetrack/exchange/data/remote/ApiServices.kt`에는 Faucet API가 없다. Android 코드는 변경/빌드/실기기 인수하지 않았다. [Android 계약 전달](faucet-android-integration.md)을 사용한다.

## 검증 이력 — 실제 실행과 예정 구분

- 첫 targeted H2 정책/API/rollback + 기존 reserve/WS Gradle 실행 BUILD SUCCESSFUL. H2/Mock은 실제 PostgreSQL 경쟁의 근거가 아니다.
- 첫 전체436개 실행:402 PASS/1 FAIL/33 SKIP. 기존 TradingIntegrationTest의 BigDecimal assertion이 새 지급액 JSON 정수 표현으로 실패했다. 지급 상수에 기존18자리 scale을 유지해 계약을 보완했다.
- 이후 전체436개:403 PASS/0 FAIL/0 ERROR/33 SKIP. **이 실행 이후** 빈 원장 활성화 조건·ADMIN 계정 조회와 일부 테스트 보완을 추가했으므로 최종 전체 회귀를 다시 실행해야 한다. 기존31 opt-in+기존 감사E2E1+신규FaucetE2E1 skip. 외부/실제 검증을 완료로 바꾸지 않는다.
- 웹66/66 PASS. Chrome 최초 유실 시나리오는 브라우저 동일 POST 재전송에 fixture가 성공 응답하여 timeout이었다. 유실 구간의 모든 전송에 일관되게 응답을 유실시키도록 fixture만 보완한 뒤 Vite93 modules/Chrome desktop·mobile PASS. 이 이후 UI 보완이 있어 최종 재실행 예정이다. 실제 backend 연결이 아닌 REST/STOMP fixture다.
- Foundry: 최초 잘못된 리포 루트에서 import resolution 실패; contracts/에서 `forge test --offline` 재실행36/36 PASS, fuzz각256. Solidity 변경/외부 다운로드 없음.
- 실제 새 fixture 준비 `audit-e2e-20261011022917-2c4143`: Docker network 생성 이전 Linux engine `_ping` HTTP500으로 실패했다. private stderr/생성 secret은 해당 runtime에 보존한다. Docker ps도 HTTP500, desktop-linux context와 docker-desktop WSL Running을 확인했으나 확정 원인은 모른다. 엔진 재시작/acceptance 데이터 초기화/다른 DB 대체는 하지 않았다.
- `FaucetPostgresAnvilE2ETest`는 실제 DB lock wait·fixture DB clock의 KST 자정 전환, 동일/다른 키 경쟁, 마지막 예산 경쟁,5회/50m,rollback/replay/version/counter/configuration·BUY/SELL 경합·온체인 불변·두 대사 호환성을 검사하도록 작성했다. **아직 실행하지 않았다**. fixture의 DB clock injection은 테스트에만 존재하며 production은 실제 DB 시각을 사용한다. 실제 자정까지 기다리는 wall-clock 장기 시험은 별개다.

## 검토·보안·통합

독립 검토 및 최종 source 회귀 결과는 후속 기록으로 확정한다. 검증된 feature commit/통합 merge는 아직 없다. integration/additional-features는 `6720b4a`, 원본melon-init은 `b7cb0f0` 상태를 보존한다. acceptance/dev DB·Anvil/runtime/.env·실제 Toss·유료AI·AWS는 사용하거나 변경하지 않는다.

필수 실제 PG/Anvil 검증과 독립 검토가 끝나기 전 완료/통합하지 않는다. Google 로그인 및 이메일 인증 착수는 Faucet 통합 검증 이후다. 기존 migration/다중 계정 방어/운영 부하/실제 Toss·AI/실기기 Android는 이번 완료 주장에 포함하지 않는다.

## 후속 실제 검증 — 2026-10-11

- Docker engine가 이후 read-only 확인에서 정상 응답했다. 에이전트가 엔진을 재시작한 것은 아니며 최초 HTTP500 원인은 확정하지 않는다.
- 새 fixture `audit-e2e-20261011024150-0fbe96`: `wallet.faucet.*` **9개 / 9 PASS / 0 SKIP / 0 FAIL / 0 ERROR**. 구성은 단위3 + H2 계약5 + 실제 PostgreSQL/Anvil E2E1이다. E2E1에 다수 시나리오 assertion이 있고 이를 별도17개 테스트 통과로 계산하지 않는다.
- 실제 PG E2E는 계정/전역 행 잠금 경쟁, 같은 키 exactly-once, 다른 키 일일 제한, 마지막 예산 동시 경쟁, rollback과 재시도, 기존 성공 키 재조회, 동일 승인 정책의 새 버전 지급과 누적 유지, 설정·counter 불일치 차단을 확인했다. 잠금 대기 중 KST 자정은 **DB fixture clock**으로 전환했으며 production DB clock은 변경하지 않았다. 실제 자정을 기다린 장기 테스트는 아니다.
- 실제 EIP-712 BUY/SELL과 Faucet 경합, pending 잠금과 REVIEW_REQUIRED 불변, 정산 후 두 대사 MATCH, DB Faucet만 호출하는 구간의 온체인 자산·공급·allowance·nonce·block 불변을 확인했다. 실패 RPC 주문의 전체 복구를 새 실제 E2E로 추가 검증했다고 주장하지 않는다.
- 별도 새 fixture `audit-e2e-20261011024502-13df27`: `tradeaudit.*` + `reserve.*` **80개 / 79 PASS / 1 SKIP / 0 FAIL / 0 ERROR**. 실제 거래 전수 대사 E2E를 포함하며 고아 Quote·ADMIN API·schema 재적용을 확인한다. 별도 ReservePostgresAnvilE2ETest는 opt-in 미활성으로 skip이다.
- 이 두 fixture의 XML을 각 private runtime logs에 보존하고 **해당 두 컨테이너씩만 정지**했다. DB·chain state·기록은 삭제하지 않았다. 기존 acceptance/dev DB/chain에 접속하지 않았다.
- 최종 제품 코드의 앞선 전체 회귀:436개 / 403 PASS / 33 SKIP / 실패0. 마지막 E2E assertion 보완까지 포함한 전체 source 회귀는 후속 결과로 기록한다. 웹 최종66/66 PASS, Vite93 modules와 Chrome desktop/mobile REST·STOMP fixture PASS. Foundry36/36 PASS는 위 실제 실행 결과이며 Solidity 변경은 없다.
- 최초 독립 검토는 **검토 불충분 — 필수 실제 검증 미완료**였다. 정적 확정 필수 구현 결함은 발견되지 않았지만 당시 PG 미실행을 완료로 처리하지 않았다. 실제 실행 결과와 최종 source를 별도 재검토에 전달한다.

ADMIN 거절 집계는 HTTP 지급 경로에서 관측한 allowlisted 사유의 프로세스 내 집계다. 내부 호출·전체 영구 거절 이력을 보장하지 않는다. fixture 준비의 배포·operator 자금 공급은 테스트 환경 설치 단계이며 제품 Faucet의 자동 온체인 공급 기능이 아니다.

## 기능 브랜치 최종 확정

- 마지막 E2E assertion을 포함한 최종 전체 source: `gradlew test --offline --no-daemon --rerun-tasks` BUILD SUCCESSFUL(11:53 KST).436개/403 PASS/33 SKIP/0 FAIL/0 ERROR.33 skip은 기존31 opt-in와 감사/Faucet 실제E2E 각1이며 두 신규 실제E2E의 별도 실행 PASS와 혼합 계산하지 않는다.
- 독립 첫 재검토 `review_faucet_policy`: **발견된 필수 수정 없음**. source·추가 assertion·문서·diff check와 당시80개 XML을 직접 확인했다. 실제 PG/RPC 재실행은 하지 않았으며 구현자 실행과 구분했다. 현재 기록 보완은 제품 동작 변경이 아니다.
- 기능 stage allowlist42개, 새 fixture 실제 비밀값9개와 대조해 노출0, 금지 산출물0, `git diff --cached --check` PASS. 실제 `.env`, key, DB dump/state, chain state, runtime 로그, build/node_modules/lib는 포함하지 않는다. 문서에 추가하는 검증 기록도 커밋 전 다시 점검한다.
- 기능 source를 통합에 병합한 뒤 최종 통합 source에서 회귀와 새 PG/Anvil fixture를 다시 실행한다. 통합 해시·결과는 후속 기록에서 확정한다.
