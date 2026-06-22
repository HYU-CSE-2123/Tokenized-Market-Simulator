# android — Kotlin + Jetpack Compose

삼성전자 가격 추종 토큰 거래소 모바일 클라이언트. 기획서 §14~15.

## 기술 스택
Kotlin · Jetpack Compose · Navigation Compose · ViewModel/StateFlow · Retrofit/OkHttp · kotlinx.serialization · DataStore

## 레이어 구조 (`com.pricetrack.exchange`)
```
data/
  remote/      ApiServices(Auth/Market/Quote/Order/Portfolio) + DTO, NetworkModule
  websocket/   MarketSocketClient, OrderSocketClient
  repository/  *RepositoryImpl
domain/
  model/       Market, PriceTick, Quote, Order, Trade, Portfolio
  repository/  *Repository 인터페이스
  usecase/     GetMarket / ObservePrice / GetBuyQuote / BuyToken / SellToken / GetPortfolio
presentation/
  login, market, trade, portfolio, history, navigation, theme
```

## 화면 (기획서 §14.2)
Login / Signup → MainScreen(하단 탭): Market · Trade · Portfolio · History · MyPage

> 현재는 컴파일·실행 가능한 **스캐폴딩**이다. 화면은 placeholder, 리포지토리 일부는 `TODO()` 로 Phase 5 에서 채운다.

## 빌드
```bash
# local.properties 의 sdk.dir 을 본인 환경에 맞게 설정 (gitignore 됨)
./gradlew :app:assembleDebug
# 산출물: app/build/outputs/apk/debug/app-debug.apk
```

## 백엔드 연결
`app/build.gradle.kts` 의 `BASE_URL`/`WS_URL` 은 안드로이드 에뮬레이터 기준 `http://10.0.2.2:8080` (호스트의 localhost). 실기기는 PC 의 LAN IP 로 변경한다.
