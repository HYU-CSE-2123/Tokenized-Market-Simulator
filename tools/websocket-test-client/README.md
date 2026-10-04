# Tokenized Market — 제품형 모의 거래 웹

기존 Vite + JavaScript 검증 클라이언트를 서비스형 UI로 전환했습니다. 디렉터리 이름은 호환성을 위해 유지합니다. 실제 삼성전자 주식·원화 거래가 아닌 모의 자산 서비스이며 Android의 실행 가능한 REST/STOMP 기준 클라이언트 역할도 유지합니다.

## 현재 사용자 흐름 (2026-10-05)

- 비로그인: 시장·캔들·공개 체결을 자동 조회/구독합니다. 개인 화면은 로그인으로 안내합니다.
- 로그인/회원가입: 기존 토큰 응답으로 자동 로그인하고 `/api/me`로 신원·역할을 확인합니다. 원래 선택한 화면으로 돌아갑니다. JWT를 화면이나 localStorage/sessionStorage에 저장하지 않으며 새로고침 후 재로그인합니다.
- 시장: 기준 가격·전일 대비·장 상태·공급자·갱신 시각과 6주기 차트/과거 봉, 최근 공개 모의 체결을 제공합니다.
- 거래: 매수 금액/매도 수량 → 견적 → 최종 확인 dialog(모바일 bottom sheet) → 접수 상태 → 내역. 가격·수수료·예상/최소 수령량·유효 시간을 확인합니다. 입력 변경·계정 변경·만료는 견적을 무효화하며 단순 시세 변화는 발급 견적 가격을 바꾸지 않습니다.
- 주문/체결: 서버 주문 상태와 실제 Trade를 구분합니다. Order.outputAmount는 예상 수령량, 최종 체결가는 Trade.price입니다. 주문 상세에서 AI 설명/ADMIN 정산 진단으로 이어집니다.
- 내 자산: 서버의 mKRW/mSEC 잔고·평균 매수가·평가액·미실현 손익·총 평가액을 사용합니다. 잠금/가용 잔고나 실현 손익은 추정하지 않습니다. '모의 자금 받기'는 기존 faucet 호출입니다.
- AI: 기존 stateless Agent와 USER/ADMIN Skill을 연결합니다. 설명/관측 사실/정책 출처/불확실성/trace를 텍스트로 분리합니다. 서버 flag 비활성·503·403은 거래 실패/로그아웃과 혼동하지 않습니다.
- ADMIN: 역할 확인 후 주문 수동 진단·자동 진단 이력/이전 페이지/상세·최소 관측성을 제공합니다. schema 초기화·index·자동 복구·재전송 기능은 추가하지 않습니다.
- STOMP 자동 연결·기존 1~30초 재연결과 REST 복구를 유지합니다. Native/SockJS 선택은 footer 연결 설정으로 이동했고 JWT·debug·JSON dump·테스트 버튼 화면은 제거했습니다.

네트워크/5xx로 주문 결과를 확정할 수 없으면 자동 재전송하지 않고 새 견적/주문을 잠급니다. 내역 조회 후 사용자가 중복 위험 안내를 확인한 경우에만 **새 견적을 통한 별도 주문**을 준비할 수 있습니다. 입력량/방향이 같다고 같은 주문이라고 추정하지 않습니다. 이 UI는 서버의 새 idempotency 계약을 만들어내지 않습니다.

`src/state.js`(요청별 ticket/세션 세대), `ui.js`(안전한 구조화 표시), `chart-controller.js`(기존 캔들/커서 재사용), `main.js`(화면·세션·거래/AI 연결), `api.js`/`websocket.js`가 책임을 나눕니다. 이전 `diagnosis.js`는 기존 4개 회귀용 adapter이며 현재 서비스 화면에는 import하지 않습니다.

## 검증·배포

`npm test`: 기존 34개를 변경/제외하지 않고 신규 13개를 추가한 47개 통과. 신규 3개는 실제 main.js 함수를 실행하여 주문 상세 terminal 유지/이벤트 갱신, ADMIN 교차 응답, 상세 404의 이전 결과 폐기를 검증합니다. `npm run build`: 90 modules, 정적 dist 생성. `npm run test:browser`: 실제 headless Chrome에서 빌드 UI를 실행하는 독립 REST/STOMP fixture smoke입니다. Node 22 이상과 로컬 Chrome이 필요하며 다른 경로는 CHROME_PATH로 지정합니다. 스크린샷/전용 브라우저 profile은 ignored `dist/.smoke/`에 남습니다. 사용자 프로필이나 기존 브라우저를 종료/삭제하지 않습니다.

Chrome desktop1440/mobile360의 시장·내역·자산·AI·ADMIN, 가입 자동 로그인·견적·만료·pending/terminal 이벤트·로그아웃 후 늦은 응답·AI 장애·관측 Map 계약·불명확 전송을 검증했습니다. fixture는 실제 거래 DB/Anvil/Toss/유료 모델이나 실제 WebSocket 네트워크 종단간 검증이 아닙니다. 최종 Docker 이미지 빌드와 Nginx1.28 컨테이너의 envsubst 후 `nginx -t`도 통과했습니다. 독립 검토의 두 상태 race를 수정하고 재검토에서 필수 수정 없음으로 확인했습니다. 외부 공개 배포·HTTPS 인증서·실제 Toss 장중/운영 AI 및 다양한 실기기 인수는 별도입니다.

### 정적 서비스 배포 준비

```powershell
docker build -t tokenized-market-web:local .
docker run --rm -p 8090:80 -e BACKEND_UPSTREAM=host.docker.internal:8082 tokenized-market-web:local
```

이미 실행 중인 로컬 백엔드를 사용하는 예시이며 백엔드/DB를 실행·초기화하지 않습니다. 배포 Docker network에서 백엔드 서비스 이름은 BACKEND_UPSTREAM으로 지정합니다(기본 backend:8082). 호스트는 운영자가 제어하는 host:port이며 scheme/path/secret을 넣지 않습니다. 이미지는 npm ci/build 후 Nginx가 dist를 제공하고 `/api`, `/ws`, `/ws-sockjs`는 같은 origin의 백엔드로 proxy합니다. JS 번들에 키/JWT를 넣지 않습니다. hash 내비게이션이라 별도 SPA rewrite는 불필요합니다.

운영 인터넷 공개 전에는 외부 ingress/reverse proxy에서 HTTPS 인증서를 구성하고 해당 origin에 기존 `WEBSOCKET_ALLOWED_ORIGINS`를 제한합니다. 브라우저는 HTTPS에서 기존 wss 경로를 사용합니다. API는 no-store 대상으로 운영하며 dist/index는 재검증, hash assets는 캐시합니다. proxy read timeout은 Agent40초/heartbeat를 수용합니다. Vite dev/preview를 운영 서버로 사용하지 않습니다. 이 작업에서 실제 `.env`·백엔드/DB·AI manifest/index는 변경하지 않았습니다.

---

아래는 전환 이전 개발 도구의 이력입니다. 버튼/JSON/JWT 확인 절차는 현재 UI에 적용하지 않습니다. 계약·destination·차트/재연결 규칙의 기존 설명을 보존합니다.

## 실행

먼저 PostgreSQL과 백엔드를 실행합니다.

```powershell
docker compose -p exchange up -d postgres
cd backend
.\gradlew.bat bootRun
```

새 터미널에서 테스트 클라이언트를 실행합니다.

```powershell
cd tools/websocket-test-client
npm install
npm run dev
```

브라우저에서 `http://127.0.0.1:5173`을 엽니다. 다른 백엔드 주소를 사용할 때는 실행 전에 `BACKEND_URL`을 지정합니다.

```powershell
$env:BACKEND_URL = "http://127.0.0.1:8082"
npm run dev
```

Vite 개발 프록시가 `/api`, `/ws`, `/ws-sockjs`를 백엔드에 전달하므로 테스트 때문에 백엔드 REST CORS 정책을 변경하지 않습니다.

## 확인 순서

1. 회원가입 또는 로그인 후 `JWT 준비됨` 표시를 확인합니다.
2. Native `/ws`를 선택하고 연결합니다.
3. 차트가 REST에서 최근 1분봉 100개를 불러오고 `READY`를 표시하는지 확인합니다.
4. `1분`·`5분`·`15분`·`30분`·`1시간`·`1일` 버튼으로 차트 주기가 전환되는지 확인합니다.
5. `/topic/markets/mSEC/price` 이벤트가 들어올 때 차트가 `LIVE`로 바뀌고 현재 봉과 거래량이 갱신되는지 확인합니다.
6. 차트를 왼쪽 끝으로 이동해 `nextBefore` 기반 과거 봉이 기존 위치를 유지한 채 추가되는지 확인합니다.
7. faucet을 호출하고 개인 포트폴리오 이벤트를 확인합니다.
8. 매수 또는 매도 카드에서 먼저 `견적 받기`를 누릅니다. `quoteId`, 서명 가격, 수수료, 예상/최소 수령량, 관측·만료 시각과 남은 시간을 확인합니다. 서명 원문은 브라우저에 전달되지 않습니다.
9. 입력 금액을 바꾸면 기존 견적과 주문 버튼이 무효화되는지 확인합니다. 새 견적을 받은 뒤 `주문 확정`을 눌러 해당 `quoteId`를 한 번만 사용합니다.
10. 요청 중에는 로컬 `LOADING` 안내가 표시되고, 서버 응답 뒤 `LAST ORDER`가 `PENDING_ONCHAIN → FILLED` 또는 `FAILED`로 바뀌는지 확인합니다. 새 주문을 시작하면 이전 체결가는 지워져야 하며, 체결되면 해당 주문에 결합된 최종 온체인 가격과 수수료가 표시됩니다. `REQUESTED`는 DB 내부 준비 상태이며 WebSocket으로 발행하지 않습니다.
11. 공개 체결, 개인 주문, 개인 포트폴리오 WebSocket 이벤트와 REST 조회 결과가 일치하는지 확인합니다.
12. 연결을 끊고 SockJS `/ws-sockjs`를 선택해 같은 흐름을 확인합니다.
13. JWT를 지우고 연결하면 공개 이벤트만 구독되는지 확인합니다.
14. 백엔드를 잠시 중단하거나 네트워크를 끊었을 때 `RECONNECTING`이 표시되고, 복구 후 `CONNECTED`와 REST 동기화 결과가 표시되는지 확인합니다.

## Android MVP 대응표

| Android 사용자 기능 | 웹 검증 위치 | 백엔드 계약 |
| --- | --- | --- |
| 회원가입·로그인 | 계정 입력과 회원가입·로그인 버튼 | `POST /api/auth/signup`, `POST /api/auth/login` |
| 내 정보 | `내 정보` 버튼 | `GET /api/me` |
| 시장 상태·현재가 | 시장 상태와 현재 가격 카드 | `GET /api/markets/mSEC` |
| 실시간·과거 차트 | 차트 주기 전환과 과거 탐색 | 캔들 REST + `/topic/markets/mSEC/price` |
| 테스트 원화 충전 | `mKRW Faucet` 버튼 | `POST /api/wallet/faucet` |
| 포트폴리오 | `포트폴리오 조회`와 개인 이벤트 | `GET /api/portfolio`, `/user/queue/portfolio` |
| 매수·매도 | 견적 카드와 주문 확정 | 견적 API → `quoteId` 주문 API |
| 주문 상태·내역 | `LAST ORDER`, `주문 조회`, 개인 이벤트 | `GET /api/orders`, `/user/queue/orders` |
| 체결 내역 | `체결 내역 조회`, 공개 체결 이벤트 | `GET /api/trades`, `/topic/markets/mSEC/trades` |

위 표의 모든 기능을 웹에서 먼저 검증한 뒤 동일한 계약을 Android에 적용합니다. WebSocket이 끊기면 1초부터 최대 30초까지 지수 백오프로 자동 재연결하고, 연결 성공 후 아래 REST 상태를 다시 읽습니다.

- 공개 연결: 시장 상태와 현재 차트
- 인증 연결: 주문, 체결, 포트폴리오 추가

복구 요청 중 더 새로운 연결이 만들어지면 이전 응답은 버립니다. 최신 주문과 해당 체결을 `LAST ORDER`에 복원하고, 전체·부분 실패 결과는 `최근 REST 복구 결과`에서 구분합니다. 인증이 거부되거나 REST가 401/403을 반환하면 자동 재연결을 중단하고 메모리의 JWT와 사용자별 화면 상태를 지운 뒤 다시 로그인을 요구합니다.

서명 견적은 30초 동안만 유효합니다. 만료된 견적, 이미 소비한 견적, 바뀐 입력량은 기존 견적을 조용히 재사용하지 않고 오류와 재견적 안내를 표시합니다. 실제 Toss 공급자를 사용하는 경우 휴장·오래된 가격 정책에 따라 신규 견적이 차단될 수 있으므로 정상 체결 시연은 장중에 수행합니다.

차트는 최초 로딩·주기 변경·WebSocket 재연결 때 REST 캔들을 다시 조회해 놓친 구간을 복구합니다. 왼쪽 끝으로 이동하면 응답의 `nextBefore`로 이전 페이지를 요청해 중복을 제거한 뒤 앞에 추가하며, 보고 있던 화면 위치는 유지합니다. 더 과거 데이터가 없거나 cursor가 전진하지 않으면 추가 요청을 중단합니다. 현재 봉은 이벤트의 서버 발행 시각이 아니라 공급자의 `observedAt`으로 선택한 주기의 벽시계 구간을 정하고, Toss에서는 실제 체결량을 누적하며 시뮬레이션에서는 tick 하나를 거래량 1로 표시합니다.

API와 DB의 시각은 표준 UTC로 유지하며, 차트 시간축과 크로스헤어는 한국 시장에 맞춰 `Asia/Seoul`로 표시합니다.

캔들 렌더링은 Apache-2.0의 `lightweight-charts`를 사용하며 라이선스가 요구하는 TradingView 저작자 로고와 링크를 차트에 유지합니다.

블록체인 연동이 활성화된 환경에서는 개인 주문 로그가 다음 순서로 나타나는지도 확인합니다.

```text
ORDER_PENDING_ONCHAIN
→ ORDER_FILLED 또는 ORDER_FAILED
```

## 이벤트 destination

| 구분 | Destination | 인증 |
| --- | --- | --- |
| 가격 | `/topic/markets/mSEC/price` | 불필요 |
| 공개 체결 | `/topic/markets/mSEC/trades` | 불필요 |
| 내 주문 | `/user/queue/orders` | JWT 필요 |
| 내 포트폴리오 | `/user/queue/portfolio` | JWT 필요 |

JWT는 페이지 메모리에만 저장됩니다. 새로고침하면 사라지며 `localStorage`나 파일에 기록하지 않습니다.

WebSocket은 상태 변경 알림 채널이며 영속 로그가 아닙니다. 따라서 재연결 후 클라이언트가 시장·차트·주문·체결·포트폴리오를 REST API로 자동 재조회해 PostgreSQL의 최신 상태를 화면에 복원합니다. 이 복구 규칙은 Android에서도 동일하게 적용합니다.

## 검증

```powershell
npm test
npm run build
```

`npm test`는 캔들 정렬, 1분~1시간 구간 계산, 동일 구간 OHLCV 병합, 새 구간 생성, 오래된 tick 무시, KST 일봉 경계, 과거 페이지 병합·cursor 종료·재시도와 REST 로딩·주기 변경·재연결 중 WebSocket tick 경합을 검증합니다. 또한 서명 견적 정규화, 서명 비노출, 입력 변경·만료에 따른 주문 차단, 남은 시간과 사용자용 오류 변환, 복구 세대 격리, 최신 주문·체결 선택, 부분 실패, 인증 오류, 지수 백오프와 명시적 연결 해제를 검증합니다.
# ADMIN 자동 진단 패널 (AI Phase 7)

로그인 후 `/api/me`가 ADMIN인 경우에만 패널을 표시합니다. 주문 ID를 지정해 기존 settlement-debugging 수동 Skill을 실행하거나, 자동 진단 이력·이전 페이지·진단 ID별 상세를 조회할 수 있습니다. 결과 JSON에는 실행 시점의 관측·정책 해석·출처·trace와 자동 수정 안 함 표시가 있습니다. 현재 거래 상태와 동일하다고 가정하지 마세요.

백엔드의 AI/Agent/Tool/Skill 설정, AI 진단 schema 명시적 초기화와 자동 진단 actor/namespace 설정이 필요합니다. [백엔드 안내](../../backend/README.md)를 따르세요. 웹은 자동 진단을 시작하거나 재거래·복구를 요청하지 않습니다. USER는 이력 API에서도 거부됩니다. 출력은 HTML이 아니라 안전한 텍스트로 표시하며 로그아웃/계정 교체 시 이전 응답을 폐기합니다.
