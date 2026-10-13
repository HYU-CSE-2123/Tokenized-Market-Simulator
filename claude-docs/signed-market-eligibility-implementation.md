# 신규 온체인 서명 견적의 시장 품질 경계

> 2026-10-13. 사용자 승인 2단계. 실제 Toss/키움·유료 AI 호출 없음.

## 변경
- `market/MarketDataEligibility`: 서버 스냅샷의 SIMULATED 여부와 OPEN+LIVE 실제 시세 품질을 판정한다. TOSS/KIWOOM 이름을 열거하지 않아 미래 실제 공급자도 동일하게 검증된다. 클라이언트가 provider/상태를 지정하는 API를 추가하지 않는다.
- `PriceReportIssuer.validateSnapshot`: **신규 서명 보고서 발급**에서만 실제 공급자 OPEN+LIVE를 추가 요구한다. 동일 고정 가격 스냅샷을 RPC 전후 검증하고 기존5초 신선도/미래2초/관측시각+30초 TTL·입력단위·최소수령량·domain/signature를 유지한다.
- Synthetic는 기존 검증을 그대로 사용한다. `MarketPriceService.requireTradableSnapshot`/DB-only, 저장된 quote 소비, SIGNED/SUBMITTED raw 복구, receipt 정산, Oracle/Vault는 변경하지 않는다.
- 보고서 발급 시 확보한 스냅샷의 상태/가격이 기준이며, 발급 뒤 시장 변화로 이미 제출된 transaction을 중단하거나 서명을 취소하지 않는다. 실행 계약 변경이나 추가 가격 보장은 없다.
- RPC가 끝난 직후 실제 공급자의 현재 OPEN/LIVE/fresh 및 공급자 동일성을 다시 확인한다. 원래 가격/수량/최소수령량은 그대로이며 새 스냅샷 가격으로 바꾸지 않는다. 연결/시장 상태가 발급 도중 악화되면 신규 서명만 거절한다.

## 검증
- 추가 단위: TOSS/KIWOOM/미래공급자의 모든 비LIVE/UNKNOWN/CLOSED 거절·RPC미호출, Synthetic SIMULATED 유지와 KIWOOM LIVE 성공, RPC 동안5초 경계 초과 거절. 기존fresh5초/미래2초/TTL30초·BUY/SELL/signature 테스트 유지.
- source 기반 신규 session phase-a-20261013132941-308f2d41: backend **465 total /430 PASS /35 SKIP /0 FAIL·ERROR**, 웹84/Chrome fixture/Foundry36 PASS.
- 새 실제 PG/Anvil conditional-0d15be4b(DB40048/RPC39122) E2E1PASS. 즉시·조건부 BUY/SELL·동일raw응답유실·receipt/review·Faucet/두대사/관측 경계를 함께 실행했다. 별도JVM restart의 최종 결과는 완료 후 기록한다.
- 기존 acceptance/devDB/Anvil/환경파일·이전 Phase A 증거는 보존한다. 실제외부LIVE 품질은 단위Mock에서 검증했으며 실제Toss/키움 장중 인수로 표현하지 않는다.
- 위465/430과첫PG는 최초 보완 source의 기록이다. RPC 이후 최신 상태 재검사 단위1개를 추가했으므로 최종 source 재검증 결과는 별도로 기록한다. 이전 결과를 최종 재실행으로 취급하지 않는다.

## 상태
최종 소스 session `phase-a-20261013134027-40d10374`: backend **466 total /431 PASS /35 SKIP /0 FAIL·ERROR**. 웹 단위/Chrome desktop·mobile fixture/Foundry 회귀 명령 성공. 신규 `conditional-f09b388e`(DB41893/RPC44823) 실제 PG/Anvil E2E **1 PASS**, 별도 JVM restart **1 PASS**. 최초 session의 restart도 **1 PASS**이며 이전 실행 기록은 보존한다.

독립 검토 `review_signed_market_policy`: 변경4파일과 주변 DB-only/저장 견적/복구 경계를 검토하여 **필수 수정 없음**. 검토자는 테스트를 직접 실행하지 않았고 private runtime ACL 때문에 최종 XML을 직접 확인하지 못했다. 실행 수치는 구현자가 새 runner 결과로 확인한 것이며 독립 재실행으로 표현하지 않는다. 동일 공급자의 최신 가격이 달라도 원래 서명 가격을 유지하는 추가 성공 테스트는 선택 보완으로 제안되었다.

기능커밋·통합 및 병합 source 새 회귀는 완료 후 추가 기록한다. AI 승인 지식9개는 키움 구현 이후 갱신하며 이번에는 수정/유료색인하지 않는다.
