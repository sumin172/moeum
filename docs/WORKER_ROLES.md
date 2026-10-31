# 실행 역할과 자원 예산

같은 backend jar를 API·대화 워커·일기 워커로 각각 실행한다.

## Cloudflare 대화 연결

대화 기본 경로는 `@cf/qwen/qwen3-30b-a3b-fp8`, 일기는 Gemini다. 자동 공급자 폴백은 없다.
대화 워커를 실행하는 PowerShell 또는 배포 환경에 다음 값을 지정한다. API Token은 해당 계정의 Workers AI 실행 권한이 필요하다.

```powershell
$env:CLOUDFLARE_ACCOUNT_ID = "계정 ID"
$env:CLOUDFLARE_API_TOKEN = "API Token"
```

설정 경로는 `moeum.cloudflare.account-id`, `moeum.cloudflare.api-token`이다.
로컬에서도 환경변수로 주입한다. `application-local.yml`은 저장소가 추적하는 파일이므로 `${CLOUDFLARE_ACCOUNT_ID:}`·`${CLOUDFLARE_API_TOKEN:}`의 콜론 뒤 기본값에 실제 값을 넣지 않는다.
키를 저장소에 기록하지 않는다. 대화 워커는 대화 라우트가 선택한 공급자의 필수 설정이 없으면 기동하지 않는다. 규칙은 [AI 공급자](LLM.md#공급자-설정과-기동-검사)를 따른다.
연결·읽기 제한은 각각 기본 3초·60초이며 `moeum.cloudflare.connection-timeout-ms`, `read-timeout-ms`로 조정한다.
`CONVERSATION_LLM_CONCURRENCY`는 Cloudflare 동시 호출 상한에도 적용한다.
`thinking-budget: 0`은 마지막 사용자 메시지에 `/no_think`를 추가하는 소프트 제어다. 강제 비활성화를 보장하지 않는다.
완결된 선두 추론 블록은 응답에서 제거하고, 미완결 추론·출력 잘림·비정상 종료는 저장하지 않는다.
토큰 수는 공급자의 전체 completion 사용량을 기록하므로 숨긴 추론도 사용량에서 제외하지 않는다.
일시적인 HTTP 오류는 기존 작업 정책의 제한된 재시도를 따른다. 일일 무료량 소진을 별도로 저장하거나 초기화까지 자동 중단하는 기능은 없다.
실제 키를 사용한 합성 대화·일기 연결·8개 동시 호출의 검증 범위와 무료량 계산은 [검증 현황](CAPACITY_RESULTS.md)을 따른다. 무료 티어의 가용성이나 실제 사용자의 입력 분포를 보장하지 않는다.

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

| 역할                | 실행 경로                          | DB pool 최대/최소 | 실행 슬롯 | provider별 동시 호출 상한 |
|---------------------|------------------------------------|------------------:|----------:|--------------------------:|
| API                 | HTTP 저장·조회·재시도 접수         |            10 / 2 | 해당 없음 |                 해당 없음 |
| conversation-worker | 대화 응답·종료 작업 정리           |             4 / 1 |         8 |                         8 |
| journal-worker      | 마감·전달·일기 생성·종료 작업 정리 |             2 / 1 |         2 |                         2 |

수치는 설정 기본값이며 성능 시험 설정과 별개다.
외부 호출 동안 DB 트랜잭션을 유지하지 않는다.
선점·입력 조회·결과 저장에서 연결을 사용한다.
프로세스별 pool·호출 제한·circuit breaker는 독립적이며 DB와 공급자 계정의 총량은 공유한다.

API에 HTTP·보안 빈이, 워커에 해당 역할의 LLM·스케줄 빈이 등록된다.
설정 바인딩도 역할별 JWT/Google 또는 LLM 사용 범위를 따른다. refresh token HMAC 검증 빈은 API 역할에서만 등록된다.
각 역할은 다른 역할의 설정(API의 JWT·Google·refresh token 서명 키, 워커의 LLM 키·호출 설정) 없이 기동하며 그 설정을 검증하지 않는다.
워커도 자기 용도의 라우트가 선택한 공급자 인증만 요구한다. 쓰지 않는 공급자와 다른 워커 용도의 인증은 필요 없다.
역할별 등록 계약과 설정 격리는 WorkerRoleIsolationIntegrationTest가 local 프로필·OS 환경변수·시스템 속성 없이 검사한다.
HTTP 진입점·스케줄 작업·LLM 호출 빈의 역할 지정은 ExecutionRoleRulesTest가 강제한다. 역할을 지정하지 않은 빈은 모든 역할에 등록된다.
등록 방식의 대안과 선택 근거는 [실행 역할별 빈 등록](engineering/execution-role-registration.md)을 따른다.

## 작업 실행

실행 풀은 가상 스레드를 사용하고 슬롯 수로 동시 작업을 제한한다.
대화는 남은 슬롯을 먼저 예약하고 한 트랜잭션에서 최대 claim-batch-size(기본 32, 상한 64)개를 선점한다.
인스턴스별 선점 트랜잭션은 하나이며 기록마다 한 작업을 포함한다.
기록 잠금 후 새 READ COMMITTED 문장에서 작업 상태와 기록의 원본 순번을 다시 읽어 선점 여부와 관측 상한을 정한다.
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

| 환경변수                         | 의미                                                                                    |
|----------------------------------|-----------------------------------------------------------------------------------------|
| API_DB_POOL_SIZE                 | API 인스턴스 최대 DB 연결                                                               |
| CONVERSATION_DB_POOL_SIZE        | 대화 인스턴스 최대 DB 연결                                                              |
| JOURNAL_DB_POOL_SIZE             | 일기 인스턴스 최대 DB 연결                                                              |
| CONVERSATION_WORKER_CONCURRENCY  | 대화 실행 슬롯                                                                          |
| CONVERSATION_CLAIM_BATCH_SIZE    | 대화 배치 선점 상한                                                                     |
| JOURNAL_WORKER_CONCURRENCY       | 일기 실행 슬롯                                                                          |
| CONVERSATION_LLM_CONCURRENCY     | 대화 프로세스 provider별 동시 호출                                                      |
| JOURNAL_LLM_CONCURRENCY          | 일기 프로세스 provider별 동시 호출                                                      |

기동 시 역할별 슬롯 합계와 provider 호출 상한을 검증한다.
DB pool의 minimum-idle은 maximum-pool-size 이하여야 한다.
API 역할의 인증 키 주입은 [인증·구독](AUTH.md)을 따른다.

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
