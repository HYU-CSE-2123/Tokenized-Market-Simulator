# claude-docs — 공통 에이전트 컨텍스트

> 디렉터리 이름은 기존 도구 및 링크와의 호환성을 위해 `claude-docs`로 유지한다. 그러나 이 문서들은 Claude 전용 메모리가 아니다.

이 폴더는 Claude Code, OpenAI Codex 및 이후 사용하는 다른 개발 에이전트와 사람이 프로젝트의 **공통 컨텍스트**를 리포지토리 안에서 공유하기 위한 공간이다. 특정 에이전트의 로컬·영구 메모리를 그대로 복사하는 장소가 아니라, 다음 작업자가 도구나 세션에 관계없이 확인할 수 있는 프로젝트 지식의 요약본이다.

목적은 다음과 같다.

- 팀원과 여러 개발 에이전트가 동일한 프로젝트 이해와 용어를 공유한다.
- 머신, 도구 또는 세션이 바뀌어도 현재 구현 상태를 빠르게 복원한다.
- 완료한 작업, 검증 결과, 중요한 설계 결정과 남은 과제를 Git으로 추적한다.
- 에이전트 개인 메모리에만 남아 다음 작업자가 알 수 없는 결정을 최소화한다.

## 문서 목록

- [`project-overview.md`](project-overview.md) — 프로젝트 개요, 기술 스택, MVP 범위, Phase, 모듈 구조
- [`implementation-log.md`](implementation-log.md) — 실제 구축된 코드 상태(모듈별 구현/스텁, 검증 명령, 환경, 임의 결정값)
- [`reviewer.md`](reviewer.md) — 별도 검토자의 역할, 검토 기준과 보고 형식
- [`ai-development-experience.md`](ai-development-experience.md) — 프로젝트 지식 관리·독립 검토를 통한 하네스 엔지니어링 적용 경험, 근거와 실제 검증 범위
- [`oracle-price-execution-design.md`](oracle-price-execution-design.md) — Toss 가격과 온체인 체결 가격의 시간차, 서명 가격 기반 Pull Oracle과 원자적 정산의 합의 방향
- [`phase-6-3-acceptance.md`](phase-6-3-acceptance.md) — 실제 Toss·PostgreSQL·Anvil·웹 종단간 인수 체크리스트와 실행 결과
- [`career-project-brief.md`](career-project-brief.md) — 프로젝트 마스터에 전달할 최신 취업용 사실 원본. 완료 구현·실행별 평가·미구현/미검증·개인 기여 확인 범위
- [`web-product-ui.md`](web-product-ui.md) — Vite/JS 제품형 웹 전환, 기존 코드 계약·사용자 흐름·상태·모바일·배포 준비·검증/한계
- [`external-deployment-design.md`](external-deployment-design.md) — 외부 배포안과 후속 결정. 로컬 배포 준비 완료·실제 AWS 생성 승인 대기, EC2/Compose 비용·HTTPS·secret·영속 데이터·공개 API·Toss 이용 제한·Anvil/Sepolia 선택
- [`deployment-preparation.md`](deployment-preparation.md) — 승인된 외부 자원 없는 배포 구현·로컬 검증·독립 검토·미검증. 실행 절차는 deployment/RUNBOOK.md
- [`deployment-capacity-cost.md`](deployment-capacity-cost.md) — production Compose 자원 실측, 서울 EC2/Lightsail 상시·간헐 기동 비용, 실제 AI token 기반 비용과 검증 한계. 예산/인스턴스는 미확정
- [`ec2-operational-validation-plan.md`](ec2-operational-validation-plan.md) — 최신 AWS 목적: 단일 EC2/root EBS/SG/public IPv4로 Linux 배포·부하·장애·복구 실험. 수동 runtime secret·SSH /32·로컬/EC2 검증 분리, 생성은 승인 대기
- [`validation-2026-10-07.md`](validation-2026-10-07.md) — 기존 기능 재검증·최초 복원 실패와 후속 stderr/실제SQL readiness 보강. 새DB3/3·종단간16/16·독립 검토 및 남은 검증 경계
- [`development-chain-state-audit.md`](development-chain-state-audit.md) — 개발 DB와 보존 state/checkpoint의 읽기 전용 대조. 기존 DB와 일치하는 복원 후보 없음·신규 격리 인수 환경 제안, 복원/배포 미실행
- [`isolated-acceptance-baseline.md`](isolated-acceptance-baseline.md) — 신규 격리 DB·persistent Anvil·독립 키·Toss/실제 웹 준비 결과. 장중 BUY/SELL·유료 AI/성공 MATCH 인수는 대기
- [`reserve-reconciliation-design.md`](reserve-reconciliation-design.md) — 장부·체인 대사 최초 설계 기록. 후속 구현 승인됨
- [`reserve-reconciliation-implementation.md`](reserve-reconciliation-implementation.md) — 별도 feature worktree 구현, Order 실행 정본·불변 기준점·consistent cut·계산식·ADMIN API/UI·격리 검증 및 한계
- [`reserve-reconciliation-final-validation.md`](reserve-reconciliation-final-validation.md) — 실제 PostgreSQL/Anvil 경합, operator ETH/Gas 한계, 테스트-only CRLF 보완과 최종 브랜치 검증
- [`backend-blockchain-learning-guide.md`](backend-blockchain-learning-guide.md) — 현재 코드 기준 구조·운영자 통합 지갑·서명 견적·BUY/SELL·receipt 정산·WS·용어·DB/chain 정본과 유실 영향 학습 문서
- [`public-market-data-research.md`](public-market-data-research.md) — 배포 보류 후 국내 실시간/지연/historical 공급자·공개 이용권 조사와 provider/replay 설계 후보
- [`synthetic-market-design.md`](synthetic-market-design.md) — 공개 simulated 선택과 축소 승인 범위의 합성 tick/거래량·영속 상태·서명 견적·UI 구현, 통합 검증·독립 검토 및 남은 한계(외부 배포는 대기)
- [`AI_AGENT_RAG_IMPLEMENTATION_MASTER_GUIDE.md`](AI_AGENT_RAG_IMPLEMENTATION_MASTER_GUIDE.md) — 사용자 작성 AI 확장 지침. AI Phase 번호는 거래소 Phase와 별개
- [AI Phase 0 감사](../docs/ai/phase-0-baseline-audit.md) — 실제 인증·거래·조회 경계, 호환성 조사와 테스트 기준
- [ADR-001 AI 실행 경계](../docs/ai/adr/ADR-001-ai-runtime-boundary.md) — Phase 2 사용자 승인 기술 선택과 격리 경계
- [AI Phase 1 지식 문서 보고서](../docs/ai/phase-1-knowledge-report.md) — 사용자 승인된 지식 문서 9개 목록·권한 metadata·검증 결과
- [AI Phase 2 실행·검증 보고서](../docs/ai/phase-2-basic-rag.md) — Basic RAG 설정, API, 재색인, 실제 검증과 남은 평가
- [AI Phase 3 검색 품질 보고서](../docs/ai/phase-3-retrieval.md) — 고정 baseline, 독립 실험, 채택 설정과 Hybrid 비교
- [AI Phase 4 Read-only Tool 보고서](../docs/ai/phase-4-read-only-tools.md) — 승인 설계, 8개 조회 Tool·권한·민감 정보·실패 계약·실제 검증과 검토 기록
- [AI Phase 4 후속 지식 갱신](../docs/ai/phase-4-knowledge-refresh.md) — SELL 보강·Tool 지식/manifest/로컬 색인 동기화와 고정 golden 회귀
- [AI Phase 5 Agent 설계·구현](../docs/ai/phase-5-rag-tool-agent.md) — 역할 필터·제한된 RAG/Tool orchestration·근거 응답, 실행 결과·별도 검토·남은 한계
- [AI Phase 6 Skill 설계·구현](../docs/ai/phase-6-skills.md) — 승인된 반복 진단 정의·고정 handler·역할/domain·trace와 이번 검증 기록
- [AI Phase 7 자동 진단 설계·구현](../docs/ai/phase-7-event-driven-diagnosis.md) — 커밋된 검토 상태 감지·중복/claim·거래 격리·AI 이력·ADMIN 조회와 검증 기록
- [AI Phase 8 최종 평가·보안·관측성](../docs/ai/phase-8-evaluation-security-observability.md) — 종합 평가·공격/민감정보·최소 관측·marker TTL·최종 문서와 실제 검증/검토 상태
- [AI 최종 아키텍처](../docs/ai/AI_ARCHITECTURE.md), [평가](../docs/ai/EVALUATION_REPORT.md), [RUNBOOK](../docs/ai/RUNBOOK.md) — 지식/Tool 보안/Skill 안내를 포함한 설명·실행·한계

## 단일 출처(Source of Truth)

추가 기능 통합 개발의 최신 순서와 보존 경계는 [통합 개발 계획](additional-features-integration-plan.md)을 따른다. 실제 Toss 장중 인수는 추가 기능·최종 통합 검증 이후 신규 acceptance에서 진행하며 기존 acceptance는 보존한다. 2026-10-09의 [거래 전수 대사 ADMIN 설계](trade-full-audit-design.md)는 다음 날 승인·구현·통합되었고, 현재 다음 작업은 Faucet 정책 설계다.

2026-10-10 거래 전수 대사 구현을 승인받았다. 현재 상태·실행별 검증과 통합 결과는 [구현 기록](trade-full-audit-implementation.md)을 따른다. 추가 기능 개발의 중심은 integration/additional-features이며 모든 추가 기능 이후 최종 기준 브랜치와 신규 Toss/AI acceptance를 확정한다. 실제 외부 호출·유료 사용은 별도 승인이다.

문서와 코드가 충돌할 때는 다음 우선순위를 따른다.

1. **실제 소스 코드와 테스트** — 현재 실제 동작 상태
2. 리포 루트의 **`구현 계획.md`** — 목표, 범위, 설계 기준
3. `claude-docs/implementation-log.md` — 완료 작업과 검증 이력
4. `claude-docs/project-overview.md` — 빠른 이해를 위한 요약
5. 각 모듈 및 루트 `README.md` — 실행법과 사용 안내

`구현 계획.md`와 실제 코드가 다르면 무조건 어느 한쪽을 조용히 덮어쓰지 않는다. 코드가 계획보다 앞선 것인지, 임시 구현인지 판단한 뒤 계획 또는 구현 로그에 차이를 기록한다.

## 문서 업데이트 규칙

### 언제 업데이트하는가

다음 중 하나가 발생한 작업에서는 코드 변경과 함께 관련 문서를 업데이트한다.

- Phase 또는 세부 마일스톤을 완료했을 때
- API, DB 스키마, 이벤트 형식, 환경 변수 또는 실행 방법이 바뀌었을 때
- 기술 스택이나 중요한 설계 결정을 추가·변경했을 때
- `TODO`/스텁을 실제 구현으로 교체했을 때
- 테스트·빌드·통합 검증 결과가 새로 생겼을 때
- 알려진 제한사항이나 다음 작업에 영향을 주는 문제가 발견됐을 때

오탈자 수정, 내부 리팩터링처럼 프로젝트 이해에 영향을 주지 않는 변경은 기록하지 않아도 된다.

### 무엇을 어디에 기록하는가

- **`구현 계획.md`**: 목표 범위, 아키텍처, API 계약, Phase 정의처럼 앞으로도 따라야 할 기준
- **`project-overview.md`**: 현재 프로젝트를 처음 보는 사람이 알아야 할 안정적인 요약과 현재 Phase
- **`implementation-log.md`**: 실제 완료한 변경, 검증 명령과 결과, 미구현 사항, 결정값, 알려진 문제
- **모듈별 `README.md`**: 해당 모듈의 최신 빌드·실행·설정 방법

### 구현 로그 작성 형식

큰 작업은 `implementation-log.md` 끝에 다음 형식으로 추가한다.

```markdown
# Phase 2.1: 인증 기반 — 완료/진행 중

> 작성 또는 갱신: YYYY-MM-DD

## 구현
- 변경된 기능과 주요 파일

## 결정
- 선택한 방식과 이유

## 검증
- `./gradlew test`
- 실행 결과와 확인한 시나리오

## 검토
- 검토 범위와 검토자, 결과 및 지적사항 처리
- 미해결 사항 또는 별도 검토를 실행하지 못한 이유

## 남은 작업
- 다음 단계에서 처리할 항목
```

기존 기록은 특별한 오류가 없는 한 삭제하거나 현재 상태에 맞게 덮어쓰지 않는다. 완료 당시의 이력은 유지하고, 이후 변경은 새 섹션으로 누적한다.

## 에이전트 작업 원칙

새 작업을 시작하는 에이전트는 최소한 `구현 계획.md`, 이 폴더의 `project-overview.md`, `implementation-log.md`, 대상 모듈의 `README.md`를 먼저 확인한다.

### 사용자 사전 승인

새 기능 구현, 동작을 변경하는 리팩터링 또는 승인된 작업 범위를 확대하기 전에는 구현 방식, 변경 범위, 주요 의사결정과 테스트 계획을 사용자에게 설명하고 명시적인 승인을 받은 뒤 구현한다.

- 승인받은 범위를 넘어서는 변경이 필요하면 임의로 확대하지 않고 이유와 선택지를 설명한 뒤 다시 승인을 받는다.
- 단순 질의·조사, 읽기 전용 점검과 승인된 구현 범위 안의 통상적인 검증은 별도 구현 승인을 요구하지 않는다.
- 사용자의 승인 없이 구현을 시작한 사실을 사후 문서화하는 것으로 사전 승인을 대신할 수 없다.

작업을 마친 에이전트는 다음을 수행한다.

1. 실제 코드와 테스트로 완료 여부를 확인한다.
2. 프로젝트 이해에 영향을 주는 변경이면 관련 문서를 함께 갱신한다.
3. 검증하지 못한 내용은 완료로 기록하지 않고 미검증 또는 남은 작업으로 남긴다.
4. 비밀키, 토큰, 개인 환경 경로 같은 민감하거나 머신 종속적인 값은 문서에 기록하지 않는다.

## 별도 검토 절차

기능 구현, 버그 수정, 동작에 영향을 주는 리팩터링 및 에이전트 작업 절차 변경은 작업 단위로 별도 검토를 수행한다. 단순 질의, 조사, 오탈자나 동작에 영향 없는 문서 수정에는 생략할 수 있다. 파일을 수정할 때마다 검토자를 호출하지 않는다.

여기서 작업 단위는 사용자가 승인한 범위 안에서 독립적으로 검증하고 커밋할 수 있는 세부 마일스톤을 뜻한다. 큰 Phase를 무조건 한 번에 검토하거나 파일을 수정할 때마다 나누지 않는다. 예를 들어 가격 계층 분리, 외부 REST 연동, 실시간 WebSocket 연동은 각각 독립 검증·커밋할 수 있다면 별도 작업 단위로 본다.

1. 구현자는 작업 시작 시 기존 변경 상태를 확인해 이번 작업의 변경 범위를 구분한다. 구현과 범위에 맞는 검증을 마친 후 변경을 잠시 멈추고 별도 에이전트에 검토를 요청한다.
2. 구현자는 프로젝트 이해에 영향을 주는 내용의 문서 초안까지 변경 범위에 포함한 뒤 검토를 요청한다. 검토 요청에는 사용자 요구사항과 완료 조건, 이번 작업의 파일·diff(추적되지 않은 새 파일 포함), 관련 문서, 실행한 검증 명령·결과·미실행 항목을 전달한다. 기존 사용자 변경과 이번 변경을 구분하며 구현자의 설명만을 검토 근거로 삼지 않는다.
3. 검토자는 [`reviewer.md`](reviewer.md)를 읽고 소스와 테스트를 독립적으로 확인한다. 별도 맥락의 하위 에이전트 또는 별도 세션을 사용한다. 같은 구현자가 역할만 바꾸어 확인한 것은 자체 점검으로 표시한다.
4. 구현자는 지적사항의 근거를 확인하고 수정한다. 수용하지 않는 지적은 코드·요구사항·검증 근거로 이유를 남긴다. 필수 수정 사항은 수정 후 관련 검증과 재검토를 수행하며, 마지막 검토 이후 변경한 부분도 검토 범위에 포함한다.
5. 검토는 최초 1회와 수정 후 재검토 최대 2회로 제한한다. 이후 필수 수정 사항이나 이견이 남으면 미완료 상태와 근거를 보고한다. 검토 통과를 위해 요구사항을 임의로 줄이거나 검증을 약화하지 않는다.
6. 완료 보고에는 실제 검증 결과, 별도 검토 여부와 결과, 미해결 사항을 포함한다. 기존 문서 업데이트 기준에 해당하는 작업은 구현 로그의 검토 항목에도 기록한다.

별도 에이전트 실행 기능이 없거나 실행에 실패하면 가능한 구현·검증·자체 점검은 계속하고, 별도 검토 미실행 사실과 이유를 명시한다. 이를 별도 검토 완료로 표시하지 않는다. 검토자가 승인해도 필수 테스트 실패나 미실행 검증이 해소되는 것은 아니다.

검토자의 `발견된 필수 수정 없음`은 검토 범위에서 필수 결함을 찾지 못했다는 뜻이며 그 자체로 작업 완료 승인이 아니다. 구현자는 사용자 요구사항, 실제 테스트 결과와 미검증 항목을 함께 판단해 완료 여부를 보고한다. 최종 검토가 끝난 뒤 그 결과를 구현 로그에 그대로 옮기는 동작에 영향 없는 기록만 추가한 경우에는 별도 재검토를 요구하지 않는다.

이 절차는 에이전트가 따르는 작업 지침이며 CI나 실행 스크립트에 의한 강제 장치는 아니다. 검토 호출 자체에는 매번 사용자 확인을 요청하지 않는다. 배포·외부 게시 등 작업 권한은 기존 사용자 요청과 실행 환경의 권한 규칙을 따른다.
