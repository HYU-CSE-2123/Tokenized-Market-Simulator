# WebSocket Test Client

Android 연동 전에 백엔드 REST, JWT, STOMP WebSocket 이벤트를 실제 브라우저에서 검증하는 개발 도구입니다. 제품용 웹 프론트엔드가 아닙니다.

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
$env:BACKEND_URL = "http://127.0.0.1:8080"
npm run dev
```

Vite 개발 프록시가 `/api`, `/ws`, `/ws-sockjs`를 백엔드에 전달하므로 테스트 때문에 백엔드 REST CORS 정책을 변경하지 않습니다.

## 확인 순서

1. 회원가입 또는 로그인 후 `JWT 준비됨` 표시를 확인합니다.
2. Native `/ws`를 선택하고 연결합니다.
3. `/topic/markets/mSEC/price` 가격 이벤트가 계속 들어오는지 확인합니다.
4. faucet을 호출하고 개인 포트폴리오 이벤트를 확인합니다.
5. 매수 또는 매도 후 공개 체결, 개인 주문, 개인 포트폴리오 이벤트를 확인합니다.
6. 연결을 끊고 SockJS `/ws-sockjs`를 선택해 같은 흐름을 확인합니다.
7. JWT를 지우고 연결하면 공개 이벤트만 구독되는지 확인합니다.

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

WebSocket은 상태 변경 알림 채널입니다. 연결 중 놓친 이벤트를 복원하는 영속 로그가 아니므로 재연결 후 주문과 포트폴리오는 REST API로 다시 조회해야 합니다.
