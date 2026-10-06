# 공개 포트폴리오용 국내 주식 데이터 공급자 조사

> 2026-10-05 · 조사/제안만 수행. 배포 구현 보류, Toss provider 유지. 계정 생성·문의 전송·구매·API 실호출·구현 없음.

## 결론과 확정 수준

공개 국내 시세를 공급할 라이선스 경로는 있다. 그러나 공개 자료만으로 개인 포트폴리오의 삼성전자 시세 표시 + REST/STOMP 전달 + 모의 토큰 가격 산정 + DB 저장 + 온체인 기록까지 저비용으로 바로 허용한다고 확정할 공급자는 찾지 못했다. 공급자가 없다는 뜻이 아니라 별도 계약/서면 확인이 필요하다는 뜻이다. API 수신, 화면 표시, 외부 재배포, 비표시 계산, 장기 저장/재생, 공개 블록체인 기록은 서로 다른 권리다.

우선 코스콤에 삼성전자 1종목의 실시간/20분 지연/제한된 과거 분봉 또는 틱 replay를 동일 사용 설명으로 견적 문의하는 안을 권장한다. 공개용 공급자가 결정되기 전 simulated 공개 배포를 확정하지 않는다. Toss 실시간 구현은 본인용 비공개 환경에서 유지하며, 허용되지 않은 Toss 데이터를 공개 DB/CSV/replay에 복사하지 않는다. 법률 자문이나 실제 계약 승인으로 이 조사 결과를 표현하지 않는다.

## 후보 비교와 공식 근거

### KRX/코스콤 — 실시간/지연 공급·계약 문의 1순위

[KRX 상품 브로슈어](https://data.krx.co.kr/inc/datasale/Market%20Data%20Product%20Brochure.pdf?v=20250732)는 유가증권 주식 등 실시간 시장정보, 인터넷 TCP 직접 수신 및 승인 정보사업자를 통한 간접 수신을 안내한다. 외부제공사는 일반 정보이용계약이 필요하며 옵션3은 일반대중 대상 웹/앱 체결가 서비스다. [공식 수신 안내](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA003.jsp)는 재배포·프로그램 개발 등 참고 외 용도에 별도 계약을 안내한다.

중요 제한: 공개 [정보이용정책 PDF](https://data.krx.co.kr/inc/datasale/Market%20Data%20Usage%20Polices_ko.pdf?v=20230121_1)는 본문 2024-01-01판이다. 옵션3은 조회 방식이며 제3자에게 가공/저장/재분배 수단(API 등)을 제공하는 것을 금지하고 내부 이용도 서비스 개발·운영·테스트·품질관리로 제한한다. 브라우저 표시를 위한 REST/STOMP가 어떻게 승인되는지, 모의 체결 계산이 해당 범위인지 별도 확인해야 한다. 옵션3만 구매하면 현재 구조 전체가 허용된다고 결론내리지 않는다. 최신 계약/정책은 계약담당자 확인 전 미확정이다.

코스콤 [CHECK 안내](https://www.check.co.kr/eng/)는 RESTful API/WebSocket과 고객 웹 표시 서비스를 안내하지만 단말 구독 요금을 공개 서비스/API 재배포 견적으로 사용하지 않는다. 현재 서비스의 실제 승인 product/프로토콜/가격은 확인하지 못했다. 삼성전자 1종목이라 계약 기본료가 1/N로 줄거나 교육용 면제가 된다고 가정하지 않는다. KRX만인지 NXT 통합인지도 공급 범위 확인 대상이다.

공식 문의: marketdata@koscom.co.kr. 일반 정보이용계약·인터넷/API vendor 접근·정액/변동/초기/망 비용·지연 시세 선택을 함께 요청한다. 문의는 아직 보내지 않았다.

### LSEG — 재배포 계약이 있는 대형 vendor 후보

[공식 재배포 서비스](https://www.lseg.com/en/data-analytics/market-data/data-redistribution)는 실시간/가격/참조/Tick History와 client-facing 플랫폼을 위한 계약·권한 관리 경로를 제공한다. 한국 영업 문의도 있다. 다만 삼성전자/KRX/NXT의 정확한 필드·지연·분봉/틱 깊이·현재 애플리케이션 권한·최소 견적은 미확인이다. 글로벌 커버리지만 보고 해당 종목이 필요한 수준으로 제공된다고 확정하지 않는다. 저비용 포트폴리오에는 후순위라는 것은 견적 결과가 아닌 서비스 성격에 따른 판단이다.

### Twelve Data — 국내 EOD 후보, 실시간 대체가 아님

[XKRX 공식 페이지](https://twelvedata.com/exchanges/xkrx)는 국내 주식 지연을 EOD, 최소 개인 Pro/사업 Venture로 표시한다. [사업 가격표](https://twelvedata.com/pricing-business)는 Venture external display와 From $149/mo 문구가 있으나 선택 credit 구성 본문에는 $499/$414도 보인다. 최저 홍보 문구를 우리 사용의 총 견적으로 확정하지 않는다.

[2026-01-01 약관](https://twelvedata.com/terms) 2.2/2.3: external display/redistribution은 명시적 plan/add-on/서면 권한, non-display는 해당 구독 범위, 파생 금융상품 생성은 명시적 서면 허용, cache 기간 제한도 있다. external display plan과 raw REST/STOMP 재배포 권한은 같다고 가정하지 않는다. 국내 EOD 사용·보관/replay·모의 토큰 산정의 허용 범위와 가격을 서면 확인해야 한다. 비영리단체 혜택도 개인 비상업 포트폴리오에 자동 적용되지 않는다.

### EODHD — 국내 historical 후보, 저가 개인 플랜은 공개 권한이 아님

[KO 거래소 페이지](https://eodhd.com/exchange/KO)가 한국 거래소 historical 공급을 안내한다. 삼성전자 symbol·분봉/틱 가용성·원천/수정주가/커버리지는 별도 확인 대상이다. [commercial 이용 안내](https://eodhd.com/financial-apis/commercial-vs-personal-license-use)는 일반 가격표의 개인 이용과 별도 commercial 계약을 구분한다.

[공식 사업 가격표](https://eodhd.com/commercial-pricing)의 월납 Internal $399, Enterprise $2,499는 표준 패키지 참고값이며 개인 가격 $19.99를 공개 사용 견적으로 쓰지 않는다. [약관](https://eodhd.com/financial-apis/terms-conditions)과 개별 Data Services Agreement에서 외부 표시/raw 전달/replay/모의 가격/온체인 기록을 확인해야 한다. Enterprise 구매가 이 프로젝트의 모든 권리를 자동 승인한다는 결론도 아니다. 한국 실시간 WS 후보로는 확인하지 못했다.

### 한국투자증권 KIS — 개인 API 교체만으로는 해결되지 않음

[공식 제휴 안내](https://apiportal.koreainvestment.com/provider)는 제3자 서비스와 본인 계좌 이용을 구분하며 KRX/해외거래소 정보이용계약을 요구한다. 제도권 금융회사 등 제휴 자격 제한도 명시한다. 따라서 개인용 실시간 REST/WS를 Toss 대신 붙이는 것은 재배포 해결책으로 권장하지 않는다. 증권사의 모의투자 API 제공은 자신의 공개 모의투자 앱에 대한 시세 이용권과 다르다.

### 금융위원회 공공데이터 — 무료 공개 replay용으로 채택 불가

[현재 금융위원회_주식시세정보](https://www.data.go.kr/data/15094808/openapi.do), 페이지 수정일 2026-09-07: 무료, 공공누리4유형이며 상세 설명에 비상업 여부와 무관한 제3자 무단 제공/재배포 금지가 있다. 요약 metadata의 '실시간'과 달리 상세 설명은 일1회·기준일 다음 영업일 오후1시 이후 제공을 명시한다. 표시 metadata만 보고 실시간/재배포 자유라고 판단하지 않는다.

API 키 자동승인, 출처 표시, [공공누리4유형](https://www.kogl.or.kr/info/licenseType4.do) 확인만으로 제3자 제한을 해결하지 않는다. 본인용 연구 자료와 공개 재생용 자료를 구분한다. 일별 OHLCV로 실제 intraday tick/1분봉을 복원할 수도 없다.

## replay 대안의 현실성

권한을 확보한 실제 과거 분봉/틱의 고정 기간을 수입해 순서대로 재생하면 외부 실시간 호출 없이 실제 시장 움직임 기반의 chart/quote/정산/복구를 시연할 수 있다. 장외 시간에도 실행하고 같은 사건을 재현하는 장점이 있다. 과거라는 이유로 재배포 제한이 사라지지는 않는다. [KRX 구입 안내](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA001.jsp)는 목적 심사 후 판매·수령과 학술 목적 학생/교직원 등의 50% 할인 가능성을 안내하지만 일반 구매나 할인은 외부 재생 허용 증거가 아니다. 제한된 dataset의 공개 replay 라이선스를 따로 문의한다.

| 방식 | 실제 데이터성 | 시연/비용 | 핵심 제약 |
| --- | --- | --- | --- |
| 계약된 실시간 | 현재 실제 움직임 | 장중 시연·계속 사용 계약 | 외부 표시/feed·비표시 산정·저장/온체인 승인 |
| 계약된 지연 | 실제 움직임, 지연 명시 | 가격은 견적 후 비교 | 지연 ≠ 무료; live 관측/견적 정책 변경 필요 |
| 계약된 분봉/틱 replay | 실제 과거 움직임 | 고정 dataset/상시 재현, 구매·라이선스 비용 미확정 | replay/장기 보관/공개 feed·온체인 권한 |
| EOD replay | 실제 일별 OHLCV | intraday보다 거친 시연 | 실제 1분봉/틱을 생성했다고 표시 금지 |

replay에서 미래 봉을 미리 공개하거나 하루 고저를 조기 사용하지 않는다. 보간한 가격은 실제 틱이 아니며 별도 synthetic 파생 모드에 해당한다. raw dataset의 Git/public download 업로드는 별도 권한 없이는 하지 않는다. provider 중단 시 몰래 simulated로 바꾸는 fallback은 제안하지 않는다.

## 현재 코드에 붙이는 방법 — 설계 제안

현재 단일 MarketDataProvider라는 타입은 없고 `market/provider/MarketPriceProvider.current()`와 `MarketCandleProvider.candles()`의 두 경계가 있다. `MarketPriceService`/`MarketCandleService`가 사용하며 설정으로 Toss 또는 simulated Bean을 선택한다.

실시간 공급자의 REST 초기 snapshot + 공급 stream/candle adapter를 새 provider package로 추가하는 구조가 맞다. 계약 프로토콜이 WebSocket이 아닐 수도 있으므로 SDK/TCP/REST는 실제 계약 후 결정한다. 신규 가격+차트 공급자를 함께 선택하고 mSEC↔005930/KRW mapping, 날짜/KST/봉 시작, previousClose/기업행위·volume 정의/장 상태·stale를 정규화한다. 현재 REST/STOMP endpoint와 거래 소유권·정산 경계는 유지한다. 가격/차트를 별도 원천으로 섞을 때도 양쪽의 라이선스와 정의가 맞아야 한다.

replay는 provider 추가 외에도 시간 의미의 작은 설계 변경이 필요하다. `PriceReportIssuer`는 관측5초 이내/미래2초 이내를 검사하고 validUntil=observedAt+30초로 서명한다. 과거 timestamp를 그대로 전달하면 거절된다. 과거 가격을 현재 실시간 가격인 것처럼 시각만 바꾸어 위장하지 않는다.

- 실제 원본 시각 sourceTimestamp, runtime 재생 위치/시각, dataset ID/hash/version, LIVE/DELAYED/REPLAY 모드를 명확히 구분하는 additive metadata/표시를 먼저 승인받는다. 현재 PriceStatus에는 DELAYED/REPLAY가 없다.
- replay의 현재 snapshot 활성화와 서명 견적 만료는 운영 wall-clock, 원본 candle 축/과거 날짜는 dataset clock. 원본 시각을 보존하고 보고서는 replay 기준 모의 가격임을 표시한다. 정지/종료/오류 시 신규 견적을 차단하며 5초 안전 경계를 무작정 제거하지 않는다.
- 차트 조회는 replay 현재 위치 뒤의 데이터를 제공하지 않는다. 기존 UI tick은 observedAt를 봉에 넣으므로 sourceTimestamp/replay 축에 맞춘 표시 처리가 필요하다. 'provider 하나만 추가하면 모든 UI/거래가 자동 호환'이라고 약속하지 않는다.
- 초기에는 배포 원장당 고정 dataset·한 방향 replay만 사용한다. 반복 rewind/loop는 기존 포지션/미소비 quote/대기 정산과 충돌하므로 세션·새 원장 정책을 별도로 결정한다. 기존 DB를 자동 지우거나 clock 회귀를 허용하지 않는다.
- 사용자 quoteId만 제출, DB 보고서/서명 조회, 발급 사용자·방향/입력/만료/상태·일회 소비, 운영자 executor·키 분리·원자성/RPC 복구, Vault PriceReport+signature 경계는 유지한다. updatePrice 거래 경로를 복구하지 않는다.

## 다음에 필요한 서면 문의

코스콤 실시간/지연/분봉·틱 replay 최소 견적을 먼저 받고 KRX historical 또는 Twelve Data/EODHD의 제한된 replay 권한을 비교한다. 아직 어떤 곳에도 문의/가입하지 않았다. 공급자에게 다음 내용을 동일하게 전달해야 답을 비교할 수 있다.

> 개인 개발자의 비상업 취업용 공개 웹입니다. 삼성전자005930/KRW 1종목만 표시하며 실제 주식·원화·상환권 없이 mKRW/mSEC 모의 거래를 제공합니다. 브라우저에 REST JSON/STOMP로 가격·캔들을 전달하고 서버는 quote/체결/포트폴리오 평가에 사용합니다. 사용 기간·예상 접속자 수는 확정 후 기재하겠습니다. 내부 DB 가격/주문 보관, 고정 과거 dataset의 순차 재생, Anvil 내부 및 향후 공개 Sepolia의 가격 보고서/거래 이벤트 기록을 각각 허용하는지 확인 부탁드립니다. 공개 chain 데이터는 계약 종료 후 삭제할 수 없습니다. 소스 공개와 raw dataset 공개는 별개로 raw download/feed 재판매는 제공하지 않습니다.

확인 항목: 개인 계약 가능 여부, external display와 REST/STOMP 범위, 모의 가격 산정(non-display/파생상품 분류), source/KRX/NXT coverage·symbol, 최신/지연·틱/분봉 깊이, 저장/replay/AI 입력·온체인 기록/계약 종료 retention, attribution, 최소 계약 기간, 기본료/단말·사용자·종목·망·add-on·부가세·학술 할인. 표준 화면 표시 허가만 받으면 전체 사용을 허용받았다고 계산하지 않는다.

채택 후에만 샘플·timestamp/결측·기업행위/기준 가격/회귀·외부 공개 범위를 검증한다. replay는 미래 데이터 비노출, pause/끝·중복/순서·복원, quote clock/만료·RPC 실패 정산, 동일 dataset 결정성도 테스트한다. 기존 backend/contracts/웹 회귀와 독립 검토는 구현 승인 후 수행하며 이번에는 테스트를 실행하거나 공급자 이용 가능성을 실계정으로 검증하지 않았다.
## 후속 결정 (2026-10-06)

공개 환경은 사용자가 simulated provider로 확정했다. Toss 연동은 그대로 보존하며 공개 환경에서는 비활성화한다. 위 조사 결과는 이력으로 유지하되 공급자 문의/계약과 historical replay를 현재 작업으로 진행하지 않는다. 다음 작업은 [합성 시장 개선 설계](synthetic-market-design.md)의 승인 후 구현이며 실제 배포는 별도 승인이다.
