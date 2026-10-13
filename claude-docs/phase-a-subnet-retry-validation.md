# Phase A 신규 fixture subnet 충돌 보완

> 2026-10-14. 입력 integration/additional-features `73f8f3d`. 제품 소스·기존 환경 변경 없음. 실행 결과는 완료 후 추가한다.

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
