# 관측과 완료 작업 보존

현재 R5 구현 계약이다. 호출별 진단 DB 원장과 platform 스키마는 사용하지 않는다.
원본·결과·정확한 요청 수 quota·리스·실패 복구 상태는 업무 모듈이 저장한다.

## 진단 흐름

워커가 선점한 작업 ID를 traceId로 사용한다. 이는 HTTP 요청의 분산 추적 ID가 아니라
재시작과 재시도에도 같은 작업을 검색하기 위한 실행 흐름 식별자다.
jobId, recordDayId, attempt를 실행 스레드 MDC에 설정하고 실행 종료 시 이전 문맥을 복구한다.
LLM 호출 전에 generationId를 발급하므로 공급자 실패도 생성 시도 ID로 연결할 수 있다.
성공한 호출 뒤 일기 JSON 파싱이나 저장이 실패해도 같은 generationId로 조사한다.

LLM 호출 관측은 안전한 필드만 담은 이벤트를 bounded queue에 offer한다.
전용 daemon 스레드 1개가 큐를 읽어 메트릭을 집계하고 구조화 로그를 출력한다.
수집기가 느려져도 업무 스레드는 전송 완료를 기다리지 않는다.
큐 기본 크기는 프로세스당 1024개이며 포화 시 진단 이벤트만 버린다.
본문·프롬프트·사용자 ID·인증 토큰·응답 본문·원래 예외 객체는 이벤트에 없다.

로그 수집 경로는 SLF4J key-value → 비차단 콘솔 버퍼 → Spring Boot Logstash JSON console이다.
LLM 관측 큐와 별도로 모든 애플리케이션 로그의 콘솔 출력에도 1024개 버퍼를 둔다.
작업·마감·실패 로그가 동일한 콘솔 appender 잠금을 통해 업무 스레드를 막는 경로를 차단한다.
콘솔 큐 포화 시 ERROR를 포함한 진단 로그를 버리고 유실 수를 집계한다.
실행 환경의 stdout 수집기가 JSON 필드를 인덱싱하면 jobId/traceId로 호출과 작업 실패를 검색할 수 있다.
외부 Sentry·클라우드 로그 계정이나 배포 에이전트를 연결했다는 뜻은 아니다.
원격 수집기 도착·장기 보존은 배포에서 별도로 검증한다.

| 이벤트        | 주요 필드                                                                                                                         |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------|
| llm.call      | purpose, provider, 설정 model, promptVersion, generationId, latencyMs, succeeded, errorCode, finishReason, 입력/출력/캐시 토큰 수 |
| job.completed | 작업 문맥과 generationId                                                                                                          |
| job.failed    | 작업 문맥, generationId가 발급된 경우 해당 ID, errorCode, 코드 위치                                                               |

provider·purpose·model은 설정된 라우팅으로 제한한다. 공급자가 반환한 finishReason은 허용 목록 외에 OTHER로 정규화한다.
공급자 예외는 HTTP 상태·TIMEOUT·IO_ERROR 등으로 분류하고 메시지와 cause를 업무 예외에 복사하지 않는다.
파싱 실패에도 원문을 로그에 남기지 않는다.
애플리케이션 예외 로그는 오류 분류·코드 위치만 남긴다.
PostgreSQL 오류 DETAIL에 실패 행의 본문이 포함될 수 있어 Hibernate의 해당 오류 로거도 비활성화한다.
의도적으로 로그 레벨이나 HTTP wire logging을 바꾸면 이 보호 범위를 다시 검토해야 한다.

## 메트릭과 유실

Micrometer registry를 Actuator로 구성한다.
워커는 업무 HTTP 서버를 만들지 않으며 health/metrics 관리 엔드포인트는 로컬 JVM JMX로 노출한다.
HTTP Actuator 노출은 제외한다. 원격 JMX 접속이나 공개 관리 포트는 자동으로 열지 않는다.
운영 수집 에이전트는 해당 JVM의 JMX metrics 엔드포인트를 읽거나 stdout 이벤트를 집계할 수 있다.

| 메트릭                                                       | 의미                                                 |
|--------------------------------------------------------------|------------------------------------------------------|
| moeum.llm.duration                                           | 수집한 호출의 수와 지연                              |
| moeum.llm.input.tokens / output.tokens / cached.input.tokens | 수집한 호출의 토큰 수                                |
| moeum.telemetry.buffer.size                                  | 전송 대기 중 이벤트 수                               |
| moeum.telemetry.dropped                                      | buffer_full / sink_failure / closed로 나눈 진단 유실 |
| moeum.telemetry.metric.failures                              | 메트릭 집계 실패                                     |
| moeum.logging.dropped                                        | 콘솔 큐 포화로 버린 로그 수                          |
| moeum.logging.buffer.size                                    | 콘솔 출력 대기 로그 수                               |

호출 메트릭 label은 purpose/provider/outcome만 사용한다.
사용자·작업·생성 ID, 오류 메시지, 실제 모델 응답 문자열은 label에 넣지 않는다.
콘솔 메트릭은 로깅 재구성 후에도 현재 appender를 조회한다. appender 교체 시 해당 콘솔 유실 카운터는 초기화될 수 있다.
메트릭은 정확한 과금·요금제 집행용 원장이 아니다. 큐 포화나 프로세스 종료로 유실될 수 있다.
정확한 대화 횟수 제한은 기존 conversation_usage가 집행한다.

LLM 관측 큐 종료 시 최대 2초 동안 큐를 비운 뒤 남은 이벤트를 유실로 집계한다.
콘솔 버퍼도 최대 2초 동안 종료를 기다린다. 종료 시 콘솔에 남은 로그와 이미 전송 중인 로그의 원격 도착은 확정하지 않는다.
interrupt에 반응하지 않는 수집기도 업무 종료를 무기한 막지 않도록 daemon 스레드를 사용한다.
프로세스 강제 종료와 stdout 뒤의 원격 수집기 유실은 로컬 카운터로 확정할 수 없다.
Logback appender가 내부에서 오류를 삼킨 경우도 sink_failure가 원격 도착 실패까지 증명하지 않는다.

설정은 moeum.telemetry.buffer-capacity와 shutdown-timeout이다.
수집 실패 자체를 DB에 다시 저장하거나 업무 재시도 큐에 넣지 않는다.

## 완료 작업 정리

기본 보존 기간은 완료 시각(updated_at)에서 30일, 한 배치에 최대 100행, 실행 간격은 10초다.
제품 확정값이 아닌 조정 가능한 운영 기본안이다.
moeum.jobs.retention.completed-age / batch-size / interval / initial-delay로 설정한다.

대화와 일기 워커가 각 모듈의 정리만 실행한다.
정리 scheduler는 응답 poll·일기 poll·마감 전달과 별도 스레드를 사용한다.
한 배치의 선점과 DELETE는 하나의 SQL statement로 원자적으로 실행하며 SKIP LOCKED로 경합 중인 행을 건너뛴다.
대상이 남으면 한 실행에서 최대 10배치 이어 처리한다. 2초가 지나면 다음 배치를 시작하지 않고 대상이 없으면 즉시 멈춘다.
완료 행의 updated_at/id 부분 인덱스를 사용한다. 무제한 삭제 반복 루프는 없다.
여러 replica의 배치 합산 부하는 R6에서 측정한다.

대화 정리 조건:

- COMPLETED이고 보존 기간이 지났다.
- 기록이 마감됐다.
- 기록과 작업 행을 함께 잠글 수 있다.

후속 완료 여부는 보관된 ASSISTANT 메시지의 source_sequence로, 실행 중 여부는 PROCESSING 작업으로 판단한다.
따라서 FAILED나 PENDING 작업이 같은 기록에 있어도 오래된 COMPLETED 작업은 정리할 수 있다.
FAILED/PENDING/PROCESSING 작업 자체는 정리하지 않으며 열린 기록의 완료 작업도 아직 정리하지 않는다.
향후 ASSISTANT 메시지 보관 기간을 줄인다면 실패 작업의 재시도 가능 기간과 함께 변경해야 한다.
원본 메시지와 AI 메시지는 정리 대상이 아니다.

일기 작업 정리 조건은 COMPLETED와 보존 기간 경과다. 결과 INSERT와 작업 완료는 같은 트랜잭션이며, 일기 존재 여부는 결과 테이블의 기록 유일키로 판단한다.
완료 일기 작업이 없어져도 journals의 기록 유일키와 보관 결과를 보고 재전달 등록을 거절한다.
기존 일기에 대한 생성 재시도는 여전히 409다.
현재 일기 결과와 사용자 수정본은 보존한다. 생성 시도 ID·모델·프롬프트 버전은 결과 테이블에 저장하지 않는다.
일기 작업에는 결과와 중복인 공급자·모델·프롬프트·토큰·생성 메타데이터를 저장하지 않는다.

## 클라이언트 계약

완료 작업 상태는 영구 이력이 아니다.
보존 기간 뒤 정리된 응답 작업 ID의 상태 조회·재시도는 404가 될 수 있다.
메시지 커서로 조회한 저장 응답과 일기 현재 결과는 계속 조회할 수 있다.
클라이언트는 완료를 확인한 작업을 미완료 polling 목록에서 제거한다.
기존 메시지 중복 전송은 원본을 그대로 반환하고 quota나 새 작업을 만들지 않으며 responseJob은 null일 수 있다.
일기 상세는 journal이 있으면 결과를 표시하고 generation이 null이어도 생성 완료로 취급한다.

실패 작업의 만료, 사용자 기록 삭제, 백업 보존은 이 정리 작업의 범위가 아니다.

## 검증

단위 테스트는 수집기 지연·실패·큐 포화·종료 후 입력·MDC 복구와 메트릭 label을 검사한다.
실제 콘솔 appender 정체 중에도 ERROR 로그 발행이 대기하지 않고 유실을 집계하는지 검사한다.
통합 테스트는 실제 JSON console 출력, 호출·파싱 실패의 작업/생성 ID 연결,
원문 없는 timeout/429 분류와 새 PostgreSQL의 완료 작업 정리를 검사한다.
정리 대상 제한, 잠긴 기록 건너뛰기, 사용자 수정본/원본 생성 정보 보존,
작업 정리 뒤 재전달 멱등성·재시도 거절·중복 접수 무차감을 검증한다.

전체 백엔드 테스트 155개가 통과했다(실패·오류·생략 0개). 실행 명령과 기존 회귀 범위는 [실행 역할](WORKER_ROLES.md)을 따른다.
외부 수집 서비스·실제 공급자 호출·대규모 누적 데이터/정리 비용 검증은 별도 운영 검증이다.

참고: [Spring Boot 구조화 로그](https://docs.spring.io/spring-boot/reference/features/logging.html),
[메트릭](https://docs.spring.io/spring-boot/reference/actuator/metrics.html),
[JMX 관리](https://docs.spring.io/spring-boot/reference/actuator/jmx.html).
