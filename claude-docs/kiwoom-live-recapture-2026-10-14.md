# 키움 item=[] 장중 재관찰 — 읽기 연결 PASS / OPEN 인수 BLOCKED

> 2026-10-14. feat/kiwoom-live-fixes, HEAD240cb398 + 승인된 미커밋 P0/P1 소스. 새 private run `kiwoom-diagnostic-20261014022735-a84f1564`. 기존 첫 capture 및 환경은 보존했다.

## 1. 승인·실행 전제

- 사용자가 승인한 OAuth1 + ka10080최대3, WS1,180초,5000events,KRX005930만 실행했다. 재시도·재연결·추가token 없음.
- prepared.json의 sourceHashes와 실제 diagnostic/core/KiwoomProtocol 소스 및 정확한 상한을 실행 전에 대조했다. 종료 후에도 소스 일치 확인. 키/Secret/Token/인증 fingerprint는 출력하지 않았다.
- 기존 서비스/IP 등록 사용자 확인을 그대로 인정했으며 같은 확인을 다시 요청하지 않았다. 호스트의 Established TCP 목록 조회에서 remote port10000 연결 후보0건 확인. 다른 PC·Docker 내부·동일 계정의 모든 세션 부재까지 확인한 것은 아니다. 기존 세션을 종료하지 않았다.
- 새 run에 기존 capture/report가 없음을 확인했다. 이전 run은 재사용하거나 덮어쓰지 않았다.

## 2. 실제 실행과 증거 방식

기존 진단 도구의 capture()·LiveTransport·Budget·Observer를 사용했다. 전송·수신을 메모리에서 감싸 허용된 **REG payload만** send() 정상 반환 직후 기록하고, 수신0s의215/20만 정해진 짧은 형식으로 별도 정제 기록하도록 했다. LOGIN/Token/원문응답은 기록하지 않았다. 제품/진단 소스 파일을 수정하거나 요청·재시도 정책을 바꾸지 않았다.

준비 payload와 실제 send에 전달한 REG가 동일함을 검사했다. 서명이나 거래 명령이 아닌 다음 시세 등록만 전송했다.

```json
{"trnm":"REG","grp_no":"1","refresh":"1","data":[{"item":["005930"],"type":["0B"]},{"item":[],"type":["0s"]}]}
```

송신 정상 반환과 REG ACK는 등록 요청 수락의 증거다. **0s 초기 전달 또는 item별 구독 성공의 별도 보장으로 해석하지 않는다.**

## 3. 호출·시각·건수

| 항목 | 이번 실제 결과 |
| --- | --- |
| capture 종료 | COMPLETE, truncation없음, 프로세스exit0 |
| OAuth | 1회 성공, token검사 후 LOGIN/차트 진행 |
| ka10080 | 3회 성공, 각20봉, 합60 CANDLE |
| REST / WS | 총4회 / 연결1회 |
| WS 수신·송신 frame | 667 /19, 송신은LOGIN/REG/PING 응답 포함 |
| 저장 이벤트 | 870: CONNECTED1/LOGIN1/REG1/CANDLE60/TRADE807 |
| MARKET_STATE / REJECTED | 0 /0 |
| CONNECTED KST | 11:33:25.202578 |
| LOGIN ACK KST | 11:33:25.241071, OK |
| REG 송신 KST | 11:33:25.242369, sendReturned=true |
| REG ACK KST | 11:33:25.252388, OK |
| 첫·마지막0B 수신 KST | 11:33:25.710205 /11:36:25.114917 |
| chart 요청 KST | 11:33:25.252388 /11:34:50.217664 /11:36:15.216829 |

180초는 도구의 단일 WS 관찰 상한이다. 마지막tick시각만으로 전체 프로그램/OAuth 시간을180초라고 주장하지 않는다. WS 종료 이후 새 연결/외부 요청 없음.

## 4. 필수 판정

| 항목 | 판정 | 근거·한계 |
| --- | --- | --- |
| item=[] REG 실제 송신 | PASS | preparedpayload와sendpacket동일,send정상반환 기록 |
| OAuth·LOGIN/REG | PASS | 각1회, ACK성공 |
| 관찰 중0s수신 | FAIL(관찰 목표 미충족) | 180초동안0건;공급자규격위반판정아님 |
| 0s215와20 | INCONCLUSIVE | 수신이없어 실제 값 없음.3/현재시각을 만들지 않음 |
| 동일 연결0B | PASS | 807건 모두generation1,REGULAR807 |
| 0s와0B 동일generation | INCONCLUSIVE | 0s가없어 두유형의연결동일성검증불가 |
| 0B fresh / LIVE자료조건 | PASS(진단근거만) | 807건모두productTimeCompatible=true.마지막observedAt11:36:24,수신까지1.114917초 |
| 제품OPEN+LIVE+fresh | BLOCKED | report.stateReceived=false,productEligibilityEvidence=INCONCLUSIVE. 실제SpringBoot실행없음 |
| 실제서버/웹/견적/거래정산 | NOT_RUN | 이번승인에포함되지않음 |

0B의LIVE자료조건과실제제품서버LIVE응답검증은다르다.가격시각은확인됐지만같은generation의명시적시장OPEN근거는없다.시간표나체결수신으로OPEN을대체하지않았다.

부가적기존analyze: native봉START_SUPPORTED1/INCONCLUSIVE59. 과거완료봉결과를이번새봉결과로재사용하지않았으며, 이번단계의핵심은0s재관찰이다.

## 5. 원인과 다음 조치

- **확정:** 기존[""]와공식예제[]차이를수정한payload를실제보내도11:33장중연결180초에서는0s가없었다.구독배열차이만수정하면초기상태제공문제가닫힌다는가정은이번결과로뒷받침되지않는다.
- **미확정:** 상태변화때만발행하는특성,초기스냅샷미지원,혼합REG/item별등록처리등어느것이원인인지이capture만으로확정하지못한다.REG전체승인은0s별등록/초기전달입증이아니다.
- **유지:** 시장상태5초/미래2초,당일/connectiongeneration,OPEN+LIVE+fresh,OracleTTL·정산정책그대로.문제해결을위한자동재연결/상태추론/추가REST없음.
- **최우선문의:** 키움에0s구독직후현재215상태제공여부,20이전환시각인지송신시각인지,장중재연결복원수단,0B와빈item0s의혼합REG지원여부를확인한다.사용자는계정등록을다시할필요가없다.
- **별도승인후관찰후보:** 공식0s-only구독으로혼합등록변수를분리하거나,다음장전환을포함하는구간에서0s수신을관찰한다.각각새run/호출상한/관찰시간을사전승인받아야하며이번에는실행하지않았다.장전환관찰성공이장중늦은연결bootstrap까지보장하는것은아니다.
- OPEN증거또는공식복원계약을확보하기전에는SpringBoot거래인수로넘어가지않는다.읽기전용서버연결검증도별도실행범위다.

## 6. 보존·제한

새private run에prepared.json/events.jsonl/report.json/allowed-send-state-evidence.json보존. marketStateFields=[]이며없는215/20을기록하지않았다.모든private파일은Git제외,기존capture변경없음.

실제주식주문·로컬모의BUY/SELL·DB/Anvil접속/변경·유료AI·배포0.신규제품코드/정책수정없음.이번관찰/문서는별도구현검토를실행하지않았으며,이전P0/P1독립검토·Mock회귀와이번실관찰을구분한다.현재dirty사용자파일과기능변경은임의커밋/병합하지않았다.
