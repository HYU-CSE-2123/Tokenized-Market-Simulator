# 거래 전수 대사 구현 및 검증

> 2026-10-10 / feat/trade-audit / 기능 구현·격리 E2E·독립 재검토 완료. 통합 병합 후 회귀 기록은 아래 후속 절에서 확정한다. 커밋 식별자는 Git log와 완료 보고를 기준으로 확인한다.

## 구현

`backend/src/main/java/com/pricetrack/exchange/tradeaudit/`:

- AuditModels: 비민감 projection·항목 check·run coverage. rawTransaction/signature는 내부 메모리에서만 비교하고 JsonIgnore+hash로 결과에서 제외한다.
- AuditStore: 거래 DB read-only REPEATABLE_READ timeout5초/query3초, 테이블별 최대50,000행. 같은 snapshot에서 PostgreSQL row_to_json/OCTET_LENGTH 집계로 raw/signature를 포함한 원본 합계16MB를 JDBC 적재 전에 제한하고, 결과 projection도 UTF-8 16MB를 제한한다(이는 JVM heap 사용량 보장이 아니다). baseline·Order·Trade·BUY/SELL transaction·연결 PriceQuote 수집. UTC TIMESTAMP를 Calendar.UTC로 읽는다. 상한은 부분 정상 처리가 아니라 INCONCLUSIVE다.
- AuditChain/RpcAuditChain: 별도 read-only Web3j client, RPC call timeout2초/run 기본30초/500호출. 고정 block/canonical·code identity, tx/receipt, 500블록 단위 Vault 역방향 scan(총10,000 logs 상한). 거래 직전 block의 signer/fee에 같은 블록의 이전 transaction 설정 변경 로그를 순서대로 적용한다. 현재 설정으로 과거를 추론하지 않는다.
- AuditValidator: DB links, RPC from/to/nonce/value/hash/block, raw hash/chain/sender/calldata, 보고서 재구성/서명 복구, Vault Bought/Sold와 Oracle PriceReportConsumed를 비교한다. 실제 이벤트 수량과 과거 fee 산식 모두 exact 정수 검증. quote 발급 fee는 현재 체결 fee와 무조건 같아야 한다고 가정하지 않는다.
- AuditService: 독립 single worker·1실행 admission·deadline, snapshot 이후 RPC, 후속 fingerprint/head 안정 확인, orphan DB/chain 이벤트 검사. item verdict와 coverage/전체 verdict 분리. cut을 확보하지 못하면 provisional item도 INCONCLUSIVE로 보존한다.
- 안정된 cut에서 확인한 DB 모순은 receipt/과거 설정 근거가 없어도 항목 MISMATCH와 checks로 보존한다. CHAIN_EVIDENCE_INCOMPLETE limitation과 chainComplete=false로 전체는 INCONCLUSIVE/INCOMPLETE다. chainComplete는 역방향 스캔뿐 아니라 필요한 개별 체인·quote 근거의 완료를 뜻한다. 불안정 cut에서는 기존처럼 전 항목 INCONCLUSIVE다.
- 소비된 견적은 orderId가 유실되어도 수집하여 DB_QUOTE_WITHOUT_ORDER로 검증한다. 정상 미소비·미연결 견적은 감사 대상이 아니다.
- AuditController: ADMIN 수동202/중복409, 이력·상세·필터 keyset pagination. 기능 off503. 감사 전용 JSON 오류 처리로 /error 재dispatch가 409를401로 가리지 않게 한다.

새 trade_audit_runs(불변 시작), trade_audit_results(종료 1회 append), trade_audit_items(종료 시 append) 테이블만 추가했다. PostgreSQL UPDATE/DELETE/TRUNCATE 차단 trigger와 runId unique로 원문 덮어쓰기/종료 중복을 막는다. source Order/Trade/quote/잔고 쓰기나 sender/settlement 호출은 없다. 시작만 저장되고 종료가 유실된 실행은 다음 기동에 PROCESS_INTERRUPTED INCOMPLETE로 보존하고 자동 재실행하지 않는다. DB 자체가 불가해 종료 저장이 실패하면 완료 결과를 반환하지 않으며 시작 기록이 남을 수 있다.

## 계약·설정

- TRADE_AUDIT_ENABLED=false 기본. 환경 provenance에는 기존 RESERVE_EXECUTION_ID와 불변 reserve baseline을 사용한다. baseline 없음·다른 namespace이면 전체 INCONCLUSIVE. 기존 유잔고에 baseline을 임의 재설정하지 않는다.
- TRADE_AUDIT_MAX_RPC_CALLS=500, TRADE_AUDIT_TIMEOUT_SECONDS=30. 허용 설정은1..10,000호출/1..120초다. deadline은 cooperative이며 현재 진행 중인 DB/RPC와 종료 저장 timeout까지 더해질 수 있다. 30초가 hard SLA라는 뜻은 아니다.
- POST /api/admin/trade-audits, GET /api/admin/trade-audits?before=runUUID, GET /api/admin/trade-audits/{id}, GET /api/admin/trade-audits/{id}/items?before=seq&verdict=&mode=&side=&orderId=.
- 이력20개/항목50개 keyset, nextBefore. run은 lifecycle/전체 verdict/coverage/count, 항목은 verdict/reason/expected·actual check/limitations. DB_ONLY의 MATCH는 CHAIN_NOT_APPLICABLE이며 온체인 MATCH와 구분한다. FAILED 이전 전송 없음·UNKNOWN·미정산은 추정하지 않는다.
- 웹 src/trade-audit-panel.js: ADMIN 읽기 전용 독립 패널, 수동 실행 확인·제한적 GET polling·이력/항목 필터·상세·incomplete 경고·logout stale 응답 차단. REST/STOMP 거래 계약은 유지한다.

## 현재 실행 기록

- 오프라인 main 컴파일 성공. 초반 ABI generics/test HttpService close 컴파일 오류는 보완 후 재컴파일했다.
- 첫 fixture 준비에서 별도 worktree에 contracts/lib가 없어 도구 이미지 COPY/build가 실패했다. 해당 실패 자료·DB/chain은 보존했다. helper는 캐시된 검증 도구 이미지 inspect 및 --pull=never 재사용으로 보완했다. 최초 build에는 이미지 metadata resolve 단계가 있었으므로 이를 외부 접근 시도도 없었던 실행이라고 주장하지 않는다. 실제 Toss/AI/AWS 호출은 없다.
- 첫 실제 거래 E2E: BUY/SELL 정산 성공, 감사 MISMATCH. JDBC timestamp에 로컬 KST가 적용되어 quote observedAt/validUntil이9시간 이동하는 reader 오류를 발견했다. Calendar.UTC 및 단위 회귀를 추가했다.
- 다음 새 fixture: 실제2거래 감사 MATCH, RPC26회·2Vault events·stable cut 확인. 중복 실행 HTTP409가 /error dispatch로401이 되는 문제를 재현해 감사 controller 내부 JSON exception handler로 보완했다.
- 웹61/61 PASS(기존55+신규6), Vite92모듈 build PASS, 로컬 Chrome fixture의 감사 incomplete/과거 근거불가·ADMIN/기존모바일회귀 PASS. Chrome은 실제PG/Anvil 연결 인수가 아닌 REST/STOMP fixture다.
- Foundry36/36 PASS, fuzz256회 각각. Solidity 변경 없음. 이전 검증 library를 로컬 복사했고 설치/다운로드 없이 실행했다.
- 감사 전용42개(당시 단위41+실제PG/Anvil E2E1) PASS. `audit-e2e-20261009183728-77c4e6`에서 양방향 BUY/SELL·과거 설정 변경 후MATCH·원장 무변경·비민감 payload·immutable DELETE 차단·이력 필터·중복409·실제Trade변경/새블록 CUT_CHANGED·금액차이MISMATCH·UNKNOWN INCONCLUSIVE·DB 삭제 후체인 orphan MISMATCH를 확인했다. E2E XML은 해당 private runtime logs/e2e-results에 보존했다.
- 이후 metadata API에서 전체 source projection 배열을 반환하지 않고 시점/출처·digest·coverage와 paginated items만 반환하도록 보강하고 단위 테스트를 추가했다. 전체 회귀·독립 검토는 계속 진행 중이며 아래 후속 기록으로 확정한다.

## 제한·최종 인수 경계

Windows Docker Desktop 격리 fixture 기준이다. 테이블 상한·timeout·RPC cap·과거 상태 미지원·head 변화·legacy UNKNOWN·사전 실패 근거 부족은 INCONCLUSIVE이며 큰/활성 원장에서 전수 완료가 보장되지 않는다. RPC가 성공 빈 목록으로 거짓 응답하거나 임의로 로그를 누락하는 경우를 cryptographic receipt proof로 검출하는 기능은 없다. node 신뢰·표준 RPC 계약과 블록 hash/canonical 대조를 전제한다.

기능 gate는 단일 프로세스다. 다중 backend claim/분산 nonce/HA/자동 retry·수정은 미구현이다. 결과는 ADMIN 관측이고 준비금·실제 자금 보증이 아니다. 실서비스 보존 기간/retention은 별도 승인으로 정한다. 실제 Toss·유료AI·AWS·Sepolia·장기부하 및 최종 전체제품 인수는 미실행이며 기존31 skip을 완료로 바꾸지 않는다. 기존 acceptance/runtime/DB/chain/개발.env는 그대로 보존한다.

## 최종 기능 검증·검토 (2026-10-10)

- 독립 최초 검토에서 RPC unavailable이 기존 DB 모순 checks를 지우는 문제와 JsonIgnore raw가 source byte cap에서 빠지는 문제를 지적했다. item MISMATCH/전체 incomplete 분리 및 동일 RR SQL byte preflight로 보완했다. receipt 부족+Trade 없음/귀속 차이, 과거 설정 부족+금액 모순, 단일/누적/UTF-8 raw 상한 테스트를 추가했다.
- 마지막 dangling 경계로 CONSUMED+NULL orderId를 수집하고 실제 PG에서 고아 항목 MISMATCH/전체 INCONCLUSIVE 및 연결 복구 후 MATCH를 확인했다.
- `audit-e2e-20261009185917-1da64b` 최초 기동은 PostgreSQL socket timeout으로 실패했다. 같은 시점 Docker container API500도 관측했으나 확정 원인으로 단정하지 않는다. engine 재시작/DB 초기화 없이 응답 회복과 public table0을 확인 후 동일 빈 fixture에서 감사50/50 PASS였다.
- 최종 소스의 새 `audit-e2e-20261010120117-7bda03`: 감사50개 모두 PASS(단위49+실제PG/Anvil1). BUY/SELL EIP-712 정산·양방향 events·과거 fee/signer 변경 후 MATCH·DB source fingerprint 불변·RR/read_only·RPC 밖 DB transaction·raw/signature 미저장·immutable DELETE 차단·권한/409·실제Trade 변경/새블록 CUT_CHANGED·금액차이 MISMATCH·UNKNOWN·고아 chain event·CONSUMED 고아quote·PG 단일/누적 raw 초과 INCONCLUSIVE 및 복구 MATCH를 실행했다. 이 테스트는 다수 assertions를 가진 E2E1개이며 50개 실거래를 실행했다는 뜻이 아니다. XML은 해당 private runtime에 보존하며 fixture container만 정지하고 데이터는 남긴다.
- 전체 backend 오프라인 회귀427개:395 PASS/0 FAIL/0 ERROR/32 SKIP. 이 실행은 마지막 고아quote SQL 수정 직전이다. 수정 후 전체 회귀는 통합 브랜치에서 다시 기록한다. 32 skip=기존31개의 외부/opt-in 시나리오+이번 감사 E2E1(별도 실행 PASS). 기존31 skip에는 AI live/pgvector/tools/Agent/diagnosis/Skill 및 기존 Anvil·Synthetic·quote 동시성·reserve opt-in이 포함되며 이번 실행으로 완료 처리하지 않는다.
- 웹 최종61/61 PASS, Vite92 modules build PASS, 로컬 Chrome desktop/mobile fixture PASS. coverage 표시명 변경 뒤 이전 문구 assertion1 실패는 기대 문구 보완 후61PASS였다. 실제 web↔PG/Anvil 브라우저 인수가 아니라 fixture 회귀다. Foundry36/36 PASS(fuzz256회 각각), 계약 소스 변경 없음.
- `review_trade_audit` 최초 검토 + 재검토2회. 최종 결론 **발견된 필수 수정 없음**. 재검토1에서 신규 웹6개 직접 PASS, 마지막은 source/nullable 고아 처리/PG assertions/문서 읽기 전용 확인이다. 실제 PG/Anvil·전체 Gradle·Foundry·Chrome은 구현자 실행이며 검토자 독립 재실행으로 쓰지 않는다.
- 계획 중 restart interruption·동일 블록 설정 변경·snapshot/revert reorg·대규모 활성 원장·다중 서버·RPC의 악의적 로그 누락은 실제 운영 E2E 미검증이다. timeout/call cap과 근거 누락은 단위/mock 검증과 위 실제 fixture 범위를 구분한다. 불완전 범위를 전수 MATCH로 표시하지 않는다.
- 커밋 전 allowlist30개 소스/문서만 stage하고 새 fixture6개에서 생성된 실제 secret18개를 staged blob과 대조했다. 일치0/금지산출물0/cached diffcheck PASS. 실제.env·DB dump·Anvil state·runtime 로그·build/lib/node_modules는 Git에 포함하지 않는다. 최종 fixture2컨테이너만 정지했고 데이터를 보존했다. 원본melon-init b7cb0f0와 완료reserve00046d7 변경 없음.
