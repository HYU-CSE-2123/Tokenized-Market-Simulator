# 거래 전수 대사 ADMIN — 조사 및 설계안

> 2026-10-09 / `feat/trade-audit`, 출발점 `00046d7` / **설계만 작성, 구현 승인 대기**.
> 실제 DB/RPC/서비스는 이번에 조회하거나 변경하지 않았다. 코드 사실과 아래 제안을 구분한다.

## 1. 목적과 기존 기능의 차이

ADMIN이 DB의 확정 주문·체결과 해당 온체인 transaction·receipt·Vault 이벤트가 대응하는지 전체 범위를 조회한다. 자동 정산·재전송·자산 보정·대사 기준점 재설정은 없다. 거래가 대기 중이거나 근거가 없다고 임의 실패 처리하지 않는다.

- 기존 `BlockchainReconciliationService`는 SIGNED/SUBMITTED를 복구하고 **잔고·상태를 변경**한다. 전수 대사에서 호출하지 않는다.
- `ReserveService`는 지급/거래 증감으로 예상 자산 총량을 계산한다. 전수 대사는 주문별 대응과 누락/중복을 보여준다. 자산 대사를 대신하거나 준비금 보증을 제공하지 않는다.
- AI Skill/자동 진단과 연결하지 않는다. 이번 추가 기능에는 AI 설정·Tool·유료 호출이 필요 없다.

## 2. 현재 구현 조사 — 코드 사실

경로 prefix는 `backend/src/main/java/com/pricetrack/exchange/`다.

| 실제 파일 | 확인한 현재 계약 |
|---|---|
| `order/Order.java`, `ExecutionMode.java` | 실행 유형 ONCHAIN/DB_ONLY/UNKNOWN의 정본, user/symbol/side/input/status/txHash. 기존 행은 UNKNOWN |
| `trade/Trade.java` | 주문당 unique 1체결, mode 복사, price/base/quote/fee/txHash |
| `blockchain/transaction/BlockchainTransaction.java` | orderId unique, sender+nonce unique, type/status/hash/blockNumber/rawTransaction. 행에 chainId·Vault 주소·blockHash·환경 ID는 없음 |
| `quote/PriceQuote.java`, `PriceQuoteService.java` | quoteId PK, orderId unique, 사용자 귀속·CONSUMED, 보고서 필드·서명 보존. 주문 시 quoteId로 서버 DB에서 보고서 복원 |
| `order/OnchainOrderPreparationService.java` | 입력 잠금·견적 소비·주문 생성. 잔고 부족 또는 SIGNED 이전 실패는 ONCHAIN FAILED지만 실제 tx가 없을 수 있음 |
| `blockchain/transaction/BlockchainTransactionSender.java` | raw 서명·사전 hash·nonce 저장 후 broadcast. synchronized는 단일 프로세스만 보호 |
| `blockchain/reconciliation/BlockchainReconciliationService.java` | receipt confirmation 확인, Bought/Sold parser, 성공/실패 정산·REVIEW_REQUIRED 및 SIGNED 재전송. read-only audit가 아님 |
| `blockchain/settlement/OnchainSettlementService.java` | 성공 이벤트에서 Trade 작성: BUY base=output, quote=input; SELL base=input, quote=output; price=priceE8/1e8; fee 이벤트값. 실패 receipt는 잠금 해제·Trade 없음 |
| `blockchain/contract/ContractEventParser.java` | Vault 주소·종류·operator·input과 이벤트 정확히 1개 검증. PriceReportConsumed decoder나 tx calldata 비교는 없음 |
| `blockchain/support/TokenUnits.java` | 토큰 수량 18자리 정수 변환. double 기반 비교 불가 |
| `reserve/ReserveStore.java`, `ReserveService.java` | read-only REPEATABLE_READ cut와 후속 fingerprint/head 검증. 10,000행 등 읽기 상한, 전체 원장 불변 기준점 필요 |
| `reserve/RpcReserveChainReader.java` | 별도 RPC timeout·지정 block 조회·canonical 검사·receiptMatches. 반환 boolean은 미존재/불일치 원인을 충분히 구분하지 않음 |
| `common/config/SecurityConfig.java`, 웹 `src/reserve-panel.js` | 기존 ADMIN 권한 및 상태/이력 UI 패턴. 새 endpoint에는 명시적 ADMIN rule 필요 |
| `backend/src/main/resources/schema.sql` | 기존 schema의 additive 초기화. 이번 기능 schema 변경은 아직 없음 |

컨트랙트 `contracts/src/ExchangeVault.sol`의 Bought/Sold.user는 **operator**다. 실제 서비스 userId는 DB의 Order→PriceQuote/Trade 연결로 검증한다. 컨트랙트가 사용자별 DB ID를 보증하지 않는다.

`contracts/src/PriceOracle.sol`은 동일 receipt에서 `PriceReportConsumed(quoteId,priceE8,observedAt,validUntil,signer)`를 발행한다. Vault는 `buy/sell(PriceReport,bytes)`만 받아 보고서 executor·side·input·minimumOutput을 검증한다. Oracle의 마지막 priceE8이나 현재 시세는 **과거 체결 비교 정본이 아니다**. 관리용 updatePrice 함수는 여전히 있으나 기존 주기적 updatePrice 거래 경로가 살아 있다는 뜻은 아니다.

## 3. 정본과 환경 provenance 제안

1. **DB 사용자 의도/귀속:** Order 실행 유형·user/symbol/side/input. PriceQuote의 사용자·order 연결과 저장 원문. Trade는 DB 정산 결과.
2. **온체인 실행 사실:** 확인된 canonical transaction/receipt와 해당 Vault/Oracle 로그. txHash가 성공 여부나 사용자 소유권을 단독 증명하지 않는다.
3. **대사 결과:** 당시 관측·비교의 불변 증거이지 새 자산 정본이 아니다. 결과 생성으로 원 주문/Trade를 수정하지 않는다.
4. **환경:** execution namespace, chainId, 네 contract 주소·code hash, operator, baseline anchor block/hash, 선택 DB 기준 시점. chainId31337 같음만으로 같은 Anvil이라고 판단하지 않는다.

신규 통합/fixture 환경은 기존 자산 대사 불변 baseline을 0원 원장 시점에 생성해 출처를 공유한다. 전수 대사 run은 그 baseline ID/identity/anchor를 복사 보존한다. 자산 대사 결과가 MATCH여야 전수 대사를 허용하는 것은 아니다. 토큰 자산 조회 실패도 거래 증거 자체의 조회와 분리한다.

과거 유잔고 환경에 baseline을 새로 만들어 정상처럼 맞추지 않는다. 검증된 환경 출처가 없으면 `ENVIRONMENT_NOT_VERIFIABLE`로 전체 결과는 INCONCLUSIVE이며, 얻은 개별 비교는 “관측된 기록 비교”로만 제시한다. 과거 환경 migration/정본 backfill은 별도 설계·승인 작업이다. signer·fee 설정의 정상 변경 이력은 거래 당시 block/tx position 기준을 사용하고 현재 설정과 다르다는 이유로 과거 기록을 MISMATCH로 만들지 않는다.

## 4. 비교 항목과 판정 제안

### 4.1 성공 ONCHAIN 거래

- Order FILLED ↔ 정확히 1 Trade ↔ 정확히 1 BUY/SELL transaction CONFIRMED ↔ 정확히 1 연결 CONSUMED PriceQuote.
- userId/symbol/side/mode, input, Order/Trade/transaction hash, transaction.type↔side와 sender/nonce/block 일치.
- RPC transaction hash/from/to/nonce/value 및 chain binding, receipt hash/status=success/block/hash/canonical/confirmations 검증. rawTransaction은 내부 hash/decoded chainId·sender·destination·nonce/calldata 검증에만 쓰며 응답·결과 payload·로그에 복사하지 않는다. raw가 유실돼도 RPC의 충분한 검증 가능한 증거가 있으면 해당 보조 검사가 미실행임을 명시한다.
- calldata의 함수 방향과 PriceReport 모든 필드·signature를 DB quote와 내부 비교한다. quote report digest/recovered signer 검증은 기존 EIP-712 순수 계산 사용; 새 서명 발급 금지. signer는 해당 성공 receipt의 PriceReportConsumed와 거래 당시 설정 근거로 대조하며 현재 signer와 단순 비교하지 않는다. calldata 내부 bytes/signature는 UI·일반 로그에 노출하지 않는다.
- Vault Bought/Sold 정확히 1개, operator/input 검증. Oracle PriceReportConsumed도 정확히 1개, quoteId·price·observedAt·validUntil·signer 및 주소 확인. calldata와 receipt block timestamp로 만료/미래 오차 검증. 과거 quote가 **현재 시각**에 만료됐다는 이유로 실패시키지 않는다.
- BUY: `Order.input=Trade.quote=event.krwIn`, `Trade.base=event.tokenOut`.
- SELL: `Order.input=Trade.base=event.tokenIn`, `Trade.quote=event.krwOut`.
- 양쪽: `Trade.fee=event.fee`, `Trade.price×1e8=event.priceE8`, 출력≥보고서 minimumOutput. 수량은 18자리 정수로 exact 비교한다. BUY quote는 수수료 포함 총 입력, SELL quote는 수수료 차감 순 출력이다. fee를 다시 더하거나 빼지 않는다.
- 현재 feeBps로 과거 출력을 재계산하지 않는다. 초기 구현에서는 실제 이벤트값/보고서와 DB 수량의 일치를 필수로 확인한다. 과거 fee 변경의 tx 위치별 산식 재구성은 별도 검증 항목이며 이것을 검증했다고 주장하지 않는다. `PriceQuote.fee`는 발급 시 참고값이므로 체결까지 fee 변경 가능한 현 계약에서 Trade.fee와 무조건 같아야 한다는 조건은 넣지 않는다.

### 4.2 과거·실패·대기·비온체인

| 사례 | 제안 판정 / 이유 |
|---|---|
| 모든 성공 DB·RPC·Vault·Oracle 근거 대응 | MATCH / 대응 범위를 명시 |
| stable cut+검증된 환경에서 Trade 수량/price/fee 또는 소유·방향·hash 연결이 서로 모순 | MISMATCH / 구체 expected/actual 및 check code |
| 성공 receipt가 다른 to/from, 실패 status, 잘못된/중복 Vault 이벤트인데 DB FILLED/CONFIRMED | MISMATCH / canonical 관측 근거가 있는 모순 |
| FILLED인데 Trade 없음, CONFIRMED 거래에 주문/Trade 없음 또는 DB_ONLY에 온체인 거래 링크 | stable snapshot에서 명확한 구조 모순은 MISMATCH. chain 검사를 못 했으면 별도 chain coverage는 incomplete |
| UNKNOWN 실행 유형 | INCONCLUSIVE / LEGACY_EXECUTION_UNKNOWN. hash 유무나 현재 flag로 추론하지 않음 |
| receipt/tx 미존재, RPC 장애/과거 block 미지원, cut 변경/reorg, 환경 미확인 | INCONCLUSIVE / missing evidence는 손실 확정 아님 |
| DB_ONLY 명시적 완료 | DB_LINK MATCH/MISMATCH와 CHAIN NOT_APPLICABLE 분리. 온체인 검증 성공 수에 넣지 않음 |
| REQUESTED/PENDING_ONCHAIN, CREATED/SIGNED/SUBMITTED/REVIEW_REQUIRED | 미정산 목록으로 노출·INCONCLUSIVE, 복구/실행하지 않음 |
| ONCHAIN FAILED, tx/receipt 없음 | 정상적인 사전 전송 실패 가능. NOT_EXECUTED_OR_UNPROVEN 정보 표시, chain INCONCLUSIVE. 성공 체결 누락으로 단정하지 않음 |
| FAILED transaction에 canonical failure receipt 있고 Trade 없음 | 실패 기록 대응 MATCH, 성공 거래 검증 수와 분리. 성공 receipt/Trade가 있으면 MISMATCH |
| CANCELED/기타 과거 상태 | 저장 근거 범위만 표시. 취소 기능이 현재 완성됐다고 쓰지 않음 |

항목 verdict와 전체 실행 상태를 분리한다. run lifecycle은 RUNNING/COMPLETED/INCOMPLETE/FAILED, verdict는 MATCH/MISMATCH/INCONCLUSIVE다. **전체 MATCH는** 명시된 범위의 source/event scan이 complete이고 모든 필수 검사가 성공한 경우만 가능하다. 검증 가능한 모순은 항목에 보존하지만 경합/전체 coverage 실패 run은 INCONCLUSIVE이며 “전수 완료”로 표시하지 않는다. 빈 원장은 “대상 0건” 표시, 거래 성공 검증 실적으로 쓰지 않는다.

### 4.3 양방향 누락 검사

DB→chain만 보면 DB에서 삭제된 주문/Trade를 놓친다. baseline 다음 block부터 cut B까지 해당 Vault Bought/Sold 로그도 chunked block range로 전수 조회하고 DB txHash에 연결한다. canonical 성공 Vault 거래가 DB에서 설명되지 않으면 stable/verified epoch에서 `CHAIN_EVENT_WITHOUT_DB_SETTLEMENT` MISMATCH다. 제출/미정산 기록이 있는 거래는 누락 확정 대신 INCONCLUSIVE로 분리한다. 대상 밖 operator가 같은 Vault에서 거래한 경우도 미설명 이벤트로 표시하며 임의 사용자 귀속은 하지 않는다. scan이 provider 제한/timeout으로 중단되면 coverage incomplete이지 “로그 없음”이 아니다.

## 5. DB/chain cut·자원 격리 제안

1. 수동 실행 admission 1개/프로세스, 중복 실행은 409/429, 자동 retry 금지. 고정 worker/queue와 명시적 RPC timeout·총 실행 deadline을 둔다. AI worker를 쓰지 않는다. 프로세스 재시작 시 미완료 run은 INCOMPLETE로 남기며 자동 실행하지 않는다.
2. B.number/hash와 환경 출처를 관측한다. 거래 DB에서 **짧은 read-only REPEATABLE_READ snapshot**으로 대상·링크·상태 projection을 수집한다. Order/Trade/BUY·SELL transaction과 연결 quote, dangling records를 포함한다. PESSIMISTIC_WRITE·사용자 잔고 잠금·거래 중단 없음. baseline 정보도 관측 provenance에 포함한다.
3. 증가형 ID가 있는 Order/Trade/transaction의 high watermark, snapshot 시각·snapshot ID, 대상 전체 manifest/digest를 확보한다. PriceQuote는 quoteId PK이므로 최대 ID로 범위를 만들지 않고 **최초 snapshot의 Order 연결 및 quoteId manifest membership**으로 대상·링크를 고정하고 재확인한다. RPC는 DB transaction 종료 후 수행한다. 보고서·signature/raw 원문은 새 audit 테이블에 복사하지 않고 비교용 hash·필수 비민감 필드만 보존한다.
4. DB snapshot 수집도 statement/transaction timeout·행/바이트 상한을 둔다. 페이지 수집은 **동일 snapshot transaction 안에서만** 한다. 각 페이지를 별도 snapshot으로 읽어 하나의 시점인 것처럼 조합하지 않는다. 상한 초과는 INCOMPLETE/INCONCLUSIVE이며 기존 10,000행 ReserveStore를 그대로 호출해 “전수”라 부르지 않는다. 큰 원장 지원은 chunk별 서로 다른 cut을 분명히 표시하거나 별도 snapshot export 설계 승인이 필요하다.
5. RPC transaction/receipt가 B 이하이고 확인 수를 충족하는지 확인, 이벤트는 고정 anchor..B 구간에서 모든 페이지 완료를 추적한다. block-number 기반 eth_call은 canonical hash 재확인한다. reorg/다른 state로 관측 기준이 바뀌면 판정 보류한다.
6. 종료 시 대상 DB projection/링크/상태와 범위 membership을 새 read-only snapshot에서 재확인한다. 단순 updatedAt만 비교하지 않는다. high watermark 이하의 비대상 대기 주문이 새로 정산된 경우도 포착하도록 snapshot의 상태를 포함한다. 신규 주문은 최초 watermark 밖으로 명시 제외하지만 **head가 바뀌면 초기 보수적 정책은 CUT_CHANGED/INCONCLUSIVE**다. 그래서 신규 주문/블록이 발생하는 busy 환경에서 전수 판정이 자주 불가할 수 있음을 UI에 설명한다.
7. cut이 stable일 때만 definitive verdict. 최종 결과는 audit 전용 테이블에 별도 짧은 write transaction으로 저장한다. 원장/온체인 자산 수정 없음. 저장 실패를 정상 완료로 표시하지 않는다.

이 절차는 동일 DB snapshot과 지정 chain 관측을 비교한 **안정된 감사 창**이다. DB와 블록체인 간 분산 transaction이나 원자적 세계 시점이 생긴다는 뜻이 아니다. ongoing 거래를 막아 이를 보장하려 하지 않는다. 비교 대상과 관측 시점·anchor·coverage를 결과에 항상 남긴다.

## 6. API·UI·DB 변경 제안

ADMIN 전용 `POST /api/admin/trade-audits`는 수동 run 생성(202/runId), `GET /api/admin/trade-audits`는 cursor 이력, `GET /api/admin/trade-audits/{id}`는 cut/summary, `GET /api/admin/trade-audits/{id}/items`는 verdict/mode/side/orderId 필터·cursor page를 반환한다. 범위는 서버 검증된 환경 anchor부터 고정 cut까지이며 사용자 입력 RPC URL/임의 주소/SQL/개인키를 받지 않는다. 기능 기본 비활성·disabled 응답은 명시한다. USER403/anonymous401, API/UI 둘 다 통제한다.

새 package `tradeaudit`(제안) 아래 reader/validator/store/coordinator/DTO/controller를 분리한다. 기존 mutable receipt reconciliation은 주입하지 않는다. `ContractEventParser`와 `TokenUnits`/EIP-712 순수 helper는 재사용한다. Oracle report parser/calldata decoder와 reason별 read-only RPC evidence reader는 신규다. 기존 reserve boolean reader·10,000행 store는 직접 대체하지 않고 cut/identity 설계 패턴만 재사용해 reserve 회귀를 최소화한다.

추가 테이블 제안:

- `trade_audit_runs`: UUID, actor, lifecycle/verdict, 시작/종료, baseline/execution namespace, chainId/주소/code hash/anchor/cut block·hash, DB snapshot 시각·watermarks·manifest hash, 범위·검사 버전·coverage/count/reason. provenance 생성 후 불변; lifecycle은 허용 전이만 사용하고 verdict/count/coverage 등 결과 필드는 종료 시 한 번 확정한 뒤 종료 결과 전체를 불변으로 유지한다.
- `trade_audit_items`: runId+stable item key unique, order/trade/tx/quote 식별자(존재 시), mode/상태, verdict/reason, 비민감 expected/actual·receipt block/hash·event logIndex·evidence digest. orphan event는 txHash/logIndex 키. 완료 item append-only.
- 대상 projection manifest는 runId에 결합된 비민감 snapshot payload/별도 source 테이블 중 구현 단계에서 크기/페이징을 기준으로 선택하되 DB cut 보존 요건은 같게 유지. 금액 정수는 문자열/NUMERIC, double 금지. raw tx/signature/password/JWT/RPC credentials 없음.

Order/Trade/잔고 스키마와 쓰기 경로를 바꾸지 않는다. schema는 기존 additive 방식을 따라 신규 테이블/index만 추가하고 격리 DB에서 최초·반복 적용을 검증한다. read-only source projection과 audit write repository를 분리한다. 불변 결과 삭제/정정 API·baseline reset·재실행 거래·자동 수정 API 없음. 결과 보존 기간/상한은 사용자 승인으로 운영 정책을 별도 정한다.

ADMIN UI: 기존 AI 진단/장부·체인 자산 대사와 구분한 **거래 기록 전수 대사** 패널. 수동 실행 확인 → RUNNING/조회 진행 → 전체 coverage와 MATCH/MISMATCH/INCONCLUSIVE 건수 → 항목 필터·페이지 → expected/actual 및 근거 상세. DB_ONLY·UNKNOWN·미정산/사전 실패 건수 별도, scan incomplete 큰 경고, 최신 상태가 아닌 과거 관측임을 표시한다. JSON dump·재전송/잔고 보정 버튼 없음. 모바일 표는 카드/상세로 전환, 로그아웃 시 in-flight 응답 차단한다. 기존 USER 흐름/REST·STOMP 계약 유지, 감사 전용 WS는 추가하지 않는다.

## 7. 테스트·검토·승인 경계

승인 후 독립 기능 fixture와 Synthetic만 사용한다. 실제 Toss/유료 AI/AWS/보존 acceptance·개발 DB 연결 금지.

- 단위: BUY/SELL exact 정수·수수료 gross/net·minimumOutput, event 종류/주소/operator/중복/누락, quoteId·소유·side·calldata·signature/report/digest·nonce/hash/block·confirmation, 과거 만료/fee/signer 변경, dangling DB/chain、legacy UNKNOWN、DB_ONLY、정상 사전 실패와 mined failure 구분.
- 실제 PostgreSQL: snapshot isolation/read-only·source row 무잠금/RPC 밖 transaction, 대상 상태 변경·Trade/quote/link 변경·membership 변화·늦게 commit한 낮은 ID, 최초/반복 schema·audit 결과 immutable·권한·cursor/coverage/count. 실행 중 새 데이터·다중 요청/중단을 재현한다.
- 새 persistent Anvil: 서명 BUY/SELL 성공의 Order/Trade/tx/quote/Vault/Oracle 대응, oracle event report 연결, 임의 fixture DB 변조 MISMATCH, snapshot/revert와 새블록 INCONCLUSIVE, receipt 미존재·RPC 장애·불완전 log page·deadline、chain orphan event. 계약이 막는 비정상 이벤트/서명은 unit/mock로만 주입하고 EVM에서 발생했다고 주장하지 않는다.
- 불변성: 실행 전후 원장/주문/견적/tx/자산·nonce/block 변화 없음(별도 concurrent fixture mutation만 예외). tx sender/settlement/quote 발급 호출0, 감사 결과 테이블만 증가. 계정/환경 secret0·Toss0·AI0.
- 웹: ADMIN/USER/anonymous, run 상태/부분 결과·빈 대상·기능 off·오류/재클릭·logout stale response·필터page·mobile·기존 55개 회귀 포함. 실제 Chrome fixture와 PostgreSQL/Anvil E2E의 범위를 구분한다.
- 전체 backend/Foundry/web 회귀와 opt-in 실제 fixture E2E를 분리 보고, 31개 skip의 사유와 실제 외부 미검증 유지. 독립 검토→보완/재검토→secret 및 runtime 제외 audit→기능 commit→통합 merge/회귀. 자동으로 melon-init/acceptance에 적용하지 않는다.

이번 설계의 검증은 코드·경로·Git/문서 대조뿐이다. 위 테스트는 **계획이며 미실행**이다. 구현 승인 필요 항목: 성공 거래+실패/미정산 보조 분류, 양방향 Vault event coverage, quote/Oracle/calldata 연결, 보수적 stable cut·bounded incomplete, 추가 감사 테이블/비동기 ADMIN API/UI. 사용자 지갑·지정가·원장 보정·AI 확장은 포함하지 않는다.
