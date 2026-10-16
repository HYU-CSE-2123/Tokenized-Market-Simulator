# 키움 실환경 E2E 전용 격리 환경

> 2026-10-15. 환경 준비만 승인됨. 실제 키움 OAuth/REST/WS, Quote/BUY/SELL/정산 인수, 유료 AI 및 배포는 이번 작업에서 금지한다. 기존 환경과 증거를 재사용하거나 초기화하지 않는다.

## 준비 도구와 보호 경계

- 새 `deployment/kiwoom-e2e-prepare.py`: 기존 제품/배포 코드 수정 없이 신규 이름·포트·private root·키·DB·persistent Anvil을 준비하는 1회용 도구다. 재실행은 새 환경을 만드는 것이므로 완료 환경의 start 명령으로 사용하지 않는다.
- 캐시 이미지, Gradle offline, Docker build network=none/pull=false를 사용한다. 기존 `.env`, credentials.json, runtime은 import하지 않는다. 인증정보는 새 secret 파일에만 저장하며 로그와 보고서에는 남기지 않는다.
- 기존 `DeployPublic.s.sol`의 로컬 chainId=31337 제한을 그대로 따른다. 숫자가 다른 기존 로컬 체인과 같을 수 있으므로 이름·실행 ID·독립 RPC·새 operator/signer·계약 주소·blockHash를 함께 확인한다. chainId 단독으로 소유권/환경을 판정하지 않는다.
- 초기화용 initializer만 내부 전용 네트워크에서 잠시 실행한다. 공급자는 Synthetic, 반복 tick·복구 스케줄은 지연하고 AI/조건부 실행은 비활성화한다. 계정·스키마·불변 reserve baseline만 생성한 뒤 종료한다. 공급자의 시작 시 생성하는 로컬 합성 tick은 실키움 시세나 거래 검증으로 표시하지 않는다.
- 장중 설정 `live-NOT-APPROVED.json`은 명시적 `live` profile이며 PRICE_PROVIDER=kiwoom, 신규 secret 파일의 키는 공란이다. 현재 이 설정이나 egress 네트워크를 실행하지 않는다. 실제 연결은 다음 승인 사항이다.
- 승인된 최신 정책은 `kiwoom-mock-trading-availability-implementation.md`다. 0s는 완료 필수 조건이 아니며 UNKNOWN과 모의 견적 가용성을 구분한다. 기존 5초/미래 2초/TTL30 및 CLOSED/generation 차단은 유지한다.

## 실제 준비 결과

### 이름 및 실행 상태

- runId: `kiwoom-e2e-20261014171302-662c5ba0` (이름은 UTC 기준, 준비일은 KST 2026-10-15).
- 경로: 통합 worktree의 `deployment/runtime/kiwoom-e2e-20261014171302-662c5ba0`.
- Docker project: `exchange-kiwoom-e2e-20261014171302-662c5ba0`.
- DB: `kiwoom_e2e_662c5ba0`, loopback PostgreSQL `34379`, Anvil RPC `44379`.
- 다음 승인 후 사용할 포트: backend `30832`, web `35070`. 현재 두 포트의 제품 서버는 실행하지 않았다.
- PostgreSQL/Anvil: healthy. initializer: 종료(143은 명시적 stop에서 Java 프로세스가 종료된 코드). live backend/web: 생성·실행하지 않음.
- chainId=31337. 기존 로컬 체인과 숫자가 같아도 별도 project·DB·데이터 경로·계약·키·RPC로 구분한다.
- 호스트 bind 디렉터리 `data/trade`, `data/chain`을 사용하며 기존 named volume은 연결하지 않는다. 별도 internal network와 새 project의 host-access network만 사용한다.

### 신규 계약 및 계정

| 대상 | 새 주소 |
|---|---|
| MockKRW | `0xD9CEE0d6e571a87bf82B930ea584fb29155Af1BE` |
| mSEC | `0x2a33115bfa0B265d5aAA014260aEff69a196f429` |
| PriceOracle | `0xCd1203E7AC442D873008e6c6798Ee60bf04E7E71` |
| ExchangeVault | `0x36c58AD1Fd892994C83378F32c98e3a501e219BD` |
| operator | `0x6E47bA947d0e7a27E9E205cb3b47aCe2Deda78c6` |
| price signer | `0xBFcF196Fe17903C3EF5CD965dD0eD2d8F24D5a73` |

ADMIN 및 USER를 신규 DB에 준비했다. 로그인 정보는 전용 `secrets/accounts.json`에서만 확인하며 문서/터미널/로그에 비밀번호를 복사하지 않는다. 두 계정의 DB 자산은 0이고 Faucet은 지급하지 않았다. operator mKRW=5,000,000, Vault mKRW=5,000,000, MockKRW 공급=10,000,000, operator mSEC 및 mSEC 공급=0이다. operator ETH는 배포 후 약999.9964이고 Vault allowance는 uint256 최대치다. DB Faucet이 이 공급을 늘린 것이 아니라 기존 로컬 배포 스크립트의 초기 준비다.

### 불변 기준점과 검증

- 기준점 ID `c1ed0b0b-8607-455f-915e-d2ca0267488f`, executionId는 위 runId.
- DB 기준 시각 `2026-10-14T17:14:47.159960Z` (KST 다음 날02:14:47).
- blockNumber=2, blockHash=`0xcd769ab844aef8474e0c0f926065d44e802fad95b6cac2b5cf810b7d6085b346`.
- 실제 기존 reserve service/API로 baseline을 한 번 생성했다. 기존 schema 및 append-only trigger를 그대로 적용했다. 수동으로 payload를 합성하거나 과거 기준점을 복사하지 않았다.
- PASS: 신규 SQL 준비·스키마/계정 초기화, chainId·계약 code 존재, Vault 연결3종·minter·authorizedConsumer·별도 signer, 초기 잔고/공급/allowance/ETH 조회.
- PASS: 신규 Anvil stop/start 및 state.json 존재, operator nonce와 mKRW 잔고 보존, baseline blockHash가 여전히 canonical. 재배포 없음.
- PASS: orders/trades/price_quotes/blockchain_transactions/faucet_grants=각0, reserve_baselines=1, 비영 잔고/잠금 행=0.
- PASS: latest-source offline bootJar/Vite/image build, 미래 live Compose `config --quiet` 구문 검증(서비스 실행 아님), 키움 key/secret 공란, initializer internal-only network 검사.
- NOT_RUN: 실제 키움/AI 호출, Quote/BUY/SELL/Receipt 정산/개인WS/포트폴리오 E2E, 조건부 실행, 새 환경에서 대사 실행 결과 MATCH와 coverage. baseline 생성은 대사 실행 PASS를 뜻하지 않는다. 전체 backend/web/Foundry 회귀도 이번에는 재실행하지 않았다.
- 증거: 전용 run의 `run.json`, `baseline.json`, `ready.json`, `logs/`, `data/chain/state.json`. secret/state/log는 Git 제외·private ACL로 보존한다.

## 다음 장중의 정확한 실행 순서 (현재 실행 금지)

1. 외부 연결·인증정보 사용·자동재연결/토큰 갱신 포함 호출 범위·관찰 시간·신규 로컬 모의 BUY/SELL 승인을 받는다. 기존 진단의 0s-only 실험은 실행하지 않는다.
2. 이번 run의 `sourceHead/sourceHash/images`와 현재 소스, 새 DB/RPC/project 소유권, immutable baseline의 blockHash를 읽기 전용으로 재확인한다. 변경이 있으면 기존 환경을 무단 업그레이드하지 않고 반영 방법을 먼저 승인받는다.
3. 전용 `secrets/kiwoom.properties`의 두 공란에 실전 App Key/Secret만 준비한다. 기존 credentials 전체나 old .env를 import하지 않는다. 서비스/IP는 이미 완료한 사용자 확인을 유지하되 IP 변경·동일 계정의 다른 활성 WS 여부를 확인한다.
4. 아래에서 `$run`은 **새 run만** 가리킨다. `infra.json up`은 DB/Anvil만 재개하며 배포/기준점 생성 명령을 실행하지 않는다. initializer를 다시 시작하거나 준비 도구를 다시 실행하지 않는다.

```powershell
# integration-worktree 폴더에서 실행. 5~6행은 별도 장중 승인 전 실행 금지.
$run = Join-Path (Get-Location) 'deployment/runtime/kiwoom-e2e-20261014171302-662c5ba0'
$project = 'exchange-kiwoom-e2e-20261014171302-662c5ba0'
docker compose -p $project --env-file "$run/release.env" -f "$run/infra.json" up -d --wait postgres anvil
docker compose -p $project --env-file "$run/release.env" -f "$run/live-NOT-APPROVED.json" --profile live up -d backend web
# 종료: 해당 신규 backend/web만 정지. DB/chain 데이터는 그대로 보존.
docker compose -p $project --env-file "$run/release.env" -f "$run/live-NOT-APPROVED.json" --profile live stop backend web
```

5. 승인 후 웹 `http://127.0.0.1:35070`, 서버 `http://127.0.0.1:30832`를 사용한다. 최초 현재 연결의 유효 정규 체결·UNKNOWN 허용·CLOSED 우선·fresh 및 generation 검사부터 확인한다. 가격을 보았다는 이유만으로 OPEN을 확정하지 않는다.
6. 신규 계정의 Faucet → Quote → BUY/SELL → receipt/event → DB·개인WS·포트폴리오 → 두 대사를 검증하고 실패/불확실하면 증거 보존 후 중단한다. 실주식 주문·AI·Sepolia·배포는 제외한다.

## Git 및 기존 환경 영향

기준 HEAD `240cb3982d6c05724d6a89e99aed66788d77d697`, 브랜치 `feat/kiwoom-live-fixes`. 기존20개 수정·17개 미추적 파일은 정리/삭제하지 않았다. 이번에는 새 준비 도구·이 문서와 구현 로그 후속 기록만 추가한다. 제품 코드와 기존 실행 도구를 수정하지 않았고 commit/merge/push는 하지 않는다. 기존 playground4개 서비스 및 보존 acceptance/개발 DB·Anvil, private capture, 인증정보는 변경하지 않았다. 초기 read-only Docker 사전 점검 이외에는 새 project에만 작업했다.

독립 검토: 실행 전 안전 검토 및 실행 후 최종 소스·문서 재검토에서 모두 발견된 필수 수정 없음. reviewer 직접 AST parse PASS이며 Docker/API/DB 결과는 구현자의 실제 실행으로 구분한다. reviewer는 자격 증명·private capture·기존 DB나 Docker에 접근하지 않았다. tmpfs 소유 검사에 관한 방어 제안은 현 환경에서 실제 실패가 없었으므로 수정하지 않았다.

최종 점검: ADMIN/USER 각1명, 0원 user_balances4행, reserve_baselines/faucet_grants/reserve_runs의 immutable trigger3개 존재를 신규 DB에서 확인했다. 새 로그74개 및 initializer Docker 로그를 생성된 key/password/JWT secret의 실제 값과 메모리에서만 대조해 노출0건을 확인했다(값 출력 없음). root melon-init HEAD/clean tree 유지, 새 runtime ignore, stage 비어 있음도 확인했다. 준비 환경은 healthy DB/Anvil만 남겨 두었으며 전체 제품 인수 PASS 선언은 하지 않는다.

## 다음 장중 인수의 승인 경계

전용 `secrets/kiwoom.properties`에 실제 키움 키를 준비하는 것, 외부 연결 및 자동 재연결/토큰 갱신 포함 호출 한도, 새 로컬 환경에서만 모의 거래를 실행하는 것은 별도 승인 후 진행한다. 서비스 신청/IP 등록은 사용자 확인 사실을 유지하고 중복 신청을 요구하지 않는다. 계정 충돌/접근 환경 변경 여부는 실행 직전에 확인한다.

표준 서버의 정상 최초 연결은 OAuth1+현재가1+분봉 최소1/WS1이다. 차트는 조회당 최대8페이지, 인증 오류 시 재발급·요청 재시도, WS는 자동 재연결이 있어 진단 도구의 REST4/WS1 상한을 그대로 보장하지 않는다. 엄격한 상한이 필요하면 다음 승인에서 실행 제어 방식과 중단 조건을 먼저 확정해야 한다.
