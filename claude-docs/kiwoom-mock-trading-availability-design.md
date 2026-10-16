# 키움 모의 거래 가용성 분리 — 최소 정책 변경 승인안

> 후속: 사용자가 본 정책을 승인했다. 아래는 설계 당시의 코드 조사 기록이며, 변경한 현재 코드와 실행 결과는 [구현·검증 기록](kiwoom-mock-trading-availability-implementation.md)을 기준으로 확인한다.

> 2026-10-14. feat/kiwoom-live-fixes/기준 integration240cb398. 사용자 방향 전환: 0s 수신은 완료 필수 조건에서 제외하고 추가 실관찰 중단. 기존 진단·private run·증거 보존. 이번에는 코드 읽기와 설계 문서만 작성했고 구현·테스트·API/DB/RPC 실행 없음.

## 1. 실제 차단 경로

- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomPriceProvider.java:44~73`: 현재 generation 프레임만 수락, 누적량/시각 역순 차단.0s 없는 정상0B도LIVE가격은생성하지만marketStatus는UNKNOWN이다. nonregular체결은CLOSED처리한다. current()는기존realTick/age/connected만보고가격품질을판정해,빠른재연결에서**이전generation의가격도LIVE처럼보일가능성**은별도거래gate에서막아야한다.
- `market/MarketPriceService.java:32~42`: CLOSED/STALE만차단하는공통조회이며UNKNOWN을OPEN으로변환하지않는다. DB-only·기존정산경계에공유되므로이메서드를포괄적으로강화/완화하지않는다.
- `market/MarketDataEligibility.java:15`: liveExternal은OPEN+LIVE만허용한다.
- `blockchain/oracle/PriceReportIssuer.java:issue/validateSnapshot`: 위검사+과거5초/미래2초, RPC견적/domain조회뒤다시검사하고최신상태도확인한다.가격계산에쓴snapshot은고정한다.
- `conditional/ConditionalOrderService.java:checkedMarket`: 동일OPEN+LIVE검사와DB판정시각의5초/미래2초검사. trigger와실행준비때재검증한다.
- `quote/PriceQuoteService.java:lockAndValidate`: 기존발급견적은소유자/ISSUED/만료/방향/입력량검사이며시장상태재검사는없다. **이번변경은신규발급/조건평가gate이지모든기발급견적의실행정책변경이아니다.**

## 2. 모의 거래 허용의 근거와 한계

공식 [0B API spec](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json)의290은장구분이며2는장중이다. 현재KiwoomProtocol.trade도290=2를regular로변환한다. 이번에는이필드하나만공식명세로확인했으며0s반복조사/추가실관찰은하지않았다.

이것은 **해당 체결의 세션 분류**이지 현재거래소OPEN·오늘휴장아님·종목거래가능의독립증명이아니다. 휴장캘린더/별도세션현재상태조회는현재없다. 신규시간표/휴일추정/외부서비스를추가하지않는다.

승인할정책은“현재연결의공급자장중체결이유효하고최신이면그가격을모의거래견적에사용”이다.0B로MarketStatus.OPEN을만들지않으며`UNKNOWN`과모의거래허용이동시에가능하다. 명시적CLOSED는이예외보다우선한다.

## 3. 정책 조건표 — 모두 서버에서 판단

| 조건 | 키움 신규 서명 견적·조건부 실행 판단 |
| --- | --- |
| 0s없음/UNKNOWN,현연결005930·0B·290=2,양수가격/수량,단조누적량,당일,시간호환,LIVE | 모의 거래 허용,시장상태UNKNOWN유지 |
| 수락된명시적CLOSED 또는nonregular체결에의한CLOSED | 차단;fresh0B만으로해제안함 |
| 명시적이지만미지원UNKNOWN시장상태/잘못된0s | 단순0s부재와구분해차단;fresh0B로덮어쓰지않음 |
| 장마감후새체결없음 | 기존품질/5초신선도차단.즉시마감확정이아님 |
| stale/degraded/initializing/조회오류/RESTbootstrap만있음 | 차단 |
| 미연결/종료/연결generation변경/이전세대snapshot만있음 | 차단;재연결후현세대새정상체결필요 |
| 과거세대프레임/중복/역순/누적량이증가하지않음 | 가용성근거갱신안함 |
| 290누락/미지원값/시간외/다른종목·NXT | 예외허용근거로사용안함 |
| 과거5초초과/미래2초초과/KST날짜전환 | 차단,기존허용치유지 |
| 견적RPC중재연결/명시적CLOSED/가격노후화 | 서명전재검증에서차단 |

CLOSED veto는확인한KST날짜와함께두며같은날재연결만으로지우지않는다. current marketStatus의generation경계는유지하되veto는모의허용gate에별도적용한다. 해제는기존과같이수락한명시적OPEN또는다음날의새근거평가다. 서버재시작시과거CLOSED를영속복원하는기능은이번범위에넣지않으며재시작후새현연결체결이필요하다.

**승인해야할한계:** 0s마감알림없이마지막장중tick이5초이내면마감직후짧은기간도모의발급이허용될수있다. 독립휴장근거가없으므로“휴장확정차단”이라고표현하지않고fresh장중체결이없으면차단한다고표현한다. 이것까지즉시/정확하게막으려면신뢰할수있는세션/캘린더근거가추가로필요하며현재최소범위로보장할수없다. 이미발급된30초보고서/견적의사용창도기존대로남는다.

## 4. 제안 구현 위치 — DB/계약 변경 없음

1. **MarketPriceProvider.java**: 내부읽기용`currentForTrading()` 기본메서드. 기존Toss/Synthetic는기존정책에맞는기본context를제공하므로각provider수정은피한다.
2. **내부 TradingPriceContext/readiness 자료형(신규 작은 파일)**: snapshot·허용여부·근거/차단사유·연결generation을묶는다. 기존MarketPriceSnapshot의9필드/모든생성자와DBschema는변경하지않는다. 사용자입력으로generation/허용여부를받지않는다.
3. **KiwoomPriceProvider.java**: 마지막수락tick의generation·290세션근거·날짜·CLOSED veto 보관, synchronized내가격과판정동시캡처. 연결상태/generation을캡처전후확인하고변화하면판정불가로차단한다. 이전generation의fresh가격을허용하지않는다.
4. **MarketPriceService.java**: 별도신규견적context조회/검증진입점. 기존current/requireTradableSnapshot/isSettlementAllowed는유지한다. MarketDataEligibility.liveExternal도바꾸지않아다른호출부의UNKNOWN을일괄허용하지않는다.
5. **PriceReportIssuer.java**: 고정가격context의가용성검증으로변경.기존5초/미래2초검사·RPC후재검증유지. RPC전후키움generation이다르면새tick이있어도이번서명은거절하고사용자재견적으로처리한다.보고서필드/EIP712/domain/30초TTL/수수료/최소수령량은유지한다.
6. **ConditionalOrderService.checkedMarket**: 같은내부가용성사용,기존DB시각신선도·target정수비교·quote재발급/입력잠금/취소·복구정책유지. Synthetic fixture예외/public차단도그대로다.
7. **최소표시정합성**: 기존MarketController 응답에모의거래허용여부·사유만추가하고웹state.js/main.js에서“시장상태확인불가·최신장중체결기반모의거래”로안내.0B로웹이OPEN/허용을자체추정하지않는다. 공개가격WS에같은안내를보내려면기존PriceEventPayload/MarketWebSocketPublisher의동일필드만추가한다.새endpoint/화면/차트기능은추가하지않고Android에는additive계약변경을기록한다.

표시필드는서버context와같은관측에근거하고개인키/원문보고서/운영상세를노출하지않는다. Toss/Synthetic의기존표시와허용정책은유지한다.개인WS/정산이벤트는손대지않는다.

변경하지않는곳: PriceQuoteService의소비/DB모델,OnchainOrderPreparation/Sender/nonce/raw,Oracle/Vault,receipt/event정산,자산/거래대사,Faucet,기존실행환경/secret,0s진단도구및과거증거. 견적생성gate수정으로기제출거래를FAILED처리하거나잠금을해제하지않는다.

## 5. 필요한 검증 — 이번에는 미실행

- KiwoomPriceProviderTest: UNKNOWN+현세대fresh290=2허용/시장UNKNOWN유지,REST-only차단,이전generation프레임/빠른재연결의oldtick차단,새tick복원,명시CLOSED+정상tick차단,CLOSED veto재연결유지,시간외/누락290/날짜전환/중복역순.
- MarketPriceServiceTest 및 PriceReportIssuerTest: age5초·future2초경계,동일context가격사용,견적RPC중닫힘/노후/연결종료·generation변경(새fresh tick있어도거절),Toss OPEN+LIVE와Synthetic기존회귀,TTL/서명/minimumOutput불변.
- ConditionalOrderIntegrationTest: 키움UNKNOWN조건부가격조건판단과신규quotegate일치,닫힘/세대변경미실행,기존fixture·취소/잠금/복구회귀.
- API/웹test: 시장 UNKNOWN과 모의 허용 표시의 분리, 신선도 상실 표시, 새 field 없는 기존 응답 호환, 개인/운영정보 비노출.
- 승인된구현후backend전체기본회귀·웹·Foundry 및신규격리Synthetic PG/Anvil E2E로기존정산호환확인,별도검토. 실제키움추가호출은여전히금지이며저장된807/1163tick자료와Mock을활용한다. Mock성공을실서버인수완료로표현하지않는다.

## 6. 승인 사항과 현재 상태

승인필요: 위키움전용모의거래gate,명시CLOSED veto의우선/재연결보존,연결generation을견적전후고정,기존API/WS의최소표시필드추가,휴장/마감즉시성/기발급quote사용창한계수용.

승인받으면 이 범위만 구현하고 외부 API 없이 검증한다. 0s-only 준비 run은 미실행으로 보존하고 실관찰 준비를 추가로 하지 않는다. 이번에는 설계만 작성했으며 제품 코드·테스트·기존 환경·private 증거는 변경하지 않았다.
