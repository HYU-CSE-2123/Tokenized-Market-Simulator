# 공개 데모 운영 절차

최신 최초 AWS 범위는 [단일 EC2 운영 검증 계획](../claude-docs/ec2-operational-validation-plan.md)을 먼저 따른다. 장기 공개 운영이 아닌 실험이므로 아래 별도 data EBS/SSM/S3/도메인·공인TLS 요구는 최초 실험에서 제외·대체된다. 기본 root EBS, 기존 runtime file 수동 준비, 사용자 /32 SSH와 loopback HTTPS/WSS tunnel로 검증한다. 기존 도구 계약은 유지하며 manual CLI를 새로 구현한 것은 아니다. 실제 자원 생성/실험 실행은 아직 승인 대기다.

이 디렉터리는 승인된 **배포 구현/로컬 운영형 검증**이다. AWS 생성·DNS 변경·공개 게시·SSM 저장/조회·S3 업로드는 사용자 승인 전 실행하지 않는다. 기존 개발 Compose/.env/DB를 재사용하지 않는다. 단일 Linux x86_64 backend, 합성 시장·비공개 Anvil이며 Sepolia/HA/SLA가 아니다.

## 1. 릴리스 준비

- Python3, Docker Compose v2, AWS CLI v2(SSM/S3 단계만), Linux root 운영 권한이 필요하다. Docker 권한은 secret 접근 권한이다.
- `docker build -t tokenized-market-backend:release backend`
- `docker build --build-arg PUBLIC_DEMO=true -t tokenized-market-web:release tools/websocket-test-client`
- `docker build -f deployment/Dockerfile.tools -t tokenized-market-ops:release .`
- `python deployment/ops.py artifact --destination /srv/exchange/artifacts/knowledge-RELEASE`
- artifact는 manifest에 등록된 active9개만 정규화 SHA-256/version 검증 후 복사한다. 코드/로그/자격 증명/전체 docs를 ingest하지 않는다. 시장 정책v2·개요v7은 합성 공개 시장의 경계를 반영한다. 배포 AI index는 자동 생성하지 않는다.
- `.env.example`을 **비밀 없는** 운영 설정 파일로 복사해 domain/origin/data/runtime/artifact/cert 경로를 지정한다. 이미지 태그는 로컬 검증용이다. 실제 배포 전 모든 이미지의 검증된 registry digest를 고정하고 릴리스 설정을 보관한다. 버전 변경은 새 릴리스/재검증이며 latest를 사용하지 않는다.
- `/srv/exchange`는 별도 암호화 data EBS mount가 실제 완료된 경로여야 한다. root disk에 같은 이름의 디렉터리만 만든 것을 EBS 영속화로 오인하지 않는다. data EBS 종료 시 삭제 비활성은 AWS 승인 후 확인한다.

```sh
install -d -m 700 /srv/exchange/data/trade /srv/exchange/data/ai /srv/exchange/data/release
install -d -o 10001 -g 10001 -m 700 /srv/exchange/data/chain
install -d -m 700 /srv/exchange/runtime /srv/exchange/certificates/live /srv/exchange/backups
install -d -m 755 /srv/exchange/certificates/acme
```

DB 컨테이너는 첫 시작 때 데이터 폴더 소유권을 설정한다. backend UID/GID10001·read-only root·tmpfs, Anvil UID10001·capability 제거, 로그 rotation/메모리 제한을 적용한다. 예산4GiB의 실제 동시 부하/OOM SLA는 아직 검증하지 않았다.

## 2. Secret 주입 — AWS 승인 후

SSM 전용 `/exchange/public/` 아래 `DB_PASSWORD`, `AI_DB_PASSWORD`, `JWT_SECRET`, `ADMIN_LOGIN_ID`, `ADMIN_PASSWORD`, `OPERATOR_PRIVATE_KEY`, `PRICE_SIGNER_PRIVATE_KEY`, 선택 `OPENAI_API_KEY`를 각각 **SecureString**으로 준비한다. 공개 전용 새 키를 쓰며 로컬 Anvil 기본키·노출된 키를 재사용하지 않는다. 운영자/서명 키는 분리한다. SSM 작성은 보안 관리 화면/승인된 절차를 이용하고 shell history에 값이 남는 `--value` 예시는 제공하지 않는다.

```sh
python deployment/ops.py ssm --prefix /exchange/public/ --region ap-northeast-2 --runtime /srv/exchange/runtime
```

EC2 role 정책 예시는 `ssm-policy.example.json`이다. 계정/KMS ARN을 확정해 **8개 이름의 GetParameters만** 허용한다. recursive GetParametersByPath는 사용하지 않는다. IAM role/SSM/KMS는 아직 실제 검증하지 않았다. 스크립트는 secret을 출력하지 않고, 파일 버전 디렉터리+atomic current symlink를 만든다. application.properties는0640/group10001, DB password 파일은0600, parent는제한한다. backend config import와 PostgreSQL PASSWORD_FILE로 주입하므로 Docker inspect/Compose environment에 secret 값이 없다. Docker 관리자/프로세스 메모리 침해까지 방어하지 않는다.

초기 `acceptance.conf`는 **deny all**이다. 승인한 인수 IP만 `allow 실제-IP/32;` 줄로 넣고 마지막 `deny all;`을 유지한다. 가입/견적/주문/faucet/AI/ADMIN 경로에 적용한다. 이동 IP 지원/무제한 체험은 별도 범위다. 공개 가입과 faucet을 제한 없이 열지 않는다. 계정 초기화는 기존 ADMIN_PASSWORD 정책을 사용하며 ADMIN 계정이 이미 있으면 비밀번호를 덮어쓰지 않는다.

비밀 갱신은 컨테이너 `restart`만으로 새 symlink 대상 bind가 반영된다고 가정하지 않는다. backend/web을 `up -d --force-recreate`하고 검증한다. 기존 DB의 PASSWORD_FILE 교체는 DB 암호 변경이 아니다. DB 암호와 실제 role 변경을 함께 계획하고 operator/signer 변경은 컨트랙트 권한·기존 pending 보고서와 함께 별도 검토한다. 이전 runtime secret 디렉터리는 보안 보존/폐기 정책으로 관리한다.

## 3. 최초 체인 준비 — 자동 기동 때 재배포 금지

```sh
docker compose --env-file deployment/.env -f deployment/compose.yml up -d --wait postgres ai-postgres anvil
docker run --rm --network exchange-public_chain \
  -v /srv/exchange/runtime/current:/run/secrets:ro \
  -v /srv/exchange/data/release:/release tokenized-market-ops:release
```

`DeployPublic`는 chain31337에서만 실행하고 새 전용 operator에 테스트 ETH를 내부 RPC로 지급한다. default funded accounts는0개다. mKRW10회 faucet 후 Vault5백만/operator5백만 재고와 allowance를 준비한다. 유한 데모 재고이며 DB 사용자 faucet이 자동 충전하는 자금이 아니다. 재고 관측/수동 충전은 운영자가 기존 권한으로 수행한다. 실물 원화 담보/준비금 reconciliation은 없다.

init-attempt 또는 chain.properties가 있으면 재배포를 거부한다. 실패 시 컨트랙트·nonce·broadcast를 확인하고 임의로 marker 삭제/새 배포하지 않는다. tool이 성공했을 때만 backend를 시작한다. 공개 주소 파일/체인code·signer·consumer·minter·operator잔고/allowance/chainId를 확인하고 릴리스 기록에 남긴다. `vm.writeFile`은 forge simulation 중 주소를 기록하므로 파일 존재만으로 실제 broadcast 성공을 판정하지 않는다. RPC는 호스트에 publish하지 않으며 도구는 private chain network에서만 실행한다.

Anvil entrypoint는 state를 복원한 뒤 과거 블록 시각이면 **현재 host 시각의 새 빈 블록**을 만든 다음 ready를 표시한다. 기존 블록/잔고/nonce를 초기화하지 않는다. host보다2초 넘게 미래인 체인은 ready를 거부한다. 이 정렬이 없으면 복원 후 최신 서명 보고서의 gas estimation이 future observation으로 거부되는 것을 실제로 확인했다. 서명 TTL/미래시각 제한을 완화하지 않는다. host 시간 동기화는 AWS 승인 후 확인한다.

## 4. HTTPS/WSS

SG80/443만 공개, SSH 기본차단/SSM관리, 8082/5432/8545 미공개. 단일 EC2 직접 ingress이므로 클라이언트의 X-Forwarded-For를 신뢰하지 않고 remote_addr로 덮어쓴다. ALB/CDN을 추가하면 trusted proxy/IP 제한 재설계가 필요하다.

최초 인증서가 없으면 `nginx/acme.conf`를 사용하는 임시 Nginx80/ACME만 기동한다(제품 backend는 공개하지 않는다). 승인된 domain A record/EIP와 HTTP challenge를 확인한 뒤 검증된 digest의 Certbot 이미지를 이용해 webroot certonly를 실행한다. 발급/갱신은 실제 AWS/DNS 승인 후이며 로컬 self-signed TLS가 공인 인증서를 대신하지 않는다.

`docker compose --env-file deployment/.env -f deployment/acme-compose.yml up -d`로 challenge 전용 서버를 사용한다. 인증서 준비 후 이 프로젝트를 `down`하고 production Compose의 web을 기동해80 포트 충돌을 피한다. 개발/다른 운영 web을 중지하는 절차가 아니다.

```sh
# PINNED_CERTBOT_IMAGE는 공식 Certbot 이미지의 검증한 @sha256 digest를 지정
docker run --rm -v /srv/exchange/certificates/letsencrypt:/etc/letsencrypt \
  -v /srv/exchange/certificates/acme:/var/www/acme "$PINNED_CERTBOT_IMAGE" \
  certonly --webroot -w /var/www/acme -d "$PUBLIC_DOMAIN" --email "$ADMIN_EMAIL" --agree-tos --no-eff-email
```

domain을 cert root의 `domain` 파일에 기록하고 live의 일반 PEM 파일로 복사한다. symlink만 mount해 archive 대상이 컨테이너 밖에 남지 않게 한다. `renew-cert.sh ENV_FILE CERT_ROOT PINNED_CERTBOT_IMAGE`를 Linux systemd timer로 하루2회 실행한다. nginx -t 후 reload이며 HTTPS/WS 연결에 영향이 없는지 확인한다. 실제 인증서 발급/renew dry-run과 timer 설치는 외부 승인 후 실행한다.

timer/service 원본은 `deployment/systemd/`에 있다. 배포 저장소를 `/opt/exchange`에 두는 예시이며 `/etc/exchange/renew.env`에는 비밀 없는 `ENV_FILE`, `CERT_ROOT`, `CERTBOT_IMAGE` 세 값만 둔다. 운영 위치가 다르면 ExecStart를 실제 코드 경로로 바꾸고 검증한다. unit 설치/enable/start는 승인 후 수행한다.

```sh
docker compose --env-file deployment/.env -f deployment/compose.yml up -d backend web
docker compose --env-file deployment/.env -f deployment/compose.yml exec -T web nginx -t
```

public web build는 API/JS 최초 실패에도 **SIMULATED · 합성 시장**을 정적으로 보인다. Toss 실제 검증/공개비활성·비공개 테스트체인 설명도 포함한다. single backend+public guard가 simulated를 강제하므로 별도 provider mismatch UI guard는 추가하지 않았다. 혼합 upstream/외부 candle cache를 도입하면 다시 검토한다.

Nginx는 host allowlist/TLS1.2·1.3/보안header/body32KiB/API/로그인/거래/AI rate limit429와 WS 연결8개 제한을 둔다. 운영AI index/search/direct RAG/Tool은 외부404, Agent·진단조회·observability만 IP 제한 및 기존 JWT/ADMIN 경계로 proxy한다. secret/body/쿼리/JWT는 access log에 넣지 않는다. WS frame 폭주·분산IP·월AI비용 hard cap은 보장하지 않는다. 부하에 맞게 rate를 조정한다.

## 5. AI artifact 활성화

AI DB/JPA는 분리하고 backend 시작에 AI DB health 의존을 넣지 않는다. 두 DB 분산 transaction은 없다. OpenAI key가 없으면 AI 비활성, 자동진단은 기본false다. 지식 artifact는 read-only `/knowledge`에서 읽는다.

인수 운영자는 SSM tunnel/서버 내부 경로에서 기존 ADMIN 인증으로 `/api/ai/index`, 필요 시 `/api/ai/diagnoses/index`를 호출한다. 이 단계는 실제 embedding API 비용이 발생하므로 배포 전 예산/승인 확인 후 실행한다. 재색인 결과 indexVersion/documents/chunks를 release.json/manifest와 함께 기록하고 기존 golden12 검색/USER·ADMIN 경계를 다시 평가한다. 새 manifest에 이전 색인을 성공으로 잘못 표시하지 않는다. 로컬 운영형 검증은 유료 색인을 실행하지 않는다.

호스트 backend 포트를 열지 않고 실행할 수 있는 명시 helper가 있다. `$PINNED_PYTHON_IMAGE`는 검증한 Python3 이미지 digest이며 예산 승인 후에만 아래를 실행한다. credentials는 파일에서 읽고 JWT는 메모리에만 둔다. 자동 기동 단계가 아니다.

```sh
docker run --rm --network exchange-public_edge \
  -v /srv/exchange/runtime/current:/run/secrets:ro \
  -v /srv/exchange/data/release:/release \
  -v "$PWD/deployment/init-ai.py:/init-ai.py:ro" \
  "$PINNED_PYTHON_IMAGE" python /init-ai.py --confirm-cost
```

## 6. Backup / restore

`down -v`, volume prune, data root 삭제와 DB/체인 한쪽만 rollback은 금지한다. daily7/weekly4 보존은 초기 운영안이다. 스크립트는 자동 삭제/retention/S3 업로드를 하지 않는다. 로컬 checkpoint는 private staging이며 EBS암호화만으로 서버 손상 대응이 끝나지 않는다.

```sh
python deployment/ops.py backup --project exchange-public --env-file deployment/.env \
  --data-root /srv/exchange/data --destination /srv/exchange/backups/checkpoint-UTC
```

web·backend graceful stop → unresolved 주문 REQUESTED/PENDING_ONCHAIN/REVIEW_REQUIRED·전송 CREATED/SIGNED/SUBMITTED/REVIEW_REQUIRED·자산 잠금이0인지 확인 → Anvil 정상종료/state저장 → 두 DB별 pg_dump → 체인state/주소·비밀없는release설정·SHA256 기록. 남은 주문/실패가 있으면 서비스는 **중지 상태 유지**하고 운영자 확인 없이 자동 재개하지 않는다. 처리 중 보고서 만료/불명확 RPC는 기존 reconciliation과 진단으로 먼저 해결한다. 백업으로 자동 복구하지 않는다. snapshot으로 dump 복원 검증을 대신하지 않는다.

백업 bundle과 동일 릴리스 artifact/이미지 digest, SSM parameter version 참조·체인namespace를 보관한다. SSM key를 평문 backup에 추가하지 않는다. 승인된 S3 bucket에는 SSE-KMS + private policy/수명주기/최소 IAM으로 업로드한다. 실제 bucket/KMS/IAM/업로드는 미실행이다. role 이름은 고정exchange/exchange_ai이며 새 DB initialization으로 재생성되고 DB dump는 owner를 요구하지 않는다. 운영 backup 후에는 `up -d anvil backend web`하고 코드/체인/잔고/정산을 재검증한다.

```sh
# restore.env는 새 빈 DATA_ROOT, 같은 release/keys/artifact, 비공개/충돌 없는 포트로 준비
python deployment/ops.py restore --project exchange-restore-drill --env-file deployment/restore.env \
  --data-root /srv/exchange/restore-drill --source /srv/exchange/backups/checkpoint-UTC
```

복원은 `exchange-restore-*` 신규프로젝트·빈 data root만 허용하며 effective Compose mount와 요청경로도 일치해야 한다. 기존 운영 root에는 overwrite하지 않는다. 두 DB pg_restore와 체인 파일을 같은 checkpoint로 복원한 뒤 ingress는 자동 기동하지 않는다. 테스트체인 재시작 후 code/nonce/receipt/서명domain·주문/체결/잔고/잠금/AI indexVersion을 확인하고 fixture 거래가 되는지 검증한다. AWS 장애 복구 RTO/RPO는 아직 측정하지 않았다.

양DB healthcheck는 `postgres-readiness.sh`의 TCP/실제 `SELECT 1` 성공을 확인한다. 파일의 password를 검사 프로세스에서만 읽고 health 출력은 숨긴다. restore는 양DB SQL을 각각 최대60초 확인한 뒤 각 dump 직전 다시 확인한다. 준비 확인만 짧은 주기로 polling하며 pg_restore 자체는 단 한 번 실행한다. 초기화 시간을 가정한 sleep이나 부분 복원 위 retry는 하지 않는다.

pg_restore stderr는 복원 DATA_ROOT의 `.restore-diagnostics/<service>.stderr`에 보존한다. POSIX 디렉터리0700/파일0600이며 실패 metadata는 DB/service/exit code/시각/재시도false만 담는다. **raw stderr에는 SQL/행 데이터가 포함될 수 있으므로 공개 로그·Git·보고서에 복사하지 않는다.** Windows는 mode bit만으로 ACL 보호가 보장되지 않으므로 runtime 접근 권한을 별도로 제한한다. 파일은 exclusive create이며 기존 증거를 덮어쓰지 않는다. 실패 시 신규root를 보존하고 원인 확인 후 다른 빈root를 준비한다.

로컬 시험 checkpoint 전용 반복 회귀는 `python deployment/restore-regression.py --source deployment/runtime/local-RUN/backup --rounds 3`이다. 각 라운드에 새root/project를 만들고 두DB의 체결/pgvector probe 및 chain 동일성을 확인한다. 최초 실패에서 멈추며 부분 복원은 재시도하지 않는다. 기존 개발/운영 checkpoint를 임의 지정하는 도구가 아니다. 삭제는 정확한 시험 컨테이너/네트워크만 down(no-v), bind 증거는 보존한다.

## 7. 로컬 검증

```sh
python -m unittest discover -s deployment -p test_ops.py
python deployment/local-smoke.py
```

local-smoke는 loopback18080/18443, 신규exchange-ops/restore 프로젝트와 ignored runtime 아래의 전용 DB/체인, 임시 무작위 키·self-signed 인증서만 사용한다. 인증서를 테스트 CA로 신뢰해 hostname/TLS 검증하며 verify를 끄지 않는다. TEST 전용 acceptance allow all은 loopback에만 적용하고 운영에 복사하지 않는다. AI는 켜되 provider를 localhost의 닫힌 포트로 지정해 실패 격리를 검증하며 유료 OpenAI·SSM·AWS는 호출하지 않는다. 끝나면 검증 컨테이너·네트워크만 `down`하고 bind DB/체인·private 로그/증거는 보존한다(-v/prune 금지). 기존 개발5432/5433/8545/8082는 변경하지 않는다. 결과와 미검증은 배포 설계/구현 로그에 기록한다.
