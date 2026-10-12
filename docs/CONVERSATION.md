# 대화 접수·응답·조회 계약

R3의 서버 구현 계약이다. 하루 귀속·마감·입력 동결은 [하루 기록](RECORD_DAY.md),
프로세스와 공급자 자원 예산은 [실행 역할](WORKER_ROLES.md)을 따른다.

## 책임과 순번

Identity는 현재 유효 구독에 따른 일일 대화 한도를 publicapi로 제공한다.
Conversation은 접수 사용량, 메시지, 응답 작업과 기록별 순서를 소유한다.
구독 상태는 JWT에 넣지 않으며 현재 유효 구독만 DB에서 조회한다.

대화 스트림의 단위는 recordDayId다. 서로 다른 기록은 독립적으로 처리한다.
사용자 원본 추가 시 sourceRevision이 증가하며 USER 메시지의 sourceSequence는 그 값이다.
ASSISTANT의 sourceSequence는 답한 USER의 원본 순번이다.
일기 생성 입력은 USER만 읽으므로 ASSISTANT가 원본 버전을 증가시키지 않는다.

streamSequence는 USER·ASSISTANT를 합친 메시지 증분 순번이다.
메시지 추가 시 RecordDay의 카운터를 UPDATE하고 잠금을 커밋까지 유지한다.
UUID 생성 시각이나 occurredAt으로 커밋 순서를 추정하지 않는다.
화면은 occurredAt으로 정렬할 수 있지만 다음 조회 커서는 최대 streamSequence를 사용한다.

## 접수와 한도

POST /api/conversations/messages는 입력 검증과 사용자 상태 잠금 뒤 중복·귀속·동결 여부를 판단한다.
기존 clientMessageId는 기존 결과를 반환하며, 동결 후 새 원본은 DISCARDED_INPUT_FROZEN으로 종결한다.
이 두 경로는 사용량을 차감하지 않는다.

접수 가능한 새 원본에는 현재 구독 한도와 서버 접수 속도를 원자적으로 검사한다.
한도 소진은 HTTP 429 QUOTA_EXCEEDED이며 현재 quota를 포함한다.
속도 초과는 HTTP 429 MESSAGE_RATE_EXCEEDED다.
거절 또는 저장 오류는 트랜잭션 전체를 롤백하여 메시지·원본 변경·마감 변경·작업·사용량을 남기지 않는다.
닫힌 과거 기록에 동결 전 접수한 원본도 한도를 소비하지만 새 AI 응답 작업은 만들지 않는다.

사용량은 사용자당 한 행으로 보관한다.
서버 UTC 날짜와 분 단위 접수 창을 기록하고 기간이 바뀌면 같은 행을 갱신한다.
과거 occurredAt이나 여행 타임존으로 한도를 초기화하지 않는다.
접수 후 AI 실패가 발생해도 최초 사용량은 유지하고 자동·사용자 실패 재시도는 추가 차감하지 않는다.

GET /api/conversations/quota는 limit, used, remaining, resetAt을 반환한다.
구독 변경은 같은 UTC 기간의 used를 유지하며 새 limit을 적용한다.
하향 변경으로 used가 limit보다 크면 remaining은 0이다.
클라이언트는 잔여 횟수를 사전 표시하고 0이면 전송을 막는다. 서버 검사는 별도로 유지한다.
클라이언트 화면 구현은 이 서버 이슈의 범위에 포함하지 않는다.

## 응답 실행과 복구

작업 생성 시 inputSourceRevision을 답할 USER의 원본 순번으로 고정한다.
DB에서는 해당 순번 이하의 최근 문맥만 제한해서 읽는다.
문맥은 원본 순번별 USER → ASSISTANT 순서이며 후속 USER를 섞지 않는다.
성공한 선행 응답은 후속 문맥에 포함한다. 공급자 전송 직전 글자 예산도 적용한다.

실행 슬롯 확보 후 기록 행을 SKIP LOCKED로 잠그고 가장 앞선 PENDING/PROCESSING 작업만 선점한다.
선행 작업이 backoff 대기 중이면 후속 작업을 선점하지 않는다.
최종 FAILED 또는 COMPLETED 후에는 다음 작업이 진행한다.
DB 잠금과 트랜잭션은 선점 후 해제하며 LLM 호출 동안 유지하지 않는다.

만료된 PROCESSING은 같은 작업을 재선점하고 version을 증가시킨다.
결과 저장은 기록 잠금 아래 작업 완료와 ASSISTANT 추가를 같은 트랜잭션으로 수행한다.
오래된 version의 저장은 원본 결과·메시지·스트림 카운터까지 롤백된다.
리스 만료 시 외부 호출 중복 자체를 없애는 보장은 아니며 영속 결과의 중복을 차단한다.

POST /api/conversations/messages/{messageId}/response-attempts는 실패한 같은 작업을 다시 대기 상태로 만든다.
PENDING/PROCESSING의 반복 요청은 현재 상태를 반환한다.
COMPLETED는 409이며, 응답 작업이 없는 과거 입력은 404다.
같은 기록에서 더 뒤의 응답이 PROCESSING 또는 COMPLETED이면
HTTP 409 RESPONSE_SUPERSEDED로 과거 응답 삽입을 거절하고 새 메시지로 대화를 이어간다.
이 판단과 선점은 같은 기록 잠금 아래 이루어진다.
후속 작업에 실행 중·완료 상태가 없으면 선행 최종 실패의 재시도를 허용한다.

## 메시지와 작업 상태 조회

GET /api/conversations/records/{recordDayId}?after=&limit=은 record, messages, nextCursor를 반환한다.
after는 0 이상의 정수 streamSequence다. after=0은 처음부터 조회하며 생략하면 최근 페이지를 읽는다.
증분 조회는 순번 오름차순으로 페이지를 고른 뒤 표시 시각으로 반환한다.
limit은 1~200 범위로 제한하고 nextCursor는 페이지의 최대 순번이다.
빈 증분 페이지는 받은 after를 유지한다.

메시지는 streamSequence와 사용자 메시지의 responseJobId를 포함한다.
새 메시지 없이 응답 작업만 실패할 수 있으므로 메시지 커서로 상태 변경을 추적하지 않는다.
GET /api/conversations/response-jobs?ids={id1},{id2}는 1~100개 작업의
id, userMessageId, status, failureReason, retryable을 반환한다.
중복 ID는 합치며 요청 ID 중 조회 불가능한 것이 있으면 전체를 404로 응답한다.
메시지 페이지와 작업 묶음 조회는 후속 실행·완료가 있는 과거 실패의 retryable을 false로 표시한다.
조회와 재요청 사이에는 상태가 바뀔 수 있으므로 재요청 시 서버가 다시 검증한다.

## 개발 기본값과 제한

| 설정 | 기본안 |
|---|---|
| moeum.identity.conversation.free-daily-limit | 20 |
| moeum.identity.conversation.premium-daily-limit | 100 |
| 사용 기간 | 서버 UTC 00:00 초기화 |
| moeum.conversation.limits.accepted-messages-per-minute | 60 |
| moeum.conversation.limits.context-message-limit | 100 |
| moeum.conversation.limits.context-char-budget | 20,000 |
| moeum.conversation.limits.max-message-length | 4,000 |

횟수와 기간은 사용자 확정값이 아닌 조정 가능한 개발 가정이다.
접수 속도 제한은 승인된 새 원본을 제한한다. 인증 실패·잘못된 입력·중복 재전송·조회 요청 전체의
네트워크 남용 차단은 API gateway 등 운영 경계에서 별도로 집행해야 한다.
접수 횟수, 자동 재시도 횟수, 실행 슬롯/공급자 호출 예산은 서로 다른 제한이다.

기능·동시성 검증은 실제 PostgreSQL과 fake 공급자를 사용한다.
독립 JVM 강제 종료·실제 공급자 할당량·P0/P1/P2 부하·6개월 누적 데이터 성능 승인은 R6 범위다.
