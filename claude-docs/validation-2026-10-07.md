# 기존 기능 검증 / 복원 readiness 문제 보고 (2026-10-07)

사용자 요청: 추가 기능 이전에 가능한 기존 기능 검증을 진행한다. 문제 발견 시 바로 수정하지 않고 원인·재현 증거·수정 후보를 먼저 보고한다. AWS/유료 AI/신규 부하·fault runner 구현은 제외했다. 기존 미커밋 변경을 보존했다.

## 실행 결과

| 검증 | 명령 / 결과 |
|---|---|
| Backend 전체 | `./gradlew.bat test --no-daemon --rerun-tasks`:347개 중317통과/30조건부skip/실패0/오류0 |
| Contracts | `forge test -q`, `forge test --summary`:36통과/실패0/skip0, fuzz 각각256 runs 포함 |
| 웹 회귀 | `npm test`:48통과/실패0 |
| 제품형 웹 브라우저 | `npm run test:browser`:build90모듈, Chrome desktop/mobile fixture PASS. 최초 sandbox 파일접근 오류 후 권한 허용 재실행. 실제 Toss/유료 AI UI 인수는 아님 |
| 배포 helper unit | `python -m unittest discover -s deployment -p 'test_*.py'`:15개 중13통과/2POSIX 조건부skip/실패0 |
| 격리 운영형 E2E | `python deployment/local-smoke.py`:14 checks 통과 후 새 거래 DB의 pg_restore 실패, 종료1. 최종 복원 거래 포함 나머지2 checks는 미실행 |

기존 운영형16체크 통과 기록과 이번 실패를 구분한다. 이번 실행의 결과로 전체 운영형 검증 완료라고 쓰지 않는다. 기본 backend 조건부skip을 실제 외부 연동 테스트 통과로 쓰지 않는다.

## 이번 E2E의 통과 범위

- loopback gateway/private DB·RPC·backend, Toss 자격증명 없음, 새 시험키/체인 배포.
- nonroot/read-only backend와 image/env secret 비노출.
- 정적 SIMULATED 표시, 실제 합성 시장 REST, Native/SockJS WSS,6주기 차트 조회·인증/운영AI 경계.
- 실제 JWT/faucet/EIP-712 quoteId BUY→receipt 정산과 재사용 거부.
- backend/Anvil 재시작 후 합성 checkpoint·체결 보존.
- AI DB/닫힌 로컬 AI API 장애 격리, RPC 중단 시 견적 거부·시장/자산 조회 유지와 복귀.
- 로그인429·backend 중단 중 정적 합성 표시.
- 미정산 주문/전송/잠금0 확인 후 두DB+chain checkpoint 저장.

SELL 실거래, 실제 Toss 장중 브라우저 인수, 자동 진단의 실제 Anvil 성공 MATCH, 실제 공급자/ADMIN 화면 인수,1/3/5 부하와 상세 자원·latency 수집, 신규 fault runner, EC2/장기 chaos는 이번 실행 범위에 포함하지 않았다. 컨트랙트 SELL unit 통과와 실제 SELL E2E 미실행은 구분한다.

## 복원 실패 재현과 증거

- 신규 프로젝트 `exchange-ops-20261007145955`에서 checkpoint를 만든 뒤 `exchange-restore-20261007145955`의 새 빈root에 기존 `ops.restore`를 실행했다.
- `deployment/ops.py:232`의 `docker compose exec -T postgres pg_restore -U exchange -d exchange --exit-on-error --no-owner`가 종료1. helper가 stderr를 캡처하지만 예외 보고에 본문이 남지 않아 **pg_restore 직접 오류 본문은 미확보**다.
- Git 제외 증거 위치: `deployment/runtime/local-20261007145955/results.json`, `private-stack.log`, `private-restore.log`, `backup/checkpoint.json`. 시험용 secret/data도 runtime에 보존하므로 공개하거나 커밋하지 않는다.
- restore PostgreSQL 로그(UTC):06:04:20.863 임시 서버 ready →06:04:23.714 `database "exchange" does not exist` →CREATE DATABASE →06:04:25.482 shutdown →06:04:25.507 `the database system is shutting down`. AI DB도 최초 DB 생성 이전의 유사한 readiness 흐름을 보였다.
- checkpoint의5개 파일 SHA-256 모두 일치. 거래 dump30,308bytes. 같은 postgres16 이미지의 read-only `pg_restore --list` 성공(16.11/custom1.15/86TOC), `pg_restore --file /dev/null --no-owner` 전체 해독 종료0. 목록/해독 성공은 실제 DB 복원 성공을 뜻하지 않는다.

## 원인 후보와 수정 후보 — 승인 전 미수정

가장 유력한 원인은 최초 PostgreSQL 초기화의 **readiness race**다. 현재 Compose healthcheck는 기본 Unix socket의 `pg_isready -U exchange -d exchange`를 사용하고, restore는 `up --wait` 이후 바로 접속한다. 초기화 임시 서버가 healthy로 판단된 뒤 DB 생성/임시 서버 종료와 복원이 경합한 것으로 로그가 뒷받침한다. 정확한 pg_restore stderr를 확보하지 않아 전체 원인을 확정했다고 쓰지 않는다.

[PostgreSQL 공식 pg_isready 설명](https://www.postgresql.org/docs/16/app-pg-isready.html)에 따르면 올바른 사용자/DB 이름이 아니어도 서버 상태를 얻을 수 있다. 따라서 이 probe의 성공은 목표 DB 초기화 완료/실제 SQL 실행 가능을 보장하지 않는다.

수정 후보:

1. DB healthcheck를 명시적 TCP endpoint로 바꾸고 목표 DB의 실제 SQL 성공까지 준비 확인. 초기화 임시 Unix socket 서버를 준비 완료로 보지 않도록 한다.
2. restore의 pg_restore 시작 전에 양DB의 실제 SQL readiness를 bounded wait로 재확인. 임의 sleep이나 부분 복원 위에 무조건 재실행하는 retry는 쓰지 않는다.
3. pg_restore 실패의 안전한 분류/비공개 오류 증거를 보존해 다음 원인 분석을 가능하게 한다. secret/데이터 SQL을 일반 로그에 출력하지 않는다.
4. 승인 후 구현·신규DB 반복 복원 회귀·전체 E2E·독립 검토. 필요 범위는 사용자에게 먼저 제안한다.

## 환경 보존 / 현재 상태

- 시험 컨테이너/네트워크는 정확한 project만 down(no-v), 원본/복원 bind·backup·로그는 보존했다. 기존 exchange-postgres/exchange-ai-ai-postgres-1/exchange-anvil3개만 실행 중인 것을 재확인했다.
- 실제.env/개발 DB·체인/제품·배포 helper 소스는 변경하지 않았다. AWS 생성/유료 AI 호출/commit 없음.
- 문제 발견 이후 read-only archive/log/hash 검사만 진행했고 제품 수정·DB 보정·잠금 해제·추가 실험은 하지 않았다.
- 검증 및 보고서 문서화만 수행했으므로 별도 구현 검토는 생략했다. 다음 진행은 위 수정 후보에 대한 사용자 승인 또는 다른 인수 환경 준비다.

## 후속: restore 보강 승인 / 구현·검증 중

사용자는 stderr 안전 보존, 목표 DB 실제SQL healthcheck, restore 직전 양DB bounded SQL 확인, 신규 빈DB 반복 회귀/전체16체크·독립 검토를 승인했다. 앞선 미수정/생략 문구는 최초 실패 보고 당시의 기록이다.

- 먼저 `ops.restore_dump`의 private stderr/실패 metadata/재시도 금지부터 추가했다. healthcheck 변경 전 신규 `restore-regression-20261007070406459096` 첫 라운드에서 실제 `pg_restore` stderr `database "exchange" does not exist`를 확보했다. 기존 최초 실패의 직접 stderr를 복원한 것은 아니며 새 동일 경로 재현 증거다.
- 이 재현에서 목표 DB 생성 이전 접속은 확인했지만 최초 실행의 모든 원인/모든 restore 실패를 readiness race로 일반화하지 않는다. 원래 실패 로그와 신규 stderr를 구분한다.
- 이후 양DB의 TCP/실제SQL health script를 연결했다. password는 파일에서 검사 프로세스만 읽고 출력하지 않는다. 각DB 최대60초 준비 확인 후 dump 직전 재확인. pg_restore 자체는1회, 실패하면 부분root/증거 보존이다.
- 신규root3개 `restore-regression-20261007070539878353`에서3/3통과. 각라운드 두DB의체결·vector/chain 동일성을 확인하고 down(no-v)했다. 기존 개발 데이터는 사용하지 않았다.
- unit: Windows20개 중18통과/2POSIXskip(이후 양DB 사전검사 테스트를 추가해21개 중19통과/2skip). 첫 Linux ops18/18 통과 후 최종19개를 재실행한다. 독립 검토와 전체 local-smoke16 체크는 아직 진행 중이다.
- 변경은 deployment ops/Compose/새 readiness shell/restore regression과 관련 unit·문서만이다. backend/web/contracts/실제.env/기존 개발 DB·chain/유료 AI/AWS는 변경하지 않았다.

### 후속 최종 구현자 검증 / 독립 검토 대기

- `local-20261007160704` 전체 local-smoke16/16, 종료0. 원본 BUY/receipt·WSS·재시작·AI/RPC 장애 격리·consistent checkpoint, 새root 양DB/pgvector/chain 복원 뒤 새 서명 BUY 정산까지 통과했다.
- 최종 Linux ops19/19 통과. Windows 전체 helper21개 중19통과/2POSIXskip. 실패stderr canary의 비공개 보존/일반 예외 비노출·0600/0700·exclusive create, readiness deadline/허용 service·probe만 재시도, 양DB 준비 확인 실패 전 dump미실행을 검증했다.
- 신규root3개 반복 회귀3/3통과 증거는 위 run이다. 각각 최초 복원만 수행했으며 부분root 덮어쓰기·pg_restore retry 없음. 실패 재현과 보강 후 성공을 서로 다른 run으로 보존했다.
- 기존 backend347(317통과/30skip),forge36,웹48/Chrome fixture는 직전 사전점검 결과다. 제품 변경이 없어 이번에 다시 실행하지 않았다. actual EC2/장기 부하/실제 Toss/유료AI 인수로 일반화하지 않는다.
- 테스트 컨테이너/네트워크만 down(no-v)했고 bind/로그/backup·private stderr를 보존했다. 독립 검토 요청 전 코드·테스트·문서 초안을 동결한다.

### 독립 검토 완료

- `review_restore_readiness`: 발견된 필수 수정 없음. TCP/실제SQL·양DB/각dump bounded 확인·pg_restore1회·private stderr/POSIX권한/WindowsACL 한계·문서의 원인 확정 경계를 확인했다.
- 검토자가 직접 Windows helper21개19PASS/2skip, 신규stderr/metadata·회귀3/3·local16·chain 동일성 및 최종backup5개 SHA-256을 대조했다. Linux19/19와 DockerE2E는 구현자 결과를 확인했으며 독립 재실행하지 않았다. 공유서비스/유료API/AWS 변경 없음.
- 이번 승인 수정 범위는 구현/검증/독립 검토 완료다. 실제 EC2·장기 부하·실제Toss/유료AI/운영 자동진단 성공MATCH 인수는 여전히 별개로 남는다. 신규 운영 부하/fault runner 구현으로 범위를 확대하지 않았다.

## 기존 기능 실제 인수 착수 — Anvil 배포 상태에서 차단

- 사용자 승인 범위는 실제 Toss 장중 웹 BUY/SELL 및 실제 AI Agent/Skill·성공 receipt MATCH 자동 진단·ADMIN 조회 인수다. 운영 부하/장애 검증과 AWS 생성은 제외한다. 유료 AI 호출은 예상 횟수/비용에 대한 별도 승인 전 금지다.
- 2026-10-07 실제 Toss OAuth 및 당일 KR 시장 캘린더 조회 성공. integrated 애프터마켓은 15:30–20:00 KST로 조회 당시 열려 있었다. 정규장 종료만으로 전체 거래 불가라고 판단하지 않는다. 이것은 캘린더 확인이며 LIVE tick 수신/거래 인수 성공을 뜻하지 않는다.
- 17:44 KST 기존 backend를 AI_ENABLED=false / AI_AUTO_DIAGNOSIS_ENABLED=false 프로세스 환경으로 실행했다. PostgreSQL 연결 후 PriceReportStartupValidator에서 priceSigner 조회 결과가 비어 있어 종료했다. 웹 Vite 실행은 성공했으나 backend 차단으로 브라우저 주문을 진행하지 않았고 이번에 시작한 Vite만 종료했다.
- 읽기 전용 Anvil RPC: chainId=31337, blockNumber=0. 실제 .env의 MockKRW/mSEC/PriceOracle/ExchangeVault 네 주소 모두 eth_getCode=0x(0 bytes). 현재 체인에 해당 배포가 없는 것은 확인됐다. 체인이 비게 된 시점/재시작 원인은 이 증거만으로 확정하지 않는다.
- 체인/DB 초기화, 재배포, .env 수정, 임시 계정 생성/주문, AI 유료 호출은 하지 않았다. 기존 변경사항과 데이터를 보존한다. 두 인수 모두 미완료이며 baseline 동결도 하지 않는다.
- 조치 후보는 기존 Anvil 상태의 안전한 복원 또는 현재 빈 체인에 재배포 및 서명자/운영자/유동성 검증이다. 기존 DB 거래 기록과 새 체인의 관계를 확인한 후 별도 승인을 받아 수행해야 한다.
- 자동 진단의 source는 REVIEW_REQUIRED이며 일반 FILLED 주문을 자동 진단하는 기능이 아니다. 성공 MATCH 인수는 REVIEW_REQUIRED 대상에 성공 receipt가 존재하는 시나리오를 별도로 구성해야 하며, 기존 거래 행을 임의로 변경해 통과시켜서는 안 된다.
