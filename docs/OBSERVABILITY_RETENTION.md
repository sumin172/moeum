# 관측과 작업 보존

업무 원본·결과·접수 사용량·리스·복구 상태는 각 업무 모듈이 저장한다.
호출 진단 상세는 구조화 로그와 메트릭으로 제공한다.

## 진단 흐름

워커 작업 ID를 traceId로 사용한다.
jobId, recordDayId, attempt를 실행 스레드 MDC에 설정하고 종료 시 이전 문맥을 복구한다.
LLM 호출 전 generationId를 발급하여 호출·파싱·저장 실패를 연결한다.

LLM 이벤트는 bounded queue에 offer하고 전용 daemon 스레드가 메트릭과 로그로 전달한다.
기본 용량은 프로세스당 1,024개다.
전체 애플리케이션 로그의 콘솔 출력에도 별도의 1,024개 비차단 버퍼가 있다.
큐 포화는 진단 이벤트 유실로 집계하며 업무 처리와 독립적이다.

수집 경로는 SLF4J key-value → 비차단 콘솔 버퍼 → Spring Boot Logstash JSON stdout이다.
외부 수집 계정·에이전트 연결과 보관 기간은 배포 구성 항목이다.

| 이벤트        | 주요 필드                                                                                                     |
|---------------|---------------------------------------------------------------------------------------------------------------|
| llm.call      | purpose, provider, model, promptVersion, generationId, latencyMs, succeeded, errorCode, finishReason, 토큰 수 |
| job.completed | 작업 문맥과 generationId                                                                                      |
| job.failed    | 작업 문맥, 발급된 generationId, errorCode, 코드 위치                                                          |

원문·프롬프트·인증 토큰·응답 본문은 로그 이벤트에 포함하지 않는다.
오류는 안전한 분류와 코드 위치로 기록한다.
공급자 finishReason은 허용 목록 외 값을 OTHER로 정규화한다.
PostgreSQL 오류 DETAIL 노출을 막는 Hibernate 오류 로거 설정을 적용한다.

## 메트릭

Actuator의 로컬 JMX health/metrics를 사용한다.
운영 수집기는 JMX 또는 stdout을 수집할 수 있다.

| 메트릭                                                       | 의미                                     |
|--------------------------------------------------------------|------------------------------------------|
| moeum.llm.duration                                           | 수집된 호출 수와 지연                    |
| moeum.llm.input.tokens / output.tokens / cached.input.tokens | 수집된 토큰 수                           |
| moeum.telemetry.buffer.size                                  | LLM 이벤트 대기 수                       |
| moeum.telemetry.dropped                                      | buffer_full / sink_failure / closed 유실 |
| moeum.telemetry.metric.failures                              | 메트릭 집계 실패                         |
| moeum.logging.dropped / buffer.size                          | 콘솔 유실·대기 수                        |

호출 label은 purpose/provider/outcome을 사용한다.
정확한 요금제별 횟수 집행은 RecordDay의 USER 원본 순번을 사용한다. `conversation_usage`는 분당 접수 속도를 집행하고 관측 수치는 진단에 사용한다.
LLM 큐와 콘솔 큐는 종료 시 각각 최대 2초 기다린다.
강제 종료·원격 수집기 유실은 로컬 카운터로 확정할 수 없다.
설정은 moeum.telemetry.buffer-capacity와 shutdown-timeout이다.

## 종료 작업 정리

| 설정                              | 기본값                             |
|-----------------------------------|------------------------------------|
| moeum.jobs.retention.terminal-age | 완료·최종 실패 updated_at부터 30일 |
| batch-size                        | 100                                |
| interval                          | 10초                               |
| initial-delay                     | 1분                                |

역할별 독립 스케줄러가 작은 배치를 실행한다.
실행당 최대 10배치이며 2초가 지나면 다음 배치를 시작하지 않는다.
대상이 없으면 즉시 종료한다.
선점·삭제는 하나의 SQL statement이며 SKIP LOCKED로 경합 행을 건너뛴다.

대화 작업은 COMPLETED 또는 FAILED·보존 기간 경과·기록 마감·기록과 작업 잠금 가능 조건으로 정리한다.
후속 완료는 보관된 ASSISTANT의 source_sequence, 후속 실행은 PROCESSING 작업으로 판단한다.
같은 기록에 FAILED/PENDING이 있어도 완료 이력은 정리할 수 있다.
PENDING/PROCESSING과 열린 기록의 종료 작업은 보존한다.

일기 작업은 COMPLETED 또는 FAILED와 보존 기간 경과 조건으로 정리한다.
결과 유일키가 재전달의 중복 등록을 차단하며 이미 완성된 일기의 재시도는 409다.
현재 일기·수정본·USER·ASSISTANT 메시지는 보관한다.
최종 FAILED 작업은 실패 시점부터 최대 30일간 보관한다. 정리 전에도 만료된 실패 작업의 수동 재시도와 retryable 표시는 중단한다. 기존 누적 시도 상한과 후속 응답 순서 제한도 적용한다.
원본 대화의 최종 보관 기간은 미확정이다.

## 클라이언트 계약

완료를 확인한 작업은 미완료 polling 목록에서 제거한다.
정리된 작업 ID 조회·재시도는 404일 수 있다.
저장된 메시지·일기 결과는 계속 조회된다.
중복 메시지 재전송은 원본을 반환하고 추가 차감하지 않으며 responseJob은 null일 수 있다.
일기 상세는 journal이 있으면 결과를 표시하고 generation이 null이어도 완료로 취급한다.

## 검증

LlmTelemetryTest와 NonBlockingConsoleAppenderTest는 수집 실패·포화·종료·유실을 검사한다.
LlmTelemetryIntegrationTest는 JSON 출력·JMX·작업 문맥·원문 보호를 검사한다.
LlmTelemetryIsolationIntegrationTest는 관측 전송 정체 중 업무 진행을 검사한다.
TerminalJobRetentionIntegrationTest는 정리 조건·재시도 판단·재전달 멱등성과 원본·수정본 보존을 검사한다.
