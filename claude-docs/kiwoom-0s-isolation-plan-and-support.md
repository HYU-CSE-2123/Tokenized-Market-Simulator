# 키움 0s 단독 구독 조사·승인안·기술지원 문의문

> 2026-10-14. feat/kiwoom-live-fixes, 기준 HEAD240cb398+기존 미커밋 수정. 이번 작업은 공개 공식자료/현재소스 조사와 문서 작성뿐이다. 실전호출·인증정보 접근·새private run 생성·테스트 실행·제품 변경 없음.

## 1. 결론과 현재 근거

혼합 구독을 같은 방식으로 반복하지 않는다. **공식 0s 단독 payload의 1회 관찰과 공급자 초기/복원 계약 문의**가 다음 조치다. 단독 성공은 사용할 수 있는 수신 경로를 좁히지만 혼합 오류를 단독 원인으로 확정하지 않는다. 단독 미수신은 초기snapshot미지원의 증명도 아니다.

기존 [첫 실관찰](kiwoom-live-readonly-acceptance-2026-10-14.md)과 [item=[] 재관찰](kiwoom-live-recapture-2026-10-14.md)을 보존한다. 후자는11:33~11:36KST, LOGIN/REG성공,0B807건,저장된MARKET_STATE0건이다. 이번 조사에서 새 capture를 실행하거나 원본 증거를 쓰지 않았다.

## 2. 공식 단독 예제와 현재 요청 비교

공식 [async 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/국내주식/실시간시세/subscribe_domestic_market_open_time_async.py) main의REG는 다음이다.

```json
{"trnm":"REG","grp_no":"1","refresh":"1","data":[{"item":[],"type":["0s"]}]}
```

현재는 같은REG의data에005930/0B 엔트리도 함께 넣는다. grp_no/refresh/0s item 형식은공식과같고 **단독과혼합 여부가 다르다**.

| 항목 | 공식 예제 | 현재 제품·진단 |
| --- | --- | --- |
| 등록 | 0s만 한 엔트리 | 0B와0s 두 엔트리 |
| LOGIN/PING | 공통 공식WS클라이언트 처리 | 직접LOGIN후ACK확인,REG,PING응답 |
| ACK 후 수신 | 공식예제는최대10메시지수집 | 제품은REG후connected,진단은시간/건수제한 |
| 재연결 | 예제본문에상태복원설명없음 | 제품은새generation,진단은승인상한따라retry0 |

실제 경로: KiwoomRealtimeClient.onText LOGIN처리→protocol.subscribe()(96행), REG수락→connected(98~105행). KiwoomProtocol.subscribe 및 deployment/kiwoom_diagnostic_core.py:59~62가혼합payload생성, deployment/kiwoom-diagnostic.py:189에서LOGIN후송신한다. 현재 CLI에는0s-only 선택지가없다. **현재capture명령을그대로재실행하면혼합구독이므로실행하지않는다.**

공식 [ws_client.py](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/core/ws_client.py)의subscribe/send는전달payload를JSON송신하고,초기시장상태를별도조회하거나생성하지않는다. 예제의10메시지수집은즉시10건발행보장이아니다.

## 3. 계약 재조사: 사실과 미확정

공식 [API가이드](https://openapi.kiwoom.com/m/guide/apiguide)·[spec의장시작시간(0s)](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json)를재확인했다. portal본문은이번web조회에서오류였고공식공개spec/예제를근거로사용했다.

- **확인:**0s는장전환관련알림이며215는장운영구분,20은HHmmss필드다. REG ACK의0은통신정상코드다.
- **미확정:** 구독직후현재상태snapshot을반드시제공하는지,상태변경시에만발행하는지,중간접속/reconnect시이전상태를재전달하는지. 검토한공식소스에보장문구나명시적복원절차를찾지못했으며그것이서비스의미지원증명은아니다.
- **미확정:**20이원래상태전환시각인지현재송신시각인지. 날짜도없어임의로당일과긴허용치를부여할수없다.
- **미확정:**0B+빈item0s 혼합등록의동작/0s별ACK. 단독공식예제가있다는것만으로혼합미지원이라고단정하지않는다.
- 공개공식repo이슈목록도확인했지만해당초기상태계약을확정할답변을확보하지못했다. 비공식블로그/다른OCX제품동작을REST계약으로대체하지않는다.

### 현재 관측의 한계

Observer.frame(140행)은type=0s이면서item이빈경우만MARKET_STATE로저장한다. 전회sidecar도빈item0s만수집했다. 원문을저장하지않았으므로 **0건은현재정제규칙에서의0건**이다. 다른item의0s가실제로있었다는증거도없지만그가능성을과거capture만으로배제할수없다.

다음실험에서는parser전의0s유형건수/시각/generation과item분류(EMPTY/MISSING/NONEMPTY/INVALID),형식검사한215/20을별도관측한다. 원문·임의item값·인증정보는저장하지않는다. 제품의KRX판정기준은변경하지않는다. REAL이ACK전오는경우도별도건수로구분한다.

## 4. 제안: 0s-only 읽기 관찰 1회 — 아직 미승인/NOT_RUN

- 승인후새`deployment/runtime/kiwoom-diagnostic-<UTC>-<random>`생성. 과거run재사용/덮어쓰기금지,credentials사용도새실행승인안에서만준비한다.
- 실전OAuth **최대1회**,시세REST/ka10080 **0회**,WS **최대1회**,관찰 **최대180초**,정제이벤트 **최대5000개**.
- LOGIN1→성공ACK→위공식0s-only REG1. 0B/다른종목/계좌/주문등록없음.0s는시장전체알림이므로005930종목item을넣지않는다.
- PING응답은허용. retry/reconnect/추가token/두번째REG/혼합fallback없음. 인증·ACK거절/연결종료/timeout/상한도달시즉시종료,부분증거보존.
- 0s수신시점과형식·215/20·나이·generation을확인하되정책비호환값도정제관측으로남긴다. 전체180초안의초기·후속여부를구분한다.
- 기존도구에진단전용명시적0s-only profile 및위허용필드관측을최소추가하는준비가필요하다. **사용자승인후Mock/보안검증·별도검토를먼저하고외부실행한다.** 제품구독/시장정책은이승인으로바꾸지않는다.
- 기존계정서비스/IP확인은재요청하지않는다. 실제활성WS충돌이발견되면종료하지않고보류한다.

이실험은0B와차트REST를제거하므로엄밀한단일변수동시대조실험은아니다. 성공해도혼합등록과REST수집부하를단독원인으로가르지못한다. 다만공식단독경로의가능여부·필터링위험·초기수신시각을확인할수있다. 차트조회제거는비용/부하최소화를위한것이며혼합반복은하지않는다.

| 단독 결과 | 해석과 다음 조치 |
| --- | --- |
| 초기0s수신/3/시각호환 | 단독초기수신경로근거확보. 제품구독분리설계후별도승인된SpringBoot연결검증;모든시점의bootstrap보장아님 |
| 0s수신/20오래됨 | 수신경로확보,시각계약확인필요.5초완화없이공식답변대기 |
| 0s수신/3아님 | 공식상태기록,OPEN불확인.현지장중시각으로덮어쓰지않음 |
| 0s유형수신/제품parser미수락 | item/형식/ACK순서차이를특정해공식규격확인후최소수정안 |
| ACK성공/0s유형0건 | 단독에서도관찰안됨. 반복단독180초금지,기술지원답변또는별도승인된장전환구간관찰 |
| 인증/ACK/연결실패 | transport문제보고,추가호출없이중단 |

0B를구독하지않으므로LIVE/fresh가격및0s와0B동일연결검증은NOT_RUN이다. 단독실험만으로거래연동완료를선언하지않는다. 제품분리시여러REG ACK처리를기존단일connected조건과함께설계해야한다.

## 5. 기술지원에 그대로 전달할 문의문 — 아직 발송하지 않음

제목: 실전 REST WebSocket 장시작시간(0s)의 장중 초기 상태·혼합 등록·재연결 복원 계약 문의

실전서비스신청/IP등록은확인된환경입니다. 실제주식주문은없고시세조회만수행했습니다. wss://api.kiwoom.com:10000/api/dostk/websocket에OAuth발급후LOGIN합니다.10월14일09:40:43~09:43:43(KST)과11:33:25~11:36:25(KST)에각각독립연결1회,LOGIN/REG성공을확인했습니다. 첫연결0B1163건/두번째807건을받았지만저장된0s시장상태는0건입니다. 두번째REG는다음과같고송신반환과ACK를확인했습니다.

```json
{"trnm":"REG","grp_no":"1","refresh":"1","data":[{"item":["005930"],"type":["0B"]},{"item":[],"type":["0s"]}]}
```

1. 0s는등록직후현재215상태를보내나요,장전환시점에만발행하나요?장중늦은연결에서무수신이정상인지,초기전달보장이있다면지연상한을알려주세요.
2. 공식단독item=[]와위혼합data를동일하게지원하나요?빈item의시장전체등록은별도REG/grp_no로분리해야하나요?REG성공코드가개별type등록성공까지뜻하나요?
3. 0s응답item은항상빈문자열인가요?누락/null/다른시장식별자가올수있나요?REAL이REG ACK전도착할수있나요?
4. 20은장상태가전환된시각인가요,해당알림송신시각인가요?장중초기snapshot에09:00:00등과거시각이오는지와거래일/타임존확인방법을알려주세요.
5. WS재연결/토큰갱신후현재상태복원은어떻게하나요?명시적초기조회/조회용REST/TR이있다면ID·필드·사용절차를알려주세요.일반0B수신으로OPEN을추정하지않고공급자상태로확인하려는목적입니다.
6.0s-only구독에서도장중무수신이면어떤장전환구간과payload로검증해야하나요?KRX/NXT/선옵알림범위를구분하는공식방법도부탁드립니다.

원문시세응답·token·AppKey·Secret·인증fingerprint는첨부하지않습니다. ACK코드/정제시각/허용payload/건수만제공하며계정식별정보가필요하면공식보안문의경로로별도제공하겠습니다.저장필터상빈item만관측한한계가있으므로wire-level전체0s부재를입증했다고주장하지않습니다.

## 6. 연동 완료 경로와 승인 경계

문의는사용자가공식고객센터/API기술지원또는공식repo에제출할초안이다. 에이전트가외부게시/문의전송하지않았다. **최소추천은문의와단독실험승인을병행**하는것이다.

초기상태가공식제공되면그계약대로최소adapter수정→Mock회귀→새격리SpringBoot읽기검증→별도승인된거래인수로진행한다. 초기상태가전환이벤트뿐이라면공식전환관찰/복원수단을확인한다. 복원수단이없으면재시작직후UNKNOWN이라는가용성한계를사용자가수용할지별도결정해야하며단독등록수정만으로해결됐다고하지않는다. 시간표·0B추론을자동대안으로추가하지않는다.

이번완료: 요청비교·공식계약재조사·관측한계분류·실험승인안·문의초안.이번실전호출0/새run0/테스트0/제품변경0,기존증거·DB·Anvil·키보존.문서-only조사이므로별도구현review는실행하지않았고과거PASS와구분한다.
