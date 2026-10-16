# 원본 프로젝트 루트 통합 — 2026-10-16

## 범위와 결과

사용자 승인에 따라 `feat/kiwoom-live-fixes`의 미커밋48개를 의미 있는6개 커밋으로 저장하고 원본의 실제 브랜치 `melon-init`에 일반 merge했다. 원격 push, worktree 제거, 제품 소스 보완, 외부 API 및 DB/Anvil 조작은 수행하지 않았다.

원본 초기 HEAD는 `f069a1085fd7cfe1d8d75f7bb454fc20feb50ede`, 기능 초기 HEAD는 `240cb3982d6c05724d6a89e99aed66788d77d697`, 공통 조상은 `b7cb0f0ed8fd936e73733a6d2f167efe4a951a63`이다. 원본만의 AI 개발 경험 문서 변경은 기능 브랜치에도 같은 내용이 있어 손실이나 충돌 없이 합쳐졌다. merge 전 원본은 clean, 기능은 수정21/미추적27/stage0이었다.

## 커밋

| 커밋 | 내용 | 파일 수 |
|---|---|---:|
| b6edc723792b2db2a22cc85b32ea5be39501dc03 | 키움 구독·모의 거래 가용성·Java/웹 테스트·설계/구현 문서 | 18 |
| 79ae93e40ce971a755e7d27571431353b3fa30ca | 0s/NXT/9081 진단·회귀·관측 기록·README | 14 |
| 84f99b299269bf9ec1f3912cd9690460b92e26e2 | Synthetic playground 도구·테스트·안내 | 7 |
| fe361db066ffc8120646d5b022874a439e060a97 | KRX 격리 준비·watchdog·실행 runner·기록 | 6 |
| 600f13ba92ca4fa1a2abee810647b999ffaa21b7 | Synthetic 실제 Chrome 거래 검증 보고서 | 1 |
| 2fb8cd66c688c3737d60492060c7a617434b4f45 | 누적 구현 로그·면접 학습 문서 | 2 |

merge 커밋: `8af86e8df8456ed2cbdb8cac6cc6bc30b46c1ece`.

부모는 원본 `f069a10`과 기능 tip `2fb8cd6`이다. `merge-tree` 사전 검사 후 `git merge --no-ff --no-edit`를 사용했고 ort merge가 충돌 없이 완료됐다. merge 직후 전체 Git tree는 기능 tip과 동일하고 양쪽 worktree는 clean이었다. 이 문서와 최종 로그는 이후 별도 문서 커밋으로 원본에 기록한다.

## 보존과 보안

- 신규 ignored private 세션 `deployment/runtime/integration-worktree/deployment/runtime/git-integration-20261016080207-281f81a3`에48파일 백업·원시 SHA256·명시적 파일 그룹·staged patch·회귀 로그를 보존했다. 기존 private 자료를 복사한 백업이 아니다.
- 각 커밋은 allowlist 파일만 stage하고 staged 목록·diff check·비밀정보 패턴 검사를 통과했다. 후보48개에는 key/JWT/PEM 등 탐지 후보가 없었다. 패턴 검사가 비밀 부재의 완전한 보증은 아니다.
- runtime/credentials/실제.env/DB/Anvil state/capture/브라우저 profile/빌드 산출물은 Git에 추가하지 않았다. ignored 파일 강제 추가, reset/checkout, 파일 삭제, manifest 재기록 없음.
- 기존 worktree의48파일 원시 바이트와 KRX 제품 sourceHash는 모든 커밋·merge·테스트 후 일치했다. 기존 Git hooksPath 별도 설정이나 실행 hook은 발견하지 못했다.

## 새로 실행한 회귀

기능 worktree에서 커밋 전, 원본 루트에서 merge 후 각각 새로 실행했다. 현재 폴더의 테스트/build 출력만 생성했으며 기존 개발 DB·Anvil을 대상으로 하지 않았다. 테스트 환경은 기존 도구의 clean environment를 사용하고 `.env` import 및 외부 공급자 설정을 차단했다. 유료/API/PG·Anvil E2E gate는 활성화하지 않았다.

| 검증 | 기능 worktree | merge 후 원본 루트 |
|---|---|---|
| Gradle test + bootJar --offline --rerun-tasks | 총498 / PASS463 / SKIP35 / FAIL0 / ERROR0 | 총498 / PASS463 / SKIP35 / FAIL0 / ERROR0 |
| 웹 단위 테스트 | PASS88 / SKIP0 / FAIL0 | PASS88 / SKIP0 / FAIL0 |
| Vite build + Chrome desktop/mobile fixture smoke | PASS | PASS |
| Foundry --offline | PASS36 / SKIP0 / FAIL0 | PASS36 / SKIP0 / FAIL0 |
| 키움/NXT 진단 오프라인 | PASS68 | PASS68 |
| Phase A guard/clock 도구 오프라인 | PASS15 | PASS15 |
| playground 도구 오프라인 | PASS8 | PASS8 |

SKIP35개는 PASS에 포함하지 않는다. 기존 PG/Anvil E2E5/5 및 Synthetic 실제 Chrome 거래2건은 과거 실행 근거이며 이번에 재실행한 결과가 아니다. 이번 Chrome smoke는 fixture이며 실제 제품 서버/DB/chain 거래 테스트가 아니다. KRX/NXT 제품 실환경 E2E는 여전히 미완료다.

테스트 성공 후 요약 로그를 읽는 과정에서 UTF-8/cp949 디코딩·콘솔 출력 오류2종이 있었으나 ASCII JSON 요약으로 확인했다. 테스트 실패나 재실행으로 분류하지 않는다.

## KRX 인수 환경과 경로 의존성

기존 `kiwoom-e2e-20261014171302-662c5ba0`의 manifest는 그대로다. sourceHead `240cb39`는 준비 당시 이력이다. live runner는 현재 HEAD와의 동일성을 요구하지 않고 제품 파일 바이트 기반 sourceHash, 고정 image ID, 소유권 및 baseline을 검증한다. Git 커밋으로 image ID가 바뀌지 않으며 이번 작업에서는 Docker를 조작하거나 이미지 재빌드·재태깅하지 않았다.

원본과 기능 worktree의 Git 내용은 동일하지만 `core.autocrlf=true` checkout으로 제품 해시 대상 파일15개의 LF/CRLF 바이트가 다르다.15개 모두 줄바꿈을 제외한 내용 차이는0개다. 원본 제품 sourceHash는 기존 KRX 값과 다르며 이를 맞추려고 기존 manifest나 원본 파일을 보정하지 않았다. 기존 기능 worktree의 sourceHash는 계속 일치한다.

KRX Compose bind mount·release.env·secret 참조·진단 manifest 및 playground pointer는 기존 worktree 하위 절대 경로에 의존한다. 원본으로 소스를 merge했다고 runtime이 이동한 것은 아니다. 원본 `kiwoom-live-run.py`를 같은 환경처럼 바로 실행하거나 준비 도구를 재실행하면 안 된다. 기존 인수는 보존된 worktree 경로에서만 기존 guard를 다시 통과한 뒤 별도 승인 범위로 진행한다. 기존 빈/정체 주문을 임의 보정하거나 새 baseline으로 교체하지 않는다.

## 앞으로의 작업 위치와 제거 선행 조건

새 개발과 Codex 실행 위치는 원본 `Tokenized-Market-Simulator` 폴더다. 작업 브랜치는 원본에 checkout된 `melon-init`을 기준으로 확인한다. 제품 코드가 변경되면 기존 KRX 이미지를 새 코드로 간주하지 않는다.

worktree 제거는 아직 승인되지 않았다. 먼저 기존 KRX/NXT/playground 및 실패/성공 증거의 보존 경로·ACL·무결성을 정하고, Docker bind mount와 manifest의 경로 의존성을 조사해야 한다. 인수 완료 후 기존 환경을 계속 보존할지, 승인된 별도 새 환경을 원본에서 구성할지 결정해야 한다. 데이터 경로를 임의 이동하거나 manifest를 고쳐 guard를 우회하지 않는다. 모든 의존성 해소와 사용자 승인 전에는 worktree 및 하위 runtime을 삭제하지 않는다.

## 독립 검토

`review_acceptance_baseline`의 실행 전 검토에서 merge 순서 gate와 원본 merge 결과 확인 누락을 발견했다. 커밋6개 완료/파일 그룹/tip/clean 검증과 원본 전체 tree/canonical blob/원시 해시 기록을 보강했다. 첫 재검토 결과는 발견된 필수 수정 없음, 직접 AST PASS였다.

최종2차 재검토도 발견된 필수 수정 없음이다. 검토자가 실제6커밋/파일수48/merge부모/feature와전체tree동일/원본branch및문서2개상태/원본XML498·35skip·실패0·오류0/diff check를 직접 대조했다. fixture·과거E2E·미완료실환경과줄바꿈·경로의존성 구분을 확인했다. 테스트 재실행·private백업/hash·WT환경·Docker/DB/credentials는 검토하지 않았다. 이 범위는 구현자 검증과 구분한다. 최종 검토 후에는 이 결과의 사실 기록만 추가했다.
