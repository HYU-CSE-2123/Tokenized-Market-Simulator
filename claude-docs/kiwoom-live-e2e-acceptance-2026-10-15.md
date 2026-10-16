# 키움 실환경 E2E — 시작 전 BLOCKED

> 2026-10-15. 사용자가 기존 신규 환경의 실제 키움 시세 최대10분 및 Faucet/BUY/SELL 각1회 인수를 승인했다. 실행 전 필수 점검에서 전용 인증정보 부재로 중단했으며 실제 외부 연결·거래는 시작하지 않았다. 승인된 것을 실행한 것으로 표시하지 않는다.

## 확인한 환경

- 기존 run `kiwoom-e2e-20261014171302-662c5ba0`만 읽기 전용 확인.
- 브랜치 `feat/kiwoom-live-fixes`, 준비 HEAD `240cb398`. 현재 제품/웹 소스 hash와 준비 hash 일치.
- 이미지6개 모두 준비 image ID와 일치. 신규 postgres/anvil 실행 중, initializer 종료, live backend/web 미생성.
- 기존 신규 project 소유권·bind 경로 일치. DB immutable baseline payload가 준비 기록과 같으며 baseline block2 hash가 Anvil에서 canonical이다.
- UTC17:33:29(KST10월15일02:33:29)에 점검했다. 호스트 시각만으로 공급자 시장 상태를 OPEN/CLOSED로 판정하지 않는다. 실제 장중 정상0B는 아직 관찰하지 않았다.

## 시작하지 않은 이유

전용 `secrets/kiwoom.properties`에서 `KIWOOM_APP_KEY` 및 `KIWOOM_SECRET_KEY` 존재 여부가 모두 false다. 실제 값을 출력·복사하거나 기존 credentials/.env를 import하지 않았다. 사용자가 이 파일에만 두 값을 입력해야 한다. 키 존재 확인을 위해 OAuth를 요청하지 않는다.

서비스 신청/IP 등록은 사용자의 이전 완료 확인으로 유지하며 중복 신청을 요구하지 않는다. 호스트의 RemotePort10000 established 연결 후보는0건이지만, 다른 장비를 포함한 계정 전체 WS 부재나 현재 공인IP와 등록IP 일치를 도구가 확인한 것은 아니다.

## 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| 소스·이미지·컨테이너 소유권 | PASS | 이번 새 read-only 대조 |
| 기존 baseline 및 blockHash | PASS | DB payload 및 로컬 Anvil RPC 대조 |
| 전용 인증정보 준비 | BLOCKED | 두 항목 모두 공란 |
| 현재 공인IP 등록 일치·계정 전체 다른 WS | INCONCLUSIVE | 이전 사용자 완료 사실과 호스트 후보0건만 확인 |
| 독립10분 자동 중단·실제 egress 차단 검증 | NOT_RUN | 인증정보 점검 차단 이후 외부 준비/실행을 진행하지 않음 |
| 실제 OAuth/REST/WS·0B 가용성 | NOT_RUN | 외부 연결0 |
| Faucet/Quote/BUY/SELL | NOT_RUN | 지급/주문0 |
| Receipt/Event·DB정산·개인WS·포트폴리오 | NOT_RUN | 거래 미실행 |
| 자산 대사 MATCH·전수 대사 coverage | NOT_RUN | baseline 대조는 대사 실행이 아님 |

BUY/SELL txHash는 발급되지 않았고 성공/실패 receipt 결과도 없다. BLOCKED를 거래 FAIL로 표현하지 않는다. 새 환경 재생성·재배포·DB초기화·baseline재생성·기존playground변경·AI·Sepolia·commit/merge/push는 하지 않았다. 이번 기록과 구현 로그만 추가했다.

## 재개 조건

1. 사용자가 전용 secret 파일 두 값을 입력했다고 알려준다. 실제 값은 채팅에 보내지 않는다.
2. 실제 장중 실행 시점에 기존 승인 범위와 현재 공인IP/다른계정WS 확인 사실을 점검한다. 기존 승인과 무관한 새 서비스 신청은 요구하지 않는다.
3. 외부 연결 전에 독립 자동 종료 수단 및 신규 backend egress 차단의 실효성을 무과금·비키움 방식으로 검증한다. 준비되지 않으면 계속 BLOCKED다.
4. 위 조건을 모두 충족한 경우에만 승인된 기존 환경에서 시세/거래 인수를 진행한다. 기존 상태를 reset하지 않는다.

## 후속 재점검 및 무과금 안전 수단 검증

사용자 전용 인증정보 입력 완료 후 같은 환경을 재점검했다. 두 키 존재=true, 값 출력/복사 없음. 소스hash·이미지6개·project 소유권·데이터 경로·DB baseline·Anvil blockHash 모두 일치했다. 호스트RemotePort10000 established 후보0건이며 현재 공인IP의 계정 등록 일치와 다른 장비의 계정 WS는 도구가 확인하지 못했다. 이전 사용자 서비스/IP 완료 사실은 유지하고 중복 서비스 신청을 요구하지 않는다.

실제 외부 연결 없이 새 `deployment/kiwoom-e2e-safety.py`로 기존 신규 project 내부에만 로컬 TCP mock/probe/watchdog을 준비했다. DB/Anvil/기존playground/실제 backend에는 쓰기·재시작·배포하지 않았다. 별도의 외부 서비스도 사용하지 않았다.

- 첫 safety 시도는 egress network 준비에서 BLOCKED됐다. 해당 실패의 stderr가 보존되지 않아 정확한 원인은 미확정이다. 기존 network를 삭제하지 않고 명시적 subnet 후보·제한된 충돌 시도와 실패 증거 보존을 추가했다.
- 최초 독립 검토는 disconnect 성공/실제 분리 및 reconnect 후 활성 flow 확인이 PASS 조건에 빠졌다고 지적했다. 이 검증 도구만 수정했다. 제품의 시세/거래 정책은 변경하지 않았다.
- 수정 후 실제 로컬 검증: 이미 연결된 TCP flow 차단, 새 TCP 연결 차단, backend와 같은 topology에서 egress 해제 후 internal network만 남음, 재연결 후 정상flow복원 모두 PASS.
- 독립 Docker watchdog은 host의 검증 orchestration과 별도로 실행했다. network=none, 두 pinned target ID와 owner 검증, Docker Unix socket을 통해 해당 probe들만 제어한다. socket의 read-only mount는 Docker API가 읽기 전용이라는 뜻이 아니며, 종료 권한을 가진 임시 제어 프로세스다. DB/Anvil/다른 project는 target이 아니다.
- 축소 timer 실측: 10.196초에 egress disconnect HTTP200, 두 SIGTERM HTTP204, 15.531초까지 두 SIGKILL fallback HTTP204 및 두 target 종료. 최종 egress 실제 분리/종료를 함께 확인했다. 검증용 컨테이너는 종료·보존했고 새 project의 egress network만 준비해 두었다.
- 실제 연결용 timer 계획은 arm 시점 기준570초 차단/종료 시작,585초 강제 종료로600초 한도에 여유를 둔다. **실제 backend 대상으로 10분 timer를 arm하거나 키움으로 검증한 것은 NOT_RUN이다.** 다음 실제 시작 전에 소유권·network·watchdog ARMED 상태를 확인해야 하며, Docker daemon/socket이 고장나는 상황까지 종료를 보증하는 장치는 아니다. 보장 조건이 확보되지 않으면 외부 연결하지 않는다.

이번 측정 시각은 UTC18:06(KST10월15일03:06)이다. 실제 정규장 연결 시점이 아니므로 추가 키움 호출 없이 인수는 계속 BLOCKED/NOT_RUN으로 남긴다. marketStatus를 시각만으로 OPEN/CLOSED로 만들지 않고, 이후 승인된 장중의 정상0B290=2와 서버가용성검사를 실제 실행조건으로 유지한다. Synthetic 거래로 대체하지 않았다.

### 최신 판정 (앞선 키 공란 차단은 해결됨)

| 항목 | 이번 판정 |
|---|---|
| 두 인증정보 존재·소스/이미지/DB/Anvil/baseline | PASS |
| 기존/신규 TCP egress 차단 및 독립축소timer/SIGKILL | PASS (로컬 mock) |
| 현재 계정의 공인IP 등록일치·다른장비 WS 부재 | INCONCLUSIVE (이전 사용자 확인과 호스트관측 구분) |
| 실제 backend10분 watchdog ARMED | NOT_RUN |
| 실제 장중 키움·Faucet·BUY/SELL·Receipt정산·개인WS·두대사 | NOT_RUN |

외부키움호출0, BUY/SELL txHash없음. 안전 도구의 proof PASS를 키움 E2E PASS로 표시하지 않는다. 새로운 Git 변경은 safety 도구와 이 후속 보고/구현로그 기록뿐이며 commit/merge/push는 하지 않는다.

### 최종 소스 재검증과 독립 검토

소유권/role 및 network allowlist를 엄격하게 보완한 최종 도구를 실제 다시 실행했다. `safety-check-08a8945d.json`: 활성TCP차단/새연결차단/재연결복원 PASS, watchdog은10.183초disconnect200,15.471초까지SIGKILL fallback204/두대상종료/egress실제분리 PASS. 앞선 `safety-check-caf58592.json`과 결과를 별도로 보존했다. 짧은 timer로 검증한 것이며 실제backend10분arming을 실행한 것으로 표시하지 않는다.

초기 독립 검토의 두 필수 지적을 수정하고 첫 재검토에서 발견된 필수 수정 없음으로 확인했다. reviewer는 script 및 WATCHDOG 문자열의 AST검사와 정적 검토만 직접 수행했고 Docker/credentials/private evidence에는 접근하지 않았다. 실제 mock 결과는 구현자의 실행 결과다.

최종 read-only 확인: 신규 PG/Anvil은healthy, initializer종료, 실제backend/web미생성. 안전 검증용8개컨테이너는모두종료했고state/evidence는보존했다. 주문/견적/거래/지급0·baseline1·비영잔고/잠금0 유지. root melon-init HEAD=f069a1085fd7cfe1d8d75f7bb454fc20feb50ede 유지. source/policy/계약/DB/baseline 변경·재생성 없음. 장중 실인수 재개까지 현재 실행 승인 범위는 유지하되, 현재공인IP/타기기WS확인과실제watchdogARMED·daemon정상조건을 실행직전에 확인해야 한다.

### 사용자 IP/타기기 WS 확인 이후 (KST03:23)

사용자가 현재공인IP 등록일치와다른장치의동일계정활성WS없음을 모두확인했다. 이는 사용자 확인 사실이며, 도구가 계정관리화면을 직접검증한 결과와 구분한다. 이 두전제의 추가사용자확인은 현재해결됐다.

이번 새 read-only 대조에서 sourcehash·이미지6개·컨테이너소유권/데이터mount·DB baseline payload 및 canonicalblockHash 일치를 다시확인했다. PG/Anvil가동·initializer종료·livebackend/web미생성 유지. 현재호스트KST2026-10-15 03:23으로 승인된장중실행시점이아니므로 외부연결0,거래0이다. 실제backend/webwatchdogarming은장중연결직전 단계로남겼으며 이번에수행하지않았다. 기존준비도구·재배포·초기화·baseline재생성·제품정책변경·commit/merge/push없음. 현재실제E2E는장중재개대기 BLOCKED/NOT_RUN이며 승인범위를취소하거나Synthetic성공으로대체하지않는다.

### KST13:01 재개 — Docker Linux Engine 응답 장애로 BLOCKED

사용자의 장중 재개 요청에 따라 KST2026-10-15 13:01:24에 시작했다. 현재공인IP 등록일치/타기기동일계정WS없음은 앞선 사용자 확인을 유지했다. 로컬파일만 사용하는 재검사에서 현재 sourcehash=준비sourcehash 및 전용 두키존재=true를 확인했다. 비밀값 출력/복사는 없다.

Docker 재점검은 다음 실제 결과로 차단됐다.

- 준비 image ID를 `docker image inspect`로 대조하는 중15초 timeout.
- 해당 신규 project의 `docker ps -a` 조회에서 `dockerDesktopLinuxEngine/v1.51/containers/json` HTTP500.
- 별도 읽기 전용 `docker version` Server 요청12초 timeout, `docker info` Server 요청12초 timeout.

따라서 이번에는 이미지/컨테이너소유권/DB/Anvil/baseline의 현재 daemon 상태를 끝까지 확인하지 못했다. 이전 PASS를 이번 재검증 PASS로 재사용하지 않는다. Docker socket 기반 watchdog의 실전 ARMED를 보장할 수 없어 서비스 create/start나 키움 연결 전에 중단했다.

| 이번 단계 | 판정 |
|---|---|
| sourcehash·전용두키존재 | PASS |
| IP등록일치·타기기WS없음 | 사용자확인완료 (도구직접검증과구분) |
| Docker Engine Server 정상성 | BLOCKED (HTTP500/timeout) |
| 현재 image/소유권·DB/Anvil/baseline 대조 | INCONCLUSIVE (Docker장애로완료불가) |
| 실제 backend/web watchdog ARMED | NOT_RUN |
| 실제키움연결·Faucet·Quote·BUY/SELL·Receipt/정산·개인WS·두대사 | NOT_RUN |

이번 키움 OAuth/REST/WS 호출0, 지급/주문0, BUY/SELL txHash없음. 이미 제출된 거래를 변경하거나정산하지않았다. 새준비도구/재배포/DB초기화/baseline재생성/실제backend/web시작/기존playground변경/유료AI/commit/merge/push없음. Docker Desktop/WSL 전체재시작·Docker API version강제변경·network/volume삭제도실행하지않았다.

재개에 필요한 사용자조치: Docker Desktop의 Linux Engine이 Server version/info에 정상응답하도록 복구한 뒤 알려준다. 전역재시작은기존컨테이너에영향을주므로에이전트가임의실행하지않는다. 정상화후에도같은run을보존하여freshpreflight→실제watchdogARMED→승인된장중0B가용성확인순서로재개하며Synthetic으로대체하지않는다.

### Docker 정상화 후 재개 — 사전 대조 PASS / 장중 실행 NOT_RUN

사용자 확인(IP 등록 일치, 타기기 동일 계정 API WS 부재)은 유지하며 중복 확인을 요청하지 않았다. 이번 작업 시작 시각은 KST14:33이다. 처음 일반 CLI의 version/info는 각각15초 timeout이었지만, 명시적 Docker 실행 파일·`--context desktop-linux` 및 기존 `clean_env()`를 사용한 후 Server version/info28.4.0 정상 응답을 확인했다. timeout의 정확한 원인을 환경변수나 Docker 장애로 확정하지 않는다. Docker/WSL 재시작, context 전역 변경, API version 강제 지정은 하지 않았다.

외부 연결 전 read-only preflight에서 다음을 새로 확인했다.

- HEAD `240cb3982d6c05724d6a89e99aed66788d77d697`, 준비sourcehash와 현재 제품sourcehash 일치.
- 기존 image6개 ID·컨테이너 project/service 소유권·mount 범위 일치.
- PostgreSQL/Anvil 정상, initializer 정지. 기존 baseline DB payload 일치 및 block2 canonical hash `0xcd769ab844aef8474e0c0f926065d44e802fad95b6cac2b5cf810b7d6085b346` 일치.
- 전용 두 인증정보 존재 확인. 값·fingerprint 출력/복사 없음.

기존 live Compose에서 backend/web 컨테이너만 `create --no-build --pull never`로 생성했다. 이미지를 재빌드하거나 계약·DB·baseline을 재생성하지 않았다. **두 컨테이너는 시작하지 않았다.** backend의 isolated+egress, web의 egress network 및 고정 이미지/소유권을 대조했다.

실제 브라우저 증거 수집과 종료 제어를 위해 다음 실행 보조 도구를 추가했다. 제품 코드나 정책 변경은 없다.

- `deployment/kiwoom-live-run.py`: 기존 run/source/image/owner/mount/기존0원원장/baseline 검증, 비밀값 없는 credential 존재 검사, 기존 WATCHDOG를 두 고정 컨테이너 대상으로 ARMED 확인 후에만 start, 570/585초 제한, 종료 시 egress 분리 및 두 대상 정지 확인. 종료 미확인이면 BLOCKED. 명시적 실행 옵션과 최대120초의 fresh start deadline 필수이며, 이는 시장 OPEN 판정이나 자동 실행 승인이 아니다.
- `deployment/kiwoom-live-browser.mjs`: 실제 Chrome 한 탭·기본1m, 기존 USER 로그인, 개인 STOMP 구독, Faucet1회→BUY1회→SELL전량1회, 두 FILLED 개인 이벤트 및 SELL 이후 최종 포트폴리오 이벤트 대조, ADMIN 두 대사 조회. 응답 본문 완료 후 수집하며 JWT/비밀번호/raw transaction/서명 원문을 결과 파일에 기록하지 않는다. **이 실행 흐름은 아직 실제 실행하지 않았다.**

두 도구의 Python AST와 Node 구문 검사는 PASS이다. 별도 reviewer의 초기 필수 지적 두 건(SELL 이후 portfolio 이벤트 구분, 종료 실패의 PASS 오인)을 수정했고 첫 재검토에서 발견된 필수 수정 없음이었다. reviewer는 정적 소스/API/UI 계약 및 구문 검사를 확인했으며 Docker·credentials·실전 API에는 접근하지 않았다. 이 검토는 실환경 성공 증거가 아니다.

실행 직전 시각 재확인에서 Windows `2026-10-15T18:22:36+09:00`와 UTC09:22:36이 일치했다. 정규장 인수 전제를 충족하지 못하므로 **watchdog 실전 arming과 외부 연결 전에 중단**했다. 앞선 시각과 달라진 원인을 시각 동기화 결함 등으로 추정하지 않는다. 로컬 시각으로 provider의 marketStatus를 생성하거나 OPEN을 확정하지 않았다. Synthetic 대체 실행도 하지 않았다.

KST18:37의 최종 읽기 전용 확인:

| 항목 | 새 확인 결과 |
|---|---|
| Docker version/info Server | PASS · 모두28.4.0 |
| 신규 backend/web | PASS · 두 대상 모두 `Created`(한 번도 시작하지 않음) |
| 신규 PostgreSQL/Anvil | PASS · 둘 모두 healthy |
| initializer | PASS · 기존 Exited(143) 상태 유지 |
| 실제 backend/web watchdog ARMED | NOT_RUN |
| 실제 장중 연결 전제 | BLOCKED · 실행 직전 확인 시각18:22 KST |
| 키움 OAuth/REST/WS | NOT_RUN · 이번 작업 호출0회 |
| Faucet/Quote/BUY/SELL | NOT_RUN · 지급/주문0회, txHash 없음 |
| Receipt/Event/DB정산/개인WS/포트폴리오 | NOT_RUN |
| 자산 대사 MATCH/거래 전수 coverage | NOT_RUN |

기존 DB/Anvil은 계속 가동하며 state·baseline·private capture를 보존했다. 실제 backend/web이 시작되지 않았으므로 신규 외부 연결을 차단하거나 종료할 활성 대상은 없다. 기존 서비스 중지, 초기화, 재배포, 유료AI, commit/merge/push 없음. 첫 ps 출력의 Go-template 인용 오류는 조회 명령만 단순화해 다시 확인했으며 Docker 장애로 분류하지 않는다.

다음 작업은 같은 환경에서 다음 정규장 인수 재개 한 가지다. 새 준비 도구·baseline 생성 없이 fresh preflight→실제 대상 watchdog ARMED→현재 generation 정상0B 기반 가용성 확인 순서로 진행한다. 이번에 추가한 실행 도구는 아직 실전 실행 검증 전이므로 도구 구문 검사·별도 검토를 E2E PASS로 대체하지 않는다.

최종2차 독립 재검토에서도 발견된 필수 수정 없음이었다. 마지막 fresh start deadline 변경의 AST와 arming 전후 만료 차단을 확인했으며, 실전 호출이나 Docker 검증은 수행하지 않았다. root `melon-init`은 clean/HEAD `f069a1085fd7cfe1d8d75f7bb454fc20feb50ede` 유지, 기능 worktree HEAD는 그대로이며 기존 미커밋 변경을 포함한 status43항목을 보존했다.
