# 로컬 수동 체험 환경

최신 통합 소스를 웹에서 직접 사용하기 위한 별도 환경이다. 기존 개발/acceptance/검증 원장을 재사용하지 않는다. 실행·검토 결과는 확인한 뒤 아래에 기록한다.

## 사용법

통합 worktree 폴더에서 PowerShell을 열고:

```powershell
cd C:\Users\kyobin\Desktop\study\Tokenized-Market-Simulator\deployment\runtime\integration-worktree
.\playground.cmd start
.\playground.cmd status
.\playground.cmd account
.\playground.cmd stop
```

- 첫 start는 새 private runtime·키·비밀번호·DB·Anvil을 준비하고 계약을1회 배포한다. 이후 start는 저장된 DB/chain을 사용하고 재배포하지 않는다.
- 웹: http://127.0.0.1:25173. backend: http://127.0.0.1:28082. DB/RPC는 Docker 내부에서만 사용한다.
- 가입 후 자동 로그인 → 내 자산에서 모의 자금 받기 → 시장에서 견적/매수 → 주문/체결 → 자산 → 매도.
- 관리자: account 명령이 전용 관리자 ID/비밀번호 파일을 로컬 메모장으로 연다. 값은 terminal/log/Git에 출력하지 않는다. 관리자 로그인 후 장부-체인 대사와 거래 감사·정체 관측을 사용할 수 있다.
- Synthetic 시세이며 실제 삼성전자 가격이 아니다. 온체인 거래는 이 환경의 새 local Anvil에만 제출된다. AI/조건부 주문은 비활성이다. 공개 데모 표기·onchain DB장부 모델을 유지한다.
- 종료는 해당 project 컨테이너만 정지한다. 데이터를 삭제하지 않으며 다음 start에서 잔고/거래 이력을 유지한다.

## 환경변수는 왜 직접 안 넣어도 되나

backend는 원래 .env를 자동 읽을 수 있다. 이 도구는 기존.env를 읽지 않고 전용 runtime secret/property 파일을 자동 생성해 Compose에서 import한다. 사용자가 반복해서 키·DB 주소·계약 주소를 터미널에 입력할 필요가 없다. 기존.env는 변경하지 않는다.

## 안전 경계와 제한

- 기존 project/DB/Anvil/secret은 import하지 않는다. 새 이름, 새 bind root와 loopback 포트만 사용한다. occupied port는 강제 확보하지 않는다.
- .env/Compose/외부키 환경 상속을 차단한다. Toss/Kiwoom/AI API 호출 없음. AI DB 컨테이너도 만들지 않는다.
- 계약 배포는 기존 init-chain.sh/DeployPublic의 init-attempt·chain.properties guard를 사용한다. 준비 실패는 private 증거를 남기고 reset/redeploy하지 않는다.
- 최초 준비 전에 선택 pointer를 exclusive 예약한다. 준비가 중단되면 다음 start도 새 환경을 자동 생성하지 않는다. 미완료 환경은 status/stop도 거부하므로 소유권 확인 후 해당 project만 수동 정지하는 별도 진단이 필요하다. Docker 전체 정지·삭제는 하지 않는다.
- 처음 DB잔고0 상태에서 ADMIN 불변 reserve baseline을 생성한다. 불일치를 이유로 기준점을 다시 설정하지 않는다.
- 현재 소스·런타임 이미지 변경은 start에서 거부한다. 제품 변경 후 새 체험 환경/업그레이드는 별도 작업이다. 기존 환경 지우는 명령은 없다.
- Docker Desktop, 설치된 Java21·Python·Node, 캐시된 Gradle/npm/컨테이너 이미지가 필요하다. 오프라인 빌드이며 자동 설치/pull 없음. 현재 Java21 경로는 이 Windows 머신의 설치 위치다.
- 실제 시장/AI/조건부/장기 운영/외부 배포 인수와 이 로컬 수동 체험은 별개다. 최초 준비에 필요한 DB/password/Anvil 자금은 전용 환경에만 생성된다.

## 구현 파일

playground.cmd, deployment/playground.py, playground-compose.yml, Dockerfile.playground-backend/web, test_playground.py. 제품 Java/계약/UI 소스는 변경하지 않는다.

## 검증 기록

2026-10-14, 입력 통합 HEAD `240cb3982d6c05724d6a89e99aed66788d77d697`. 신규 run `playground-20261014013317-621cacdd`만 생성했다.

- 도구 단위 테스트: `python -S -m unittest discover -s deployment -p test_playground.py -v`, 8/8 PASS. 준비 중단·동시 pointer 예약·기존 runtime 차단·외부 설정 상속 차단 포함.
- 웹 단위 테스트: 84/84 PASS, SKIP0. Vite 빌드와 Chrome desktop/mobile browser-smoke PASS. Chrome은 fixture 서버이며 실제 Docker API 인수와 구분한다.
- 실제 신규 PostgreSQL/Anvil: 회원가입·JWT·Nginx REST proxy·100만 mKRW Faucet → 새 서명 견적 BUY 10만 mKRW → SELL 0.5 mSEC, 두 주문 FILLED.
- 장부-체인 대사 MATCH. 거래 감사 COMPLETED/MATCH, DB/chain/cut/environment coverage 모두 true, sourceOrders2/scannedEvents2/inspected2.
- stop/start 실제 성공. 동일 주문 상태/txHash와 DB mKRW·mSEC 잔고·평균 매수가 유지. 재배포 없음. Nginx WebSocket proxy를 통해 STOMP CONNECTED와 실제 합성 가격 MESSAGE 수신.
- 독립 최초 검토의 필수 지적(완료 후에만 pointer를 생성해 준비 실패 후 재배포될 위험)을 수정했다. 첫 재검토: 발견된 필수 수정 없음, reviewer 직접 단위8/8 PASS. Docker 거래·보존은 구현자 실행 결과이며 reviewer가 재실행한 결과가 아니다.
- 검토 후 이번 새 runtime의 harness sourceHash만 명시적으로 정렬해 재시작했다. 변경 전 metadata 보존, 제품 artifact·키·DB·chain 재생성 없음. 기존 환경은 미접근/미변경. runtime·secret·chain은 Git ignore 확인.
- backend 전체/Foundry 전체 회귀, 실제 외부 시세/유료 AI/조건부 주문 및 실제 Android는 이번 작업에서 NOT_RUN. 제품 소스 변경은 없으며 bootJar/웹 빌드는 실제 실행했다. 기존 검증 결과를 이번 PASS로 재사용하지 않는다.

실행 증거는 해당 private runtime의 validation.json, audit-result.json, smoke.private.json, logs/에 보존된다. smoke.private.json은 테스트 JWT를 포함하므로 공유하지 않는다. 준비 완료 환경은 사용자가 바로 접속하도록 켜 둔다.
