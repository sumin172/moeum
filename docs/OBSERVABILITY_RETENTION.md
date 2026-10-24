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
모델은 응답에 포함된 실제 모델을 기록하고, 응답 정보가 없으면 요청 설정의 모델을 사용한다.
출력 잘림·차단 등 완료되지 않은 응답도 실패 이벤트에 실제 모델·종료 사유·사용 토큰을 남긴다. 본문은 기록하지 않는다.
succeeded는 공급자 응답이 정상 종료 판정과 본문 공백 검사를 통과했는지를 나타낸다. 이후 업무 모듈의 JSON 검증·결과 저장 성공을 의미하지 않는다.
누락된 공급자 토큰 수는 0으로 기록한다.

| 출력 판정 errorCode      | 의미                                                      | 자동 재시도                     |
|--------------------------|-----------------------------------------------------------|---------------------------------|
| OUTPUT_TRUNCATED         | 출력 토큰 상한 또는 모델 문맥 한계로 중단                 | 중단                            |
| OUTPUT_BLOCKED           | 입력·출력 차단 또는 공급자의 명시적 거절                  | 중단                            |
| UNEXPECTED_FINISH_REASON | 종료 사유 누락 또는 현재 요청에서 지원하지 않는 종료 사유 | 중단                            |
| EMPTY_CONTENT            | 정상 종료했으나 본문이 비어 있음                          | 기존 제한된 출력 오류 정책 적용 |

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

## 수용한 간헐 현상과 관찰 기준

시험에서 기동 직후 DB 연결 획득 오류와 일기 워커 재기동 중 대화 대기 증가가 관측됐다.
동일 부하·풀 조건의 최종 30분 시험에서는 재현되지 않았으며 원인은 미확정이다. 해결된 결함으로 분류하지 않는다.
현재는 개발을 계속하면서 기동 시각·API 오류와 지연·작업 대기·DB 연결 획득과 풀 대기·JVM CPU/GC·DB 잠금과 I/O 대기를 함께 관찰하는 방향이다.
정상 운영 중에도 발생하거나, 재기동마다 반복되거나, 오류·대기가 회복되지 않으면 우선 분석한다.

현재 수집 경로는 위 JMX와 구조화 stdout이며 외부 수집·보관 연결은 배포 구성 작업이다.
Prometheus 연결이나 새 진단용 DB 컬럼은 이 검증에 포함되지 않는다.

## 종료 작업 정리

| 설정                              | 기본값                                                              |
|-----------------------------------|---------------------------------------------------------------------|
| moeum.jobs.retention.terminal-age | 기본 30일. 완료·대화 실패는 updated_at, 일기 실패는 created_at 기준 |
| batch-size                        | 100                                                                 |
| interval                          | 10초                                                                |
| initial-delay                     | 1분                                                                 |

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
FAILED 대화 작업은 최근 실패부터, FAILED 일기 작업은 작업 생성부터 기본 30일간 보관한다.
정리 전에도 만료된 실패 작업의 수동 재시도와 retryable 표시는 중단한다.
대화에는 누적 시도 상한과 후속 응답 순서 제한을 적용한다. 일기 수동 재개에는 횟수·대기 제한이 없으며 생성 기준 기한은 재실패해도 연장되지 않는다.
원본 대화의 최종 보관 기간은 미확정이다.

## 클라이언트 계약

완료를 확인한 작업은 미완료 polling 목록에서 제거한다.
정리된 작업 ID 조회·재시도는 404일 수 있다.
저장된 메시지·일기 결과는 계속 조회된다.
중복 메시지 재전송은 원본을 반환하고 추가 차감하지 않으며 responseJob은 null일 수 있다.
일기 상세는 journal이 있으면 결과를 표시하고 generation이 null이어도 완료로 취급한다.

## 검증

GeminiProviderTest와 ClaudeProviderTest는 정상 종료·잘림·차단·종료 사유 누락·생략된 사용량의 응답 해석을 검사한다.
RoutingLlmClientTest는 미완료 출력 거절·오류 분류·실제 모델과 토큰 수의 실패 관측 보존을 검사한다.
GeminiProviderTest는 라우터의 출력 판정 실패가 공급자 서킷의 실패 횟수에 반영되지 않는지도 검사한다.
LlmTelemetryTest와 NonBlockingConsoleAppenderTest는 수집 실패·포화·종료·유실을 검사한다.
LlmTelemetryIntegrationTest는 JSON 출력·JMX·작업 문맥·원문 보호를 검사한다.
LlmTelemetryIsolationIntegrationTest는 관측 전송 정체 중 업무 진행을 검사한다.
TerminalJobRetentionIntegrationTest는 정리 조건·재시도 판단·재전달 멱등성과 원본·수정본 보존을 검사한다.
