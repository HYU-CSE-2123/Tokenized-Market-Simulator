# SIGNED/SUBMITTED 거래 정체 관측 구현

> 2026-10-12 · feat/transaction-stall-observability · 기능 검증 완료, 최종 커밋·통합 회귀 대기

## 범위와 정본

ADMIN DB 조회와 마지막 완료 복구 시도의 best-effort snapshot만 추가한다. LONG_WAIT는 실패나 REVIEW_REQUIRED가 아니며 기존 복구는 계속된다. 거래 상태, raw transaction, nonce, quote, 자산 잠금, 재전송/Receipt 정산 정책은 변경하지 않는다. 기존 AI Tool WAITING_LONG 계약도 그대로다. 신규 RPC, 외부 모니터링, 자동 종료/해제/충전/재주문은 없다.

## 구현 위치와 계약

- `backend/src/main/java/com/pricetrack/exchange/blockchain/observation/`: controller, DB projection, snapshot entity, bounded recorder/store.
- `BlockchainReconciliationService`: 기존 SIGNED 복구와 SUBMITTED Receipt 확인의 완료 결과를 기록. 관측 시작/저장 실패가 업무 처리를 대체하지 않는다.
- `schema.sql`: `blockchain_transaction_observations` 추가. 거래별 PK, attempt_id, started_at/finished_at, phase/result_code, attempt_count, recorded_at. 기존 거래에 자동 추정/backfill하지 않는다.
- `SecurityConfig`: 명시적 ADMIN matcher. 서비스에서도 ADMIN 확인.
- 웹 `src/transaction-observation-panel.js`, `main.js`, `api.js`, `index.html`: ADMIN 수동 조회/필터/다음 페이지/상세. DOM text 기반 출력, 로그아웃·탭 이동·늦은 응답 무효화.

`GET /api/admin/transaction-observations`는 status(ALL/CREATED/SIGNED/SUBMITTED/CONFIRMED/FAILED/REVIEW_REQUIRED), attention(ALL/LONG_WAIT), limit(1..50, 기본20), cursor(양의 ID canonical base64url)를 받는다. ID 오름차순 keyset이며 목록이 변경될 수 있는 live 조회이지 불변 감사 cut이 아니다. `GET /api/admin/transaction-observations/{id}`는 존재하지 않으면404. anon401, USER403, 잘못된 필터400.

목록/상세는 observedAt, warningSeconds, total/submitted/waitingAgeSeconds, attention, order/linkStatus, conditionalOrderId/conditionalState, observationStatus와 lastAttempt를 제공한다. SIGNED는 createdAt, SUBMITTED는 submittedAt(없으면 createdAt)부터 기본300초 이상 LONG_WAIT. 설정 `app.blockchain.observation.warning-seconds`는1..86400. Order가 없거나 연결이 손상되어도 거래를 숨기지 않는다. UPDATE_PRICE는 SYSTEM, 나머지는 USER_TRANSACTION으로 구분한다.

txHash/nonce는 ADMIN 상세에만 있다. raw tx, 키, 서명, RPC URL, 오류 원문은 두 응답 모두 제외한다. lastAttempt는 마지막으로 **기록된 완료 시도**일 뿐 진행 중인 시도의 상태가 아니다. 미기록은 UNRECORDED이며, ageSeconds/recordedAt으로 지연을 확인한다. 초기 전송 자체는 기존 sender 경로이고 별도 관측 hook 대상이 아니다.

고정 resultCode: SUBMISSION_RECORDED / RECEIPT_NOT_FOUND / CONFIRMATIONS_PENDING / SUCCESS_SETTLED / FAILURE_SETTLED / REVIEW_REQUIRED / ATTEMPT_UNAVAILABLE. 마지막 코드는 결과 확인 불가이며 ETH 부족·영구 미채굴을 단정하지 않는다.

## 자원과 순서 경계

별도 writer1개, queue100, 독립 Hikari 연결 최대1개, 획득250ms, PostgreSQL statement2초/lock250ms. 기존 DB를 공유하므로 자원 영향이 절대 없다고 보장하지 않으나 업무 transaction과 자산 행 잠금에 참여하지 않는다. queue 포화/저장 실패는 안전한 프로세스-local 집계로 ADMIN 목록에 표시하며 재시작 시 집계는 초기화된다. 기록된 snapshot은 DB에 남는다.

프로세스 내 시작 시각은 microsecond 단조 증가한다. 더 늦게 시작한 시도만 조건부 갱신하고 duplicate/역순은 무시한다. attempt_count(API recordedCount)는 받아들여진 최신 완료 기록 수이며 저장 실패/queue drop/무시된 오래된 기록을 포함하는 전체 RPC 횟수가 아니다. 단일 프로세스 worker 범위이며 분산 nonce/다중 서버 보장은 추가하지 않는다. 종료 중 queued metadata는 유실될 수 있고 감사 로그가 아니다.

## 검증 기록 (실행 결과를 순차 추가)

- 관측 H2/Mock/security: 초기 txHash fixture 중복으로2건 실패 후 고유값 수정. 8개 PASS, 이후 복구 예외 격리 테스트 추가 실행도 PASS (최종 집계는 아래 추가).
- 웹 unit:84 PASS/0 SKIP. 실제 브라우저 smoke PASS: ADMIN 관측 목록/상세/필터, five-route mobile, 로그아웃·uncertain write 기존 회귀. 브라우저 API는 fixture이며 실제 네트워크 장애 인수와 구분한다.
- 실제 신규 PG/Anvil 첫 실행:1 FAIL. 동일 raw 재전송 후 confirmation2를 위한 추가 mine이 테스트에 누락됨. 제품 정책은 유지하고 settle fixture를 수정. 실패 XML과 DB/state는 private runtime에 보존했다.
- 다음 fixture 준비 실패: Docker 기본 address pool 고갈. 이번 작업의 정지된 fixture가 남긴 **연결0개 전용 network만 제거**, 다른 환경/DB/state는 유지하고 새 fixture를 생성했다.
- 실제 새 PostgreSQL/Anvil E2E:1 PASS (`audit-e2e-20261011220822-49a0bf`). SIGNED 전송 응답 유실, 실제 동일 raw 재전송, SUBMITTED 미채굴/확인 수2 대기, 실패 receipt, 성공 정산, 증거 불일치 REVIEW_REQUIRED와 잠금 유지, EXECUTING 연결, 관측 조회 중 RPC 수/업무 상태·raw/nonce/hash/자산 불변, 관측 table 일시 unavailable에서도 복구 계속 및 failure counter를 확인했다. 정상 거래 구간 Faucet/장부 대사/거래 전수 대사 MATCH도 확인했다. 이후 review를 재현하기 위해 **이 신규 fixture만** 기대 Order input을 변경했다. 이를 제품 데이터 수정으로 구현한 것이 아니다.
- 별도 JVM PostgreSQL 재시작:1 PASS. SQL 초기화 없이 기존 FAIL/SUCCESS snapshot을 읽고 metadata-only fixture에서 duplicate/역순 no-overwrite와 업무 row 불변 확인. 이 metadata fixture는 실제 RPC 결과가 아니다.
- feature 전체 백엔드:462 중427 PASS/35 SKIP/0 FAIL/0 ERROR. opt-in PostgreSQL/Anvil/AI provider 항목은 기본 회귀에서 skip이며 위 실제 E2E2개를 별도 실행했다. opt-in 미실행 항목까지 통과로 처리하지 않는다.
- Foundry36 PASS/0 SKIP. 컨트랙트 변경 없이 integration worktree의 동일 소스와 설치된 lib로 실행했다. Sepolia variant나 네트워크 테스트가 아니다.
- 독립 검토1차: 발견된 필수 수정 없음. 검토자 직접 신규 웹6/6 및 diff-check PASS. 구현/문서 재검토와 최종 통합 회귀 결과는 아래에 추가한다.
- 독립 첫 재검토: 발견된 필수 수정 없음. 최종 코드·문서·시작 예외 guard/테스트·Sepolia 승인 경계를 재확인, 직접 신규 웹6/6 재실행, XML462/427/35/0 및 contracts diff0/diff-check 확인. 실제 fixture/runtime에는 검토자가 접근하지 않았으며 E2E는 구현자의 실행 결과다. 아래 통합 회귀는 별도 실행한다.

## 미검증·승인 경계

실제 operator ETH 부족, 장시간 RPC 지속 장애, 실제 Toss 장중, 유료 AI, AWS/Linux 운영 부하, Sepolia는 미실행이다. 시계 보정·metadata drop은 best-effort 한계이며 거래 실패 원인을 추정하지 않는다. 기존 melon-init/acceptance/runtime/.env와 개발 DB/chain은 사용/변경하지 않는다.

Sepolia는 별도 최초 고정 공급 ERC-20 variant A로 결정됐다. 로컬 공개 Faucet은 유지한다. 추가 mint 없음, S0/O0/V0 미확정, 신규 배포/기준점 필요. [별도 설계](sepolia-mockkrw-issuance-design.md)를 따르며 이번에 컨트랙트 구현/배포/ETH/RPC는 하지 않는다.
