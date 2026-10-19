# 실행 역할과 자원 예산

현재 구현 기준이다. 같은 backend jar를 API·대화 워커·일기 워커로 각각 실행한다.
하루 기록과 마감 전달은 RecordDay를 사용한다. 서버 구독 한도와 응답 순서를 집행하며 일기는 생성 즉시 보관하고 구독자만 수정할 수 있다.

## 실행 방법

로컬 DB가 준비된 상태에서 각 명령을 별도 터미널에서 실행한다.

```powershell
cd apps/backend
.\gradlew.bat :bootstrap:bootRun --args="--spring.profiles.active=local,api"
.\gradlew.bat :bootstrap:bootRun --args="--spring.profiles.active=local,conversation-worker"
.\gradlew.bat :bootstrap:bootRun --args="--spring.profiles.active=local,journal-worker"
```

한 프로세스로 기능 개발할 때는 `local,local-all`을 사용한다.
기본 실행 역할은 API다. `bootRun`의 기본 `local` 프로필도 API만 실행한다.
운영에서는 `local` 없이 역할 프로필 하나를 지정하고 DB 접속 정보와 필요한 인증/공급자 설정을 외부에서 주입한다.
API·conversation-worker·journal-worker 프로필의 중복 지정과 운영 복합 역할은 기동 시 거부한다.
API는 servlet, 워커는 none web application type을 사용한다. 활성 역할은 기동 로그에 남는다.

## 역할별 책임

| 역할                | 등록되는 실행 경로                                      | 기본 DB pool 최대/최소 | 실행 슬롯 | provider별 동시 호출 상한 |
|---------------------|---------------------------------------------------------|-----------------------:|----------:|--------------------------:|
| API                 | 업무 HTTP, 저장/조회/재시도 접수                        |                 10 / 2 |      없음 |                      없음 |
| conversation-worker | 응답 poll·완료 작업 정리, 대화 LLM 실행                 |                  4 / 1 |         8 |                         8 |
| journal-worker      | 기록 마감·전달, 일기 poll·완료 작업 정리, 일기 LLM 실행 |                  2 / 1 |         2 |                         2 |

숫자는 초기 구성값이며 처리량 측정 결과가 아니다. pool 크기는 동시 LLM 수와 같을 필요가 없다.
외부 호출 동안 트랜잭션/커넥션을 유지하지 않고, 선점·원본 조회·결과 저장 때만 DB를 사용한다.
별도 프로세스의 pool·Semaphore·circuit breaker는 서로 공유되지 않는다. DB 서버와 공급자 프로젝트의 총량은 공유한다.

API에는 응답/일기 executor·생성 어댑터·LLM client/provider·스케줄러가 등록되지 않는다.
워커에는 업무 HTTP controller·security filter·Google 로그인·JWT 발급 빈이 등록되지 않는다.
역할이 사용하지 않는 JWT/Google 또는 LLM 설정도 바인딩하지 않는다.
업무 저장소와 공개 조회 계약은 같은 jar에서 필요한 서비스를 지원하도록 유지한다.

각 poll scheduler는 독립된 1개 스레드를 사용하고 외부 호출을 기다리지 않는다.
기록 마감·전달은 `journalDeliveryScheduler`에서 기본 5초 간격으로 실행한다. 마감·전달을 각각 100건씩 처리한 뒤 대상이 남으면 한 실행에서 최대 5회 이어 처리한다. 2초가 지나면 다음 반복을 시작하지 않고, 대상이 없으면 즉시 멈춘다. 일기 poll과 스레드를 분리하며 LLM 호출을 수행하지 않는다.
마감, 전달 선점, Journal 등록, 전달 확인은 각각 독립 트랜잭션이다. 전달 리스는 1분이고 `moeum.journal.delivery.interval`과 `initial-delay`로 실행 간격과 초기 시각을 설정한다.
실행 풀은 대기열 없이 가상 스레드 실행 슬롯을 제한한다.
대화는 실행 슬롯을 먼저 예약하고 한 트랜잭션에서 최대 `claim-batch-size`(기본 32, 상한 64)개의 작업을 선점한다.
실제 예약 수는 남은 실행 슬롯 이내다. 인스턴스마다 선점 트랜잭션은 하나만 진행하고 작업 실행은 가상 스레드에서 병렬로 처리한다.
일기는 슬롯 안에서 한 작업씩 선점한다. 슬롯이 없으면 DB 선점 자체를 하지 않는다.
선점 실패·빈 결과·종료 시 예약 슬롯을 반환한다. 커밋 뒤 종료로 인계하지 못한 작업은 기존 리스 만료로 회수한다.
선점은 대기 작업의 예약 시각·처리 중 작업의 리스 만료 시각을 사용하는 부분 인덱스에서 후보를 찾는다.
기록 잠금 뒤 별도 READ COMMITTED 문장에서 선두 작업을 다시 검사해 과거 작업의 동시 재시작까지 순서를 지킨다.
한 배치에는 기록별로 하나만 포함한다.
작업이 없으면 poll 간격의 절반 동안 인스턴스에서 빈 큐 재탐색을 생략한다.
입력 접수는 영향을 받지 않고 다음 정규 poll에서 다시 탐색할 수 있다.
poll은 기본 1초 간격이며 대화/일기별로 독립 설정한다.

API는 메시지와 응답 작업을 저장하고 반환한다. 최초 실행·재시도·리스 회수는 모두 대화 poll 경로를 사용한다.
API 요청 직후 실행하는 Trigger·`@Async` 진입점·작업 ID 지정 선점은 제거했다.
일기 생성도 동기 반복 루프를 제거하고 슬롯 안에서 한 작업씩 실행한다.

## 설정과 배포 상한

| 환경변수                          | 의미                                          |
|-----------------------------------|-----------------------------------------------|
| `API_DB_POOL_SIZE`                | API 인스턴스 DB 최대 연결 수                  |
| `CONVERSATION_DB_POOL_SIZE`       | 대화 인스턴스 DB 최대 연결 수                 |
| `JOURNAL_DB_POOL_SIZE`            | 일기 인스턴스 DB 최대 연결 수                 |
| `CONVERSATION_WORKER_CONCURRENCY` | 대화 실행 슬롯                                |
| `CONVERSATION_CLAIM_BATCH_SIZE`   | 예약 슬롯 이내 배치 선점 상한, 기본 32 (1~64) |
| `JOURNAL_WORKER_CONCURRENCY`      | 일기 실행 슬롯                                |
| `CONVERSATION_LLM_CONCURRENCY`    | 대화 프로세스의 gemini/claude 동시 호출 상한  |
| `JOURNAL_LLM_CONCURRENCY`         | 일기 프로세스의 gemini/claude 동시 호출 상한  |

기동 검증은 활성 역할이 사용하는 provider별 슬롯 합계가 해당 프로세스의 LLM 상한을 넘지 않도록 한다.
다른 provider를 추가하면 `moeum.llm.max-concurrent-calls.<provider>`를 별도로 설정한다. 미지정 기본 상한은 10이다.
LLM 상한은 양수, permit 대기 시간과 종료 대기 시간은 0 이상이어야 한다.
DB pool 최대값을 줄일 때는 `spring.datasource.hikari.minimum-idle`도 그 이하로 지정한다.

운영 배포의 **최대 replica 수**를 기준으로 다음 두 산식을 모두 만족해야 한다.

```text
DB 연결 상한 = API 최대 replica × API pool
             + 대화 최대 replica × 대화 pool
             + 일기 최대 replica × 일기 pool

provider 동시 호출 상한 = 대화 최대 replica × 해당 provider 대화 호출 상한
                       + 일기 최대 replica × 해당 provider 일기 호출 상한
```

롤링 배포 시 일시적으로 증가하는 replica도 합산한다. DB에는 관리·마이그레이션·복구 연결 여유를 둔다.
provider별 합계는 같은 프로젝트/계정 한도를 공유하는 다른 서비스의 호출을 포함해야 한다.
예를 들어 API 4개·대화 4개·일기 2개가 최대라면 기본 설정으로 DB 최대 60개 연결, 같은 provider 최대 36개 동시 호출이다.
이 replica 수는 산식 설명용 가정이며 배포 설정/운영 승인값이 아니다.
실제 DB 연결 한도와 공급자 동시 호출·RPM·TPM 예산을 확보하기 전에는 이 수치를 용량 보장으로 사용하지 않는다.
현재 저장소에는 autoscaling 배포 설정이 없으므로 replica 상한은 배포 단계에서 명시해야 한다.
동적 확장을 도입하려면 인스턴스 Semaphore 외에 전체 공급자 예산을 집행할 수단이 필요하다.

## 중단과 복구

종료 시 poll scheduler가 멈추고 실행 풀은 새 작업을 받지 않는다.
기존 작업에는 `moeum.runtime.shutdown-timeout`만큼 완료 시간을 주고 이후 interrupt한다.
실행 풀을 JPA보다 먼저 종료하도록 빈 종료 순서를 지정해 완료 대기 중에는 DB 저장이 가능하게 한다.
가상 스레드는 강제 중단을 보장하지 않으므로 작업 실패 기록을 남기지 못할 수 있다.
처리 중 상태가 남으면 기존 리스 만료 후 다른 워커가 회수한다.
선점 시도마다 version을 증가시키고 기존 낙관적 잠금으로 늦은 결과 저장을 거부한다.
재시도/backoff·시도 상한·결과 저장 트랜잭션은 기존 정책을 유지한다.

## 검증 범위

검증 명령: `apps/backend`에서 `.\gradlew.bat test --console=plain --offline --no-daemon`.
2026-10-18 검증: 6개 모듈 일반 회귀 161개 통과, 실패·오류·생략 0개다. [집계](capacity/regression-summary.json).
문서 로컬 참조와 `git diff --check`를 검사한다.

- 실행 풀 테스트: 슬롯 소진 시 추가 작업/선점 미실행, 다른 역할 진행, 실패 후 슬롯 반환, 종료 후 거절.
  배치 예약 상한·미사용 슬롯 반환·단일 선점 조정자·AI 실행과 다음 선점의 병렬 진행·선점 예외 후 슬롯 반환을 검사한다.
- 구성 테스트: 운영 복합 역할·역할 프로필 중복·HTTP 워커·LLM 예산 초과 거절.
- 역할 통합 테스트: 신규 PostgreSQL/Flyway에 독립 ApplicationContext와 DB pool로 API/대화/일기 역할 기동.
  API의 AI/스케줄 빈 부재, 워커의 업무 endpoint/JWT 빈 부재와 역할별 pool 상한을 검사한다.
  일기 fake provider를 대기시킨 상태와 일기 context 종료 후 HTTP 저장·대화 완료를 검증한다.
  재기동 시 만료된 PROCESSING 리스 회수와 일기 한 건 저장을 검증한다.
- 기록 테스트: DST gap/overlap·장소 이동·36시간 상한·발화 정밀도, 동시 중복·마감 경합·늦게 접수된 원본의 다음 기록 귀속·전달 리스/오래된 ack·날짜 중복을 검사한다.
- 재기동한 일기 워커가 최초 시도와 동일한 LLM 입력을 사용하는지 검사한다.
- 대화 테스트: 마지막 잔여 횟수 동시 접수·구독 변경/하향·UTC 사용 기간·접수 속도 제한·실패 재시도 무차감,
  동시 최초 기록 생성·배치 상한/기록별 선두 선점·기록별 다중 워커 선점·backoff 순서·후속 입력 제외·선행 답변 포함·정수 커서 동시 저장·리스 회수 후 저장 롤백,
  메시지 없는 최종 실패 묶음 조회·소유권·후속 응답 이후 retryable 차단을 검사한다.
- 일기 테스트: 생성 즉시 조회·구독 수정/무료 차단·같은 내용 재저장·동시 수정 한 건 승인,
  만료된 생성의 늦은 결과와 사용자 수정본 보호·기존 결과 복구,
  무료 실패 재시도 동일 입력/무차감·삭제된 확정 경로 404를 검사한다.
- 기존 실제 DB 테스트: 여러 워커의 SKIP LOCKED 선점, 만료 리스 회수, 오래된 version 저장 거부.
- R5 관측 테스트: 실제 Logstash JSON console 출력·로컬 JMX Metrics 등록·job/trace/generation 연결,
  timeout/429/일기 파싱 실패 원문 미노출, 큐 포화·수집기 실패·종료 상한과 유실 카운터,
  실제 콘솔 appender가 정체돼도 ERROR 로그 발행이 대기하지 않음과 콘솔 유실 메트릭,
  수집기 전송 정체 중 원본 접수·일기 저장 진행을 검사한다.
- R5 보존 테스트: 작은 배치 정리·활성/실패/열린 기록/최근 완료 보존·잠긴 기록 건너뛰기,
  후속 완료 작업 정리 뒤에도 ASSISTANT 메시지로 과거 실패 재시도 차단 유지, 원본 중복 접수·수정본/원본 생성 정보 보존,
  작업 정리 후 일기 재전달 멱등 등록·생성 재시도 409 유지와 V1~V4 초기 스키마를 검사한다.
- 모듈 경계/순환 의존 검사와 기존 기능 회귀 테스트.
- R1~R4 잔여 항목 점검 후 source/local_date/메시지 deleted_at 없는 V2/V3 초기 스키마,
  미사용 응답 전달 필드·테스트 의존성 제거 상태에서 전체 회귀를 검사한다.

역할 통합 테스트는 하나의 테스트 JVM 안에서 독립 context를 사용한다.
OS 강제 종료 뒤 남을 작업 상태는 DB에서 PROCESSING/만료 리스로 재현한다.
독립 JVM 부하·강제 종료 시험은 일반 회귀와 분리한 `capacityTest`에서 수행한다. [측정 결과](CAPACITY_RESULTS.md)에서 실행 설정과 실측·승인 범위를 구분한다.
실제 공급자 스모크는 키가 없어 생략됐다. 장시간 부하·P2·6개월 누적 데이터·공급자 용량은 미검증이다. 공유 DB의 총 자원은 역할 간 공유한다.

## 후속 단계

RecordDay/마감 예약과 일기 입력의 구현 계약은 [하루 기록](RECORD_DAY.md)을 따른다.
서버 구독 한도와 응답 순서·조회 계약은 [대화 계약](CONVERSATION.md)을 따른다.
자동 보관·구독 수정·수정본 보호는 [일기 계약](JOURNAL.md)을 따른다.
호출별 진단 DB 원장 대신 bounded 관측 큐·JSON 로그·메트릭을 사용한다. 완료 작업 정리는 각 역할의 별도 scheduler로 실행한다. [관측·보존](OBSERVABILITY_RETENTION.md)을 따른다.
R1의 실행 역할 분리가 이 기능들의 구현 완료를 의미하지 않는다.


## 로컬 빌드 캐시

Gradle wrapper의 기본 사용자 캐시는 `apps/backend/.gradle/user-home`이다. 기존 `GRADLE_USER_HOME` 환경변수가 있으면 그 경로를 우선하며 IDE에서 wrapper를 거치지 않으면 IDE의 Gradle user home도 별도로 맞춘다. 이 경로는 .gitignore의 .gradle 규칙으로 제외된다.
