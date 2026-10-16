# 키움 NXT 실제 응답 관측 — 2026-10-16

## 1. 결론과 실행 범위

승인된 기존 private run `kiwoom-diagnostic-20261015112753-02b328b4`에서 실제 관측 **1회**를 실행했다. OAuth·LOGIN·REG가 성공했고, 동일 generation에서 `item=005930_NX`, `290=2`인 체결742건을 기록했다.

**연결·구독은 PASS다. 9081 값과 0s 상태는 확인하지 못했으며, NXT 프리·애프터 지원이나 제품 모의 거래 가용성을 검증한 결과는 아니다.** 추가 조회·재시도·재연결은 하지 않았다.

| 검증 항목 | 결과 |
|---|---|
| prepared manifest·현재 진단 소스 해시·secret 참조·한도 | PASS · 외부 호출 직전 검증 |
| private ACL 및 두 인증정보 존재 | PASS · 비밀값·길이·인증 fingerprint 출력 없음 |
| 기존 capture 없음 | PASS · 실행 전 run.json/prepared.json만 존재 |
| OAuth·WS LOGIN | PASS · 토큰 발급 후 LOGIN 승인 |
| 실제 REG 송신·승인 | PASS · REG_SENT 및 REG 성공 기록 |
| NXT 요청/응답 item 일치 | PASS · 체결742건 모두005930_NX |
| 290 관측 | PASS · 742건 모두 문자열2 |
| 9081 관측 | INCONCLUSIVE · 742건 모두 정제 결과null |
| 0s / 215 관측 | INCONCLUSIVE · 0s0건,215값없음 |
| 프리·애프터 세션별 응답 계약 | NOT_RUN · 이번은 메인마켓 시간대 |
| 제품 OPEN/LIVE/Quote·모의 거래 | NOT_RUN · 제품 서버 미실행 |

## 2. 시각과 실제 구독

- 최초 로컬 시각: KST2026-10-16 10:57:59.
- 외부 호출 전 최종 preflight: KST11:03:21.966768.
- supervisor 시작: KST11:03:22.604204.
- WS CONNECTED: KST11:03:24.112328.
- 마지막 체결 수신: KST11:06:23.920868.
- supervisor 종료 보고 파일 시각: KST11:06:24.147071.
- 시작~종료 보고 파일 시각 차이: **181.542867초**. 파일 시각 기준이며 별도로 저장한 monotonic 실측값은 아니다. supervisor는210초 제한을 적용했고 worker 정상 종료(exit0)를 확인했다.
- CONNECTED~마지막 체결 기록 간격:179.808540초. 정상 관찰 한도는180초다.

토큰 없는 실제 REG_SENT:

```json
{"trnm":"REG","grp_no":"1","refresh":"1","data":[{"item":["005930_NX"],"type":["0B"]},{"item":[],"type":["0s"]}]}
```

## 3. 정제된 관측값과 한계

| 필드 | 최초 체결 | 마지막 체결 |
|---|---|---|
| generation | 1 | 1 |
| item | 문자열005930_NX | 문자열005930_NX |
| 290 | 2 | 2 |
| 9081 정제 결과 | null | null |
| 체결 HHmmss | 110325 | 110624 |
| 수신 시각 KST | 11:03:24.722319 | 11:06:23.920868 |
| 가격 | 214,000 | 213,500 |
| 체결량 | 10 | 1 |
| 누적 거래량 | 7,101,048 | 7,133,581 |

체결742건 모두 같은 generation1, LOGIN/REG 승인 이후의 관측이다. 가격·체결량 파싱 실패는 각각0건이다. 모두 기존 과거5초/미래2초 비교 범위 안에 있었고, 수신시각-체결시각 차이는-0.479456~+0.524268초였다. 이것은 시장OPEN 또는 제품 거래 허용 판정이 아니다. observedAt의 날짜는 수신일KST를 사용한 가정이며 vendor가 전송한 날짜 필드로 주장하지 않는다.

### 9081 관측 한계

현재 파서는9081이 **문자열1~3자리 숫자**일 때만 저장한다. null은 원 응답의 필드 부재, null/문자열 아닌JSON타입, 미허용 문자열 형식을 구분하지 못한다.

따라서 **"키움이9081을 보내지 않았다"고 확정할 수 없다.** 원문을 보존하지 않아 이번 증거에서 복원할 수도 없다. 이를 해결하려고 필터 수정이나 추가 호출을 하지 않았다.

0s는 NXT Observer의 parser 이전 관측 경로에서도0건이었다. 이번180초 미수신으로 초기 스냅샷 미지원이나 공급자 결함을 확정하지 않는다.

## 4. 실제 호출 수와 종료

| 구분 | 기록 |
|---|---:|
| OAuth 시도 | 1 |
| 시세·차트REST | 0 |
| 전체REST | 1 · OAuth포함 |
| WS연결 | 1 |
| LOGIN / REG | 각1 |
| 재연결·재시도·토큰재발급 | 0 |
| 송신WS프레임 | 19 · LOGIN/REG각1+PING응답17 |
| 수신WS프레임 | 650 |
| 정제레코드 | 746 · CONNECTED/LOGIN/REG_SENT/REG각1+체결742 |
| 0s레코드 | 0 |

프레임 하나에 여러 체결이 담길 수 있어 프레임 수와 체결 레코드 수는 같지 않다. 시도 카운터는 전송 전 기록이며 LOGIN/REG 성공은 별도ACK로 확인했다.

종료는 `captureResult=COMPLETE`, `captureTruncated=false`, `workerStopped=true`, `workerExitCode=0`이다. 정상 관찰 시간 만료로 끝났으며 이벤트 상한·강제 종료·재연결로 종료된 실행이 아니다.

## 5. 거래소 구분 및 NXT 고정 모드에 필요한 근거

확보한 사실:

- NXT전용005930_NX 구독을 실제 서버가 승인했다.
- 해당 단독 구독 응답742건 모두 같은005930_NX 문자열item으로 돌아왔다.
- 이번 메인마켓 관측에서는290=2와 정상 가격·거래량·시각을 받았다.

따라서 **이번 구독 범위에서 item의 정확한 일치 검사를 고정 모드의 출처 구분 근거로 삼을 자료는 확보했다.** KRX를 동시에 구독·대조한 실험은 아니며9081의 독립적인 거래소 교차검증은 미완료다. 기본005930·UNKNOWN item까지 NXT로 수락하는 근거로 확대하지 않는다.

남은 근거/결정:

1. 프리·애프터의 실제item/290/시각은 별도 확인해야 한다. 메인마켓290=2를 일반화하지 않는다.
2. 9081의 필드 존재·JSON타입·형식을 구분하려면 향후 승인된 진단 보완 또는 공식 계약 확인이 필요하다.
3. 0s없는NXT 체결 기반 모의 견적 허용 범위, 거래소별CLOSED, 재연결 차단은 제품 구현 승인에서 확정해야 한다. 이번 도구는 정책을 적용하지 않았다.
4. NXT차트/REST와tick결합, 서명Quote, Anvil BUY/SELL 및 정산 검증은 별도 단계다.

## 6. 증거와 보존

private run: `deployment/runtime/kiwoom-diagnostic-20261015112753-02b328b4/`.

- 기존 보존 파일:run.json/prepared.json.
- 이번 신규 증거:supervisor-start.json/attempts.jsonl/events.jsonl/report.json/supervisor-report.json.
- 실제 키를 복사한credentials.json은 없다. 산출물에서token/App Key/Secret/Authorization/개인키/서명/raw transaction 관련 금지 필드명도 발견되지 않았다. 이 산출물 검사에서secret파일은 읽지 않았다.
- 이번 실제 관측으로run은 사용됐다. 다시capture하거나 파일을 삭제해 재사용하지 않는다.
- Spring Boot·웹·KRX E2E환경·DB·Anvil·baseline·인증정보·제품 코드 변경 없음. 주식 주문·모의 거래·유료AI·배포·테스트·Git명령도 실행하지 않았다. 문서에 관측 결과만 추가했다.

추가 외부 호출은 이번 승인에 포함되지 않는다. 수신 성공을 NXT제품 지원 완료나 최종 통합 인수PASS로 보고하지 않는다.
