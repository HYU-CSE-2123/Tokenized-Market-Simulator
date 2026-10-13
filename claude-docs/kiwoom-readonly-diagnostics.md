# 키움 읽기 전용 진단 도구 구현·실행 안내

> 2026-10-14. 기능 브랜치feat/kiwoom-readonly-diagnostics, 입력통합860885c. 실제 broker/API/auth정보 사용0. 현재 검증은 Mock이며 실제0s 제공/봉경계는 NOT_RUN이다.

## 변경 범위

- `deployment/kiwoom-diagnostic.py`: init/mock/capture CLI, 전용 runtime의 private credentials.json, 허용 시에만 고정 HTTPS/WSS 연결, 호출/시간/프레임/로그 크기 제한, exclusive safe events.jsonl/report.json. 별도 Python 프로세스이며 backend/DB/Anvil/거래코드를 호출하지 않는다.
- `kiwoom_diagnostic_core.py`: 순수 파서와 generation/장중초기상태/완전tick-candle 시작·종료가설/asOf 후보 위험 분석. 실패·근거부족을 INCONCLUSIVE로 구분하며 실제 거래 가능성 PASS를 선언하지 않는다.
- `test_kiwoom_diagnostic.py`와 `fixtures/kiwoom-diagnostic-mock.json`: 공식 필드와 가짜 민감값으로 whitelist·재연결·시장상태·OHLCV/누락·한도·무외부연결을 검증한다.
- `requirements-kiwoom-diagnostic.txt`: live mode에서만 필요한 기존 로컬 `websockets==16.0`. Mock은 Python stdlib만 사용한다. 자동 설치나 네트워크 dependency 다운로드는 없다.

제품 거래/Oracle/정산/TTL·5초/미래2초/AI코드 및 기존 runtime 변경은 없다. capture의 시간 호환 판정은 현재 제품을 설명하기 위한 진단이며 제품 허용치를 바꾸지 않는다. 공식 [키움문서](https://openapi.kiwoom.com/m/guide/apiguide), [공식WS샘플](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/core/ws_client.py), 현재 설치 버전의 [websockets16.0 문서](https://websockets.readthedocs.io/en/16.0/reference/sync/client.html)를 기준으로 확인했다. 공식 문서 조회는 실제 broker API 호출과 다르다.

## 보안·외부 실행 경계

외부 연결 기본OFF. capture는 `--allow-external`과 양수의`--max-rest-requests`/`--max-ws-connections`가 모두 있어야 설정을 읽는다. REST상한10/WS상한2, 연결당5~180초, 수신프레임/저장event각 최대20000, 최대1MiB응답, REST/WS연결timeout5초다. 계획된 token1+chart수×연결수가 REST예산보다 크면 사전 차단한다. PING도 프레임수/송신 한도에 포함한다. 무한 자동 재연결·pagination·token재발급 없다.2연결을 명시하면 첫 관찰 후 연결을 닫고 새generation으로 한 번 더 연결한다.

mode=real/demo로 공식endpoint만 선택하며 임의URL·계좌·주식주문 API는 없다. REST는 token au10001와 삼성전자005930의 ka10080 1분 비조정가격 조회만 가능하다. redirect/환경proxy를 사용하지 않는다. transport logger는 비활성화한다. 실제 token/키는 메모리 및 전용credential파일에서만 사용하고 원문HTTP/WS오류·Authorization·LOGIN token·return_msg·모르는 vendor필드는 출력·기록하지 않는다. CLI오류도 입력값을 echo하지 않는다.

events에는고정종류/결과, generation/수신UTC, 검증된시장상태/관측시각/신선도호환,005930가격/체결량/누적량과안전한OHLCV/native시각/asOf후보만 기록한다. 잘못된0s도eventType=0s의거절기록으로표시하여미제공과구분한다. 원문key는저장하지않는다. init은새이름의ACL-private디렉터리와빈credential설정을만들고기존파일을덮지않는다. capture는같은run재실행을금지한다. WindowsACL은현재사용자와SYSTEM만허용하는지실행전검사,POSIX는owner/mode를검사한다.

## 오프라인 실행 — 지금 허용된 범위

통합worktree 루트에서:

```powershell
python -m unittest discover -s deployment -p 'test_kiwoom_diagnostic.py' -v
python deployment/kiwoom-diagnostic.py mock --fixture deployment/fixtures/kiwoom-diagnostic-mock.json
python deployment/kiwoom-diagnostic.py init
```

init은외부호출/실제키사용없이빈설정을만든다. 출력DIAGNOSTIC_RUN의`credentials.json`이**이번도구용 키 입력 위치**다. 기존 backend/.env를쓰거나import하지않는다. 사용자는향후승인후그파일의mode/appKey/secretKey만편집한다. 키를명령줄이나일반환경로그에붙이지않는다. Mock는자격파일을읽지않는다.

## 향후 별도 승인 후 실제 실행

```powershell
# 아래 capture는 실제 외부 호출 승인 이후에만 실행한다.
python deployment/kiwoom-diagnostic.py capture --run 'DIAGNOSTIC_RUN의 실제 경로' --allow-external --max-rest-requests 4 --max-ws-connections 1 --seconds 180 --max-events 5000 --chart-snapshots 3
```

이설정의최대예산은token1+1분봉3=REST4회/WS접속1회,LOGIN1·REG1과bounded PING송신이다.180초동안시작/중간/종료10초전 native봉을조회하고tick도수집한다.2generation관찰은REST최대7/WS2·최대360초로별도승인한다. 기존계획의현재가/다른분주기/일봉10회profile전체를자동실행하지않는다. 계정·서비스시세권한·접근IP와real/demo키구분,실제호출수·비용조건은사용자가먼저확인한다. 정확한broker요금은미확정이며무료라고가정하지않는다. 유료AI0회다.

## 판정과 한계

- LOGIN/REG거절·0s미제공·같은generation시장확인·초기60초수신을분리한다. 오래된0s는관찰하되시간호환불가로표시한다. 최신시장상태가CLOSED거나재연결에새0s가없으면정상OPEN을상속하지않는다. 표본EligibilityEvidence는현재제품의모든거래실행조건을보증하지않는다.
- 봉경계는같은generation의양쪽경계witness·순차누적거래량일치·중복/누락검사를통과한완료봉에서만 Hstart=[T,T+1m)/Hend=[T-1m,T)를비교한다. 둘다관측되고한쪽만OHLCV가맞으면 START/END_SUPPORTED,둘다맞으면INCONCLUSIVE,둘다틀리면MISMATCH다. 부족한tick/절단/누락이면INCONCLUSIVE다. 실제새주문/자산보정을하지않는다.
- 미완성봉은시간경계를새로확정하지않고같은연결의완료봉에서확보한가설을사용한다. asOfCandidate는제품처럼요청시작시각을기록하며vendor보장watermark가아니다. prefix거래량보다적거나많으면누락/중복위험을표시하되제품버그원인을단정하지않는다. 실제웹REST/STOMP검증을대신하지않는다.
- 전체원시WS/HTTP본문은파일에저장하지않으므로오류시원문공유로조사하지않는다.약관상시장데이터보관/공개허용범위는실환경승인시확인하며sanitized자료도private runtime로만보관한다.

## 테스트·검토 상태

최초진단23/23·기존Phase A guard15/15 PASS,CLI Mock출력에서generation1SUPPORTED/generation2INCONCLUSIVE 확인. 추가 malformed0s 구분 테스트 이후 최종수치는후속기록한다. 테스트의가짜키/토큰은실제자격증명사용이아니다. Mocktransport lifecycle만실행했으며실제HTTPS/WSS·실제키·DB/체인·AI는NOT_RUN이다. 독립검토/커밋/통합결과는완료후추가한다.

최종 수정 source에서 **`python -S` 진단26/26 PASS, 기존Phase A guard15/15 PASS, SKIP/FAIL0**, CLI오프라인fixture도PASS. 최초import구문오류는수정후실행했고독립검토에서stdlib테스트의설치패키지의존2건을발견하여sys.modules의가짜모듈로대체했다. OTHER체결후새0s까지닫힘유지회귀도추가했다. 이전23/24/25개결과는최종26개의재실행으로표현하지않는다. WindowsACL조회오류시fail-closed와자격파일읽기차단을검증한다.

독립 첫 재검토: **발견된 필수 수정 없음**. 검토자가 직접 `python -S` 진단26/26, Phase A guard15/15 및 CLI Mock replay를 실행했다. 실제 API/자격 증명/runtime/DB/체인 및 실제 Windows ACL 생성은 검토자가 검증하지 않았다. 구현자는 신규 빈 private 설정 생성과 외부 승인 옵션 없는 capture의 `EXTERNAL_DISABLED` 종료만 확인했다. 이번 작업의 backend/web/Foundry/제품 E2E 재실행은 NOT_RUN이며 기존 Phase A 5/5 결과를 새 실행 결과로 사용하지 않는다.

실제빈설정 scaffold의init만 실행했다: `deployment/runtime/kiwoom-diagnostic-20261013184802-2ab7ce66/credentials.json`이현재준비된입력위치다. mode=real, appKey/secretKey는빈값이며실제정보를입력하거나인증에사용하지않았다. private runtime와기존인수증거는Git에서제외한다. 필요하면사용자가init으로새진단run을만들수있으며기존run에capture로그가생기면재사용하지않는다.

## 커밋·통합 및 최종 재실행

- 기능 브랜치 `feat/kiwoom-readonly-diagnostics`: `a772d37`.
- `integration/additional-features` 병합: `3ca4945c4f4e1400e076758d9835bfe81f8e1dd3`.
- 위 병합 소스에서 새로 실행한 `python -S` 진단 26/26, Phase A guard 15/15, CLI Mock replay 모두 PASS, FAIL/ERROR/SKIP 0. 이 기록 이후 변경은 문서뿐이다.
- 커밋 대상은 도구/테스트/공식 형식 기반 가짜 fixture/의존성 안내와 문서 총 9개 파일뿐이다. 실제 `.env`, credential, DB dump, chain state, runtime, 로그와 빌드 산출물은 포함하지 않았다. staged private-key/token 패턴 검사 0건, 새 빈 credential의 Git ignore 확인.
- `melon-init`은 `f069a1085fd7cfe1d8d75f7bb454fc20feb50ede` 그대로다. 기존 acceptance/개발 환경/제품 정책 변경 없음, 실제 broker 인증 및 데이터 호출·유료 AI 0회.
