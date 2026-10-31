# 대화 접수·응답·조회 계약

현재 서버의 대화 계약이다. 하루 귀속·마감·일기 입력은 [하루 기록](RECORD_DAY.md),
프로세스와 공급자 자원 예산은 [실행 역할](WORKER_ROLES.md)을 따른다.

## 책임과 순번

Identity는 현재 유효 구독에 따른 기록당 대화 한도를 publicapi로 제공한다. 구독 조회는 [인증·구독](AUTH.md)을 따른다.
Conversation은 접수 사용량, 메시지, 기록별 응답 실행 상태와 순서를 소유한다.

대화 스트림의 단위는 recordDayId다. 서로 다른 기록은 독립적으로 처리한다.
사용자 원본 추가 시 sourceRevision이 증가하며 USER 메시지의 sourceSequence는 그 값이다.
ASSISTANT의 sourceSequence는 그 응답이 답한 마지막 USER의 원본 순번이다. 기록에서 가장 큰 ASSISTANT sourceSequence가 답변 완료 경계(answered)다.
ASSISTANT는 원본 버전을 증가시키지 않는다. 일기 생성은 마감된 기록의 마지막 USER 메시지 순번까지 AI 문맥을 읽는다.

streamSequence는 USER·ASSISTANT를 합친 메시지 증분 순번이다.
메시지 추가 시 RecordDay의 카운터를 UPDATE하고 잠금을 커밋까지 유지한다.
UUID 생성 시각이나 occurredAt으로 커밋 순서를 추정하지 않는다.
화면은 occurredAt으로 정렬할 수 있지만 다음 조회 커서는 최대 streamSequence를 사용한다.
메시지는 발생 시각을 반환한다. 기록 응답은 recordDayId, displayDate, startAt, closeAt, closedAt이며 마지막 장소 타임존은 포함하지 않는다.

## 접수와 한도

POST /api/conversations/messages는 입력 검증과 사용자 상태 잠금 뒤 중복·현재 기록의 마감을 판단한다.
기존 clientMessageId는 처음 저장한 결과를 반환하며 사용량을 다시 차감하지 않는다.
새 메시지는 발화 시각이 오래됐어도 서버 접수 시각의 열린 기록에 저장한다. 이미 마감됐으면 다음 기록을 만든다.
성공 응답은 실제 귀속된 recordDayId, 저장한 message, 기록의 응답 상태(responseStatus)를 반환한다. 접수에서는 답변 완료 경계를 조회하지 않는다.

접수 가능한 새 원본에는 현재 구독 한도와 서버 접수 속도를 원자적으로 검사한다.
한도 소진은 HTTP 429 QUOTA_EXCEEDED이며 현재 quota를 포함한다.
속도 초과는 HTTP 429 MESSAGE_RATE_EXCEEDED다.
거절 또는 저장 오류는 트랜잭션 전체를 롤백하여 메시지·원본 변경·마감 변경·작업·사용량을 남기지 않는다.
새 사용자 메시지는 한도를 소비하고 기록의 응답 실행 상태를 갱신한다. 기록의 첫 메시지는 응답 작업을 만들고, 이후 메시지는 같은 작업을 사용한다.
장소 변경으로 이전 기록이 마감되면 새 기록에 접수하고 새 기록의 응답 작업을 만든다.

현재 기록의 `sourceRevision`이 접수한 USER 메시지 수다. 사용자별 사용량 행에는 분 단위 접수 속도만 보관한다.
기록이 닫히고 새 기록이 열리면 전체 횟수 한도를 다시 제공한다. 여행으로 조기 마감돼도 동일하다.
과거 `occurredAt`은 기록 귀속이나 한도 기간을 변경하지 않는다.
접수 후 AI 실패가 발생해도 최초 사용량은 유지하고 자동·사용자 실패 재시도는 추가 차감하지 않는다.

GET /api/conversations/quota는 limit, used, remaining, resetAt을 반환한다. 열린 기록이 없거나 예정 마감이 지났으면 used는 0이고 resetAt은 null이다.
열린 기록의 resetAt은 현재 예약된 closeAt이며 장소 이동에 따라 바뀔 수 있다.
구독 변경은 같은 기록의 used를 유지하며 새 limit을 적용한다.
하향 변경으로 used가 limit보다 크면 remaining은 0이다.
클라이언트는 잔여 횟수를 사전 표시하고 0이면 전송을 막는다. 서버 검사는 별도로 유지한다.

## 마감 설정

GET /api/conversations/preferences는 사용자의 closeTime을 반환한다. 설정 전이면 서버 기본값 02:00이다.
PUT /api/conversations/preferences는 closeTime을 바꾸고 바뀐 값을 반환한다. 허용 범위 밖이면 400이다.
변경은 다음에 만드는 기록부터 적용한다. 범위와 적용 규칙은 [하루 기록](RECORD_DAY.md)을 따른다.

## 응답 실행과 복구

응답 작업은 기록당 하나이며 연속 입력을 한 실행에서 함께 답한다.
작업은 실행 주기를 시작한 선점에서 기록의 sourceRevision을 관측 상한(observedSourceRevision, O)으로 저장한다.
같은 주기의 재시도·리스 회수는 O를 유지한다. 실제 답변 범위는 저장하지 않고 실행할 때 현재 설정으로 계산한다.

- 답변 대상: (answered, O]의 USER를 오래된 순으로 한 호출의 메시지 수·글자 예산 안까지 고른다. 첫 미응답 입력은 항상 포함한다. 마지막 대상의 순번이 E다.
- 과거 문맥: answered 이하의 최근 메시지를 원본 순번별 USER → ASSISTANT 순서로 읽고, 대상에 배정하고 남은 메시지 수·글자 예산으로 줄인다.
- 답변 대상은 예산 때문에 줄이지 않는다. 예산에 들지 않은 미응답 입력은 다음 실행 주기에서 답한다.
- 한 실행의 E를 호출과 저장에 그대로 사용한다. 설정이나 워커 버전이 바뀌면 같은 O에서 E가 달라질 수 있지만, 다음 범위는 저장된 answered에서 시작하므로 누락·중복이 없다.

실행 슬롯 확보 후 실행할 차례인 작업의 기록 행을 SKIP LOCKED로 잠근다.
잠금 획득 후 새 문장에서 작업 상태와 sourceRevision을 다시 읽어 선점 여부와 O를 정한다.
DB 잠금과 트랜잭션은 선점 후 해제하며 LLM 호출 동안 유지하지 않는다.

접수·선점·성공·실패·수동 재시도는 같은 기록 행 잠금 아래에서 전이를 결정한다. 상태 전이는 다음과 같다.

| 사건                       | 처리                                                                                                                     |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------|
| 접수: 작업 없음·COMPLETED  | PENDING, 시도 횟수 0, 즉시 선점 가능                                                                                     |
| 접수: FAILED               | PENDING, 시도 횟수 0, 저장된 재개 가능 시각 이후 선점                                                                    |
| 접수: PENDING·PROCESSING   | 변경 없음. 진행 중인 실행이 끝난 뒤 이어서 답한다                                                                        |
| 선점                       | 시도 횟수 1 증가. 시도 횟수 0에서 시작하면 O를 현재 sourceRevision으로 정한다                                            |
| 성공                       | ASSISTANT 추가와 전이를 한 트랜잭션으로 저장. sourceRevision > E이면 시도 횟수 0으로 즉시 다시 PENDING, 아니면 COMPLETED |
| 실패, 자동 재시도 남음     | PENDING, 시도 횟수·O 유지, 오류별 backoff                                                                                |
| 최종 실패                  | sourceRevision > O이면 시도 횟수 0으로 재개 가능 시각에 PENDING, 아니면 FAILED와 재개 가능 시각                          |
| 리스 회수로 누적 상한 초과 | 호출 없이 최종 실패와 같은 판단                                                                                          |
| 수동 재시도                | FAILED만 PENDING으로 되돌린다. O·시도 횟수 유지                                                                          |

최종 실패 뒤 같은 기록에 새 메시지가 접수되면 실패한 입력과 새 입력을 함께 답하는 새 실행 주기를 연다.
실행 중에 접수된 입력이 있으면 최종 실패 시점에 같은 규칙을 적용한다. 자동 반복은 중단하며, 새 메시지는 미응답 내용에 대한 제한된 실행 기회를 다시 제공한다.
인증·설정 오류처럼 새 메시지로 해결되지 않는 실패도 기록당 접수 한도와 누적 시도 상한 안에서 다시 호출될 수 있다.
마감된 기록에는 새 메시지가 접수되지 않으므로 그 기록의 최종 실패는 수동 재시도로 복구한다. 이미 PENDING인 작업과 남은 입력 처리는 마감 뒤에도 진행한다.

FAILED의 next_attempt_at은 재개 가능 시각이다. 일반 backoff, 수동 재시도 대기, 429의 최소 대기와 공급자 `Retry-After` 중 가장 늦은 시각이며 새 메시지에 의한 재개와 수동 재시도 모두 이 시각을 지킨다.

만료된 PROCESSING은 같은 작업을 재선점하고 version을 증가시킨다.
오래된 version의 성공 저장은 원본 결과·메시지·스트림 카운터까지 롤백되고, 실패 저장도 거절된다.
리스 만료 시 외부 호출 중복 자체를 없애는 보장은 아니며 영속 결과의 중복을 차단한다.

일시적 네트워크·서버 오류는 제한된 backoff로 자동 재시도한다. HTTP 429는 별도 최소 대기와 공급자의
`Retry-After` 중 더 늦은 시각을 따르며, 인증·명확한 입력 오류는 자동 반복을 중단한다.
공급자가 출력 잘림·차단 또는 정상 완료로 인정하지 않는 종료 사유를 반환하면 본문을 저장하지 않고 자동 반복을 중단한다.

POST /api/conversations/records/{recordDayId}/response-attempts는 최종 실패한 기록의 응답을 다시 대기 상태로 만들고 기록의 응답 상태를 202로 반환한다.
PENDING/PROCESSING의 반복 요청은 현재 상태를 반환한다. 재시도할 실패가 없는 COMPLETED는 409, 응답 작업이 없거나 기한이 지난 기록은 404다.
수동 재시도는 자동·수동·리스 복구를 합친 누적 호출 상한을 넘지 않는다. 재개 가능 시각 전 요청은 429, 상한 소진은 409다.

## 기록·메시지·작업 상태 조회

GET /api/conversations/records?date=&limit=은 사용자의 기록 요약을 시작 시각 내림차순으로 반환한다.
date를 주면 해당 표시 날짜의 기록만 반환한다. 같은 표시 날짜의 기록이 여러 개일 수 있다. limit은 1~200, 기본 50이다.

GET /api/conversations/records/{recordDayId}?after=&limit=은 record, response, messages, nextCursor를 반환한다.
after는 0 이상의 정수 streamSequence다. after=0은 처음부터 조회하며 생략하면 최근 페이지를 읽는다.
증분 조회는 순번 오름차순으로 페이지를 고른 뒤 표시 시각으로 반환한다.
limit은 1~200 범위로 제한하고 nextCursor는 페이지의 최대 순번이다.
빈 증분 페이지는 받은 after를 유지한다.

메시지는 streamSequence와 sourceSequence를 포함한다.
response는 status, retryable, answeredSourceRevision이다. 작업이 정리됐으면 status는 null이다.
sourceSequence가 answeredSourceRevision보다 큰 USER가 아직 답하지 않은 입력이다. 묶음 응답에서는 실행 중 접수된 USER가 ASSISTANT보다 앞선 streamSequence를 가질 수 있으므로 stream 위치로 미응답을 판단하지 않는다.
새 메시지 없이 응답 작업만 실패할 수 있으므로 메시지 커서로 상태 변경을 추적하지 않는다.
retryable은 FAILED이고 누적 호출 상한과 재시도 기한 안일 때 true다. 재개 가능 시각 전에도 true일 수 있으며 요청 시 429를 반환한다.
조회와 재요청 사이에는 상태가 바뀔 수 있으므로 재요청 시 서버가 다시 검증한다.

## 현재 설정과 제한

| 설정                                                   | 현재 설정값           |
|--------------------------------------------------------|-----------------------|
| moeum.identity.conversation.free-record-limit          | 20                    |
| moeum.identity.conversation.premium-record-limit       | 100                   |
| 사용 기간                                              | RecordDay별 초기화    |
| moeum.conversation.limits.accepted-messages-per-minute | 60                    |
| moeum.conversation.limits.context-message-limit        | 100                   |
| moeum.conversation.limits.context-char-budget          | 20,000                |
| moeum.conversation.limits.max-message-length           | 4,000                 |
| moeum.conversation.response-job.max-attempts           | 3                     |
| moeum.conversation.response-job.max-total-attempts     | 5                     |
| moeum.conversation.response-job.manual-retry-cooldown  | 1분                   |
| moeum.conversation.response-job.rate-limit-backoff     | 최소 1분              |

무료·구독 횟수, 분당 새 메시지 상한, 메시지당 길이 상한은 확정된 현재 수치이며 서버 설정으로 변경할 수 있다. 횟수 한도는 기록별로 적용한다.
AI 문맥의 100개 메시지·20,000자는 사용자에게 보장하는 기억량이 아닌 한 호출의 실행 상한이다. 메시지 길이 상한은 글자 예산 이하여야 하며 기동 시 검증한다.
접수 속도 제한은 승인된 새 원본을 제한한다. 인증 실패·잘못된 입력·중복 재전송·조회 요청 전체의
네트워크 남용 차단은 API gateway 등 운영 경계에서 별도로 집행해야 한다.
접수 횟수, 자동 재시도 횟수, 실행 슬롯/공급자 호출 예산은 서로 다른 제한이다.

기능·동시성 검증은 실제 PostgreSQL과 fake 공급자를 사용한다.
독립 JVM 부하·복구와 실제 공급자 검증 범위는 [검증 현황](CAPACITY_RESULTS.md)을 따른다.

## 종료 작업 보존

마감된 기록의 오래된 종료 작업은 정리될 수 있다. 정리된 기록의 response.status는 null이고 재시도는 404이며 메시지와 AI 응답은 계속 조회할 수 있다.
기존 메시지의 중복 전송은 quota나 작업을 새로 만들지 않고 원본을 반환하되 responseStatus는 null일 수 있다.
보존 기간이 지난 실패 작업은 정리 전이라도 다시 실행할 수 없다. 정리 조건과 기간은 [관측·보존](OBSERVABILITY_RETENTION.md)을 따른다.
