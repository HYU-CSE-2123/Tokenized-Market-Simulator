# ADR-001 — AI 실행 경계

> 작성: 2026-09-27
> 상태: 승인 — 2026-09-27 사용자 Phase 2 기술 선택 승인
> 근거: [실제 코드 감사](../phase-0-baseline-audit.md)

## 문제

현재 거래소는 Boot 3.3.4 단일 서버이며 JWT/DB 사용자 인증과 본인 데이터 조회가 같은 프로세스에 있다. AI는 이 권한을 재사용하면서 운영 규칙과 상태를 설명해야 한다. AI 때문에 거래 의존성을 업그레이드하거나 거래 의미를 바꾸지 않는다.

확인한 Spring AI 1.0 지원 조합은 Boot 3.4/3.5, 2.0은 Boot 4.0/4.1이다. 현재 서버에 starter를 바로 추가할 근거가 없다. [1.0 공식 문서](https://docs.spring.io/spring-ai/reference/1.0/getting-started.html), [2.0 공식 문서](https://docs.spring.io/spring-ai/reference/getting-started.html)

## 대안

| 대안 | 장점 | 비용·제약 |
|---|---|---|
| 기존 Boot 내부 AI 모듈 + 얇은 공급자 어댑터 | 기존 인증 재사용, 운영 프로세스 추가 없음, 핵심 의존성 유지 | 공급자 요청·응답/Tool schema 처리 직접 구현, JVM 자원 공유 |
| 별도 Boot AI 서비스 + Spring AI | 버전·자원·배포 격리, 라이브러리 기능 활용 | 인증 위임·내부 REST·추가 실행 환경, 사용자 조회와 ADMIN 경계 추가 설계 |
| 기존 서버 Boot 업그레이드 + Spring AI | 단일 서버와 프레임워크 활용 | 기존 Security/JPA/WebSocket/web3j 전반 회귀 필요, 이번 AI 기본 범위 초과 |

## 추천 결정

초기에는 **기존 Spring Boot 내부 AI 모듈 + 공급자 어댑터**를 사용한다. 라이브러리 도입만을 위한 거래 서버 업그레이드를 피한다. 사용자가 이 대안을 승인했다. Tool은 Phase 2 범위에서 제외한다.

Phase 2 패키지는 `com.pricetrack.exchange.ai` 및 knowledge/provider/store다. EmbeddingProvider와 ChatModelProvider로 공급자를 분리한다. 승인된 모델은 text-embedding-3-small(1536차원), gpt-5.6-terra다. Tool·Agent·Skill은 이번 범위에 포함하지 않는다.

호출 경계:

```text
인증된 사용자 → AI 진입점 → 제한된 검색/Tool 실행 → 근거 포함 응답
                           ├─ 승인된 지식 저장소
                           └─ 인가된 읽기 facade → 기존 도메인 조회
```

LLM은 repository, SQL, 임의 RPC, 거래 전송 서비스를 호출할 수 없다. 등록된 Tool 이름·인수만 요청하고 서버가 allowlist·JSON 검증·사용자 소유권·ADMIN 권한을 집행한다. Tool 인수의 userId/role로 권한을 바꾸지 않는다. JWT는 서버 인증 경계에만 남고 모델 입력에는 포함하지 않는다.

AI 활성화는 선택 설정으로 두고, 실행 시간·동시 요청·Tool 횟수·토큰 예산을 제한한다. AI 호출 중 운영 DB 트랜잭션을 열어 두지 않는다. 비동기 진단은 별도 executor와 명시적 실행 주체를 사용하며, 운영 이벤트 commit 이후 실행한다. 동일 JVM이므로 완전한 장애 격리는 아니며 부하가 커지면 분리를 재검토한다.

지식은 별도 PostgreSQL+pgvector 컨테이너·볼륨의 ai schema에 저장한다. private 연결 풀은 Spring DataSource Bean으로 등록하지 않아 기존 JPA 자동 설정을 유지한다. 두 DB 사이 분산 트랜잭션은 없다. Phase 2 API와 서비스는 ADMIN-only다. USER 검색 개방 시 ADMIN 문서 제외는 후속 Phase에서 구현한다.

## 후속 승인·재검토 조건

- Phase 1 문서와 Phase 2 기술 선택은 사용자 승인을 받았다. active+manifest의 승인 내용 해시와 index version을 함께 검증한다.
- 실제 공급자 검증은 사용자 API 키 설정 후 수행한다. API별 토큰/timeout 및 개발 프로세스당 호출 상한을 두며 지속 월간 예산 관리로 과장하지 않는다.
- Spring AI 활용 자체가 학습의 필수 목표이거나, 공급자 어댑터 구현 비용이 커지면 별도 AI 서비스 대안을 재검토한다.
- 다중 인스턴스·AI 부하·독립 배포 요구가 생기면 런타임을 분리하되 인증·읽기 Tool 계약을 유지한다.
- 별도 서비스로 바꿀 경우 현재 HMAC JWT 서명 비밀을 AI 서비스에 단순 복사하지 않는다. 백엔드의 인증·인가를 통과하는 위임 방식과 이벤트 실행 주체를 먼저 설계한다.

## 검증 조건

기존 회귀 테스트 유지, 미인증 거부, USER 교차 데이터 차단, ADMIN-only Tool/문서 필터, raw transaction·키·JWT 비노출, 공급자 timeout·호출 상한, AI 실패의 거래 비전파를 검증한다. Phase 0에서는 기존 baseline만 실행했으며 이 미래 조건을 통과했다고 주장하지 않는다.
