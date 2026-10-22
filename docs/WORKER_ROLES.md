# 실행 역할과 자원 예산

같은 backend jar를 API·대화 워커·일기 워커로 각각 실행한다.

## 실행

로컬 DB가 준비된 상태에서 각 명령을 별도 터미널에서 실행한다.

```powershell
cd apps/backend
.\gradlew.bat :bootstrap:bootRun --args="--spring.profiles.active=local,api"
.\gradlew.bat :bootstrap:bootRun --args="--spring.profiles.active=local,conversation-worker"
.\gradlew.bat :bootstrap:bootRun --args="--spring.profiles.active=local,journal-worker"
```

한 프로세스의 로컬 개발은 local,local-all을 사용한다.
기본 역할은 API다. 운영은 역할 프로필 하나와 DB·인증·공급자 설정을 외부에서 주입한다.
운영 복합 역할과 역할 프로필 중복은 기동 검증에서 거절한다.
API는 servlet, 워커는 none web application type이다.
활성 역할은 기동 로그에 남는다.

## 역할과 기본값

| 역할 | 실행 경로 | DB pool 최대/최소 | 실행 슬롯 | provider별 동시 호출 상한 |
|---|---|---:|---:|---:|
| API | HTTP 저장·조회·재시도 접수 | 10 / 2 | 해당 없음 | 해당 없음 |
| conversation-worker | 대화 응답·종료 작업 정리 | 4 / 1 | 8 | 8 |
| journal-worker | 마감·전달·일기 생성·종료 작업 정리 | 2 / 1 | 2 | 2 |

수치는 설정 기본값이며 성능 시험 설정과 별개다.
외부 호출 동안 DB 트랜잭션을 유지하지 않는다.
선점·입력 조회·결과 저장에서 연결을 사용한다.
프로세스별 pool·호출 제한·circuit breaker는 독립적이며 DB와 공급자 계정의 총량은 공유한다.

API에 HTTP·보안 빈이, 워커에 해당 역할의 LLM·스케줄 빈이 등록된다.
설정 바인딩도 역할별 JWT/Google 또는 LLM 사용 범위를 따른다.
역할별 등록 계약은 WorkerRoleIsolationIntegrationTest가 검사한다.

## 작업 실행

실행 풀은 가상 스레드를 사용하고 슬롯 수로 동시 작업을 제한한다.
대화는 남은 슬롯을 먼저 예약하고 한 트랜잭션에서 최대 claim-batch-size(기본 32, 상한 64)개를 선점한다.
인스턴스별 선점 트랜잭션은 하나이며 기록마다 한 작업을 포함한다.
기록 잠금 후 새 READ COMMITTED 문장에서 선두를 확인한다.
실제 AI 실행은 예약 슬롯의 스레드에서 병렬로 진행한다.

일기는 슬롯 안에서 한 작업씩 선점한다.
선점 실패·빈 결과·종료 시 사용하지 않은 슬롯을 반환한다.
커밋 후 실행 인계 전에 종료된 작업은 리스 만료로 회수한다.
대화 빈 큐는 poll 간격의 절반 동안 재탐색을 생략한다.
대화·일기의 기본 poll 간격은 각각 1초다.

마감·전달은 별도 journalDeliveryScheduler에서 기본 5초 간격으로 처리한다.
마감·전달 각각 최대 100건, 실행당 최대 5회 반복하며 2초 경과 뒤 새 반복을 시작하지 않는다.
전달 리스는 1분이다. 설정은 moeum.journal.delivery.interval과 initial-delay다.
종료 작업 정리는 역할별 독립 스케줄러에서 실행하며 [보존 계약](OBSERVABILITY_RETENTION.md)을 따른다.

## 배포 설정

| 환경변수 | 의미 |
|---|---|
| API_DB_POOL_SIZE | API 인스턴스 최대 DB 연결 |
| CONVERSATION_DB_POOL_SIZE | 대화 인스턴스 최대 DB 연결 |
| JOURNAL_DB_POOL_SIZE | 일기 인스턴스 최대 DB 연결 |
| CONVERSATION_WORKER_CONCURRENCY | 대화 실행 슬롯 |
| CONVERSATION_CLAIM_BATCH_SIZE | 대화 배치 선점 상한 |
| JOURNAL_WORKER_CONCURRENCY | 일기 실행 슬롯 |
| CONVERSATION_LLM_CONCURRENCY | 대화 프로세스 provider별 동시 호출 |
| JOURNAL_LLM_CONCURRENCY | 일기 프로세스 provider별 동시 호출 |

기동 시 역할별 슬롯 합계와 provider 호출 상한을 검증한다.
DB pool의 minimum-idle은 maximum-pool-size 이하여야 한다.

```text
DB 최대 연결 = Σ(역할별 최대 replica × 해당 pool)
provider 동시 호출 = Σ(해당 provider를 쓰는 역할별 최대 replica × 호출 상한)
```

롤링 배포의 임시 replica와 관리·마이그레이션 연결도 전체 예산에 포함한다.
현재 저장소에는 autoscaling 배포 구성이 없다.
운영 replica와 공급자 RPM/TPM·비용 예산은 배포 설정에서 확정할 항목이다.

## 종료와 복구

종료 시 scheduler가 멈추고 실행 풀은 새 작업을 받지 않는다.
기존 실행은 moeum.runtime.shutdown-timeout(기본 10초) 동안 기다린 뒤 interrupt한다.
JPA 종료 전에 실행 풀의 완료 대기가 수행된다.
저장하지 못한 PROCESSING 작업은 리스 만료 후 회수한다.
version이 다른 이전 워커의 결과는 저장 시 거절한다.
오류별 재시도 계약은 [대화](CONVERSATION.md)와 [일기](JOURNAL.md)에 있다.

## 검증과 빌드

일반 회귀는 apps/backend에서 다음 명령으로 실행한다.

```powershell
.\gradlew.bat test --console=plain --offline --no-daemon
```

일반 회귀는 단위·실제 PostgreSQL 통합·모듈 의존성 검사를 포함한다.
독립 JVM 강제 종료·부하 시험은 capacityTest, 실제 공급자 스모크는 providerSmokeTest로 실행한다.
절차는 [시험 실행](CAPACITY_RUNBOOK.md)을 따른다.

Gradle wrapper 기본 사용자 캐시는 apps/backend/.gradle/user-home이다.
GRADLE_USER_HOME이 지정되면 해당 경로를 우선한다.
