# Phase A 신규 fixture subnet 충돌 보완

> 2026-10-14. 입력 integration/additional-features `73f8f3d`. 제품 소스·기존 환경 변경 없음. 실행 결과는 완료 후 추가한다.

## 수정 범위

`deployment/phase-a.py`: Docker network create의 확인된 subnet overlap stderr에만 최대8개 중복 없는10.240.x.0/24 후보를 순차 시도한다. 기존 네트워크 list/inspect/delete/update 없이 Docker 생성 결과로 판정한다. 각 시도의 metadata/원문 stderr는 신규 session private 로그에, 후보/결과는 신규 fixture network-allocation.json에 남긴다.

permission/daemon/name collision 등 다른 실패와 timeout/성공 여부 불확실한 생성은 재시도하지 않는다. 각 명령 timeout15초이며 무한 반복하지 않는다. 소유권/loopback identity/clock2초·왕복불확실성/source hash/image digest/import 차단은 그대로다. DB/계약 배포 이전에만 네트워크 후보를 변경한다. 실패 DB/체인 위에 restore/재배포를 반복하지 않는다.

`deployment/test_phase_a.py`: overlap 후 성공,8회 소진과 후보 중복 방지, 일반 오류/불확실 timeout 즉시 중단과 증거 보존을 검증한다. 실제 Docker overlap 재현 여부와 Mock 단위 결과를 구분한다.

## 실행·검토

Python 단위와 독립 검토 후 기능 커밋을 통합하고 최신 통합 HEAD의 신규 session에서 Faucet/reserve/audit/conditional·observation/별도 JVM restart를 실행한다. 독립 업무4종은 각각 새 PG/Anvil/키/포트/원장을 쓰며 restart만 성공 conditional 원장을 별도 JVM에서 읽는다. SKIP은 PASS가 아니다. 기존13일 실패와 모든 state/log를 보존한다. 실제 Toss/KIWOOM/AI/Sepolia/AWS는 호출하지 않는다.
