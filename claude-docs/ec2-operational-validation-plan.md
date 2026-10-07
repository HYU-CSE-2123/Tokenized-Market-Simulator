# 단일 EC2 Linux 운영 검증 계획

> 2026-10-07 · 사용자 목적/최소 범위 결정 반영. 장기 공개 운영이 아니라 배포·부하·장애·복구 실험이다. 최초 후보는 서울 t3a.small이며 생성 승인은 아직 없다. 이 문서는 계획이고 실험 성공 기록이 아니다. 제품/Compose/secret CLI는 수정하지 않았다.

## 1. 최초 구성과 제외 범위

| 항목 | 최초 실험 계획 |
|---|---|
| EC2 | 1대, t3a.small(2vCPU/2GiB), Linux x86_64/Ubuntu 24.04 LTS 후보. 최종 AMI/SSH 사용자/지원 이미지 확인 후 승인 |
| EBS | 기본 gp3 root 1개에 OS/이미지/data/runtime/백업 staging. 30GiB 후보이며 이미지 실제 크기 확인 후 확정. 추가 data EBS/추가 IOPS 없음 |
| 암호화 | 고객관리 KMS 키를 만들지 않는다. EBS 암호화는 AWS 관리 aws/ebs 키 사용 가능 여부를 확인. 계정 기본 키가 고객관리 키이면 그대로 사용한다고 가정하지 않는다 |
| 네트워크 | 기존 VPC의 인터넷 경로 있는 public subnet 사용, SG1개, 인스턴스 자동 할당 public IPv4. EIP 필수 아님 |
| 접속 | SSH 공개키 인증만, TCP22 사용자 현재 공인 IPv4/32만. root/password SSH 금지. 키 비밀은 사용자 장치에 보관 |
| 서비스 | 기존 production Compose의 web/backend/거래 PostgreSQL/AI PostgreSQL+pgvector/비공개 Anvil. public profile과 synthetic 시장 유지, Toss 호출0 |
| 배포 | 로컬 검증·빌드한 linux/amd64 이미지 tar와 SHA-256을 SSH로 전달해 docker load. ECR/신규 registry 없이 실행, 서버 빌드 금지 |
| 관리 | 현재 runtime file 주입을 root 권한으로 수동 준비. EC2 IAM role/SSM/AWS CLI 필수 아님 |

RDS/S3/고객관리 KMS/Secrets Manager/ECR/SSM Parameter Store/ALB/NAT Gateway/Route53·도메인 구매/공인 인증서 발급/Sepolia/HA는 최초 범위에서 제외한다. 기존 VPC/subnet/Internet Gateway를 사용할 수 없으면 새 네트워크를 임의 생성하지 말고 승인받는다. SSH public key와 EC2 key pair 참조/등록은 생성 전 입력 항목이며 IAM 사용자/access key 신규 생성으로 대체하지 않는다.

기본 EBS 사용은 현재 RUNBOOK의 별도 data EBS 의무를 **이 실험에 한해 대체**한다. `/srv/exchange`는 root EBS의 디렉터리임을 명시하고 별도 볼륨/장애 격리로 주장하지 않는다. 두 DB·체인·runtime 파일은 논리적으로 분리한다. 동일 디스크의 backup은 디스크 손실 대비가 아니다.

비용은 [실측·비용 문서](deployment-capacity-cost.md)의 단가에 실제 EC2 기동시간, EBS 보유기간, public IPv4 할당시간만 적용한다. 이전의 EIP 상시 보유/KMS1개/S3 가정은 최초 실험 비용에서 제외한다. root EBS 보존은 중지 중에도 과금되고 terminate/delete는 별도 사용자 승인이다. 무료 크레딧을 전제로 하지 않는다.

## 2. Secret 수동 준비 — 기존 파일 계약 유지

기존 `ops.py`의 `materialize(values, runtime)`는 SSM과 무관한 함수다. CLI는 `ssm`만 제공하므로 **manual 서브커맨드가 있다고 쓰지 않는다**. 실행 시 root Python 대화식 입력에서 `getpass`로 값을 받고 기존 `validate_secrets/materialize`를 호출하는 절차를 사용한다. 비밀 값은 argv/environment/명령행 history/JSON 임시파일/터미널 출력에 넣지 않는다. 실제 입력 절차는 생성 승인 후 명령을 안내하고 실행한다.

- 새 실험용 DB_PASSWORD/AI_DB_PASSWORD/JWT_SECRET/ADMIN_LOGIN_ID/ADMIN_PASSWORD/OPERATOR_PRIVATE_KEY/PRICE_SIGNER_PRIVATE_KEY 준비. 운영자와 서명 키는 서로 다른 신규 유효 키, 알려진 Anvil 기본키 재사용 금지.
- OPENAI_API_KEY는 별도 유료 호출 승인 시에만 입력한다. 미입력하면 기존 AI_ENABLED=false. 자동진단은 Compose의 false 유지. 거래 secret과 AI 호출 활성화를 혼동하지 않는다.
- root 운영 세션, `umask 077`, 제한된 runtime parent/version 디렉터리, atomic current symlink와 read-only bind를 유지한다. application.properties는 root:10001/0640, DB password 파일은0600, acceptance.conf는 비밀 없이 deny-default. backend UID10001 접근과 타 일반 사용자 거부를 실제 Linux에서 확인한다.
- `/srv/exchange/runtime/current`는 Compose의 RUNTIME_ROOT다. application.properties/DB password 파일은 기존 계약 그대로이고 chain.properties는 최초 체인 배포가 DATA_ROOT/release에 생성한다. 수동 입력으로 컨트랙트 주소를 임의 생성하지 않는다.
- 최초 acceptance 허용 대상은 SSH tunnel에서 Nginx가 실제로 관측하는 source IP다. tunnel은 사용자 공인 IP를 Nginx에 보존하지 않으므로 고정 public /32를 복사하거나 X-Forwarded-For를 신뢰하지 않는다. 관측 후 필요한 단일 source만 허용하며 allow all을 복사하지 않는다. SG의 사용자 /32 경계와 JWT/ADMIN 검증은 그대로 유지한다.
- Docker/root 권한은 비밀 접근 권한이다. 설정/로그/docker inspect에 비밀 값이 포함되지 않는지 canary로 검사하며 실제 비밀을 검색 출력하지 않는다. symlink 교체 후 관련 컨테이너 force-recreate, 기존 DB 암호·온체인 signer rotation은 자동 수행하지 않는다.
- secret 복구용 사본은 사용자 장치에 별도 암호화 보관한다. 기존 backup 함수는 secret을 포함하지 않으며 SSM parameter version 대신 비밀 없는 runtime release 식별자를 운영 기록에 남긴다. DB·체인 복원을 위해 같은 키를 확보하되 평문 bundle에 넣지 않는다.

## 3. 웹·TLS와 포트

최초에는 SG의22/사용자 /32만 연다.80/443/8082/5432/8545는 인터넷에 열지 않는다. Compose의 HTTP_BIND를127.0.0.1로 설정하고 SSH local forwarding으로 EC2 loopback443에 연결한다. 추가 ALB/VPN/도메인/DNS는 필요 없다.

실험 전용 hostname과 로컬 테스트 CA/SAN 인증서를 사용한다. 사용자 장치에 해당 hostname의 loopback 해석과 제한된 테스트 CA 신뢰를 준비한다. tunnel의 로컬 HTTPS 포트를 포함한 PUBLIC_ORIGIN, PUBLIC_DOMAIN/Host/SNI, STOMP allowed origin을 일치시킨다. HTTPS endpoint로 직접 접속하며 기존 HTTP redirect의 고정443을 다른 로컬 포트로 자동 변환한다고 가정하지 않는다. TLS 검증을 끄지 않는다. 이것은 EC2의 TLS/WSS 동작 검증이지 공인 인증서·공개 DNS 인수가 아니다.

SG 경계는 승인 후 사용자 장치에서22 연결과80/443/8082/5432/8545 차단을 확인한다. 직접 인터넷 ingress/WSS를 확인할 필요가 생기면 별도 승인 후443을 사용자 /32에만 임시 허용한다.0.0.0.0/0 및 IPv6 광역 허용은 넣지 않는다. OS firewall·Docker publish·effective bind도 확인한다.

Outbound는 OS/공개 이미지 공급/시간 동기화와 승인된 외부 API에 필요한 통신만 검토한다. Toss는 public guard로 호출0. 단일 EC2 장애에 SSH/tunnel도 함께 끊기는 제한을 기록한다.

## 4. 로컬에서 먼저 수행할 실험

모든 장애/부하는 별도 신규 프로젝트·전용 DB/체인·시험 키에서만 실행하고 기존 개발 서비스를 사용하지 않는다. 아래는 **다음 실험 계획**이며 직전 실행을 새 성공으로 기록하지 않는다.

| 실험 | 로컬 수행 내용/판정 |
|---|---|
| 기능 baseline | 기존 local-smoke + BUY/SELL/quote 재사용·만료 거부, 주문/잔고/잠금·nonce·receipt, Native/SockJS·차트6주기·SIMULATED 확인 |
| 부하 시나리오 | 사전 계정 준비 후1→3→5 동시 사용자, 각 단계5분. REST 시장1회/초/사용자·WS1개/사용자·BUY/SELL 합계1회/분/사용자. quote는 주문 직전 발급. UI 제약/rate limit 안에서 실행 |
| 제한 도달 | 로그인/거래/AI/WS 한도를 별도 짧은 burst로 넘겨429·복귀·미생성 주문을 확인. 로그인 rate limit을 부하 준비 계정 생성과 분리. WS8개 초과 차단은 정상 처리량 실패로 세지 않음 |
| AI 부하 | fixture로1/2 동시 요청, ingress6회/분 이하. Tool/DB 검색/plan/synthesis 처리·거래 병행·queue/timeout 확인. 실제 모델 품질/비용 증거로 쓰지 않음 |
| 장애 격리 | AI DB/닫힌 provider 장애 시 거래 계속; RPC 장애 시 불명확 전송/잠금/reconciliation 안전; 거래 DB 장애 시 신규 주문 fail-closed·잘못된 가격/WS 공개 없음 |
| 프로세스 실패 | backend/Anvil 개별 강제 종료·재시작을 단계별 주입. 서명/전송/receipt 이후 crash window를 식별하고 재전송/정산 멱등·중복 체결/이중 잔고 반영 없음 확인. 자동 성공 단정 금지 |
| 자원 압박 | 가능하면 격리 Linux VM 전체2GiB에서 사전시험. 컨테이너 mem_limit 합계 변경만으로 host2GiB를 재현했다고 쓰지 않음. scratch filesystem의 disk-full만 검증하고 실제 data/root를 채우지 않음 |
| 복구 | unresolved/잠금0 checkpoint → 두 DB/chain 동일 bundle·hash → 새 빈root/별도 project 복원 → 기존 nonce/주소/잔고/indexVersion·새 BUY/SELL. unresolved 상태의 backup 거부도 별도 확인 |

기존 resource-probe는 BUY·저부하·로컬 fixture 측정용이며 이 부하/SELL/crash window 전체를 이미 지원하지 않는다. 추가 runner/host-resources 수집·fault injection 자동화가 필요하면 해당 구현 범위를 제안해 승인 후 작업한다. Nginx 제한은 부하를 위해 임의 완화하지 않는다. 모의 재고/faucet 잔고를 먼저 확인해 유동성 부족과 시스템 장애를 구분한다.

## 5. EC2에서 반드시 다시 수행할 실험

| 실험 | EC2에서 재검증이 필요한 이유/증거 |
|---|---|
| 릴리스·첫 기동 | amd64 tar SHA-256/load 후 실제 image ID·artifact manifest/hash 대조, cold-start와 bootstrap 포함 자원·시간. native UID/GID/파일 권한/read-only/tmpfs·mount/disk 암호화·SSH/SG 확인 |
| 거래·웹 baseline | EC2상의 양 DB/Anvil/실제 JVM·Nginx로 BUY/SELL receipt/잠금0, quote 사용자 경계·만료/재사용·USER/ADMIN·Native/SockJS WSS·재연결 및6개 차트 주기 |
| 실측 부하 | 로컬과 같은1/3/5 사용자 단계를 외부 사용자 장치에서 실행. 부하 발생기를 EC2에 두어 서버 CPU를 오염시키지 않음. tunnel/rate limit 병목을 서버 한계와 구분 |
| 2GiB 한계 | host Available memory·swap·OOM/kernel log, backend heap/native/cgroup peak, CPU/load/iowait/disk latency/free/DB connections·lock wait·실제 API latency·체결 지연·오류율 측정 |
| 지속 관측 | idle30분, 거래+fixture AI60분 이상. T3a CPU credit balance·throttle 또는 surplus charges를 기록. 무료 기본 EC2 metric/로컬 로그로 확인하고 새 CloudWatch 유료 agent/dashboard를 필수로 두지 않음 |
| 프로세스·의존 장애 | AI DB/provider/RPC/거래 DB/backend crash 핵심 시나리오 반복. Docker Desktop 통과가 Linux IO/신호/스케줄러 차이를 보증하지 않음 |
| host 재부팅·stop/start | 우선 clean checkpoint 후 실제 EC2 reboot·stop/start, EBS 지속·Docker 재기동·Anvil code/nonce/잔고/시각 정렬·synthetic checkpoint·quote TTL 확인. stop/start 뒤 변경된 자동 public IP로 tunnel 갱신 |
| 복원·외부 사본 | quiescent checkpoint를 SSH로 사용자 장치에 안전하게 반출·SHA-256 대조. 같은 EC2의 새 빈root/restore project에 복원하고 신규 거래 확인. 공간/메모리 때문에 기존 stack을 중지한 뒤 복원하며 새EC2는 만들지 않음 |
| 외부 AI 선택 | 별도 유료 승인 뒤 실제 provider 소량 요청·token/latency/권한·장애 격리. 승인 없으면 fixture 경로만 결과로 남기고 실제 AI/유료 index는 미검증 |

한 EBS 안의 신규root 복원은 root EBS 완전 손실/서버 재생성·AZ 장애 DR 검증이 아니다. 외부 사본 존재는 확인하되 새EC2/새EBS/스냅샷 생성까지 완료했다고 주장하지 않는다. 갑작스러운 host power loss 실험은 clean restart와 다르며 별도 동의·외부 checkpoint 확인 후 진행한다.

T3a credit mode는 최초 생성 승인 때 명시한다. 우선 Standard로 추가 credit 과금을 피하는 안을 제안하며, 고갈 시 throttle를 실패 원인과 구분한다. Unlimited로 바꿔 테스트할 경우 추가 과금 승인을 받는다. swap을 넣어2GiB 정상처럼 보이게 하지 말고 기본 유무/peak와 swap latency를 기록한다.

## 6. 통과 기준·중단 조건·증거

- 금융/권한 불변성: 중복 체결·이중 잔고 반영·잘못된 quote 소비·권한 우회0. 정산 완료 후 입력 잠금0. timeout만으로 주문 실패 확정/잠금 해제하지 않으며 REVIEW_REQUIRED는 원인과 해결 여부를 기록한다.
- 장애 격리: AI 장애가 정상 거래를 막지 않음. RPC/거래 DB 장애는 기존 상태기계에 따라 안전하게 대기/거부. 정상 복귀 뒤 같은 주문을 중복 정산하지 않음. 비밀/원문 JWT/개인 Tool 결과 로그 유출0.
- 저부하 목표안: 정상 의존성에서 예상429를 제외한5xx/timeout0, OOM0, 회복 불가능한 데이터 손상0. REST p50/p95와 실제 quote→receipt 시간을 기록한다. p95 목표는 로컬 runner baseline을 먼저 얻은 뒤 제안하며 기존 pacing cycle을 API latency로 쓰지 않음.
- 즉시 중단: 첫 OOM/불변성 위반/secret canary 노출/부하 종료 후 미해결 잠금·주문. host Available<200MiB가30초 지속 또는 disk free<10%면 부하를 멈추고 증거를 확보한다. 초기 안전 경계이지 운영 SLA가 아니다. 첫 기동 전 tar+이미지+신규root restore 공간을 계산하고 부족하면 시작하지 않는다.
- 수집: release commit/diff·image IDs/hashes/artifact/indexVersion, OS/CPU/RAM/EBS/cgroup/credit mode, phase별 timestamp/실제 request latency·throughput·429/5xx·CPU/메모리·disk/DB, 주문/receipt/잠금 집계, 장애 주입/복구 시간·checkpoint hash·RTO/RPO 관측 범위. secret/토큰을 보고서에 넣지 않는다.
- RPO는 clean checkpoint 시각 대비이며, 이후 새 거래를 복원에서 잃을 수 있다. 미확정 tx를 단순 과거 DB/chain rollback으로 해결하지 않는다. 실험 종단 시간은 RTO 관측값으로 기록하되 전체 재프로비저닝 RTO로 일반화하지 않는다.

## 7. 실행 순서·종료·승인 입력

1. 현재 미커밋 측정 문서를 보존하고 사용자 릴리스 commit 확정. 로컬 baseline/부하 runner·최소 fault 실험부터 실행 승인받기.
2. 사용자가 서울 리전/기존 subnet·AMI/t3a.small·root 용량·credit mode·실험 기동시간·현재 공인 IPv4/32·SSH 공개키·예산 경계 승인. 아직 생성하지 않는다.
3. 승인 후에만 EC2+root EBS+SG+자동 public IPv4 준비 → 사용자 /32 SSH 확인 → Docker/시간동기화 → 이미지/artifact 전달 → 수동 secret/TLS 준비 → private 체인 최초 준비 → loopback 서비스 기동.
4. EC2 baseline→단계 부하→장애→checkpoint 반출→신규root 복원→reboot/stop-start 검증. 실패 시 추가 부하/자원 증설을 자동 진행하지 않고 원인과 후보를 보고.
5. 정상 종료/checkpoint·증거 반출과 해시 검증 후 SG의22 허용 규칙 제거, 열린SSH/tunnel 세션 종료, 추가 임시 web ingress도 제거. EC2 stop 확인. SG 삭제만으로 기존 연결이 즉시 끊겼다고 가정하지 않는다. 재접속 필요 시 사용자 최신 /32를 다시 승인받는다.
6. EBS 잔여 비용을 알리고 종료/삭제는 별도 확인한다. root DeleteOnTermination 기본값에 기대지 말고 생성 때 보존 여부를 확인. 외부 사본/복구 secret 확보 전에 terminate/root삭제/data prune 금지. 불필요한 public IPv4/EIP·추가유료서비스가 남았는지 점검한다.

현재 완료한 것은 코드/계약 확인과 계획 문서 작성뿐이다. AWS 생성·수동 secret 준비·부하/장애/복구 실험·유료 AI는 이번에 실행하지 않았다. 동작 변경 없는 문서 작업으로 별도 구현 검토는 생략하고 diff/계약 자체 대조를 수행한다.

## AWS 근거

- [SSH Security Group 제한](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/security-group-rules-reference.html)
- [EC2 stop/start·public IP·EBS 과금/삭제 속성](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-lifecycle.html)
- [EBS gp3 용도와 기본 볼륨](https://docs.aws.amazon.com/ebs/latest/userguide/ebs-volume-types.html)

## 재개 메모 — 운영 작업 보류 (2026-10-07)

- 사용자는 운영 검증 계획을 승인했으나 이후 운영 작업은 다음에 재개하기로 했다. EC2 생성은 여전히 금지이며 로컬 결과 검토 후 별도 승인이다.
- 직전 제안은 `deployment/validation-runner.py`, `validation-metrics.py`, `validation-faults.py` 및 관련 테스트/결과 문서 추가다. 아직 구현 범위 최종 승인을 받지 않았고 파일 생성/실험 실행도 하지 않았다. 기존 `resource-probe.py`의 저부하 실측과 구분한다.
- 재개 순서: 제안한 도구 범위 승인 확인 → 격리 환경 기능 baseline → idle → 각5분1/3/5 사용자 부하 → AI(provider/DB 각각)/RPC/거래DB/backend/Anvil 장애를 순차 수행. 부하·장애를 기존 개발 DB/체인에 주입하지 않는다.
- 기록할 항목: 요청별 p50/p95/max(대기 간격 제외), 시도/완료 throughput, timeout/5xx/예상429 구분, 서비스·Linux host CPU/메모리/OOM·disk, 두DB connection/lock, 주문/전송/잠금·중복 체결, 서비스 복귀와 미정산 해소 시간. Docker Desktop host는 Linux VM이며 EC22GiB 검증으로 쓰지 않는다.
- 문제 발견 시 신규 부하/다음 단계 중단, 주입 장애만 안전하게 해제하고 증거 보존. 원인/가설·재현·수정 후보를 먼저 보고한다. 사용자 승인 없이 제품 수정/DB 보정/잠금 해제/체인 초기화하지 않는다.
- 기존 미커밋 측정 helper/문서 변경을 보존한다. 이번 보류 메모는 문서 기록만이며 커밋·유료 AI·AWS·새 Docker 실행은 하지 않았다.
