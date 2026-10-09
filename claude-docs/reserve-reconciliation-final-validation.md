# 장부·체인 대사 — 최종 보완·브랜치 검증

> 2026-10-09 / 대상 `feat/reserve-reconciliation` / 최종 검증·독립 검토 완료, 기능 브랜치 커밋 대상
> 이전 계산식·모델은 [구현 보고서](reserve-reconciliation-implementation.md), 아래는 후속 승인 범위의 최신 검증이다.

## 보존 경계

원본 `melon-init`/acceptance baseline, 기존 개발 DB·Anvil·.env·Toss·AI 설정을 변경하거나 사용하지 않았다. 새 feature 전용 PostgreSQL(25542)/Anvil(25545), 독립 키·새 계약·SIMULATED만 사용한다. 기존 fixture의 DB를 초기화하거나 부분 복구하지 않고, 세 번째 신규 fixture에서 실행했다. 유료 AI/실제 Toss 호출 없음. 원본으로 merge·배포하지 않는다.

## 실제 PostgreSQL/Anvil 동시성

`ReservePostgresAnvilE2ETest`는 실제 Spring transaction proxy·PostgreSQL·RPC·컨트랙트를 사용한다. RPC reader spy는 결과를 mock하지 않고 `callRealMethod`로 실제 B-block snapshot을 얻은 다음 **테스트에서만** CountDownLatch로 잠시 멈춘다. 첫 DB cut이 닫힌 상태에서 별도 thread/connection의 변경을 commit한 뒤 대사를 재개한다. 제품 서비스에 sleep/barrier/hook은 추가하지 않았다.

| 실행한 변경 | 경쟁 중 판정 | 안정 후 |
|---|---|---|
| 기존 WalletService.faucet 호출로 사용자 잔고와 지급 journal 동시 commit | INCONCLUSIVE / CUT_CHANGED | MATCH |
| SQL로 기존 Trade.created_at 1초 증가 commit | INCONCLUSIVE / CUT_CHANGED | MATCH |
| 전용 Anvil evm_mine으로 새 블록 생성 | INCONCLUSIVE / CUT_CHANGED | MATCH |

각 경합에서는 checks가 비어 있고 liquidity=null임을 확인했다. Trade 검증은 **생성시각 metadata 변경에 대한 cut 감지**이며 실제 거래 금액을 수정하거나 정산 worker 부하를 재현한 것은 아니다. 변경된 시각·블록번호도 실제 SELECT/RPC로 확인한다. 신규 잔고/journal과 metadata 변경은 fixture에 유지하며 baseline을 재설정하지 않는다.

Store.cut 내부의 실제 PostgreSQL `SHOW transaction_isolation = repeatable read`, `SHOW transaction_read_only = on`을 확인했고, RPC reader 구간에서는 DB transaction 비활성임을 assert했다. writer는 첫 snapshot 종료 후 commit하므로 장시간 read transaction/write lock을 유지하지 않는다. 여러 쿼리 사이에 writer를 끼워 넣어 repeatable-read 자체의 visibility를 테스트하거나 장시간·고부하/다중 백엔드 경쟁을 재현한 것은 아니다.

같은 E2E에는 서명 BUY/SELL→receipt/event 검증→DB 정산→MATCH, 미정산 INCONCLUSIVE, 기준점/journal immutable trigger, 대사 불변성, 최종 잔고+1 MISMATCH도 포함한다. **JUnit 1개 안의 여러 하위 시나리오**이며 경합 세 건을 독립 JUnit 테스트 세 개로 세지 않는다.

## operator ETH/Gas 최소 보완

기존 `BlockchainTransactionSender`는 주문별 ethGasPrice/ethEstimateGas와 120% gasLimit buffer를 사용한다. ETH 잔고를 별도로 비교하는 사전 지급 보증은 없으며 제출 실패 처리는 기존 경로대로다. 전송/서명/자동 충전 경로는 변경하지 않았다.

대사 reader에 `eth_getBalance(operator, B.number)`를 추가했다. ETH는 정확한 wei **문자열** `operatorEthWei`로 관측하며 ADMIN에 ETH 수량을 표시한다. ETH는 토큰 장부 정합성 판정식에 넣지 않는다. 0원이어도 토큰 장부 MATCH는 가능하다.

- 값 관측: `gasAssessment=READ_ONLY_NOT_ESTIMATED`.
- 조회 실패/과거 기록의 필드 없음: ETH=null, `BALANCE_UNAVAILABLE_NOT_ESTIMATED` (신규 계산 시). 기존 증거는 수정하지 않는다.
- UI: Gas 비용 미추정·충분/부족 판정 안 함, 미래 Gas 가격·미전송 거래·주문별 소모량을 반영하지 않는다고 명시한다. 잔고0/조회불가도 별도 확인 필요이며 자동 ETH 충전은 없다.
- consistent cut이 없으면 기존 정책대로 liquidity=null이다. 단순 잔고만으로 미래 거래를 보증하지 않는다.

변경: `ReserveModels`, `RpcReserveChainReader`, `ReserveCalculator`, `reserve-panel.js`, 관련 단위/브라우저/E2E. ETH0/null과 정확한1wei UI를 검증했다.

## CRLF 실패 재확인 및 최소 수정

일반 Windows build (`gradlew test --tests '*SkillRegistryTest' --rerun-tasks`)에서 기존4개 중3PASS/1FAIL을 다시 확인했다. 이미 CRLF인 fixture에 테스트가 LF→CRLF 치환을 적용하여 `\r\r\n`이 생겼다. production registry는 정상 CRLF를 LF로 변환하지만 테스트가 만든 이중CR 데이터는 승인 hash/문법과 달라 Skill이 비활성화됐다.

기존 조건부 결과(2026-10-08 build resource 수동LF + task 제외 전체345PASS/31SKIP)는 이전 보고서에 유지한다. 이번에는 **테스트 파일만** 수정했다. resource helper에서 fixture를 LF 기준으로 읽고, 같은 테스트에서 정상 Skill의 LF/정상CRLF 입력을 각각 명시적으로 구성한다. 변조 Skill은 LF 입력으로 격리됨을 확인한다. 잘못된 YAML 테스트도 이제 checkout CRLF 때문에 우연히 실패하는 것이 아니라 변경한 invalid 입력으로 실패하도록 된다.

AI 운영 클래스·지식·manifest·hash·registry/handler/provider 구현은 수정하지 않았다. build resource 수동 수정, Gradle processResources 제외, 전역 Git autocrlf 변경도 없다. 위 최소 테스트 수정으로 SkillRegistryTest4/4 PASS, 대사29개와 합쳐 targeted33/33 PASS.

## 최종 실행 범위

- 신규 PostgreSQL/Anvil E2E 1/1 PASS (실제 경합3종·ETH 관측·BUY/SELL 포함).
- 대사 단위/H2 29/29, SkillRegistryTest4/4 PASS.
- 웹55/55 PASS. Vite build/Chrome fixture smoke desktop·360px 모바일 PASS, operator1ETH/Gas 미추정 안내도 실제 브라우저에서 assert. REST/STOMP는 fixture이며 실제 backend E2E와는 별도다.
- Foundry36/36 PASS, fuzz 포함. Solidity 변경 없음.
- 모든 외부·유료 opt-in을 false로 두고 **일반 `gradlew test --no-daemon --rerun-tasks`** 전체 회귀 **377개: 346PASS/0FAIL/31SKIP**. XML 집계 tests377/failures0/errors0/skipped31을 확인했다. processResources를 실제 실행했고 세 Skill build resource 모두 CRLF임도 확인했다. 수동 정규화/task 제외 없이 통과한 결과다.
- 위31개는 기존 외부DB/Anvil·유료provider/평가 및 신규E2E 선택 테스트다. 신규E2E는 별도 true flag로 위1/1을 실제 실행했다. 전체 기본 회귀와 별도E2E를 중복 집계하지 않는다.

## 미검증·향후 반영 조건

실제 Toss 장중 인수/유료 AI 평가/성공 MATCH 자동 AI 진단/EC2·운영 부하는 이번 보완 범위 밖이다. 다중 서버 nonce·고부하 snapshot 경쟁, 전체 RPC wall-clock deadline/canonical 캐시 보완, 주문별 Gas 지급 판정도 제외한다.

acceptance 반영은 장중 인수 전 보존 요청 해제와 별도 merge 승인 후 진행해야 한다. 스키마 신규 열/테이블/trigger DDL 권한과 기본 disabled 설정을 확인한다. 과거 행은 UNKNOWN이고 기준점은 신규0원 장부만 허용하므로 현재 유잔고 acceptance DB에 feature를 merge하고 바로 baseline을 생성할 수 없다. 기존 데이터를 지우거나 소급 추론하지 말고 별도 신규 환경 또는 독립 근거가 있는 migration 설계를 승인받아야 한다. runtime/키/chain state는 feature Git commit에서 배포되지 않는다.

## 독립 검토·Git 보안 점검

문서/코드/테스트 초안 동결 후 `review_reserve_final` 독립 검토: **발견된 필수 수정 없음**. 실제SQL/RPC 경합 구조·ETH 관측·Gas한계·CRLF 테스트-only 변경·신규 파일을 확인했다. 검토자가 웹 대사7/7 직접 실행, 전체 XML377/0FAIL/0ERROR/31SKIP, build resource3개 CRLF, diff check와 빈 index를 읽기 전용으로 확인했다. PG/Anvil E2E·전체 Gradle·Foundry·Chrome은 구현자 실행 결과로 구분한다. 선택 문서 표현 지적(변조 Skill도 두 형식이라는 표현)은 실제 정상 Skill 두 형식/변조 LF로 좁혀 반영했다. 제품 코드 수정 없음.

소스·문서 allowlist만 stage하고 `.env`, private key, DB dump, Anvil state, runtime/log/build/의존성 산출물을 제외한다. 생성된 feature fixture의 실제 secret 값과 staged blob 대조도 수행한 뒤 기능 브랜치에만 commit한다. 최종 commit 해시는 완료 답변 및 Git log에서 확인한다. 이 보안 점검은 검토자 실행이 아니라 구현자의 최종 commit 절차다.

최종 stage 점검: 승인된 소스·문서40개, 생성fixture3곳의 실제secret값9개(각 DB password/operator/price signer)와 working file 및 staged blob 대조 일치0, 허용목록 밖/금지경로0, cached diff check PASS. 값은 출력하거나 문서에 기록하지 않았다. 새fixture 컨테이너2개만 라벨 확인 후 정지하고 DB/chain/실패증거는 보존했다. 기존/acceptance 환경은 사용하지 않았다.
