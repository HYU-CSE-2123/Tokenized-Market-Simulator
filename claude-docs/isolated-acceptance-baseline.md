# Toss / AI 최종 인수용 신규 격리 baseline

> 2026-10-08: 환경 준비 완료. 실제 Toss 장중 BUY/SELL과 유료 AI/성공 MATCH 인수는 **미실행**. 최종 baseline 동결 전이다.

## 승인 경계와 보존

- [개발 체인 조사](development-chain-state-audit.md) 결과를 수용하고 새 DB·체인을 만들었다. 기존 개발 DB/state를 가져오거나 주소·nonce·원장을 보정하지 않는다.
- 기존 개발 컨테이너 3개는 ID/시작 시각이 작업 전후 동일하다. 거래 DB의 blockchain_transactions 22,603 / orders 12 / trades 10과 기존 Anvil block 0도 동일했다. `backend/.env` SHA-256 전후 일치. 기존 환경을 중지·재배포·복원·초기화하지 않았다.
- 기존 `.env`에서 **TOSS_CLIENT_ID / TOSS_CLIENT_SECRET 두 값만** allowlist로 읽어 새 비공개 설정에 복사했다. 기존 DB 설정/지갑/JWT/ADMIN/OpenAI key는 사용하지 않았다. 동일 공급자 자격 증명의 새 연결이며 기존 프로세스와 연결 한도·세션 정책을 공유할 가능성은 있다. 이 점을 완전한 외부 계정 격리로 표현하지 않는다.
- 제품 backend/web/contracts 코드는 변경하지 않았다. AWS·부하/장애 실험·유료 색인·LLM 호출·자동 진단은 실행하지 않았다.

## 구성과 실제 확인

| 항목 | 인수 전용 구성 |
|---|---|
| 프로젝트 | 새 `exchange-acceptance-*` Compose namespace |
| 웹 | `http://127.0.0.1:15173` — 기존 Vite/JS 제품 UI |
| 백엔드 | `http://127.0.0.1:18082` — acceptance profile, Toss, 서명 거래 활성 |
| 거래 PostgreSQL | `127.0.0.1:5542`, DB/user `acceptance_exchange` |
| AI PostgreSQL | `127.0.0.1:5543`, DB/user `acceptance_ai`, pgvector 0.8.6 |
| RPC | `http://127.0.0.1:18545`, chainId 31337, 자동 생성 계정 0 |
| 영속 데이터 | 새 ignored `deployment/runtime/acceptance-*/data/{trade,ai,chain,release}` bind |
| 비밀 | 새 run 아래 `secrets/`, 비공개 ACL/권한, 파일 import만 사용 |
| AI 지식 | active 문서 9개/manifest/hash 검증 후 로컬 artifact 복사, read-only mount. **색인 아님** |

실제 생성 run은 ignored runtime 아래 `acceptance-20261007153304-9bc0b3`이다. 주소는 이 run의 `data/release/chain.properties`를 정본으로 사용하고 별도 `.env`에 복사하지 않는다. 다른 run을 생성하면 이전 주소를 재사용하지 않는다.

- 새 무작위 operator/price signer는 서로 다른 주소이고 기존 개발 키를 재사용하지 않는다. operator가 배포/거래 전송, signer가 EIP-712 가격 보고서 서명을 담당한다.
- 기존 `contracts/script/DeployPublic.s.sol`과 `deployment/init-chain.sh`를 재사용하여 네 컨트랙트 배포, mSEC minter=Vault, Oracle authorizedConsumer=Vault, Vault 세 주소 연결을 확인했다.
- operator/Vault 각 5,000,000 mKRW, operator→Vault allowance=max uint256, operator mSEC=0. 기존 스크립트가 로컬 `anvil_setBalance`로 operator ETH를 준비한다. 실제 원화/실제 체인 자금 아님.
- Anvil은 5초 state 저장·역사 state 보존·정상 종료 저장을 사용한다. 전용 Compose 재기동 후 동일 네 주소의 코드/role/잔고 유지와 state 파일 존재를 확인했다. 계약 재배포는 하지 않았다. **전체 장애/backup restore 검증은 아니다.**
- Toss 실제 REST 응답 `provider=TOSS`, price=269,000, `marketStatus=CLOSED`, observedAt=`2026-10-07T10:59:59Z` 확인. `priceStatus=LIVE` 단독으로 현재 장중/신선한 견적 가능 상태라 판단하지 않는다. 실제 장 상태는 CLOSED였다.
- 휴장 BUY 견적은 HTTP 409 / `MARKET_CLOSED`. 가격/시간/장 상태를 조작하거나 simulated fallback으로 체결을 대신하지 않았다.
- 새 `acceptance_user`로 실제 Chrome 로그인, 공개 시세/체결 및 개인 주문/자산 STOMP 구독, UI 모의입금 1회 후 `PORTFOLIO_UPDATED` 수신을 확인했다. 사용자 DB mKRW=1,000,000 / mSEC=0 / 잠금=0, 주문/체결 각각 0. 이 faucet은 DB 모의입금이며 Vault를 추가 발행/충전하지 않는다.
- 390×844에서 시장·내역·포트폴리오 수평 overflow 없음. 휴장이라 실제 시세 tick 수신/캔들 증가와 BUY/SELL 주문 이벤트/정산은 확인하지 못했다.
- AI/Tools/Agent/Skills/Auto flags 모두 false, Agent API 503. AI DB/지식 준비는 완료했지만 index/AI 진단 이력은 생성하지 않았다.

## helper와 실행

이번 helper 실행/실환경 검증 대상은 **Windows + Docker Desktop**이다. POSIX 경로에서는 host 0700 디렉터리/0600 비밀 파일과 컨테이너 UID10001 소유권을 맞추는 별도 준비가 필요하며 현재 그대로 Linux에서 동작한다고 보장하지 않는다. Linux/EC2 이식·운영 검증은 별도 재개 범위다.

- `deployment/acceptance.py`: `prepare`, `start`, `stop`, `probe`. root/project/service/mount 범위를 검사하고 별도 파일·이미지를 사용한다. 실패 출력은 보호된 로그에만 저장한다.
- `deployment/acceptance-compose.yml`: 전용 4서비스, 루프백 publish, 새 run bind. 공유/external volume·고정 container_name 없음.
- `deployment/test_acceptance.py`: 오프라인 비밀 allowlist/키/덮어쓰기 거부/root/서비스/RPC/ambient config/AI 비활성 경계.
- `tools/websocket-test-client/scripts/acceptance-readiness.mjs`: fixture 없는 실제 Chrome/STOMP 준비 점검. 로그인 및 **1회 faucet**만 수행하고 주문/AI 호출은 없다.

현재 run을 이어서 사용할 때 리포 루트에서:

```powershell
python deployment/acceptance.py start --run deployment/runtime/acceptance-20261007153304-9bc0b3
python deployment/acceptance.py probe --run deployment/runtime/acceptance-20261007153304-9bc0b3
```

웹은 별도 터미널에서 **전용 백엔드 주소를 반드시 지정**한다. 일반 개발 서버의 기본 target을 사용하지 않는다.

```powershell
cd tools/websocket-test-client
$env:BACKEND_URL='http://127.0.0.1:18082'
$env:PUBLIC_DEMO='false'
npm run dev -- --host 127.0.0.1 --port 15173
```

인수 USER 정보는 run의 `secrets/ui-account.json`, ADMIN 정보는 `secrets/application.properties`에서 **로컬로만** 확인한다. 파일/비밀번호/JWT/개인키/전체 로그를 채팅·문서·커밋에 넣지 않는다. runtime 전체는 Git ignored다.

`prepare --toss-env backend/.env`는 필요 시 **새 run을 생성하는 명령**이지 현재 run을 재개하는 명령이 아니다. 고정 포트가 이미 사용 중이면 거부한다. `start`는 배포하지 않으며 `stop`은 그 run 컨테이너만 중지하고 데이터는 보존한다. `down -v`, prune, state 삭제, init guard 삭제로 실패를 우회하지 않는다.

브라우저 재실행은 이전 실패 phase/주문/잔고를 먼저 확인한다. `readiness-attempt.json`을 삭제하지 않고 새 명시적 attempt 이름을 사용한다. 불명확한 faucet 결과를 자동 재요청하지 않는다. 이번 최초 실패는 **로그인 이전**에 종료되어 쓰기 없음이 확인됐고, 수정한 검사로 명명된 `label-corrected` 시도에서 faucet 1회를 실행했다.

## 발견 사항과 조치

1. 처음에는 internal-only network의 컨테이너에서 Docker Desktop host port가 실제 publish되지 않았다. 내부 RPC는 정상, host RPC는 연결 종료, inspect의 실제 Ports 비어 있음을 대조했다. 새 namespace만 일반 host-access bridge에 추가하고 publication은 127.0.0.1로 유지했다. 기존 서비스는 변경하지 않았다.
2. 실제 웹의 공급자 표기는 `Toss 기준 시세`인데 새 검사 helper가 `TOSS`를 기다렸다. `src/main.js`와 실패 phase를 대조한 **검사 오류**다. 제품 수정 없이 helper 조건을 수정했고 최초 실패 marker를 보존했다.

## 검증 결과 / 한계

- `python -m unittest discover -s deployment -p "test_*.py"`: 31개 중 29 PASS / 2 POSIX 전용 skip, 실패 0. 신규 acceptance 10개 포함.
- `npm test`: 기존 34개 포함 현재 48/48 PASS.
- `npm run test:browser`: build 90 modules 및 desktop/mobile fixture smoke PASS. **실제 공급자 인수 결과와 구분**한다.
- 실제 Chrome: 로그인·4채널 구독·모의입금/개인 자산 이벤트·모바일 3화면 PASS. evidence는 보호된 run/browser/label-corrected 아래에 저장했다.
- 제품 코드는 변경하지 않아 이번에 전체 Gradle/Foundry를 재실행하지 않았다. 2026-10-07 전체 회귀 수치는 [이전 기록](validation-2026-10-07.md)의 해당 실행 결과이며 이번 결과로 합산하지 않는다.
- 별도 검토 `review_acceptance_baseline`: **발견된 필수 수정 없음**, 이번 Windows/Docker Desktop 준비 범위 기준. 직접 helper31개29PASS/2POSIXskip. 비밀파일 allowlist·새 키·namespace/mount·loopback·배포/재개 분리·AI 비활성·불명확한 faucet 자동재시도 없음·문서의 완료/미완료 구분을 확인했다. Linux 소유권 한계는 위에 명시했다.
- 검토자는 runtime ACL로 비밀없는 readiness 목록에도 접근하지 못했다. 실제 환경 보존·영속 재기동·브라우저 결과는 **구현자 실행 결과**이고 독립 증거 대조/실환경 재실행으로 표현하지 않는다. 제품 테스트·Docker 변경·실제 API·유료 호출을 검토자가 재실행하지 않았다.

## 다음 인수 / 별도 승인

1. 실제 Toss 시장 OPEN·가격 신선도 확인 후 이 **동일 run**에서 웹 BUY→quoteId→DB 보고서/서명→Anvil receipt/event→DB FILLED/Trade/잠금해제→개인 WS→포트폴리오, 이후 보유 수량으로 SELL을 추적한다. 실제 체결금액/수수료/nonce/receipt·event와 DB 일치를 증거화한다. 휴장 BUY/SELL은 미완료다.
2. 그 다음 AI 공급자 인수의 embedding 재색인 예상량, 기존 실제 토큰 기반 예상 호출 비용/횟수, 승인된 Agent/Skill·USER/ADMIN·이력/상세 절차를 먼저 보고하고 승인받는다. AI key를 기존 환경에서 자동 가져오지 않는다.
3. 성공 MATCH 자동 진단은 정상 거래를 검토 대상으로 만드는 최소 **격리 전용** 재현 방법/전후 상태/예상 호출·비용을 먼저 제시한다. 현재 REVIEW_REQUIRED 데이터를 조작하거나 자동 진단 flag를 켜지 않는다.
4. 두 실제 인수 완료 후에만 기능 baseline을 동결한다. 운영 부하/장애 실험 및 EC2 생성은 별도 사용자 재개/승인 사항이다.
