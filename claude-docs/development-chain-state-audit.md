# 개발 DB와 Anvil state 관계 조사

> 조사: 2026-10-07~08 KST · 읽기 전용 조사 완료  
> 결론: 조사한 보존 파일 중 **현재 개발 DB와 일관된 복원 후보를 찾지 못했다.** 복원·재배포·DB 초기화는 실행하지 않았다.

## 1. 조사 범위와 판정 기준

기존 개발 PostgreSQL `exchange-postgres` / DB `exchange`, 현재 `exchange-anvil`의 RPC, 저장소의 보존 state/checkpoint와 Foundry broadcast를 확인했다. 외부 디스크·사용자가 별도로 관리하는 백업까지 존재하지 않는다고 단정하지 않는다.

읽기 작업:

- PostgreSQL `SELECT`와 `BEGIN READ ONLY`, 메타데이터 조회. private key, signature, raw transaction 원문은 출력하지 않았다.
- 현재 RPC의 `eth_chainId`, `eth_blockNumber`, `eth_getCode`, `eth_getTransactionReceipt`.
- 보존 JSON의 계정 코드, 블록, 거래 해시, receipt/log를 오프라인 분석하고 SHA-256 확인.
- Compose/소스/기존 결과 문서를 읽었다. `anvil_loadState`, `evm_mine`, `eth_sendRawTransaction`, 복원·배포 스크립트는 실행하지 않았다.

복원 후보라면 chainId뿐 아니라 **배포 코드·연결 주소·거래 해시/receipt/event·운영자 nonce·원장 시점**이 맞아야 한다. 같은 chainId 또는 같은 주소는 같은 체인 이력의 증명이 아니다.

관련 기초 설명은 [현재 프로젝트 구조와 거래 흐름 학습 문서](backend-blockchain-learning-guide.md)를 참조한다.

## 2. 현재 개발 환경의 확인된 상태

### 현재 Anvil

| 항목 | 확인 결과 |
|---|---|
| RPC | `http://127.0.0.1:8545` 응답 가능 |
| chainId | `31337` (`0x7a69`) |
| 최신 블록 | `0` |
| `.env`의 네 컨트랙트 | 모두 `eth_getCode = 0x` |
| 대표 개발 DB txHash 4개 | 모두 receipt `null`; 조회할 event 없음 |
| 실행 구성 | entrypoint `anvil --host 0.0.0.0`, mount 없음 |

현재 `.env`의 공개 주소:

| 역할 | 주소 |
|---|---|
| MockKRW | `0x5FbDB2315678afecb367f032d93F642f64180aa3` |
| mSEC | `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512` |
| PriceOracle | `0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0` |
| ExchangeVault | `0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9` |

[개발 Compose](../docker-compose.yml)에서는 PostgreSQL이 외부 볼륨을 쓰지만 Anvil에는 state 저장 옵션/볼륨이 없다. 이 구성은 프로세스 재생성 시 체인 이력을 보존하지 못할 위험이 있다. **이번 공백이 언제, 어떤 명령 때문에 생겼는지는 확인하지 못했다.** 컨테이너 실행 시간만으로 reset/재시작 여부를 확정하지 않는다.

반면 [배포 Compose](../deployment/compose.yml)는 `--state /data/state.json`, `--state-interval 5`, `--preserve-historical-states` 및 별도 bind root를 사용한다. 개발 Compose와 다른 환경이다.

### 현재 개발 DB

| 항목 | 실제 SELECT 결과 |
|---|---|
| `blockchain_transactions` | 22,603건, 전부 `UPDATE_PRICE / CONFIRMED` |
| 해당 기록의 block number | 최소 7, 최대 22,612 |
| 해당 기록의 nonce | 최소 7, 최대 22,613; 서로 다른 nonce 22,603개 |
| raw transaction 보존 여부 | 22,603건 모두 값 있음; 원문 미출력 |
| 기록 생성 시각 | DB 출력 기준 2026-09-01~2026-09-16; timezone 보정 없이 인용 |
| `orders` | 12건: BUY FILLED 5 / SELL FILLED 5 / BUY FAILED 1 / SELL FAILED 1 |
| 주문의 txHash | 12건 모두 없음 |
| `trades` | 10건, 모두 txHash 없음 |
| `price_quotes` | 0건 |
| 잠긴 사용자 잔고 / 미완료 blockchain transaction | 각각 0건 |

따라서 이 DB에는 현재 서명 견적 BUY/SELL 체인의 증거가 없다. txHash 없는 체결은 [OrderService의 모의 거래 경로](../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java)와 부합하지만, 행만으로 과거 실행 설정까지 확정하지 않는다.

과거 `UPDATE_PRICE` 행은 현행 거래 방식과 구분한다. [백엔드 설명](../backend/README.md)의 Phase 5.3-C 이후 신규 주기적 updatePrice 전송은 제거됐으며, 옛 기록의 조회·복구 호환 경로만 남아 있다. 이 행들을 현재 웹 서명 거래 성공으로 세지 않는다.

## 3. 사용 가능한 checkpoint 4개

경로는 모두 `deployment/runtime/<이름>/backup/`이다. 각각 `state.json`, `chain.properties`, `exchange.dump`, `exchange_ai.dump`, `release.env`, `checkpoint.json`을 갖는다.

| checkpoint | 생성 시각 UTC | chainId 근거 | best block | 저장 receipt | 개발 DB 해시 교집합 | 5개 파일 checksum |
|---|---|---|---:|---:|---:|---|
| `local-20261007025021` | 10-06 17:53:38 | 저장 거래 `0x7a69` = 31337 | 2 | 19 | 0 | 전부 일치 |
| `local-20261007030121` | 10-06 18:04:40 | 동일 | 5 | 19 | 0 | 전부 일치 |
| `local-20261007145955` | 10-07 06:03:57 | 동일 | 6 | 19 | 0 | 전부 일치 |
| `local-20261007160704` | 10-07 07:10:38 | 동일 | 4 | 19 | 0 | 전부 일치 |

state 최상위에는 chainId가 없었다. **저장된 블록의 transaction.chainId**를 확인했고, 배포 Compose의 `--chain-id 31337`과도 일치한다. 복원한 RPC의 chainId를 확인한 것은 아니다.

각 state에는 아래 네 주소의 코드가 실제 존재한다. 네 주소 모두 현재 개발 `.env`와 다르다.

| checkpoint | MockKRW | mSEC |
|---|---|---|
| `...025021` | `0x4F78912F4896a823cBa84A77E1E6517f87aDf1dc` | `0x90DEE4d9b471747283097859deb979546a01f0AA` |
| `...030121` | `0xF546e1454AbD9064ce761Bb84DD74Aa4d10d69d0` | `0x2e9036D2c91DeAa8c68Ad4ec00426c8a051B0e24` |
| `...145955` | `0x2AfA552b99616B97A040288bb9c0258c943D4bD9` | `0xe018Bd479AD6F37aB2d5584aC914C7a1001272f2` |
| `...160704` | `0x7E01175651ACb8b98b950C7FA86FCC3d31367c3e` | `0x0f536d2c4cECD19B7864856B1B1A4aebBd7b8aF4` |

| checkpoint | PriceOracle | ExchangeVault |
|---|---|---|
| `...025021` | `0xED4EFc74cf3C66d0D39d53E1b509a7CC3B2DF81e` | `0xe96C997004E33421cA5FA4dAF745c99446a38875` |
| `...030121` | `0x59e1715AF4010e150625fEA1f193fDA541177202` | `0x4E41d99d4fC96bAfA1919e8f46eb5De111E5e540` |
| `...145955` | `0xb808bd5fE57c7512bC8f552C8CD3ecf314B69EF4` | `0x35b8306AD7CB548BA8176E10578C94dae4e2b26B` |
| `...160704` | `0x5099Eb31D63F70Fa653815ed9BF13d196FC2Fe60` | `0xAf15e1E6ae703aCB1584c826FfBF96D05dCd109D` |

각 checkpoint에는 해당 검증 환경의 성공 Vault 로그 receipt가 1개 있다. 다음은 오프라인 파일에서 확인한 예다. 이것은 개발 DB 거래가 아니다.

| checkpoint | 자체 성공 Vault txHash | receipt block |
|---|---|---:|
| `...025021` | `0xd41b5b13f293c2ca3700489f7735c1d3be4cfd008fa2d7d1cb029c52201b06b8` | 2 |
| `...030121` | `0x9ccc5e051fac38ee4622e00ab73968fb7759d1ac0e05b84541ccb199b98d1c22` | 3 |
| `...145955` | `0x882817365ac99398f376220618ead77da0edc3530806476623fe9e538f194f20` | 4 |
| `...160704` | `0x79b555c912d3f897b06104cff6bfebb04cbbb1ce302b5b2ed0063299d41ccf94` | 2 |

status는 `0x1`이며 Vault address와 indexed topic을 가진 로그가 있었다. checksum 일치는 파일 무결성의 증거이지 **개발 DB와의 논리적 일관성 또는 복원 후 실행 성공의 증거가 아니다.** 이번에는 dump를 DB에 복원하지 않았고 dump 내부 모든 잔고/연결을 다시 검증하지 않았다. 각 checkpoint의 자체 복원 회귀 이력은 [기존 검증 기록](validation-2026-10-07.md)과 구분한다.

## 4. 추가 state 전체 조사

state 파일은 25개, SHA-256 기준 서로 다른 내용은 16개였다. 모든 파일에 대해 개발 DB의 txHash 22,603개와 `transactions[].info.transaction_hash`의 교집합을 계산했다. **모두 0**, 개발 `.env` 네 주소의 배포 코드도 모두 없었다. 대표 4건의 저장 receipt/event 역시 없다.

`best_block_number`와 저장 블록 header의 최대 number는 각 파일에서 일치했다. 같은 chainId의 거래가 없는 빈 state 2개는 파일만으로 chainId를 확인할 수 없다.

| 보존 run | state 위치와 best block | chainId 근거 |
|---|---|---|
| `local-20261007021931` | `data/chain` 0 | 거래 없음; 미확인 |
| `local-20261007022239` | `data/chain` 0 | 거래 없음; 미확인 |
| `local-20261007022438` | `data/chain` 1 | 저장 거래 31337 |
| `local-20261007022630` | `data/chain` 4 | 동일 |
| `local-20261007023519` | `data/chain` 2 | 동일 |
| `local-20261007023741` | `data/chain` 3 | 동일 |
| `local-20261007024558` | `data/chain` 3 | 동일 |
| `local-20261007025021` | `backup` 2 / `data/chain` 2 / `restore-data/chain` 4 | 동일 |
| `local-20261007030121` | `backup` 5 / `data/chain` 5 / `restore-data/chain` 7 | 동일 |
| `local-20261007145955` | `backup` 6 / `data/chain` 6 / `restore-data/chain` 6 | 동일 |
| `local-20261007160704` | `backup` 4 / `data/chain` 4 / `restore-data/chain` 6 | 동일 |
| `probe-20261007105549` | `data/chain` 22 | 동일 |
| `probe-20261007110207` | `data/chain` 14 | 동일 |
| `restore-regression-20261007070406459096` | `data-0/chain` 6 | 동일 |
| `restore-regression-20261007070539878353` | `data-0/chain` / `data-1/chain` / `data-2/chain` 각각 6 | 동일 |

restore-regression 4개 state는 `local-20261007145955/backup/state.json`과 SHA-256이 같다. 독립적인 개발 체인 복원 후보 4개가 늘어난 것이 아니다. 일부 `restore-data`의 높이가 source보다 큰 것은 별도 검증 환경이 진행된 파일이며, source checkpoint와 구분했다.

조사한 저장소에서 그 밖의 state 이름 JSON을 추가로 찾지 못했다. Foundry `contracts/broadcast`의 run JSON 16개에는 receipt 항목 87개(중복 run-latest 포함)가 있었으나 개발 DB 해시 교집합은 0이다. Deploy 기록 6개와 latest 사본의 chainId=31337 및 네 CREATE 주소는 개발 `.env`와 일치한다. **broadcast는 배포 실행 기록이지 계정 storage/잔고/모든 블록을 복원하는 state 백업이 아니다.** 같은 주소가 여러 run에 반복되므로 주소만으로 특정 과거 실행을 고를 수 없다.

## 5. 대표 개발 DB 거래의 receipt/event 대조

| 선정 이유 | DB id | DB block | txHash | 현재 RPC / 모든 보존 state |
|---|---:|---:|---|---|
| 가장 작은 id | 6 | 15 | `0xb3df5a99bef99706d3fd4ef9cc1c2d051b091ef552e7c06bde79e96fe0ad0730` | receipt 없음 / 해당 hash 없음 |
| 최소 block | 22602 | 7 | `0x9b618ec14aa9157944e979ed0db0d8e2529d3ad1717596fdb0e638f7f2f854bf` | 동일 |
| 최대 block | 22601 | 22612 | `0xdfa6a6a3eb96cf4dd7767c0dcb157dea00812bf9a2178b317e861ff4e3670152` | 동일 |
| 가장 큰 id | 22610 | 15 | `0x2321bd3ad499871798f611d7a0d4c992ad9d8e3de036bb28f95fbc4f1fe11b06` | 동일 |

이들은 UPDATE_PRICE라 기대 event는 Oracle `PriceUpdated`다. receipt 자체가 없으므로 event 값 비교 단계까지 갈 수 없다. 이 결과는 event MISMATCH가 아니라 **관측 근거 부재**다. BUY/SELL의 대표 txHash는 개발 DB에 없어 선택할 수 없다.

DB id 증가와 block 증가가 동일하지 않은 점은 확인된다. 체인 reset/다른 실행 이력이 섞였을 가능성은 있지만, 원래 모든 체인과 실행 로그가 없어 그 원인/횟수는 미확정이다.

## 6. 안전 복원 가능 여부

**현재 확보한 파일만으로는 기존 개발 DB와 일관된 state를 안전하게 복원할 수 없다.**

이유:

1. 현재 체인은 빈 상태다.
2. checkpoint의 주소가 다른 데다 개발 DB 거래 해시 교집합도 0이다.
3. 추가 state는 최고 block 22까지이며, DB의 과거 block 22,612를 설명하지 못한다.
4. broadcast만으로 옛 상태 전체를 재구성할 수 없다.
5. DB에 저장된 raw transaction을 일괄 재전송하는 것은 복원이 아니다. 배포·선행 상태·nonce·시간·서명 조건을 잃은 상태에서 별도 실행을 만드는 위험이 있다.

빈 체인에 같은 주소로 재배포하고 `.env`를 맞추는 것도 옛 receipt/사용 견적/잔고를 복구하지 않는다. 또한 개발 DB에는 `(sender_address, nonce)` unique index가 실제 존재한다. 새 체인의 nonce가 낮은 값부터 다시 시작하면 같은 운영자 주소의 과거 DB nonce와 충돌할 수 있다. **충돌은 위험 분석이며 이번에 새 전송으로 재현하지 않았다.**

확보하지 못한 외부 state가 있다면 파괴적 조치 전에 추가 조사할 수 있다. 후보가 있어도 기존 환경에 덮어쓰기 전에 별도 승인된 격리 복원으로 signer/consumer/minter/잔고/allowance/receipt·전체 원장을 검증해야 한다.

## 7. 제안: Toss/AI 최종 인수용 신규 격리 baseline

다음은 **제안만**이며 아직 생성하지 않았다.

- 별도 Compose project 예: `exchange-acceptance-<run>`; 기존 `exchange-*` 컨테이너와 기존 volume을 참조하지 않는다.
- 새 거래 PostgreSQL과 새 AI PostgreSQL+pgvector, 새 private Anvil. 각각 새 전용 data root/volume 사용.
- loopback 전용 예시 포트: 거래 DB 5542, AI DB 5543, Anvil 18545, backend 18082, 웹 15173. 사용 가능 여부를 시작 직전에 확인한다.
- chainId 31337은 기존 호환성을 위해 유지할 수 있다. 격리는 project/RPC/data root/새 운영자 주소로 보장하고, chainId만으로 구분하지 않는다.
- 운영자와 price signer는 서로 다른 인수 전용 키로 준비한다. 테스트용 ETH와 mKRW/allowance/Vault 유동성을 준비하고 동일 실행의 주소를 전용 runtime 설정에 기록한다. 기존 `backend/.env`를 덮어쓰지 않는다.
- 기본 public profile은 simulated 강제이므로 Toss 인수에 그대로 쓰지 않는다. 기존 default 설정을 별도 runtime/process override로 실행하고 `PRICE_PROVIDER=toss`, 두 blockchain flag 및 새 DB/RPC를 지정한다. 제품 기능/설계는 바꾸지 않는다.
- 웹은 `BACKEND_URL`과 별도 포트로 새 backend에만 연결한다. 전용 사용자/ADMIN 계정만 사용한다.
- AI 지식 artifact는 현재 승인 manifest/hash/index version을 기준으로 준비한다. 새 AI DB의 실제 색인 비용도 포함해 **유료 호출 수/비용 승인 전 API를 호출하지 않는다.** 기존 DB의 개인정보·진단 이력을 복사하지 않는다.
- 정상 Toss BUY/SELL을 먼저 인수한 뒤 같은 인수 실행의 주문/견적/receipt로 AI를 검증한다. 자동 진단은 REVIEW_REQUIRED 대상으로만 실행되므로 성공 MATCH 재현 절차를 따로 제안·승인받는다. 기존 DB 행을 임의 보정하지 않는다.
- 두 인수 후 해당 실행의 거래 DB+AI DB+chain+addresses+코드 버전+지식 version을 같은 시점 checkpoint로 묶는다. ingress 중지/미완료·잠금 0 확인 등 [현재 backup 절차](../deployment/ops.py)를 적용하는 시점도 별도 승인한다.

승인 필요: 이 신규 격리 환경 생성·배포/자금 준비, AI 색인/평가 유료 호출, 성공 MATCH 자동 진단의 최소 재현 절차. 기존 환경의 복원/초기화 승인은 이 제안에 포함하지 않는다.

## 8. 한계와 보존

- 이번 판단은 **발견한 파일과 실제 개발 DB/RPC의 읽기 증거**에 한정한다. 실제 복원·RPC 로딩·receipt 재정산은 미실행이다.
- checkpoint checksum과 state 구조는 확인했지만 dump를 복원하거나 Anvil을 새로 기동해 모든 contract storage를 조회하지 않았다.
- 신규 계정/주문/AI API/AWS/새 컨테이너 생성 없음. 기존 DB·체인·.env·제품 코드 변경 없음.
- 새 조사/학습 문서 및 문서 색인·로그만 추가한다. 문서/읽기 조사이므로 제품 자동 테스트와 별도 구현 검토는 실행하지 않았으며, 자체 소스·경로·증거 대조를 수행한다.
