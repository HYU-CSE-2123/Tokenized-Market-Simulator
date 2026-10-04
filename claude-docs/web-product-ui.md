# 서비스형 모의 자산 웹 — 승인 범위와 구현 기록

> 2026-10-05. 기존 tools/websocket-test-client를 Vite/JS 제품 UI로 전환. 승인 범위 구현·검증·독립 검토 완료.

## 코드 계약 확인

1. AuthService.signup은 사용자·잔고 저장 후 TokenResponse(accessToken/expiresIn)를 반환하므로 가입 직후 자동 로그인 가능.
2. SecurityConfig의 GET /api/markets/**, /ws·/ws-sockjs handshake는 permitAll. WebSocketAuthInterceptor는 인증 없는 CONNECT와 가격/공개 체결 SUBSCRIBE를 허용하고 개인 구독은 JWT principal을 요구.
3. PortfolioService.Portfolio에 averageBuyPrice/unrealizedProfit/totalValue를 실제 제공. 별도 available/locked 잔고·실현 손익/손익 이력은 없음.

백엔드 기능/소스/REST·STOMP 계약·DB는 변경하지 않았다. 사용자 Google 로그인·예약 주문·호가창·refresh token·AI 복구/거래 Tool·schema/index 실행 UI를 추가하지 않았다.

## 화면·상태

로그인/가입 dialog → 시장/차트+매수·매도 → 내역 상세 → 내 자산 → 일반 AI/시장/견적/주문 설명 → ADMIN 정산/이력/trace/관측을 hash 화면으로 연결했다. 비로그인 공개 시장은 자동 시작, 개인/ADMIN 접근은 세션·/api/me 역할로 분리한다. 기존 경로·API와 Native/SockJS는 유지하며 운영 화면에서 JWT/debug/raw JSON은 제거했다.

요청별 ticket와 세션 세대, 입력 변경 시 quote 응답 무효화, terminal 주문 상태 역행 차단, 익명/public과 개인 상태 분리, 자동 STOMP 재연결 및 REST 스냅샷 복구를 적용했다. 입력량/30초 만료·quoteId 제출·소유권 서버 검증을 유지하고 Order의 출력량을 예상값, Trade 가격을 실제 체결로 표현한다. 전송 불명확 시 재전송 금지·내역 조회·사용자 중복 위험 확인 후 새 견적만 허용한다. 동일 입력량으로 전송 완료를 추정하지 않는다.

밝은 중립색/남색/파랑 기본색·국내 상승 빨강/하락 파랑, 모바일 하단 nav·카드 내역·최종 견적 sheet·폼 label/dialog/keyboard focus·360px 대응을 적용했다. 기존 KST/6주기/history/tick 버퍼와 차트 attribution을 유지했다. AI 설명·Tool 사실·source 버전/ID·불확실성·Skill trace, 과거 관측/targetStale·TTL 만료·승인 철회는 구조화 텍스트로 표시한다. 영속 로그인/대화 기억은 없다.

## 검증

- npm test: 변경하지 않은 기존34개 + 신규13개 =47통과/실패0/skip0. 추가 3개는 실제 main.js 함수의 주문 상세 역행/이벤트 갱신, ADMIN 교차 응답, 404의 이전 결과 폐기 회귀다.
- npm run build:90modules, JS 약279kB(gzip약91kB). 프레임워크/새 runtime dependency 없음.
- 실제 Chrome CDP fixture smoke: desktop1440/mobile360, 비로그인/가입 자동 로그인, 견적 입력 변경/만료/확정, pending→terminal와 지연 REST, 포트폴리오, AI 안전 텍스트/503/늦은 logout 응답, ADMIN 이력·stale 상세·관측, 6주기 count 계약, 불명확 전송 잠금, 5화면 가로 overflow0. 화면 PNG를 직접 확인했다.
- 최초 browser 마지막 assertion은 logout 후 ADMIN의 독립 스냅샷을 기존 USER 이벤트 상태로 기대해 실패했다. 실제 서버 fixture의 pending 상태로 수정하고 동일 전체 smoke를 통과했다. 기존 회귀나 제품 안전 assertion을 제거하지 않았다.
- 최초 browser build sandbox 경로 접근 오류는 승인 후 재실행했다. 공식 nginx1.28-alpine 일회성 컨테이너 envsubst/nginx -t 통과, 기존 컨테이너/볼륨 변경 없음.

fixture smoke는 실제 공급자/DB/Anvil/네트워크 STOMP 검증이 아니다. 실제 장중 인수·운영 모델/AI 초기화·TLS 인증서/공개 도메인·다양한 모바일 실기기는 미검증이다. 테스트 artifacts는 ignored dist/.smoke에 있고 실제 비밀·사용자는 사용하지 않았다.

## 배포 준비·검토

Dockerfile npm ci→build→Nginx dist, deploy/default.conf.template의 env BACKEND_UPSTREAM·동일 origin REST/WS/SockJS·보안 header·asset cache·요청 timeout을 제공한다. TLS는 운영 ingress에서 구성하고 기존 WEBSOCKET_ALLOWED_ORIGINS를 배포 도메인으로 제한한다. 외부 배포나 실제 .env 변경은 하지 않았다.

최종 Docker 이미지 tokenized-market-web:local 빌드 통과. 실제 백엔드 대상 proxy REST/WS 네트워크 인수와 인터넷 공개 배포는 미실행이다.

독립 검토자 review_web_product는 최초 npm test44/build90/diff 검사 및 실제 main 함수 harness로 주문 상세의 terminal 역행과 ADMIN 공유 결과 패널 교차 응답을 발견했다. canonical 주문을 상세/접수 안내의 공통 출처로 사용하고 이벤트 수신 시 상세 갱신, ADMIN 공통 결과 ticket·시작 시 본문 폐기·대상 ID 표시를 보완했다. 신규 3개 회귀와 전체47/build90/Chrome smoke/최종 Docker build를 재실행했다.

재검토1에서 필수 수정 없음. 검토자는 전체47/build90/diff를 직접 통과했으며 브라우저 smoke는 최초 환경 대기로 중단해 성공으로 계산하지 않았다. Chrome/Docker/Nginx 성공은 구현자 결과이고 실제 공유 서비스/DB/Anvil/Toss/유료 AI는 양쪽 모두 이번 작업에서 검증하지 않았다. 최종 재검토 후 제품 변경 없이 완료 기록만 갱신했다. 커밋·스테이징하지 않았다.
