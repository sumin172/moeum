# 부하·복구 시험 실행

성능·장애 시험은 일반 회귀 테스트와 분리한다. 실행 전 처리량·데이터 크기·시간·자원 예산·합격 기준을 정한다.
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
시험의 backoff는 대화 1/2초, 일기 1/2/4/8초이며 429 최소 대기는 2초다. 운영 기본 backoff를 검증했다는 뜻은 아니다.
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
일기가 완료되면 무료 수정 거부·구독 수정·version 충돌·마감 후 다음 기록 귀속과 quota를 확인한다. 주입 중 완료되지 않으면 일기 drain 이후 검사한다.
업무 스케줄러는 대화 50ms·일기 100ms poll로 시험한다. 운영 기본 1초 poll의 성능 결과로 해석하지 않는다.
자동 정리 스케줄러의 최초 실행은 24시간 뒤로 미루고, 혼합 구간에서 각 워커의 실제 100개 정리 함수를 매초 호출한다.
1초 표본으로 수집한 풀·메모리 최고값은 표본 사이의 순간 최대값을 보장하지 않는다.

benchmark·smoke·failure의 사용자 수는 ceil(예정 요청 수 / 18), 최소 10명으로 정한다. 한 사용자에게 18회 이하를 보내며 같은 사용자에게는 고정 타임존을 사용한다.
이 시나리오들은 구간마다 새 사용자를 만들고 순번대로 순환시키므로, 첫 기록 생성이 구간 초반 10초에 집중된다. 시험 길이를 늘리면 초반 신규 기록 수도 늘어난다.
`sustained`는 사용자 수를 ceil(예정 요청 수 / 17)로 정하고 각 사용자에게 첫 메시지를 API로 미리 접수해 응답 작업까지 완료한 뒤 측정한다.
측정 구간은 사용자당 최대 17회를 보내므로 준비 포함 18회 이하이며, 준비 시간·요청은 지연 통계에 넣지 않고 보고서의 `cohortPreparations`에 기록한다.
첫 기록 집중 생성의 영향과 기존 기록에 쌓이는 대화의 지속 부하를 별도로 판단한다.
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
기본 gemini-3.1-flash-lite(또는 CAPACITY_SMOKE_GEMINI_MODEL)에 최대 두 건의 합성 요청을 보낸다.
일반 응답과 일기 JSON 계약만 확인하며 실제 일기 원문을 보내지 않는다.
스모크 성공은 공급자 동시 호출·RPM·TPM·비용 예산 승인이 아니다.

## 실제 일기 생성기 검증

```powershell
.\gradlew.bat :bootstrap:journalProviderTest '-PjournalValidation.case=representative' --console=plain --offline --no-daemon
```

기본 실행은 지정한 합성 사례로 일기 생성 요청 한 건만 보낸다. 자동 재시도와 DB·스케줄러는 실행하지 않는다.
일반 test·capacityTest·providerSmokeTest에서는 이 시험을 실행하지 않는다.
환경변수·JVM 속성 다음으로 application-local.yml, 해당 경로의 worker 설정, application.yml 순으로 설정을 읽는다.
기본 실행은 설정된 journal-generation Gemini 경로와 실제 LlmJournalGenerator의 프롬프트·JSON 검증을 사용한다. 모델·출력·thinking·타임아웃은 해당 경로의 설정을 따른다.
인증 키는 환경변수 GEMINI_API_KEY 또는 로컬 설정의 moeum.gemini.api-key로 제공하며 출력하지 않는다.
대화 경로는 Cloudflare도 지원하며 로컬 `moeum.cloudflare` 또는 `CLOUDFLARE_ACCOUNT_ID`·`CLOUDFLARE_API_TOKEN`을 사용한다.
대화 품질 합성 사례 3건을 각각 한 번 실행하는 명령은 다음과 같다. DB·스케줄러·자동 재시도는 실행하지 않는다.

```powershell
.\gradlew.bat :bootstrap:journalProviderTest '-PjournalValidation.case=quality' '-PjournalValidation.variant=conversation' --console=plain --offline --no-daemon
```

분리 경로의 확장 검증은 다음 명령으로 실행한다. 품질 3사례×3회, 긴 문맥 2회, 범위 이탈·추론 유도 각 1회, 실제 응답 누적 10턴과 일기 1회, 동시 대화 8회를 포함해 총 32회다. 이 모드에서는 case·repeats 대신 고정된 검증 구성을 사용한다.

```powershell
.\gradlew.bat :bootstrap:journalProviderTest '-PjournalValidation.variant=extended' --console=plain --offline --no-daemon
```

동시 8회는 개별 클라이언트로 공급자를 호출하므로 서버·DB 부하 시험이나 장시간 처리량 보장은 아니다.
수집한 합성 응답의 사실·감정 충실도와 말투는 수동으로 검토한다. 자동 성공은 정상 종료·응답 생성·일기 JSON 계약까지의 판정이다.
IntelliJ 실행 구성에만 지정한 환경변수는 터미널 실행에 전달되지 않는다.

| journalValidation.variant | 실행 경로                                                                          |
|---------------------------|------------------------------------------------------------------------------------|
| baseline (기본값)         | 실제 일기 생성기 한 번                                                             |
| conversation              | 실제 대화 응답기 한 번. conversation-response 설정 사용                            |
| compare                   | 대화 응답기와 일기 생성기를 순서대로 각각 한 번. 앞 요청이 실패해도 다음 요청 실행 |

baseline·conversation·compare는 같은 합성 사례를 사용한다. 진단용 변경은 시험에만 적용하며 운영 설정을 수정하지 않는다.
예를 들어 `'-PjournalValidation.variant=conversation'`을 추가하면 대화 응답만 실행한다.
`'-PjournalValidation.variant=baseline' '-PjournalValidation.model=gemini-3.1-flash-lite'`처럼 일기 경로의 모델을 명시하면 해당 실행에만 적용한다. 모델 override는 공급자를 바꾸지 않으므로 선택한 경로의 공급자가 지원하는 모델이어야 한다.
결과의 configuredModel은 YAML의 경로 설정, requestedModel은 시험에서 요청한 모델이며 observedModel은 관측값이다.
생성기의 JSON 파싱·공백·길이 검사 실패 메시지는 journalValidationFailure로 기록한다. 공급자 오류 본문과 인증 값은 기록하지 않는다.

설정된 대화·일기 경로의 대표 입력 검증 명령은 다음과 같다.

```powershell
.\gradlew.bat :bootstrap:journalProviderTest '-PjournalValidation.case=representative' '-PjournalValidation.variant=compare' --console=plain --offline --no-daemon
```

이 명령은 대화·일기 각 1회, 총 2회를 호출한다. 대표·품질 사례 확인 후 최대 USER 입력으로 범위를 넓힌다.
각 단계에서 프로젝트의 잔여 RPM·TPM·RPD와 호출 예산을 확인하며, 대용량 요청은 같은 분의 누적 입력 토큰을 고려해 간격을 둔다.
대화 출력 150토큰·일기 출력 2,000토큰과 thinking 설정의 호환성·출력 잘림도 실제 응답으로 확인한다. 모델 검증 상태는 [검증 현황](CAPACITY_RESULTS.md)을 따른다.

| journalValidation.case | 합성 입력                                                         |
|------------------------|-------------------------------------------------------------------|
| representative         | 산책·발표·저녁의 대표 대화. 장소 정정과 짧은 부정 포함            |
| free-max               | 설정된 무료 횟수 × 메시지 최대 길이의 USER 입력 + ASSISTANT 문맥  |
| premium-max            | 설정된 구독 횟수 × 메시지 최대 길이의 USER 입력 + ASSISTANT 문맥  |
| negation               | 면접·미확정 결과·동생과의 식사. AI의 잘못된 해석에 대한 부정 포함 |
| changed-plans          | 취소한 등산·영화와 실제 도서관 방문·지갑 회수                     |
| quality                | representative, negation, changed-plans를 차례로 실행             |

`journalValidation.repeats`는 사례별 반복 횟수이며 기본 1, 상한 3이다.
호출 수는 사례 수 × 반복 횟수 × variant 수다. quality·compare·repeats=2는 12회 호출한다.
429·503도 결과에 남기며 자동 재호출하지 않는다. 이때 생성 품질과 공급자 가용성 판정을 구분한다.

최대 사례도 마지막 USER까지만 포함한다. AI 문맥은 합성 가정이며 전체 토큰 수의 최악 조건을 보장하지 않는다.
대표 대화의 반복 검증은 같은 representative 사례로 별도 실행한다.
결과는 build/capacity/journal-provider 아래에 입력 해시·문자 수·관측 모델·프롬프트 버전·토큰 수·종료 사유·지연과 합성 일기를 기록한다.
공급자 응답이 없으면 observedModel과 토큰 수는 null이다. 요청 모델은 requestedModel에 남긴다.
요청 실패도 안전한 오류 코드와 관측 정보를 남긴다. 결과 파일에는 키나 실제 사용자 원문을 기록하지 않는다.
HTTP 오류는 기존 Spring 오류 처리기로 변환하고, JSON API 상태·Content-Type·초 단위 Retry-After와 제한된 원인 분류만 httpFailure에 기록한다.
모델 수용량 부족·무료 등급 제약·모델 미지원·비활성화와 JSON API 오류가 아닌 응답을 구분한다. 오류 본문과 인증 정보는 기록하지 않는다.
시험 통과는 생성기의 형식·길이 검증 통과를 의미한다. 품질은 expectedFacts와 본문을 비교해 수동으로 검토하고 [검증 현황](CAPACITY_RESULTS.md)에 기록한다.
기대 사실은 사례별 expectedFacts를 따른다. 대표 사례는 서울숲 산책, 칭찬받지 않은 발표 완료, 민지와 버섯 파스타를 먹으며 느낀 안도감이다.
한도 초과·타임아웃·잘림은 각각 구분하고, 실패한 요청을 같은 실행에서 반복하지 않는다.

## 운영 주기의 지속 정리 검증

```powershell
.\gradlew.bat :bootstrap:capacityTest --tests com.moeum.capacity.RetentionCapacityTest '-Pcapacity.retention=true' '-Pcapacity.durationSeconds=1800' '-Pcapacity.warmupSeconds=30' '-Pcapacity.rps=25' '-Pcapacity.historyRecords=10000' '-Pcapacity.journalBurst=100' '-Pcapacity.drainSeconds=180' --console=plain --offline --no-daemon
```

명시적인 capacity.retention=true에서만 실행한다. 일반 부하 시험에 자동으로 추가되지 않는다.
API·대화·일기 JVM은 각각 한 개이며 conversationReplicas는 1만 허용한다.
시험 전용 PostgreSQL(CPU 4, 메모리 4GiB)과 API·대화·일기 JVM(heap 각 512MiB)을 사용한다.
DB 풀·슬롯·fake 지연은 CapacitySettings에 지정한 값을 보고서에 남긴다. 별도 설정이 없으면 풀 10/4/2, 슬롯 8/2, fake 지연 100/200ms다.
정리 워커는 운영의 최초 1분 대기·10초 간격·실행당 최대 10배치/2초 예산으로 동작한다. /clean 수동 호출은 사용하지 않는다.
워밍업 접수·응답을 완료한 후 측정하며 워밍업 요청은 지연 통계에서 제외한다.
30일 경과 작업의 유입을 압축 재현하기 위해 10초마다 닫힌 기록 13개·USER 260개·ASSISTANT 247개·종료 응답 작업 260개·종료 일기 작업 13개를 직접 저장한다.
FAILED 응답과 FAILED 일기 작업을 포함하며 실패 일기는 created_at만 30일 경과하고 updated_at은 최근인 사례도 포함한다.
이는 실제 30일 대기를 검증하는 시험이 아니며 API 부하와 별도의 만료 작업 처리 부하다.
중간에 일기 워커를 min(60초, durationSeconds/6) 동안 중단한 후 재기동하며, 종료 후 최대 drainSeconds 동안 정리와 대화 완료를 기다린다.
30분 시험의 중단 시간은 60초다. 3분 이하의 도구 확인 시험은 초기 3분 이후의 안정 구간 적체 상한 판정을 포함하지 않는다.
최종 만료 작업 0건, 모든 접수·응답 완료, 원본 보존, 보존 기간 전 응답 작업 유지와 기존 API·응답 대기 목표를 검사한다.
report.json과 samples.ndjson은 build/capacity/retention-* 아래에 생성한다. 시험 실행 중 클래스패스를 다시 빌드하지 않는다.
재기동 대기는 별도 시험 스레드에서 수행하며 API·만료 작업 주입은 계속한다.
대화·일기 선점 poll은 기존 부하 도구의 50/100ms 설정이며 운영 기본 1초와 다르다.
전체 응답 대기와 30초 구간별 대기를 함께 보고하고, 표본에 활성 응답 작업 수·DB 대기 유형을 기록한다.
워밍업의 DB 대기는 warmup-waits.ndjson에 초 단위로 남긴다. 접수 실패는 즉시 실패로 판정하며 실패 요청의 응답 완료를 기다리지 않는다.
30분 결과는 그 데이터 규모·유입량에서의 검증이며 장기 물리 저장 공간이나 실제 공급자 처리량의 승인이 아니다.

합격 기준은 [시험 기준](CAPACITY.md), 측정 상태와 승인 범위는 [검증 현황](CAPACITY_RESULTS.md)을 따른다.
