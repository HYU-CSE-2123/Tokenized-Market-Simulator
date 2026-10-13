# Phase A 신규 fixture subnet 충돌 보완

> 2026-10-14. 입력 integration/additional-features `73f8f3d`. 제품 소스·기존 환경 변경 없음. 실행 결과는 완료 후 추가한다.

> 최신 후속: Docker 정상 응답 확인 후 통합 `a317f67`의 새 session에서 필수 실제E2E **5/5 PASS**. 아래 환경BLOCKED는 이전 실행 이력이며 재사용하지 않았다. 상세는 문서 끝의 후속 결과를 확인한다.

## 수정 범위

`deployment/phase-a.py`: Docker network create의 확인된 subnet overlap stderr에만 최대8개 중복 없는10.240.x.0/24 후보를 순차 시도한다. 기존 네트워크 list/inspect/delete/update 없이 Docker 생성 결과로 판정한다. 각 시도의 metadata/원문 stderr는 신규 session private 로그에, 후보/결과는 신규 fixture network-allocation.json에 남긴다.

permission/daemon/name collision 등 다른 실패와 timeout/성공 여부 불확실한 생성은 재시도하지 않는다. 각 명령 timeout15초이며 무한 반복하지 않는다. 소유권/loopback identity/clock2초·왕복불확실성/source hash/image digest/import 차단은 그대로다. DB/계약 배포 이전에만 네트워크 후보를 변경한다. 실패 DB/체인 위에 restore/재배포를 반복하지 않는다.

`deployment/test_phase_a.py`: overlap 후 성공,8회 소진과 후보 중복 방지, 일반 오류/불확실 timeout 즉시 중단과 증거 보존을 검증한다. 실제 Docker overlap 재현 여부와 Mock 단위 결과를 구분한다.

## 실행·검토

Python 단위와 독립 검토 후 기능 커밋을 통합하고 최신 통합 HEAD의 신규 session에서 Faucet/reserve/audit/conditional·observation/별도 JVM restart를 실행한다. 독립 업무4종은 각각 새 PG/Anvil/키/포트/원장을 쓰며 restart만 성공 conditional 원장을 별도 JVM에서 읽는다. SKIP은 PASS가 아니다. 기존13일 실패와 모든 state/log를 보존한다. 실제 Toss/KIWOOM/AI/Sepolia/AWS는 호출하지 않는다.

## 이번 실제 실행 결과 — BLOCKED

- 기능 커밋 `72915fd`, 통합 병합 `6ed6d5a801e7f50d40fa948d856b97f0f5a50e9c`. 기존제품 변경0. harness Mock/guard 단위 **15/15 PASS, SKIP0**. 최초 독립검토 `review_phase_a_subnet`가 같은Python15/15를 별도로 실행했고 **발견된 필수 수정 없음**이다. 실제 Docker overlap 성공/소진을 재현한 것이 아니라 Mock 분류/제한 회귀다.
- 신규 준비 session `phase-a-20261013163039-aa3060c7`(UTC13일16:30=KST14일01:30). sourceHead 확인 뒤 cached postgres:16 이미지 inspect가 Docker Desktop Linux Engine HTTP500으로 실패했다. 신규 네트워크/DB/Anvil/키/계약 생성 및 clock preflight/E2E는 아직 실행되지 않았다. session source/metadata/stderr는 private runtime에 그대로 보존했다.
- 읽기 전용 추가 `docker version`도 /v1.51/version 요청에 같은500을 반환했다. Client28.4.0/API1.51/contextdesktop-linux 확인, Server버전은 조회 불가. Windows com.docker.service는 Stopped로 표시됐다. 이 사실만으로 API버전 불일치·Docker 내부 원인을 확정하지 않는다. 서비스/WSL/전체Docker 재시작·설정 변경 없음.

| 필수 검증 | 이번 결과 |
|---|---|
| Faucet 실제 PostgreSQL/Anvil | NOT_RUN |
| 장부-체인 자산 대사 실제 PostgreSQL/Anvil | NOT_RUN |
| 거래 전수 대사 실제 PostgreSQL/Anvil | NOT_RUN |
| conditional/observation 실제 PostgreSQL/Anvil | NOT_RUN |
| 별도 JVM snapshot restart | NOT_RUN |

전체5종은 **환경 전제 BLOCKED /실제 PASS0 /테스트 FAIL0 /SKIP0 /NOT_RUN5**다. 이전 통합451/35·웹84/Foundry36·feature E2E PASS를 이번 실행 결과로 쓰지 않는다. 제품 변경이 없고 Docker가 준비되지 않았으므로 backend/web/Foundry 전체 회귀도 이번에 재실행하지 않았다. 사용자 측에서 Linux Engine의 정상 Server응답을 확인한 후 새 session/fixture로5종을 실행해야 한다. 실패 session/기존 증거를 초기화하지 않는다.

## 작성 문서 안내

- 이 문서: harness 변경·독립 검토·이번 차단 결과와 후속 실행 조건.
- 신규 [키움 실제 인수 사전 준비](kiwoom-live-acceptance-preparation.md): App Key/전용runtime/승인, 장중 최초0s 수신·재연결·5초 시간 해석, native봉 시작/종료 가설과tick/OHLCV 대조·asOf 한계 검증 절차. 실제 키움 호출/수집도구 구현은 하지 않았다.
- 기존 최종 통합 보고서와 공통README/implementation-log에는 후속상태 링크를 추가하며13일 실패/통과 이력을 지우지 않는다.

## Docker 정상 확인 이후 최신 통합 E2E — 5/5 PASS

실제 입력 HEAD `a317f678d1b06e323eb308a69bf022e65633a59b`, integration/additional-features clean. Docker version의 Server와 docker info 모두 정상응답: Engine28.4.0/API1.51/Linux/x86_64. 에이전트가Docker/WSL/서비스를 재시작하거나네트워크를초기화하지않았다.

새 session `phase-a-20261013173219-bb0717fd`(UTC13일17:32/KST14일02:32), JDK21.0.9. 이전실패session을재사용하지않았다. offline init의이미지/sourceHash/digest검증후 다음4개업무fixture를각각신규DB/Anvil/키/loopback포트로실행했다. 성공conditional원장만별도JVM restart로검증한다.

| 실제 클래스 | 새 fixture /DB·RPC 포트 | PASS /FAIL /SKIP |
|---|---|---|
| FaucetPostgresAnvilE2ETest | faucet-e76f5239 /44634·39143 | 1 /0 /0 |
| ReservePostgresAnvilE2ETest | reserve-2211dd22 /43986·36300 | 1 /0 /0 |
| TradeAuditPostgresAnvilE2ETest | audit-7464ce32 /44171·48094 | 1 /0 /0 |
| ConditionalPostgresAnvilE2ETest | conditional-f273cb88 /39427·46449 | 1 /0 /0 |
| ObservationRestartPostgresTest | 동일 conditional fixture, 별도 Gradle/test JVM | 1 /0 /0 |

총계 **5 PASS /0 FAIL·ERROR /0 SKIP /0 BLOCKED /0 NOT_RUN**. 각1개는 여러 업무 assertion을 포함한 실제 macro E2E이며 assertion 수를 테스트 건수에 더하지 않는다. 시세는 Synthetic, AI는 비활성이다.

clock preflight4개 모두 기존2초 경계를 통과했다. DB-host offset은 Faucet+0.132181초/reserve-0.326126초/audit+0.426517초/conditional-0.134473초, RTT는0.235005~0.309464초다. 왕복 불확실성을 포함한 worst는0.261854/0.443628/0.581249/0.263337초다. 장기 시각 안정성이나 모든 시각의 역행 부재를 보증하는 측정은 아니다.

**실제 Docker 충돌 후 회복 확인**: Faucet은1회 overlap 후2번째 후보, conditional은2회 overlap 후3번째 후보 생성에 성공하고 E2E도 PASS했다. reserve/audit은 첫 후보에 생성했다. 후보별 stderr/metadata/network-allocation.json은 신규 private runtime에 보관한다. 8후보 소진·다른 오류 즉시 중단은 앞선 Mock 단위 검증이며 이번 실제 Docker에서 의도적으로 재현하지 않았다.

이번에 만든 컨테이너8개만 fixture label/이름·정지 상태를 확인했다. 컨테이너/network/DB·chain state/XML/로그는 삭제하지 않고 보존했다. 기존 acceptance/devDB/Anvil/melon-init/실패 증거에 접근·변경하지 않았다. 제품/harness 코드 변경0. 백엔드 전체·웹·Foundry 재실행은 이번 범위가 아니며 이전451 PASS/35 SKIP·웹84·Foundry36은 이전 실행으로 남긴다.

증거: `deployment/runtime/<session>/evidence/<fixture>/summary.json`과`xml/TEST-*.xml`, restart는 전용`<conditional>-restart` 디렉터리, fixture의`run.json`/`isolation.json`/`preflight-clock.json`/`network-allocation.json`과 실행 metadata다. 비밀 설정/raw/키는 공유 문서나 Git에 포함하지 않는다. 독립 증거 확인 결과는 완료 후 추가한다.

## 키움 실제 시세 인수 착수 판정

독립 증거 검토 `review_phase_a_subnet`: **발견된 필수 수정 없음(신규 실행 증거·보고 범위)**. reviewer가 신규 session의 sourceHead/externalProviders=false,5개 XML/summary의각1PASS/FAIL·ERROR·SKIP0,4개isolation·별도포트,network후보2/1/1/3회,clock원시수치와최대0.581249초,별도Gradle restart selector/gate/전용XML을직접읽어확인했다. 테스트재실행·raw stderr·현재Docker/컨테이너정지·비밀설정·실제JVM PID는직접확인하지않았으며해당항목은구현자확인/harness흐름으로구분한다. 기존환경접근·수정없음. 실행결과문서5개를통합브랜치의doc-only `a3237f9`로커밋했으며테스트입력a317의제품/harness는그대로다.

**격리 기반·기존 거래의 전제는 통과했으며, 실제 키와 별도 승인이 있으면 읽기 전용 키움 인증/WS/차트 사전 인수에 진입할 수 있다.** 실제 데이터 BUY/SELL 성공이나 제품 실환경 적합성을 의미하지 않는다.

먼저 사용자가 App Key/Secret·사용 mode/endpoint·접근/IP 조건을 확인하고, 전용 runtime·read-only capture 수단·실제 호출 상한을 승인해야 한다. 현재 capture 도구는 미구현이며 이번 작업에서 추가하지 않았다. 기존 [준비 절차](kiwoom-live-acceptance-preparation.md)에 따라 장중 최초 연결과 재연결의0s/215/20이 현재 동일 generation/fresh 판정에 맞는지, native 분봉 cntr_tm이 시작/종료 어느 경계인지, 웹 tick/OHLCV/asOf가 일치하는지부터 판정한다.

0s 미제공/오래된 시각이나 분봉 경계 불일치는 해당 단계에서 BLOCKED/FAIL/INCONCLUSIVE로 기록하며 OPEN 추정·시간 허용치 확대·임의 shift·제품 코드 변경으로 우회하지 않는다. 실제 키움/Toss·유료 AI/embedding·Sepolia/AWS·최종 Phase D는 계속 NOT_RUN이며 이번5 PASS로 대체하지 않는다.
