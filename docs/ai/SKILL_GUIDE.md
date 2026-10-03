# 제한된 Skill 안내

Skill은 모델이 새로 작성하는 실행 코드가 아니라 버전·hash·역할·대상·검색 domain·고정 단계가 승인된 반복 진단 절차다. 정의는 `backend/src/main/resources/ai/skills/` Markdown3개와 manifest이며 registry/handler가 실행한다. 도구 설치나 범용 workflow DSL이 아니다.

| Skill | 권한/대상 | 조회 범위 |
|---|---|---|
| settlement-debugging | ADMIN/orderId | 주문·견적·전송·receipt, trading/settlement/operations/support |
| signed-quote-diagnosis | USER/ADMIN/quoteId | 견적과 연결 주문·전송·receipt, trading/settlement/support |
| market-availability-diagnosis | USER/ADMIN/대상 없음 | 시장 상태·기준 가격, market/support |

`POST /api/ai/agent/answers`는 question, optional target, optional skillId만 받는다. 예:

```json
{"question":"이 견적을 진단해주세요","target":{"quoteId":"<발급된 quoteId>"},"skillId":"signed-quote-diagnosis"}
```

명시 선택과 서버가 허용한 목록에서 모델 선택을 지원한다. eligibility/target/role을 다시 검증하며 실행 중 새 Tool/단계를 추가하지 않는다. 승인 정의 불일치는 해당 Skill을 비활성화하고 거래/일반 Agent 경로를 보존한다.

## 결과 읽기

`toolEvidence`는 관측 사실, knowledgeSources/citationIds는 정책 근거, answer는 모델 해석, uncertainties는 부족/충돌/장애다. `skill`에는 id/version/definitionHash, 서버가 만든 diagnosis.classification, 단계 status/resultCode/evidenceRefs/관측 시간의 trace가 있다. 정상 PARTIAL은 실패와 다르며 정책이나 receipt를 확보하지 못했다고 체결을 추정하지 않는다.

수동 trace는 응답/안전한 audit에만 제공한다. 자동 REVIEW_REQUIRED는 서버 고정 settlement-debugging을 실행하고 안전한 trace를 AI 이력에 저장한다. ADMIN 패널/API는 과거 관측이라는 점과 targetStale/시각을 함께 확인한다. 사용자는 자동 실행 origin이나 재실행 명령을 제출할 수 없다.

예산은 기존 Agent와 공유하고 자동 실행은 프로세스당1개다. 정의/RAG 승인 취소 시 해석과 관련 인용/trace refs를 폐기한다. 자동 복구·체결/잔고 변경은 없다. [상세 Phase6](phase-6-skills.md), [RUNBOOK](RUNBOOK.md).
