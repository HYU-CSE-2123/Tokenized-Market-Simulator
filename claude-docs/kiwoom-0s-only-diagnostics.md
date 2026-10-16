# 키움 0s-only 진단 준비

> 2026-10-14. feat/kiwoom-live-fixes/기준240cb398. 사용자 승인 범위는 도구 준비·Mock/보안 검증·독립 검토·새 private run 준비이며, 실전 실행은 별도 승인 대기다.

## 구현과 경계

- `deployment/kiwoom-diagnostic.py`: capture의 명시적 `--profile 0s-only`. 기본 mixed는 호환성만 유지하며 이번 실험에서 실행하지 않는다.
- `kiwoom_diagnostic_core.subscription('0s-only')`: 공식 예제의 한 엔트리 `data:[{item:[],type:[0s]}]`, grp_no=1/refresh=1. LOGIN 성공 후 REG 한 번만 송신. 실제 send 정상 반환 후 정제 REG_SENT를 저장한다. 이 기록은 ACK/0s 전달의 보장이 아니다.
- profile은 REST상한1/WS상한1/차트0/정제이벤트최대5000을 강제한다. 관찰은 기존 최대180초, 재시도·재연결·추가 토큰·추가 REG 없음. PING 응답만 허용한다.
- 준비된 manifest가 profile·정확한 상한·공식 payload·현재 도구 두 파일의 source hash와 일치하지 않으면 **키 파일을 읽거나 transport를 만들기 전에** PREPARED_MISMATCH로 중단한다. 기본 외부 gate도 유지한다.
- events.jsonl 또는 report.json이 이미 있으면 capture를 거부한다. private 출력은 exclusive 생성, 원문 응답/토큰/키/인증 fingerprint는 저장하지 않는다.
- 기존 제품 Java/Solidity/UI·시장 판정·5초/미래2초·Oracle/거래/DB/Anvil은 변경하지 않았다. 기존 P0의 Java 수정은 작업 전부터 있던 변경이며 이번 구현으로 보고하지 않는다.

## parser 이전 관측

0s-only에서만 Observer의 observe_state_wire를 켠다. 유효하게 JSON 디코딩된 REAL/data 배열에서 type=0s인 entry를 제품 대응 parser보다 먼저 계수한다.

- MARKET_STATE_WIRE_FRAME: frame 수와 해당 frame의 entryCount. 한 frame에 여러 entry가 있을 수 있다.
- MARKET_STATE_WIRE: type(고정0s), 수신 UTC시각, generation, itemFormat, stateCode(215), marketTime(20), LOGIN/REG ACK 여부.
- itemFormat은 MISSING/NULL/EMPTY_STRING/STRING/EMPTY_ARRAY/ARRAY/OTHER만 기록한다. 실제 item 문자열이나 배열 원문은 저장하지 않는다.
- 215는 알려진 짧은 공식 코드만, 20은 6자리 숫자 문자열만 보존하며 그 외는 null로 마스킹한다. 6자리 형식 통과가 실제 유효한 HHmmss나 가격 신선도 통과를 의미하지 않는다.
- ACK 전 수신, nonempty/null/array item, 오래되거나 잘못된20도 **관측과 기존 parser 수락 결과를 분리**한다. stateWireFrames/stateWireEntries/stateParsedEntries를 각각 보고한다.
- 모든 frame/entry/parsed/ACK/REG_SENT 기록은 **하나의 5000 정제 이벤트 상한**을 공유한다. 넘으면 EVENT_LIMIT와 truncated 표시, 이후 추가 호출 없음. 부분 관측으로 공급자 미지원이나 전체 부재를 단정하지 않는다.
- JSON malformed·배열500초과·관찰/건수 종료·단절은 수신 증거 한계다. 이전 generation은 기록하지 않는다. 전체 raw WebSocket 메시지를 저장하는 기능은 없다.

0s-only는0B를구독하지 않으므로 LIVE가격 검증은 NOT_RUN이다. report.priceEligibilityEvaluation=NOT_RUN, productEligibilityEvidence=INCONCLUSIVE를 명시하고, 예상치 않은0B가들어오면unexpectedTradeEntries에별도계수한다. 0s수신만으로제품OPEN+LIVE+fresh 완료나 초기snapshot 보장을 선언하지 않는다.

## 이번 검증

- `python -S -m unittest discover -s deployment -p test_kiwoom_diagnostic.py -q`: **39/39 PASS**, 실패/skip0. 최초36개 실행 후 검토 보완3개를 추가한 최종 실행이다.
- `python -S -m unittest discover -s deployment -p 'test_phase_a*.py' -q`: **15/15 PASS**, 실패/skip0.
- 새 테스트: 공식단독payload, item7형식/ACK전수신, invalid215/20 마스킹·SECRET_CANARY 비노출, 이전generation차단, 공유상한, 가격없는상태의판정불가, profile한도거부, manifest불일치의키/네트워크전차단, Mock전체capture의1OAuth/1WS/1REG/차트0/중복LOGIN무추가구독/송신증거.
- Mock 테스트에서는 transport를 가짜로 대체했다. 실제 키움/API·운영DB/RPC·유료AI·배포는0회. 기존 실제 capture/Java 테스트 XML은 쓰지 않았다. backend/web/Foundry 전체는 도구-only 작업에서 재실행하지 않았다.
- 독립 최초 검토 중 준비된 단독 run에 CLI profile을 생략하면 혼합 경로로 우회할 수 있는 문제가 지적돼 수정했다. manifest가 있으면 profile에 관계없이 검증하고, 기본/명시적mixed 모두 키/transport 전에 거부한다. source/상한/payload/status/profile 변조와 auth/connect 실패 시 재시도0·원문 비노출 테스트도 추가했다. 첫 재검토 결과와 새 run 위치는 완료 후 기록한다.

최종 독립 첫 재검토: **발견된 필수 수정 없음**. reviewer 직접 진단39/39·Phase A guard15/15 PASS. 실제 키/API/DB/Anvil 및 새 run에는 접근하지 않았으며, 아래 로컬 run 준비 확인은 구현자 수행 결과다. 이전 최초36/37개 결과와 최종39개 실행을 구분한다.

## 준비 완료한 새 run

`deployment/runtime/kiwoom-diagnostic-20261014030349-291b6524` (통합 worktree 기준).

- 기존 인증 설정을 ACL 검사 후 새 private run에만 준비했다. mode=real, 키 존재·사용자/SYSTEM 제한 접근 권한 확인, 값은 출력하지 않았다. 기존 credentials/capture/test 증거는 변경하지 않았다.
- prepared.json: 0s-only payload·REST1/WS1/180초/5000events/chart0·최종 도구 source hash. 실제 validate_prepared 통과, 외부 gate는 EXTERNAL_DISABLED 확인.
- 새 run에서 Mock39/39·guard15/15를 다시 실행하고 diagnostic-tests.txt/phase-a-guards-tests.txt에 보존했다. 이전 XML이나 capture를 재실행 결과로 재사용하지 않았다.
- readiness.json에 준비 완료/외부 승인 대기를 기록했다. events.jsonl/report.json 없음, 외부 호출0. 새 source/profile/상한이 달라지면 재준비 승인 없이 실행하지 않는다.

## 실전 실행 승인안

새 private run과 제한된 credentials/준비 manifest는 위와 같이 준비했다. 기존 서비스/IP 등록 사용자 확인은 그대로 인정한다. 실제 연결 충돌 발견 시 세션을 종료하지 않고 보류한다.

실제 실행 명령은 새 run 준비 후 아래 옵션으로만 사용한다(현재 실행하지 않음).

```powershell
python deployment/kiwoom-diagnostic.py capture --run deployment/runtime/kiwoom-diagnostic-20261014030349-291b6524 --profile 0s-only --allow-external --max-rest-requests 1 --max-ws-connections 1 --seconds 180 --max-events 5000 --chart-snapshots 0
```

승인 대상: 실전 인증정보 사용 + OAuth1 + WS1/180초/5000events/0s단독REG1. 시세REST·주문·혼합fallback·추가구독·재연결은 제외한다. 0건이면 초기snapshot미지원으로 확정하지 않고 [문의/결과별후속계획](kiwoom-0s-isolation-plan-and-support.md)에 따라 보고한다.
