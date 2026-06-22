# claude-docs

이 폴더는 Claude(Claude Code)가 이 프로젝트에 대해 **기억하는 컨텍스트**를 리포지토리 내부에 함께 보관하기 위한 공간이다.

Claude의 영구 메모리(`~/.claude/.../memory/`)에 기록되는 프로젝트 관련 내용은 이곳에도 미러링된다. 목적은:

- 팀원이 Claude의 프로젝트 이해 상태를 git으로 함께 추적할 수 있도록
- 머신/세션이 바뀌어도 동일한 컨텍스트를 복원할 수 있도록

## 문서 목록

- [`project-overview.md`](project-overview.md) — 프로젝트 개요, 기술 스택, MVP 범위, Phase, 모듈 구조
- [`implementation-log.md`](implementation-log.md) — 실제 구축된 코드 상태(모듈별 구현/스텁, 검증 명령, 환경, 임의 결정값)

## 단일 출처(Source of Truth)

세부 구현 기준은 항상 리포 루트의 **`구현 계획.md`**가 우선한다. 이 폴더의 문서는 그 요약·미러본이다.
