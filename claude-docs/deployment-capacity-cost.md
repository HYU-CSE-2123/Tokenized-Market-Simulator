# 배포 자원 실측·서울 비용 비교

후속 사용자 결정: [EC2 운영 검증 계획](ec2-operational-validation-plan.md)으로 목적을 축소했다. 아래 상시 서비스 비용 비교는 당시 가정의 이력이다. 최초 실험은 기본 root EBS/자동 public IPv4만 사용하고 EIP 상시 보유·고객관리 KMS·S3 비용을 기본값으로 더하지 않는다. t3a.small은 실험 후보이지 생성 완료가 아니다.

> 2026-10-07. 예산·인스턴스는 미확정. 실제 AWS/SSM/S3/DNS 생성과 유료 AI 색인/호출은 하지 않았다. 제품·production Compose·실제 .env는 변경하지 않았다. 로컬 측정 helper와 결과 문서만 추가한다.

## 측정 방법과 경계

- `python deployment/resource-probe.py`: 신규 `exchange-ops-probe-*`, 무작위 테스트 키/별도 DB·체인·2일 bootstrap·loopback HTTPS. 기존 개발 컨테이너/DB/체인을 사용하거나 중지하지 않는다. 종료 때 측정 프로젝트와 provider fixture만 정리하며 bind 데이터·증거는 Git 제외 runtime에 보존한다.
- 현재 production Compose의 Nginx/backend/거래 PostgreSQL/AI PostgreSQL/Anvil **5개 서비스**를 Docker stats로 약2초마다 샘플링한다. Docker Desktop Linux VM은12CPU/약6.70GiB이며 EC2 자체 측정이 아니다.
- CPU는 컨테이너 합계 **100%=vCPU1개**,2vCPU 전체 사용률로 보려면2로 나눈다. 메모리는 Docker stats의 working set(비활성 file cache 차감) 합계이며 host OS/Docker daemon·다른 개발 컨테이너·fixture provider는 제외한다. 서비스 제한 합계2608MiB는 예약/실제 점유가 아니다.
- startup 샘플은 backend/web 시작 후부터다. 선행 PostgreSQL/Anvil 초기화와 forge 배포는 포함하지 않으므로 전체 cold-start 최대 자원이라고 쓰지 않는다.12CPU에서 관측한 기동 시간을2CPU 서버 시간으로 환산하지 않는다.
- idle90초, 서명 매수60초, AI60초, 거래+AI60초. 거래는 실제 quoteId/PriceReport 서명·Anvil 전송·receipt FILLED 확인이다. BUY만 측정하며 SELL/다수 사용자/최대2 AI worker 동시 부하·장시간 soak는 아니다.
- AI는 유료 API 대신 backend network namespace의 localhost HTTP fixture를 쓴다. 실제 서버 plan→Tool→pgvector 검색→synthesis/검증 경로는 실행하지만 동일1536 단위벡터·정해진 답변·LLM당2초 대기다. 실제 LLM 품질/외부 지연 검증이 아니다. 각 검색은 embedding을 호출하며 반복 질의 캐시를 가정하지 않는다. 실제 공급자의 payload/지연이 달라지면 메모리/동시 처리도 달라질 수 있다.
- `phases.*.latencyMs`는 helper pacing(trade4초/AI6초)을 포함한 **작업 주기**이며 API latency가 아니다. 비용 계산에는 fixture의 usage=0을 쓰지 않고 과거 실제 공급자 기록을 사용한다.
- 최초 `probe-20261007105549`는 helper가 주문 응답을 id로 읽어 orderId 계약과 달랐다. trade/combined 실패가 있어 해당 실행은 성공 경로 용량 결론에서 제외한다. 제품 계약은 수정하지 않았다.

## 최종 실측

최종 증거는 Git 제외 `deployment/runtime/probe-20261007110207/results.json`이다. 종료0,145표본, 단계별 실패0, sampling 오류0을 확인했다. 표본 최대는 관측 최대이며 프로세스 전체/미래 부하 최대가 아니다.

| 구간 | 표본 | 평균 CPU(1vCPU=100%) | 최대 CPU | 평균 메모리 MiB | 최대 메모리 MiB |
|---|---:|---:|---:|---:|---:|
| backend/web 기동 |10|211.92%|393.54%|380.68|463.30|
| idle |43|8.25%|77.20%|469.06|473.89|
| 서명 매수 |30|7.30%|23.80%|480.47|484.75|
| AI |31|7.70%|22.02%|487.99|492.22|
| 거래+AI |31|12.72%|70.21%|492.76|496.07|

- 단독 매수6건+병행 매수5건 모두 FILLED. 단독 AI5건+병행 AI5건 모두 ANSWERED이며 각 요청의 modelCalls2/retrievalCalls1/Tool 근거를 확인했다. 거래량은 저부하이고 최대 처리량 측정이 아니다.
- backend cgroup memory.peak405.875MiB, memory.events의 OOM/oom_kill 포함 모든 카운터0. cgroup peak는 working-set 합계와 다른 지표다. backend/web up 명령 반환 이후 시장 REST200까지11.37초이며 전체 기동 시간이 아니다.
- 관측 결과만 보면4GiB를 필수로 둘 근거는 없고 **2GiB x86을 조건부 최소 후보**로 검토할 수 있다.1GiB는 현재 관측 점유에 OS/daemon/native·장기 성장·동시 부하 여유를 더한 검증이 없어 안정적인 최소 구성으로 확정하지 않는다.0.5GiB는 서비스 working set만으로 거의 소진한다.
- helper는 Windows Docker Desktop에서 검증했다. native Linux 재현은 bind ownership 등 준비를 추가 확인해야 하며 이번 결과를 Linux/EC2 실행 보장으로 쓰지 않는다. 테스트용 bind 데이터와 fixture secret은 로컬 runtime에 보존되고 공개/커밋하지 않는다.

## 서울 EC2 요금 계산

2026-10-07에 AWS 공개 가격표를 읽었다(EC2 manifest 게시2026-09-25). Linux On-Demand/shared tenancy 기준이며 계정 할인·무료 크레딧·세금·환율·트래픽·CPU credit 초과·registry/CloudWatch·도메인은 제외한다. 특정 AZ의 실제 capacity는 확인하지 않았다.

- [서울 EC2 공식 공개 가격 데이터](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/Asia%20Pacific%20%28Seoul%29/Linux/index.json)
- [공식 EBS 지역별 가격 데이터](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ebs.json): 서울 gp3 `$0.0912/GB-month`,40GiB=`$3.648`. 기본 IOPS/throughput만 사용한다.
- [공인 IPv4](https://aws.amazon.com/vpc/pricing/): EIP1개 유지730h×$0.005=`$3.65`.
- [KMS](https://aws.amazon.com/kms/pricing/): 고객관리 대칭 키1개 기본 월$1+요청/rotation 조건. EBS는 AWS 관리 키로 암호화하고 SSM/S3용 고객관리 키1개 가정이다. 키 개수는 승인 전 미확정.
- [서울 S3 공식 가격 데이터](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/ap-northeast-2/index.json): S3 Standard 첫50TB `$0.025/GB-month`+요청/전송/KMS. 예컨대5GB 보관이면 저장만$0.125이며 **실제 사용량이라는 뜻은 아니다**.

비교를 위해 기존 disk 후보(root20+data20=40GiB), EIP와 KMS를 유지하면 공통 월비용은 `$8.298`+S3/요청/전송이다. 새로 더 작은 disk를 확정한 것이 아니다.

| 후보 | vCPU/RAM | $/hour | 계산상24시간(730h) | 필요 시 기동60h/월 | 현재 구조 판단 |
|---|---|---:|---:|---:|---|
| t3a.micro |2/1GiB|0.0117|$16.84|$9.00|1GiB host 검증 없음, 현 JVM/native/DB/OS 여유 미보장|
| t3a.small |2/2GiB|0.0234|$25.38|$9.70|실측 기반 조건부 작은 x86 우선 후보|
| t3.small |2/2GiB|0.0260|$27.28|$9.86|Intel 대안, 더 비쌈|
| t2.small |1/2GiB|0.0288|$29.32|$10.03|CPU 적고 더 비싸 최저비용 이점 없음|
| t3a.medium |2/4GiB|0.0468|$42.46|$11.11|메모리 여유 대안이지 전제/확정 아님|
| t3.medium |2/4GiB|0.0520|$46.26|$11.42|Intel 메모리 여유 대안|
| t4g.small |2/2GiB ARM|0.0208|$23.48|$9.55|현재 amd64 pin/AI platform과 불일치, 즉시 배포 후보 아님|
| t4g.medium |2/4GiB ARM|0.0416|$38.67|$10.79|ARM 이미지/컨트랙트 도구 검증 별도 필요|
| c5a.large |2/4GiB|0.0860|$71.08|$13.46|지속 CPU용 비교, 저부하 최소비용 목적에는 과함|
| c6i.large |2/4GiB|0.0960|$78.38|$14.06|동일, 메모리 절약도 없음|

예산은 모두 CPU 추가 과금0 가정이며 실제 청구 보장은 아니다. [T3/T3a baseline](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-credits-baseline-concepts.html)과 장기 CPU/credit 관측이 필요하다. Standard로 비용을 막으면 credit 고갈 때 성능이 제한될 수 있다. Spot은 중단/복구·예약 TTL/원장 운영 부담 때문에 이번 최소 변경 후보로 권장하지 않는다.

필요 시 기동은 **EC2 stop/start**를 뜻한다. Compose stop만으로 EC2 요금이 멈추지 않는다. [EC2 billing](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-lifecycle.html)에 따라 stopped 인스턴스 계산 요금은 없지만 EBS/EIP/KMS/S3는 남는다. t3a.small은30h=$9.00,60h=$9.70,120h=$11.11(+변동 항목). EIP를 유지해야 DNS를 매번 바꾸지 않는다.

소형 disk를 검토한다면 root16+data8=24GiB로 공통 비용을 월$1.4592 줄일 수 있지만 **용량을 확정하거나 파일/DB를 축소하지 않았다**. 이미지 사전 빌드/전달, OS/로그·백업 staging·DB/Anvil 성장과 여유공간을 실제 Linux에서 확인한 후 선택한다.8GiB 데이터 용량이 장기 충분하다고 보장하지 않는다.

## Lightsail 비교

[공식 public IPv4 Linux 플랜](https://aws.amazon.com/lightsail/pricing/)은2GB/2vCPU/60GB SSD 월$12,4GB/2vCPU/80GB SSD 월$24이며 static IP·전송량이 포함된다. 별도 data disk20GB(월$2)와 위 KMS1개를 더하면 `$15`/`$27`+S3/변동 비용이다. 디스크를8GB로 선택하면 각각 `$13.80`/`$25.80`이지만 아직 승인하지 않았다. 번들 부팅 SSD를 data disk20GB 요금과 중복 계산하지 않는다.

- **일반 Lightsail은 stop해도 과금된다.** 유지하는 방식이라면60h만 켜도 월비용은 같다. 삭제·복원으로 시간 과금을 줄이는 방식은 현재 원장/체인/운영 연속성에 복잡도를 더하므로 이번 최소 변경 운영안이 아니다. [공식 과금 설명](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html).
- 2GB/4GB는 모두 CPU baseline20%/vCPU이며 RAM 증설이 baseline CPU를 늘리는 것은 아니다. [공식 baseline](https://docs.aws.amazon.com/lightsail/latest/userguide/baseline-cpu-performance.html).
- Compose/두DB/Anvil/Nginx는 유지할 수 있지만 **EC2 사용자 지정 IAM instance role/SSM 관리 흐름을 그대로 쓸 수 있다고 가정하지 않는다**. [AWS 측 설명](https://repost.aws/questions/QUOuXgoecfSRCJ_XZYA2E8iA/strange-error-when-trying-to-use-codewhisper-on-cloud9-targeting-a-lightsail-instance)은 기본 Lightsail 역할에 사용자 권한을 붙이는 방식과 구분한다. 장기 AWS key를 서버에 저장하는 대안은 추천하지 않는다. 승인된 운영자 세션에서 secret을 가져와 안전하게 주입하고 백업 업로드를 수행하는 관리 절차 등 별도 승인/검증이 필요하다. 제품 코드 변경은 필요 없지만 운영 준비 비용은0이 아니다.
- 따라서24h 최저 요금 후보는 Lightsail2GB, 기존 IAM/SSM 운영 경계 유지 및 간헐 기동 후보는 EC2 t3a.small이다. 최종 안정성은 해당 환경에서 승인 후 검증하며 아직 어느 것도 선택/생성하지 않았다.

필요 시 기동은 방문자가 언제든 접속할 수 없다는 의미다. 꺼진 동안 웹/차트도 제공되지 않고 합성 시장은 실행되지 않는다. 기존 설계대로 전체 downtime catch-up은 없어서 차트의 시간 공백이 생길 수 있다. 재기동 시 DB/chain/시각 정렬/인증서 갱신·readiness를 확인하고, 미정산/잠금0 checkpoint와 정상 종료를 거친다. 임의 체인 초기화나 원장 reset은 하지 않는다.

## AI: 실제 토큰 사용량의 현 단가 환산

[OpenAI Docs Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra) 표준 short-context 단가는 입력$2/백만,출력$12/백만,캐시 입력$0.20/백만이다. [text-embedding-3-small](https://developers.openai.com/api/docs/models/text-embedding-3-small)은 입력$0.02/백만이다. 모델/구조를 변경하지 않았다.

공식 가격을 기존 실제 공급자 usage에 적용한다. 캐시 할인·cache write token 상세는 현재 기록되지 않아 전부 일반 input으로 계산하며 실제 invoice가 아니다. Fast/Batch/긴 문맥/지역 residency 단가를 임의 적용하지 않는다. reasoning none 사용이며 공급자가 보고한 output_tokens를 쓴다. Agent run metrics와 provider counters는 중복 합산하지 않는다.

| 실제 표본 | 요청 수 | 입력/출력 tokens | 환산 LLM 총액 | 요청당 평균 |
|---|---:|---:|---:|---:|
| Phase6 Skill 성공 실행 |6|16,506/3,782|$0.078396|$0.013066|
| Phase7 자동 정산 진단 |3|9,129/2,185|$0.044478|$0.014826|
| Phase8 raw의 Agent/Skill7개 |7|14,719/2,564|$0.060206|$0.008601|

근거는 `docs/ai/phase-6-results.json`, `phase-7-results.json`, `backend/build/reports/ai/phase8-live-evaluation.json`의 보고 usage다. 과거6/7 raw는 후속 regression에서 덮어쓸 수 있어 **당시 committed 결과**의 합계를 사용한다. Phase8의8번째 synthetic RAG injection은 Agent metrics가 없으므로7표본 평균에 섞지 않는다.7표본에는 안전 거부1개가 포함되므로 보편적인 정상 질문 평균으로 일반화하지 않는다.

Phase8 요청별 실제 LLM 환산: STATE시장(503/23 tokens) `$0.001282`; KNOWLEDGE정책(2549/198) `$0.007474`; MIXED주문(3334/611) `$0.014000`; 명시견적Skill(2229/565) `$0.011238`; 시장Skill(2556/509) `$0.011220`; 자동정산(3027/635) `$0.013674`; 안전거부(521/23) `$0.001318`. 요청1개가 모델1~2회일 수 있으며 모델 HTTP 호출당 평균과 사용자 요청당 평균을 혼동하지 않는다.

| 같은 표본 분포로 사용한다면 |100요청|1,000요청|3,000요청|
|---|---:|---:|---:|
| STATE시장 표본 |$0.13|$1.28|$3.85|
| Phase6 Skill 평균 |$1.31|$13.07|$39.20|
| Phase7 자동정산 평균 |$1.48|$14.83|$44.48|

위는 사용량별 산술 비교이며 호출 가능량/품질/비용 hard cap 보장이 아니다. 현재 공급자 process lifetime500 HTTP 호출 제한은 재기동 때 reset되며 월 quota가 아니다. 자동진단은 production 기본false라 단순 idle로 자동진단 비용이 발생한다고 계산하지 않는다.

Phase8 전체 최종 평가(대표8+지식 회귀)의 실제17 model HTTP input24,920/output3,171=`$0.087892`; embedding8 HTTP/input170=`$0.0000034`. 전체=`$0.0878954`. 이는 위7요청 표본을 포함하므로 추가로 합산하지 않는다. embedding 평균21.25tokens/query면1,000번≈$0.000425이나 실제 질의 길이에 따라 달라진다. 최초 재색인 embedding tokens는 당시 독립 계량이 없어 정확한 총액을 계산할 수 없다. 실제 재색인/usage 계량은 별도 승인 후이고 fixture의 zero usage를 재색인 가격으로 쓰지 않는다.

## 검증·아직 결정하지 않은 항목

- helper pure unit2 통과, 최종 실측 성공. 독립 소스/증거/공식 가격/토큰 산술·최종 문서 재검토에서 발견된 필수 수정 없음. 표본 합계 오기165→145를 수정했다. 검토자는 직접 unit2·가격 조회·증거 대조를 수행했고 Docker 측정은 재실행하지 않았다. 제품 회귀는 제품 변경이 없어 이번에 재실행하지 않았고 직전 승인된 전체 결과를 새 실행으로 주장하지 않는다.
- 1/2GiB native Linux host 제한 실험, EC2/Lightsail 실제 기동·CPU credit·OS/daemon 여유·장기 DB/chain 증가·2AI worker·다수 사용자·백업 최고점·SLA는 미검증이다.
- 예산/인스턴스/disk/운영 방식/domain/IP/AI 활성화와 최초 유료 색인은 사용자 최종 승인을 기다린다. 현재 자료는 t3a.medium 또는 월 AI$5~10을 확정하지 않는다.
