# 포트폴리오 외부 배포 설계안

> 2026-10-05 · 제안/사용자 승인 대기. 인프라 생성, 도메인 구매, 외부 게시, 실제 .env 변경, DB/체인 배포는 하지 않았다.

후속 사용자 결정(2026-10-06): 공개 환경은 simulated provider로 확정했다. Toss provider/실제 연동은 유지하되 공개 환경에서 비활성화한다. [합성 시장 개선안](synthetic-market-design.md)을 먼저 승인받아 구현하며, 외부 배포 구현/유료 자원 생성은 아직 승인되지 않았다. 아래 인프라/비용/보안 조건은 기존 제안이고 공개 시세 정책만 확정된 상태다. [공급자 조사](public-market-data-research.md)는 실제 데이터 공개 사용권을 검토한 이력으로 유지하며 공급자 계약은 현재 배포의 선행 조건이 아니다.

## 권장안

서울 EC2 Linux x86_64 단일 서버(t3a.medium, 2 vCPU/4 GiB 후보) + Docker Compose + 기존 웹 Nginx/Let's Encrypt + 거래 PostgreSQL16 + 별도 AI PostgreSQL/pgvector + 비공개 Anvil. 공개 시세는 기존 simulated 공급자. 처음에는 소수 인수 사용자 대상으로 거래/AI 접근을 제한한다. 무제한 공개 가입/AI 사용은 이번 최소 비용안의 안전 범위가 아니다.

4 GiB는 저부하 시작 후보이지 부하 검증 결과가 아니다. AI 모델은 외부 API로 호출하며 로컬 GPU/모델 서버는 두지 않는다. JVM heap/native 메모리, 두 DB 연결/메모리, Anvil, 로그를 제한하고 동시 사용 실측 후 증설한다. 현재 AI 이미지는 linux/amd64 고정이므로 ARM/t4g는 별도 호환 검증 없이 선택하지 않는다. 배포 서버에서 Gradle/npm 빌드를 동시에 하지 않고 검증한 이미지를 올린다.

```text
브라우저 ─ HTTPS/WSS ─ 도메인 → Elastic IP
                              └ EC2 / Nginx (80 redirect·ACME, 443 서비스)
                                  ├ Vite 정적 웹
                                  └ /api, /ws, /ws-sockjs → backend:8082 (1개)
                                                              ├ 거래 PostgreSQL:5432
                                                              ├ AI PostgreSQL+pgvector:5432
                                                              ├ Anvil:8545 (초기안)
                                                              └ 외부 OpenAI HTTPS
                                 Toss는 공개 서비스에 연결하지 않음
```

거래 DB/JPA와 AI DB JDBC·계정·저장 영역은 계속 분리한다. 분산 트랜잭션, Agent 쓰기 Tool, 거래 로직 변경은 없다. 단일 서버 고장은 모두에 영향을 주며 HA/SLA 구성은 아니다. backend 1개를 유지해 운영자 nonce/현재 프로세스 STOMP/예산의 다중 서버 문제를 이번 범위에 들이지 않는다.

## 현재 준비 상태와 보완

- 웹 Dockerfile/npm ci/build/Nginx 이미지, REST/Native WS/SockJS proxy·보안 header·캐시가 있고 최종 이미지 빌드/설정 문법 검증은 통과했다. HTTPS certificate/443/갱신/실제 외부 네트워크 인수는 아직 없다.
- backend/Dockerfile은 Java21 bootJar를 만들지만 COPY . .를 사용하고 backend .dockerignore가 없다. 비밀/로그/로컬 빌드 제외, 비root 실행, Linux 실제 빌드·기동 확인이 배포 전 필수다. 빌드 단계에 복사된 비밀은 최종 이미지에서 빠져도 안전하지 않다.
- AI 지식9개와 승인 manifest는 backend 바깥 docs에 있다. 현재 backend-only build에는 포함되지 않는다. 승인 버전의 문서와 manifest를 배포 artifact에 포함하고 절대 경로로 read-only mount한다. hash/index/version은 기존 승인 경계를 유지한다.
- 루트 Compose는 DB5432/Anvil8545를 호스트에 공개하고 기본 거래 DB 암호를 사용한다. AI Compose는 localhost5433·별도 volume이지만 production 네트워크를 구성하지 않는다. 개발 파일을 그대로 쓰거나 덮어쓰지 않고 별도 deployment Compose를 준비한다.
- 현재 /api/health는 고정 UP 응답으로 DB/RPC/AI 준비를 보장하지 않는다. 기동 검사에서 DB 연결, 컨트랙트/운영자/승인 색인과 실제 주문을 별도로 확인한다.

## Toss 공개 배포 제한 — 차단 조건

공식 developers.tossinvest.com/docs의 llms.txt가 연결하는 overview.md/faq.md를 직접 확인했다. FAQ의 데이터 이용 정책은 본인 매매 목적만 허용하며 비상업적 제3자 배포도 금지한다. 모의 거래/취업용이라는 이유로 예외를 가정하지 않는다. 공개 웹 차트/REST/STOMP뿐 아니라 공개 testnet에 실제 시세 보고서를 쓰는 경로도 허용 확인 전 배포 범위에서 제외한다. 로컬 Toss 데이터를 공개 DB에 복제하지 않는다.

공개 배포는 PRICE_PROVIDER=simulated로 신규 DB에서 시작하고 시뮬레이션 표기를 유지한다. 삼성전자 기준 모의 자산이라는 상품 설명과 실시간 실제 주가 표기는 구분한다. Toss 연동 코드는 유지하되 공개 이미지의 실행 설정에는 키를 주입하지 않는다. 별도 허용이 확보되면 EC2 Elastic IP를 WTS 허용 IP로 등록하고 서버가 REST 초기조회+WebSocket을 기존 방식대로 수행한다. 공식 overview는 계정당 WS 동시2개를 명시하므로 로컬+서버 동시 실행도 점검 대상이다. 조회 API 가용성과 외부 배포권/요금은 별개다.

근거: [공식 진입점](https://developers.tossinvest.com/docs), [공식 소스 안내](https://developers.tossinvest.com/llms.txt), [FAQ 데이터 이용 정책](https://openapi.tossinvest.com/openapi-docs/faq.md), [Overview/IP·연결 조건](https://openapi.tossinvest.com/openapi-docs/overview.md). 웹 도구의 Markdown 읽기 실패 후 승인된 공개 HTTP 읽기로 정책 문맥을 확인했으며 제3자 복제 스펙을 근거로 사용하지 않았다.

## HTTPS/WSS·도메인·방화벽

기존 Nginx에 443 TLS와 80 ACME/HTTPS redirect를 추가하고 Certbot으로 인증서를 발급/자동 갱신·Nginx reload한다. 도메인 A record는 Elastic IP, 인증서 저장 영역은 영속 보관. API와 웹은 같은 origin이라 별도 CORS wildcard가 필요 없다. 기존 웹은 HTTPS에서 wss를 선택한다. Native/SockJS 모두 실제 TLS proxy 테스트한다. WEBSOCKET_ALLOWED_ORIGINS는 정확한 https 도메인으로 제한한다.

- SG 인바운드: 80/443만 인터넷. SSH22는 기본 닫고 SSM 관리, 필요 시 관리 IP /32에만 임시 허용.
- 8082/5432/5433/8545, Docker socket, DB/RPC는 publish하지 않는다. 호스트 방화벽만으로 Docker 노출을 막았다고 판단하지 않는다.
- Compose는 Nginx-backend, backend-거래DB, backend-AIDB, backend-Anvil 네트워크를 분리한다. backend의 외부 HTTPS 경로는 유지한다. 외부 egress를 무조건 차단하거나 변하는 공급자 IP를 추측해 고정하지 않는다.
- host allowlist, 원본 IP 신뢰 범위, 로그인/가입/견적/주문/AI rate·connection limit, body/timeout 제한을 배포 gateway에 둔다. WS upgrade 제한만으로 연결 후 frame 폭주가 해결된다고 주장하지 않는다.
- 도메인은 기존 도메인의 서브도메인 재사용 우선. 신규 구매는 연간 갱신 가격 확인 후 별도 승인. DNS는 기존 제공자 이용 가능하며 Route53/ALB/NAT Gateway는 필수가 아니다.

인증서는 [Let's Encrypt](https://letsencrypt.org/) 무료 발급을 사용하지만 도메인·서버 비용은 별도다. Caddy 자동 HTTPS도 대안이지만 초기안은 검증한 Nginx를 유지해 새 proxy를 추가하지 않는다.

## 공개 API와 비용/권한 경계

| 범위 | 초기 배포 정책 |
| --- | --- |
| 정적 웹, GET /api/health, GET /api/markets/**, 공개 STOMP | 비로그인 허용, simulated 데이터, 요청/연결 제한 |
| /api/auth/login | 기존 계약 + 로그인 속도 제한, 공개 ADMIN 자격 증명 없음 |
| /api/auth/signup, quotes, orders POST, wallet/faucet, ai/agent/answers | 초기 인수 IP allowlist + 기존 인증/소유권. signup만 기존 계약상 미인증 |
| me, portfolio, orders/trades GET, 개인 STOMP | 기존 JWT·본인 데이터 경계 유지 |
| AI diagnoses GET/상세/observability | ADMIN JWT + 운영 IP 제한 |
| AI index, diagnoses/index, 직접 RAG/Tool API 등 웹에서 불필요한 운영 경로 | 외부 gateway 차단. SSM tunnel/서버 로컬 경로에서 기존 ADMIN 인증으로 필요할 때만 호출 |

IP 제한은 별도 제품 권한 기능을 만들지 않는 초기 운영 정책이며 이동 IP 사용자에게는 불편하다. 무제한 공개 체험이 필요하면 사용자별 지급/거래/AI 일일 quota 등 별도 보강 설계를 승인받는다. 현재 faucet은 요청마다 DB 잔고를 100만 mKRW 늘리며 온체인 자금 자동 충전이 아니다. Agent 동시 worker2/40초와 자동 진단 day20은 전체 수동 AI 호출의 일일 비용 상한이 아니다. IP rate limit도 분산 남용과 월별 과금을 완전히 막지 못한다.

OpenAI는 배포 전용 project/key, 알림·호출 관측과 운영 중단 절차를 사용한다. 초기 AI는 허용된 인수 대상만 활성화하고 자동 진단은 기본 false로 배포/초기화한 후 별도 켠다. AI DB/외부 API 장애에서 거래가 계속 동작하는 기존 경계와 gateway timeout을 실제로 검증한다. 광고 없는 포트폴리오라도 공급자 비용이 무료라고 가정하지 않는다.

## Secret·영속 데이터

배포 전용 JWT/DB/ADMIN/OpenAI/운영자·가격서명 키를 생성한다. 로컬 Anvil 기본키나 과거 대화/로그에서 노출된 키를 재사용하지 않는다. 운영자와 가격 서명 키는 서로 다르지만 현재 같은 backend 프로세스가 접근하므로 침해 격리는 보장하지 않는다. 별도 signer/KMS 서명 서비스는 이번 최소안에 추가하지 않는다.

AWS SSM Parameter Store SecureString + EC2 IAM role의 해당 prefix 최소 읽기 권한을 우선한다. 배포 시 비밀을 제한 권한의 runtime properties 파일로 준비하고 read-only mount/Spring config import로 읽는다. 공급자 키를 번들/build ARG/이미지/Git/명령행/로그에 넣지 않는다. 현재 앱은 임의 *_FILE 변수를 자동 지원하지 않으므로 지원한다고 가정하지 않는다. Docker 운영 권한은 비밀 접근 권한으로 취급한다. [표준 Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)의 parameter 저장 추가 요금은 없지만 KMS/사용 옵션 비용은 별도 확인한다.

거래/AI/Anvil 상태/인증서 각각 별도 영속 영역을 데이터 EBS에 둔다. root20GiB+data20GiB gp3는 초기 용량 후보이며 모니터링으로 조정한다. 외부 named volume을 사용한다면 데이터 EBS의 경로에 실제로 매핑하고 서버 종료 시 data EBS 자동 삭제를 끈다. 두 DB volume 분리는 동일 서버/EBS 장애에 대한 물리적 격리가 아니다. docker compose down -v/volume prune은 운영 절차에서 금지한다.

두 DB의 별도 pg_dump 및 필요한 role/설정, Anvil 상태·주소·원장 namespace·release 버전을 암호화된 외부 S3 backup으로 보관한다. 일일7개/주간4개는 초기 보존 후보다. volume/snapshot만으로 복구 검증을 대신하지 않는다. 체인과 DB는 분산 원장이므로 거래 입력을 잠시 차단하고 pending 정산/상태를 확인한 정합 checkpoint로 함께 백업한다. 운영 DB 한쪽만 과거로 되돌리면 중복 정산/원장 불일치 위험이 있다. 격리 환경의 복원 검증을 배포 완료 조건에 넣는다.

## 블록체인 선택

초기 외부 배포는 비공개 Anvil을 권장한다. DB-only mock 경로 대신 기존 PriceReport/signature 온체인 실행을 실제로 시연하면서 지연/가스 조달/RPC 과금 변수를 줄인다. RPC는 외부 차단, 버전 pin·영속 state·정상 종료/주기적 저장·재시작 주소/nonce/receipt 복구를 검증한다. DB만 보존하고 체인을 초기화하거나 매 기동 재배포하지 않는다. 테스트 체인이라는 표기를 유지하며 공공 블록체인 검증 가능성을 달성했다고 쓰지 않는다.

Sepolia는 2차 milestone 후보이며 이번에 자동 전환하지 않는다. [Ethereum 공식 네트워크 안내](https://ethereum.org/developers/docs/networks/)는 앱 개발에 Sepolia를 권장한다. 전환 시:

1. 새 demo 원장/체인 namespace, 신규 전용 키, RPC 공급자와 무료 한도/과금 조건을 확인한다. 자체 Ethereum full node는 운영하지 않는다.
2. Sepolia test ETH 확보 후 Deploy/PrepareOperator, Vault 모의 준비금 공급, signer/minter/consumer/approve를 확인한다. test ETH는 실제 원화가 아니지만 지급/가용성은 무제한 보장이 없다.
3. 신규 주소/RPC와 chainId/EIP-712 domain을 확인한다. 사용자 quoteId만 제출·DB 보고서/서명 조회·소유권/방향/입력/만료/상태/일회 소비·운영자 executor·키 분리·기존 복구 경계를 그대로 유지한다.
4. 현재 보고서 TTL30초는 체인 포함 시간까지 고려해야 한다. 공개 testnet 지연으로 실패할 수 있어 현재 코드와 동일 UX가 보장되지 않는다. 지연/가스/견적 만료/확인 수/재조직/nonce·RPC 불명확 응답/정산 회귀를 측정하며 TTL 변경은 별도 승인한다.
5. Anvil의 주문·서명 트랜잭션/잔고를 주소만 바꿔 Sepolia에 이어붙이지 않는다. 신규 DB 또는 검증된 migration으로 시작한다. 사용자별 지갑/MetaMask는 현재 계약에 없으며 추가하지 않는다.

## 비용 기준 (2026-10-05 조회)

실제 서울 region의 최종 견적은 인프라 생성 전 Pricing Calculator로 확정한다. 아래는 세금/환율/무료 크레딧을 반영하지 않은 낮은 사용량의 예산이며 요금 견적/비용 상한이 아니다.

- [AWS T3 공식 표](https://aws.amazon.com/ec2/instance-types/t3/): t3a.medium 4GiB는 **미국 동부 참고 단가** $0.0376/h, 월730h 약 $27.45. 이 수치를 서울 단가로 쓰지 않는다.
- [공인 IPv4](https://aws.amazon.com/vpc/pricing/): 1개 $0.005/h → 730h 약 $3.65, 미사용 Elastic IP도 비용이 남는다.
- [EBS gp3](https://aws.amazon.com/ebs/pricing/): 공식 $0.08/GiB-month 예시 region이면 총40GiB $3.20. 서울 단가는 별도다. S3 backup/request/전송·로그/CPU 초과 credit도 별도다.
- 서울 EC24GiB+40GiB+IPv4+소량 backup의 **초기 예산 추정 $40~55/월**. OpenAI 소수 시연 예산 $5~10/월을 별도로 잡고 도메인 연간 비용을 더한다. OpenAI 예산은 실제 모델 토큰 사용량/계정 조건 확인 전 예상이며 시스템 hard cap이 아니다. Toss/외부 RPC를 무료라고 포함하지 않는다.
- [Lightsail 공식 표](https://aws.amazon.com/lightsail/pricing/): publicIPv4 Linux4GiB/80GB 묶음 $24/월이므로 순수 비용은 더 낮을 수 있다. 지역/전송/backup/AI/domain 비용 별도. IAM/SG/EBS 운영을 포트폴리오로 보여주려면 EC2, 비용이 최우선이면 Lightsail도 동일 Compose로 비교한다. ARM/Spot/RDS/ALB/NAT/Kubernetes/Redis/Kafka는 초기안에 넣지 않는다.
- 빌드/로그/DB 부하의 CPU credit 비용과 OOM을 관측한다. EC2 중지 후에도 EBS/Elastic IP/도메인 등 잔존 비용이 있다. AWS 예산 알림은 자동 hard stop이라고 설명하지 않는다.

## 승인 후 작업·완료 기준

1. 비용/도메인/공개 수준/Anvil 유지에 대한 승인 → 외부 자원 없이 production Compose, Nginx TLS/renew, 안전한 이미지/지식 artifact·secret 주입·backup/runbook 구현/독립 검토.
2. 전체 backend·contracts·웹47회귀 + 운영형 Linux Compose 검증. HTTPS/WSS/Native/SockJS, USER/ADMIN/익명·타인 접근·외부 운영 API 차단, nginx timeout/rate/429, restart/DB·체인 보존/backup 복원, AI DB/OpenAI/RPC 장애 격리를 확인한다.
3. 사용자 AWS 계정·region·고정 예산·도메인/키 준비 확인 후 실제 유료 자원 생성/게시 권한을 별도로 받는다. 초기화/인수용 데이터는 신규 배포 전용만 사용한다.
4. 비용·자원 명세 확인 → EC2/DNS/TLS → private service 기동 → 명시 ADMIN AI index/schema 초기화 → 외부 브라우저 E2E/권한/외부 포트 검사 → 완료 보고. 인증서 자동 갱신/복원과 known limits도 기록한다.

작업 시작 Git은 clean, HEAD는 7b178af(web 제품화)였다. 이번 변경은 이 설계 문서와 공통 문서 링크/로그뿐이며 제품/설정은 수정하지 않았다. 이 파일은 승인 전 설계 기록일 뿐 배포 완료 증거가 아니다.
