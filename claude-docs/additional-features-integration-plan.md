# 추가 기능 통합 개발 계획

> 2026-10-10 갱신. 준비금 대사·거래 전수 대사 통합 완료. 나머지 추가 기능은 개별 설계/구현 승인이 필요하다.

## 1. 현재 기준과 보존 경계

- 원본 `melon-init`: `b7cb0f0ed8fd936e73733a6d2f167efe4a951a63`. 원본 working tree는 변경하지 않는다.
- 완료 기능 `feat/reserve-reconciliation`: `00046d7da28579eaccbff6a31cfda6ce46c398a4`. 커밋·clean 확인. [최종 검증](reserve-reconciliation-final-validation.md)의 검증 범위/한계를 유지한다.
- 통합 `integration/additional-features`: reserve 완료 커밋에서 생성. worktree는 저장소 기준 `deployment/runtime/integration-worktree`다. 준비금 대사 ancestry와 거래 전수 대사 병합 `2a5d8d3a29b3e38822678da613c0da9dfa56ea52`를 포함하며 원본 브랜치에 merge한 것이 아니다. 후속 문서 기록 commit은 Git HEAD에서 확인한다.
- 완료 독립 기능 `feat/trade-audit`: `eed6b2f98e518a6c5d13603158b1aa9d3f8f0e06`. worktree `deployment/runtime/trade-audit-worktree`. [구현·검증](trade-full-audit-implementation.md)과 독립 재검토 후 통합했고 다음은 Faucet 정책 설계다.
- 보존 acceptance `acceptance-20261007153304-9bc0b3`와 해당 runtime/DB/chain/설정/제품 버전은 변경·재생성·merge·배포하지 않는다. 기존 개발 DB/Anvil/실제 `.env`도 그대로 둔다.
- 최초 브랜치 구성(10-09)은 Git metadata만 추가했다. 승인 후 기능 검증(10-10)에는 새 격리fixture만 생성·배포·조회했고 종료 후 보존했다. 기존 환경 secret 복사/DB·RPC 접속/배포는 없다.

최신 사용자 결정은 **추가 기능 → 최종 통합 회귀 → 새 acceptance 환경 → 실제 Toss 장중 최종 인수**다. 과거 문서의 “장중 인수 후 추가 개발” 순서를 대체하되 당시 실행 이력을 삭제하지 않는다. 유료 AI 인수는 예상 호출/비용 보고와 별도 승인 후 실행하며 Toss 인수와 별도 결과로 기록한다. 운영 부하·장애·AWS 실험은 이번 개발 순서 변경으로 자동 재개되지 않는다.

## 2. 브랜치 운영

1. 통합의 검증된 HEAD에서 기능별 branch/worktree를 만든다. 현재 통합 worktree는 실행 환경이 아니다.
2. 기능 설계·계약·정본·migration·테스트 계획을 설명하고 승인받는다. 다른 기능의 구조를 선행 변경해야 하면 별도 승인한다.
3. 각 기능은 자신만의 새 PostgreSQL/Anvil/runtime, Synthetic 공급자, AI 비활성 fixture를 사용한다. 이전 fixture를 초기화하거나 acceptance secret을 가져오지 않는다.
4. 기능 테스트 + backend/contract/web 회귀 + 필요한 실제 PostgreSQL/Anvil E2E + 독립 검토를 수행한다. skip과 외부 미검증은 별도 표시한다.
5. 소스·문서 allowlist 및 secret/산출물 제외 점검 후 기능 branch에 commit한다. 검증된 commit만 통합에 순차 병합하고 병합 후 통합 회귀를 수행한다. 충돌은 임의 해결하지 않고 계약 영향부터 검토한다.
6. `melon-init`으로의 merge, acceptance 적용, 외부 배포는 이 절차에 포함하지 않는다. 최종 인수도 보존 환경을 재활용하는 것이 아니라 최신 통합 버전의 **새 환경**으로 진행한다.

장부 대사 377개 중 346 PASS / 31 SKIP, 별도 PG/Anvil E2E, Foundry 36 PASS, 웹 55 PASS는 **이전 완료 기능의 결과**다. 이번 문서/브랜치 구성에서 재실행한 테스트로 쓰지 않는다. 실제 Toss/유료 AI/EC2·장기 부하·다중 서버 및 Gas 지급 정확 판정은 완료되지 않았다.

## 3. 추가 기능 전체 범위와 의존관계

기존 목록 근거는 [career brief §8.2](career-project-brief.md), [구현 계획](../구현%20계획.md), 실제 아래 클래스들이다. 아래 표는 완료 선언이나 기능 범위 축소가 아니라 선행 결정을 분리한 계획이다.

| 기능 / 상태 | 유지 가능한 구조 | 구조 변경·선행 결정 | 외부 승인 |
|---|---|---|---|
| 장부·체인 자산 대사 / feature 완료 | 운영자 통합 지갑, Order 실행 유형, 지급 이력, read-only snapshot·불변 기준점, ADMIN UI | 신규 0원 원장 기준점 필요. 과거 유잔고 환경에 임의 기준점 재설정 불가 | 격리 fixture 외 실제 환경 적용은 별도 승인 |
| **거래 전수 대사 / 기능·통합 회귀 완료** | Order·Trade·transaction·PriceQuote, 이벤트 parser/단위 변환, ADMIN 권한 | 항목별 근거/이력, 고정 cut·전체 coverage, 과거 UNKNOWN, 양방향 이벤트 누락 검사 구현. 큰 활성 원장·실제 운영 인수는 미검증 | Synthetic/격리 Anvil에는 외부 자격증명 불필요 |
| faucet 정책 / 미구현 | `WalletService.faucet`, 지급 journal, 잔고 transaction, commit 후 WS | 지급 주기·총한도·idempotency·동시성·시간대 결정. 사용자 DB 지급과 operator/Vault 온체인 공급 정책을 **별도** 정의; 제한을 도입해도 자동 담보 공급이 되는 것은 아님 | 자동 충전/ETH 지급·외부 네트워크는 별도 승인 |
| 사용자 인증 확장 / 미구현 | Spring Security/BCrypt/JWT, users의 nullable email/email_verified/google_sub | Google 로그인·이메일 인증·명시적 계정 연결·refresh/revoke 저장과 세션/WS 만료 정책. 같은 이메일만으로 자동 연결 금지 | Google OAuth client/redirect domain, 발송 서비스 credentials·네트워크·요금 승인. fixture 먼저 가능 |
| 지정가·예약 주문 / 미구현 | Vault 즉시 체결·서명 보고서 유지 가능 **조건부 트리거 방식이면** | 사용자가 승인하는 장기 주문 의도, trigger·취소·만료·잠금 상태·경합·유동성·새 quote 발급/소비. 30초 서명 견적을 예약 기간 동안 저장해 실행할 수 없음 | fixture는 무료; 실제 공급자/체인 비용 별도 |
| 사용자 지갑 / 미구현 | 조회 UI·인증·시세 일부 유지 | 지갑이 단순 연결 식별자인지 직접 자산 보유/서명 실행자인지 먼저 결정. 직접 실행이면 현재 executor=operator, 사용자 DB 원장·faucet·Vault 자산 대사 모두 재설계. WalletConnect·입출금/보관 키/복구 범위 결정 | wallet 프로젝트 credentials·외부 RPC/test ETH·네트워크 승인; 개인 키 서버 임의 보관 금지 |
| 분산 nonce 제어 / 미구현 | 저장 raw tx/txHash와 동일 원문 복구, sender+nonce unique | 현재 sender synchronized는 프로세스 내 전용. 다중 worker/server를 도입하기 **전** DB 기반 lease/할당·fencing·승계·RPC pending/미확정 nonce 처리 설계. Redis가 필수라는 전제 없음 | 격리 2프로세스 fixture로 검증 가능; 외부 서비스 추가는 별도 승인 |
| 준비금 증명 확장 / 미구현 | 자산/거래 대사 자료·불변 cut·지급 이력 | 부채의 정의/미정산 잠금/DB_ONLY/모의 발행/기준점 provenance부터 확정. Merkle liability root와 proof/개인정보·root 공개·검증 시점 설계. operator mKRW÷사용자 DB mKRW를 PoR로 표시 금지 | root 외부 공개/체인 anchor·RPC·test ETH는 승인 필요. 암호학적 proof≠실제 원화 지급능력 |
| Sepolia / 미구현 | Solidity/EIP-712·RPC adapter·receipt 조회 기본 구조 | 새 chainId/주소/domain·키·배포·confirmations/reorg/finality·fee·복구·환경 provenance. Anvil DB와 txHash를 이어붙이지 않는 새 환경 | 외부 RPC credentials·네트워크·test ETH 확보·배포 승인. AWS 생성과 별개 |

## 4. 권장 순서와 구현 전에 해결할 충돌

2026-10-10 사용자 확정 순서: **거래 전수 대사 → Faucet 정책 → Google 로그인 및 이메일 인증 → 지정가/예약 주문 → 사용자 지갑 → 분산 Nonce → 준비금 증명 확장 → Sepolia**. 기능별 기본 검증·독립 검토·커밋·통합 병합 뒤 통합 회귀를 진행하며, 전체 제품 최종 통합 인수와 실제 Toss/AI 인수는 모든 추가 기능 뒤 신규 환경에서 수행한다. 구조 변경이 큰 기능, 특히 지갑 직접 실행 모델은 착수 전에 설계와 영향을 승인받는다. 외부 호출·네트워크·유료 사용은 별도 승인 대상이다.

`melon-init`을 영구 분리하지 않는다. 당장은 `integration/additional-features`를 중심으로 개발하고 기존 `melon-init`/acceptance는 보존하며, 전체 개발 완료 후 최신 통합 버전을 `melon-init`에 반영하거나 새 기준 브랜치를 확정한다. 현재 요청은 거래 전수 대사를 검증한 후 통합 브랜치에 병합하는 권한이며 원본 적용·최종 환경 생성의 선행 실행 권한이 아니다.

- 지정가의 의미는 미확정이다. 시세가 조건을 만족하면 Vault와 거래하는 주문과 사용자 간 주문장 매칭은 다르다. 후자는 단순 scheduler 추가가 아니라 거래 구조 변경이다. 다음 대사 구현 전에 지정가 기능을 끼워 넣지 않는다.
- scheduler를 추가해도 단일 sender/인스턴스이면 분산 nonce가 즉시 필수는 아니다. 별도 프로세스/서버/다중 executor가 전송하면 nonce 설계가 선행되어야 한다.
- 지갑 직접 거래는 기존 operator executor 결정과 충돌한다. 현재 검증된 경로를 임의 교체하지 않고 사용자 승인으로 새로운 실행 모델·버전·대사 기준을 정의한다.
- faucet 제한과 준비금 자동 충전은 다른 작업이다. 가상 DB 지급 한도를 정해도 실제 담보/원화 입금이 생기지 않는다. 변경된 지급 정책은 journal을 유지해야 한다.
- 전수 대사는 거래 기록의 대응을, 자산 대사는 총량과 기준점 변화를, PoR은 부채 포함·사용자 proof를 다룬다. 한 기능 통과로 다른 기능 완료/지급능력을 주장하지 않는다.
- 인증 확장은 계정 연결 시 사용자 자산·과거 주문 소유권을 이동/통합할지 별도 결정해야 한다. 이메일 같음만으로 장부를 합치지 않는다.
- Sepolia에 체결을 올리면 공개·삭제불가 데이터와 확인 지연이 생긴다. Synthetic은 가능하지만 실제 Toss 보고서의 공개 온체인 기록은 공급자 이용권 확인/승인 없이 전환하지 않는다.

## 5. 다음 승인

[거래 전수 대사 설계](trade-full-audit-design.md)는 2026-10-10 승인되어 [구현·검증 기록](trade-full-audit-implementation.md)대로 기능 검증을 완료했다. 통합 회귀 후 다음 Faucet 정책의 주기·총한도·idempotency·동시성·온체인 공급과의 구분을 먼저 설계/승인받는다. 다른 추가 기능은 순서만 확정했으며 구현하지 않았다.
