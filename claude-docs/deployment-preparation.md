# 공개 demo 배포 준비 — 구현·로컬 검증 완료 / AWS 승인 대기

> 2026-10-07. 승인 범위: 기존 external-deployment-design 기준의 production Compose, backend 이미지 안전화, AI artifact, HTTPS/WSS/rate limit, SSM 주입, volume/backup/restore와 로컬 검증. 실제 AWS 생성·게시·도메인·유료 평가·실제 .env 변경은 제외한다.

## 구현 경계

- 별도 `deployment/compose.yml`: Nginx만80/443 publish, private trade/AI/chain network와 backend egress, 별도 data bind mounts, log rotation/메모리 제한. 기존 개발 Compose/DB는 보존하고 단일 backend를 유지한다.
- backend Dockerfile: allowlisted copy/.dockerignore, Java21 base digest, 비root UID10001/read-only root/tmpfs/capability 제거. 개인 .env·로그·build는 context에서 제외한다.
- web PUBLIC_DEMO 빌드: 최초 API/WS/JS 실패에도 정적 SIMULATED · 합성 시장 안내. 로컬 Toss 웹의 표시는 고정하지 않는다. public 서버 guard+단일 backend/동일 candle 경로로 충분해 mismatch UI guard는 추가하지 않았다. 혼합 upstream/cache 도입 시 재검토한다.
- `ops.py artifact`: 지식9개 구조/권한 유지, 시장정책v2·시스템개요v7에 합성 공개 경계 반영. manifest hash/version 검증 후9개만 read-only artifact로 전달. 배포 색인·실제 golden 재평가는 유료 호출 승인 후 별도 ingest한다. 이전 평가를 새 artifact 평가로 쓰지 않는다.
- Nginx: 정확한 host/TLS1.2·1.3/redirect/ACME, Native/SockJS WSS, body/time·connection 제한, 로그인/거래/AI429, 인수IP deny-default. 외부 index/Tool/direct RAG 차단과 기존 USER/ADMIN JWT 유지. upgrade 제한은 frame quota/비용 hard cap이 아니다.
- `ops.py ssm`: SecureString8개 이름의 GetParameters, 키 분리·allowlist·길이 검증, 비밀 출력/argv 금지, runtime version+atomic symlink, properties/PASSWORD_FILE 주입. IAM/KMS 예시만 작성했고 AWS는 호출하지 않았다. DB암호/signer rotation은 자동화하지 않는다.
- `DeployPublic.s.sol`·offline compiled tool: 전용키·default funded accounts0·chain31337만 허용, 유한 모의재고/allowance. init-attempt/주소 파일로 재배포 거부, private 오류 로그. 기존 src 컨트랙트·거래/TTL은 변경하지 않았다.
- `ops.py backup/restore`: ingress/backend 정지·미정산 주문/전송/잠금0·Anvil 정상저장 후 두DB dumps/체인/주소/릴리스 해시. 실패하면 중지 유지. 복원은 신규 isolated project/빈 data root·effective mount 일치만 허용. 운영 overwrite 금지. [RUNBOOK](../deployment/RUNBOOK.md)에 TLS/S3/secret/영속화 절차를 기록한다.

## 검증 현황

- backend 최종 전체347 중317통과/30조건부skip/실패·오류0. 고정문서 version6 assertion을 actual system7/market2로 수정했고, 실제 HTTP 권한 회귀1개를 추가했다. hash·role·청크 크기 assertion은 유지했다. 이번 기본 전체 실행의 조건부skip을 직전 Synthetic의 실제 외부 통합 실행과 혼동하지 않는다.
- 웹48통과,90module build/Chrome desktop·mobile fixture smoke 통과. forge test -q 통과. backend/web Linux빌드 성공.
- 운영 스크립트13개 Linux 컨테이너에서 모두 통과. Windows에서는11통과/2POSIX 조건부skip이다. secret/키 분리·properties injection/Unicode·정확한 SSM 요청/평문거부·artifact/해시불일치·운영restore/기존data거부·POSIX 권한·실제 mount 불일치 사전거부를 검증했다.
- `python deployment/local-smoke.py` 최종 종료0, 운영형16개 체크 모두 통과. 증거는 Git 제외 `deployment/runtime/local-20261007030121/results.json`이다. 신규 격리 PostgreSQL/pgvector/Anvil/backend/Nginx를 사용했으며 기존 개발 환경·실제 .env는 변경하지 않았다.
- 테스트 CA/hostname 검증 HTTPS, Native WSS/SockJS 실제 STOMP 가격 이벤트,6주기 차트, JWT/권한/운영API 차단, faucet→quoteId 매수→FILLED·견적 재사용 거부, 재시작 연속성, AI DB/닫힌 로컬 AI 공급자/RPC 장애 격리, 로그인429, 최초 API 장애 시 정적 합성 표시를 확인했다.
- 미정산0에서 양DB+체인 checkpoint 백업 후 신규root 복원, pgvector row·기존 체결·체인 상태와 복원 후 **새 EIP-712 매수의 FILLED**까지 통과했다. 이번 운영형 fixture는 BUY이며 SELL은 직전 Synthetic 통합 검증 이력과 구분한다. 테스트 컨테이너/네트워크만 정리하고 bind 데이터·증거를 보존했다.
- 독립 초기 검토의 필수2건은 수정했다. 1차 재검토와 최종 운영형 증거 검토에서 발견된 필수 수정 없음. 검토자는16개 결과·백업5개 SHA-256 일치·원본/복원 bind 보존을 직접 확인했다. 전체 Docker 재실행은 하지 않았다. 이번 증거 폴더에는 private-stack.log만 있고 복원 private 로그는 없다(최신 helper의 후속 실행부터 보존). self-signed TLS를 공인 인증서/AWS 운영 검증으로 표현하지 않는다.

## 후속 검증과 수정 (2026-10-07)

- backend는 실제 Tomcat의 USER 권한403이 secured `/error` 재dispatch로401로 바뀌는 것을 확인했다. 권한 rules는 그대로 두고 명시403 JSON handler를 추가했다. 실제 HTTP 익명401/USER403/ADMIN200과 MockMvc 회귀 통과, 최종 전체347 중317통과/30skip/실패0이다.
- 독립 검토의 필수2건을 수용했다. Linux restore의 chain/state UID:GID10001·700/600와 chain.properties root:GID10001·640을 명시한다. backup은 예정 Compose뿐 아니라 **실제 실행 중 container label/Bind Mounts**도 stop 전에 비교한다. Linux 컨테이너에서 POSIX 실제stat·secret640/600/버전교체·old-root 혼합 백업 사전거부를 포함한13개 unit이 모두 통과했다.
- 운영형 HTTPS/WSS는 테스트 CA/hostname을 검증한다(verify off 아님). AI는 켜고 provider를 localhost 닫힌 포트로 지정해 실제 연결 실패·AI DB 중단과 거래조회 격리를 검증했다. RPC 장애는 기존 web3j timeout을 고려해 별도75초 fixture와 gateway503/504를 구분하며, 대기 중 시장/포트폴리오200을 확인했다. production RPC timeout/보고서 TTL/재시도는 변경하지 않았다.
- 초기화 helper는 빌드 때 solc를 준비하고 private network에서 offline 실행한다. 오류는 private-init.log로만 남긴다. 공인 ACME 최초 발급용 별도 Compose, 갱신 shell·systemd 원본, 명시 비용승인 후 내부 ADMIN 색인 helper를 추가했고 실제 발급/설치/유료 색인은 실행하지 않았다.
- 백업·양DB dump/pgvector row 복원까지 실제 통과 후 새 복원 주문에서 Anvil 저장 블록 시각 때문에 gas estimation이 future observation으로 거부되는 것을 확인했다. 현재 시각의 빈 블록을 만든 뒤202 접수가 성공했다. 이를 private Anvil entrypoint/healthcheck에 반영했고 미래2초 초과 체인은 fail-closed한다. 기존 블록/잔고/nonce를 reset하거나 서명TTL/검증을 완화하지 않는다.
- 테스트 컨테이너/네트워크 누적으로 Docker 주소풀이 찼을 때 이번 프로젝트만 down해 정리했다. bind 데이터/증거 보존, -v/prune/기존 개발 프로젝트 변경 없음. local-smoke finally에도 같은 정리와 private 로그 보존을 넣었다. 최종 새 전체 Compose 실행·재검토 결과는 아래에 추가한다.

### 아직 별도인 운영 인수

AWS EC2/IAM/SSM/KMS/S3/EBS/SG/DNS 생성·접근, 공인 인증서 발급/갱신·timer 설치, 공개 브라우저/외부port 인수, Linux 호스트 전체 Compose·자원/부하·RTO/RPO는 미검증이다. 유료 AI 재색인/golden·실제 외부 OpenAI 장애·실제 Toss 장중 수동 인수도 구분한다. 이번에는 Docker Desktop의 Linux 컨테이너로 검증했다. 공개 배포는 Anvil이며 Sepolia/HA/개인 지갑/준비금 reconciliation은 없다. 실제 AWS 생성은 별도 예산·도메인·운영 정책 승인 후 진행한다.

## 공식 참고

[AWS GetParameters](https://docs.aws.amazon.com/cli/latest/reference/ssm/get-parameters.html)의 정확한 이름/decryption을 사용하고 recursive path 권한 확대를 피한다. [Anvil](https://www.getfoundry.sh/anvil/index.html)의 state는 pinned 이미지 help와 실제 실행으로 확인한다. [Nginx WS](https://nginx.org/en/docs/http/websocket.html)와 [rate limit](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)의 경계는 frame quota와 구분한다.
