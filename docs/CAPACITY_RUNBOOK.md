# R6 시험 실행

성능·장애 시험은 일반 회귀 테스트와 분리한다.
capacityTest는 새 Testcontainers PostgreSQL과 별도 API/대화/일기 JVM을 생성한다.
기존 로컬 DB나 개발 서버를 사용하지 않으며 강제 종료도 자신이 시작한 자식 PID에만 수행한다.
운영 jar에는 시험용 제어 서버와 fake provider가 포함되지 않는다.

## 짧은 실행 경로 점검

PowerShell에서는 점이 포함된 Gradle -P 인자를 따옴표로 묶는다.

```powershell
cd apps/backend
.\gradlew.bat :bootstrap:capacityTest --console=plain --offline --no-daemon '-Pcapacity.scenario=benchmark' '-Pcapacity.durationSeconds=5' '-Pcapacity.warmupSeconds=0' '-Pcapacity.journalBurst=10'
```

이 시험은 도구 검증이다. 5초 결과를 P0 성능 승인으로 사용하지 않는다.
초기 class loading/JIT 지연은 워밍업과 별도로 취급한다.

## 단기 P1 재현

현재 통과 결과의 설정이다. 60초 시험이며 장시간 운영 승인을 대신하지 않는다.

```powershell
.\gradlew.bat :bootstrap:capacityTest --console=plain --offline --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pcapacity.profile=P1' '-Pcapacity.scenario=benchmark' '-Pcapacity.durationSeconds=60' '-Pcapacity.warmupSeconds=30' '-Pcapacity.conversationDelayMs=1000' '-Pcapacity.journalDelayMs=10000' '-Pcapacity.conversationSlots=320' '-Pcapacity.conversationPool=12' '-Pcapacity.conversationReplicas=2' '-Pcapacity.conversationBatchSize=32' '-Pcapacity.apiPool=24' '-Pcapacity.journalSlots=48' '-Pcapacity.journalPool=4' '-Pcapacity.historyRecords=50000' '-Pcapacity.journalBurst=1000' '-Pcapacity.journalDrainSeconds=400'
```

## 실제 종료·리스 복구

429·timeout·5xx 첫 시도 실패, 일기 JVM 종료, 전달 등록 전·후 `Runtime.halt`를 실행한다.
운영의 생성 리스 5분과 전달 리스 1분을 실제로 기다린다. DB 시각을 앞당기지 않는다.
전달 경계 시험의 중단 JVM은 자동 전달과 자동 생성을 일시 정지하고 수동으로 정확한 경계에 진입한다.
복구 JVM은 정상 스케줄러를 사용한다. 이 시험은 용량 승인과 분리한다.

```powershell
.\gradlew.bat :bootstrap:capacityTest --console=plain --offline --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pcapacity.profile=P0' '-Pcapacity.scenario=failure' '-Pcapacity.durationSeconds=10' '-Pcapacity.warmupSeconds=10' '-Pcapacity.conversationDelayMs=1000' '-Pcapacity.journalDelayMs=200' '-Pcapacity.conversationSlots=32' '-Pcapacity.conversationPool=8' '-Pcapacity.apiPool=10' '-Pcapacity.journalSlots=2' '-Pcapacity.journalPool=2' '-Pcapacity.journalBurst=10' '-Pcapacity.journalDrainSeconds=30'
```

## 목표 부하

```powershell
.\gradlew.bat :bootstrap:capacityTest --console=plain --offline --no-daemon '-Pcapacity.scenario=sustained' '-Pcapacity.profile=P0' '-Pcapacity.durationSeconds=7200' '-Pcapacity.warmupSeconds=600' '-Pcapacity.conversationDelayMs=1000' '-Pcapacity.journalDelayMs=10000' '-Pcapacity.conversationSlots=32' '-Pcapacity.conversationPool=8' '-Pcapacity.journalSlots=48' '-Pcapacity.journalPool=4' '-Pcapacity.historyRecords=50000' '-Pcapacity.journalBurst=10000'
```

P0/P1/P2 기본 저장 목표는 각각 25/250/2500 RPS다.
목표 부하를 보내더라도 API/대화 대기 기준을 통과했다고 자동으로 간주하지 않는다.
기본 대화 8·일기 2 슬롯은 용량 승인값이 아니다.
워커 수·슬롯·풀을 바꿀 때 호출 예산과 DB 연결 합계를 함께 보고한다.

주요 인자:

| 인자                                              | 의미 / 기본값                                                     |
|---------------------------------------------------|-------------------------------------------------------------------|
| capacity.profile                                  | P0 / P1 / P2, 기본 P0                                             |
| capacity.rps                                      | 직접 지정한 저장 RPS; 미지정 시 프로파일 기본값                   |
| capacity.scenario                                 | smoke / benchmark / sustained / failure                           |
| capacity.durationSeconds                          | 측정 구간 20초                                                    |
| capacity.warmupSeconds                            | 워밍업 5초                                                        |
| capacity.conversationSlots / journalSlots         | 인스턴스 슬롯 8 / 2                                               |
| capacity.conversationReplicas                     | 대화 워커 수 1, 2 이상이면 1→N 비교 구간 추가                     |
| capacity.conversationBatchSize                    | 워커당 배치 선점 상한 32; 예약 실행 슬롯 이내                     |
| capacity.apiPool / conversationPool / journalPool | DB 풀 10 / 4 / 2                                                  |
| capacity.conversationDelayMs / journalDelayMs     | fake 호출 지연 100 / 200ms                                        |
| capacity.historyRecords                           | 180일에 분산할 과거 기록 수 0; 기록당 USER 20개·ASSISTANT 19~20개 |
| capacity.journalBurst                             | 동시에 마감 대상으로 넣을 기록 수 100; 기록당 원본 20개           |
| capacity.drainSeconds                             | 대화 backlog 완료 대기 상한 120초                                 |
| capacity.journalDrainSeconds                      | 주입 종료 후 일기 추가 대기 상한 0초                              |

sustained의 혼합 구간을 7200초로 설정하면 2시간 지속 시험이다.
sustained 기준선과 1→N 구간은 최대 1800초로 제한한다.
의도한 워밍업 10분·목표 구간 30분·지속 구간 2시간을 보고서에서 구분한다.
위 명령은 워밍업·기준선·혼합 구간에 총 2시간 40분이 필요하며 기동·데이터 준비 시간은 추가다.
P1 기준선이 저장·대화 대기 목표를 충족하지 못하면 마감 부하를 추가하기 전에 시험을 실패로 종료한다.

## 장애 시험

smoke/failure는 429·timeout·5xx를 첫 시도에 주입하고 실제 재시도를 확인한다.
시험의 backoff는 대화 1/2초, 일기 1/2/4/8초다. 운영 기본 backoff를 검증했다는 뜻은 아니다.
일기 JVM은 30초 fake 호출 중 실제 강제 종료한 뒤 새 JVM으로 회수한다.
운영 기본 5분 generation lease를 사용하며 DB 리스 시각을 인위적으로 당기지 않는다.
이 때문에 장애 시험은 짧은 부하 구간보다 수 분 오래 걸릴 수 있다.
일기 JVM이 없는 동안에도 실제 API 요청 25건을 보내 대화 완료를 확인한다.
이어 전달 등록 직전·직후 Runtime.halt로 각각 종료하고 실제 1분 전달 리스 만료 뒤 단일 작업·단일 결과와 ack 회수를 확인한다.

## 측정과 원본 자료

결과는 bootstrap/build/capacity/<실행 UTC 시각>-<식별자>/ 아래 남긴다.

- report.json: 설정, JVM/DB 자원 제한, 데이터 크기, 구간별 결과와 기능 무결성.
- <구간>.json: 저장 지연·open-loop 주입 지연·대화 대기·오류·backlog·풀/heap 상한·SQL 상위 비용.
- <구간>-metrics.ndjson: 매초 수집한 역할별 CPU/메모리/GC/풀과 큐·잠금 상태.
- <역할>-<번호>.log: 각 자식 JVM의 안전한 구조화 로그.
- java.args / ready.json: 시험용 실행 조건과 자식 PID·loopback 포트.
- runtime/: 첫 역할 기동 전에 복사한 저장소 build 산출물. 다른 빌드가 실행돼도 재기동 역할에 다른 코드가 섞이지 않는다.

운영 계정의 인증 토큰이나 원본은 기록하지 않는다. 실행 인자는 시험용 DB/서명 설정만 포함한다.
누적 DB는 원본·AI 메시지와 현재 일기, 35일 작업 보존 지연과 일부 실패 작업을 합성한다.
본문은 가짜 ASCII 데이터다. 실제 한국어 분포·첨부·백업·전체 운영 가입자 수를 재현했다고 주장하지 않는다.
데이터를 직접 넣은 마감 fixture는 API 접수/요금제 집행 검증에 포함하지 않는다.
마감 원본 fixture는 한 트랜잭션으로 넣어 일부 원본만 보고 생성하는 경쟁을 막는다.
혼합 부하 중 무료 수정 거부·구독 수정·version 충돌·늦은 원본 폐기와 quota/생성 메타데이터 보존을 별도로 확인한다.
업무 스케줄러는 대화 50ms·일기 100ms poll로 시험한다. 운영 기본 1초 poll의 성능 결과로 해석하지 않는다.
자동 정리 스케줄러의 최초 실행은 24시간 뒤로 미루고, 혼합 구간에서 각 워커의 실제 100개 정리 함수를 매초 호출한다.
1초 표본으로 수집한 풀·메모리 최고값은 표본 사이의 순간 최대값을 보장하지 않는다.

사용자 수는 ceil(예정 요청 수 / 18), 최소 10명으로 정한다. 한 사용자에게 18회 이하를 보내며 같은 사용자에게는 고정 타임존을 사용한다.
짧은 구간에서는 동일 기록 입력이 1초 LLM보다 빨리 반복될 수 있어 기록별 순서 대기가 생긴다. 짧은 failure 시험을 P0 용량 승인으로 해석하지 않는다.

주입기는 응답을 기다려 요청 수를 줄이는 closed-loop 방식이 아니다.
512개 in-flight 상한에 막힌 예정 요청을 generatorRejected로 기록한다.
HTTP 지연 외에 예정 시각부터 응답까지 지연과 주입 지연을 제공한다.
보고서 `endToEndLatency`는 예정 전송 시각부터 접수 HTTP 응답까지다. AI 작업 대기와 공급자 실행 시간은 별도 항목이다.
generatorRejected가 있으면 해당 목표 RPS를 통과한 것으로 처리하지 않는다.

대화 대기는 실제 작업 created_at부터 fake provider 진입까지다.
최대 5만 개의 균등 reservoir 표본을 사용하고 표본 수를 제공한다.
아직 시작하지 못한 작업은 대기 percentile에서 제외되므로 backlog/미완료 상태와 함께 판단한다.
미완료 backlog가 남으면 대화 대기 목표는 실패다.

SQL calls/rows/실행 시간/shared hit/read는 pg_stat_statements 전후 차이로 읽는다.
현재 SQL 상위 비용도 구간별 차이를 사용한다. 초기 단기 산출물의 누적 SQL에는 fixture·ANALYZE 비용이 포함되므로 직접 비교하지 않는다.
부하 주입·원본 검증·메트릭 수집 자체의 관측 쿼리도 별도로 고려해야 한다.
페이지 hit는 디스크 I/O 보장이나 전송 바이트 수와 같지 않다.
실측한 DB 크기 이상 또는 다른 운영 사양으로 결과를 선형 확장하지 않는다.

## 실제 공급자 스모크

```powershell
.\gradlew.bat :bootstrap:providerSmokeTest --console=plain --no-daemon
```

GEMINI_API_KEY가 없으면 명시적으로 생략된다.
설정된 gemini-flash-latest(또는 CAPACITY_SMOKE_GEMINI_MODEL)에 최대 두 건의 합성 요청을 보낸다.
일반 응답과 일기 JSON 계약만 확인하며 실제 일기 원문을 보내지 않는다.
스모크 성공은 공급자 동시 호출·RPM·TPM·비용 예산 승인이 아니다.

측정 상태와 승인 범위는 [부하 기준](CAPACITY.md)과 실행 결과 보고서를 따른다.

