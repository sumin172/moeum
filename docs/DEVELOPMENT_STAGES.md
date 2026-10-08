# 모음 — 단계별 개발 계획

> 원칙: 지배권을 유지하며 단계별로 완성도를 올린다.
> 나중에 되돌리기 어려운 것만 초기에 잡고, 나머지는 필요할 때 추가한다.

---

## 개발 단계 개요

```
Stage 0: 골격 (Clean Architecture + DDD 모듈 구조)
Stage 1: 대화 코어 (메시지 저장 + AI 반응)
Stage 2: 일기 생성 (하루 마감 + 초안 + 확정)
Stage 3: 아카이브 (조회 + 검색)
Stage 4: 게이미피케이션 (스트릭 + 포인트)
Stage 5: Insight (감정 패턴 + 회고)
Stage 6: 상용화 (구독 + 결제 + 관리자)
```

---

## Stage 0: 골격

> 목표: 이후 모든 기능이 올바른 위치에 들어가는 구조를 만든다.
> 기능 없음. 구조만.

**할 것**

Gradle 모듈 (실제 기능이 있는 것만 생성)
- `app` — 부트스트랩, 설정 조합
- `identity` — 사용자, 인증
- `conversation` — 메시지, ConversationDay
- `journal` — 일기 생성, 확정
- `platform` — LLM 추상화, 보안, 관측가능성, 공통 인프라
- `shared-kernel` — UserId, TimeProvider

insight, gamification, notification은 해당 Stage에서 모듈 추가.
논리적 Bounded Context는 설계 문서에 정의하되, Gradle 모듈은 구현 시 생성한다.

각 모듈 내 레이어 패키지
- `domain/`, `application/command/`, `application/query/`, `application/publicapi/`
- `infrastructure/`, `interfaces/`

공통
- PostgreSQL schema 분리 (conversation.*, journal.*, identity.*)
- Shared Kernel 정의
- LLM 추상화 인터페이스 (ConversationResponder, JournalGenerator)
- JWT 인증 구조 (auth provider ID와 내부 UserId 분리)
- Domain Event / Integration Event 구분 원칙 정의 (공통 베이스 타입은 첫 실제 이벤트 구현 시 정의 — Stage 0에 만든 `DomainEvent`/`EventEnvelope`는 사용처 없이 남아 #27에서 제거)
- 로컬 개발 환경 (Docker Compose: PostgreSQL)

**절대 하지 않을 것**
- 실제 기능 구현
- Outbox, Kafka, Redis
- 모든 클래스에 인터페이스 생성

**이 단계 완료 기준**
- 앱이 실행된다
- 모듈 간 크로스 참조 없이 각자 독립된 패키지를 가진다
- DB schema가 모듈별로 분리되어 있다

---

## Stage 1: 대화 코어

> 목표: 사용자가 메시지를 보내면 AI가 반응하고, 기록이 저장된다.
> 이 단계에서 MVP의 핵심 가치를 검증한다.

**할 것**

Identity
- 사용자 가입 / Google ID Token 검증 방식 로그인 (2026-07-25 결정, 리다이렉트 기반 OAuth2Login 아님 — 근거는 `ARCHITECTURE.md` "로그인 방식" 참고)
- UserId 생성 (내부 UUID v7, auth ID와 분리 — RFC 9562 직접 구현, `shared-kernel/UserId.kt`)
- JWT 발급 (`platform/security/jwt`)

Conversation
- 메시지 수신 및 저장 (occurred_at, timezone, local_date 포함)
- ConversationDay 자동 생성 및 관리
- AI 반응 호출 (Gemini, 해당 ConversationDay의 전체 메시지를 컨텍스트로 전달)
  - 모델명은 `moeum.gemini.model` 설정값(`GeminiProperties`), 기본값 `gemini-flash-latest` (2026-08-08 결정 — `gemini-2.5-flash`는 신규 API 키에 404 확인되어 폐기)
  - `RestClient` 기반 REST 직접 호출(`GeminiConversationResponder`), 응답 저장 전까지는 트랜잭션을 걸지 않고(외부 HTTP 호출을 트랜잭션 밖에 둠), 응답 저장은 별도 `SaveGeneratedResponseService.save()`(`@Transactional`)로 분리 — 자기 자신 호출로 인한 AOP(`@Transactional`/`@Async`) 무시 문제 회피
  - `@CircuitBreaker(name="geminiConversationClient")`(Resilience4j) 적용, 반복 실패 시 빠른 실패 + fallback에서 사용자 메시지를 FAILED로 전환(2026-08-08 실제 서킷 오픈 동작 확인)
- AI 응답 저장
- 오늘/전날 대화 조회 API (`GET /api/conversations/today`, 2026-08-02 결정)
  - `previousDay` 옵션으로 전날 조회, 나머지 파라미터·동작은 오늘 조회와 동일
  - `after`(마지막으로 받은 messageId) + `limit` 커서 페이지네이션 — 커서 없으면 최근 limit개, 있으면 그 이후 신규만
  - 매번 하루 전체를 재조회하지 않기 위함이며, 같은 커서 메커니즘으로 AI 응답 도착 여부(비동기 생성 완료)도 폴링으로 감지 가능
  - 커서 비교는 id(UUIDv7) 기준, 반환 시 표시 순서는 occurredAt 기준으로 재정렬(지연 전송된 오프라인 메시지가 늦게 도착해도 실제 발화 시점에 맞게 노출)
- 유저당 일일 메시지/토큰 quota 최소 안전장치 (요금제와 무관하게 어뷰징·버그로 인한 비용 폭주 방지, Stage 6 요금제별 Rate Limit과는 별개)

**AI 컨텍스트 전달 방식 (2026-07-25 결정)**
- 고정된 "최근 N개 메시지" 대신, ConversationDay가 자연스러운 하루 단위 경계이므로 해당 날짜의 전체 메시지를 매 호출마다 전달한다.
- LLM API는 무상태라 서버가 대화를 "기억"하지 않는다 — 매 호출마다 컨텍스트 전체를 다시 실어 보내는 것이 유일한 방법이며, 하루 대화가 길어질수록 호출당 비용이 늘어나는 트레이드오프가 있다.
- 비용 완화는 컨텍스트를 깎는 방식이 아니라 Gemini 2.5+의 암묵적 캐싱에 맡긴다 — 요청이 최소 2,048 토큰 이상이고 직전 요청과 동일한 prefix로 시작하면 자동 히트되며, 별도 구현이 필요 없다(명시적 캐싱은 최소 32,768 토큰이 필요해 이 앱 규모에서는 해당 없음). 우리 요청 구조(고정 시스템 지시 + 매번 그대로 재전송되는 누적 대화 이력)가 이미 이 조건에 맞다.
- 무료 티어라도 대화 컨텍스트 품질(핵심 기록 경험)은 깎지 않는다 — 비용 통제는 quota(위)와 자동 캐싱으로, 수익화는 Stage 6에서 Insight 같은 고비용 기능 게이팅으로 한다.

**스키마 핵심**
```
conversation.messages
  id                UUID PK      -- UUIDv7, 시간순 정렬 보장 → 목록 조회 페이지네이션 커서로 사용 (2026-08-02)
  conversation_day_id UUID
  user_id           UUID
  role              TEXT        -- 'user' | 'assistant'
  content           TEXT
  occurred_at       TIMESTAMPTZ
  timezone          TEXT
  local_date        DATE
  client_message_id UUID NULL   -- 클라이언트 멱등키 (user 메시지만)
  response_status   TEXT NULL   -- 'user' 메시지만: PENDING | PROCESSING | COMPLETED | FAILED
  generation_id     UUID NULL   -- AI 응답에만 값 있음
  model             TEXT NULL
  prompt_version    TEXT NULL
  input_tokens      INT NULL    -- AI 응답에만 값 있음, quota 집계용
  output_tokens     INT NULL    -- AI 응답에만 값 있음, quota 집계용
  deleted_at        TIMESTAMPTZ NULL

  UNIQUE (user_id, client_message_id)  -- 중복 메시지 방지

conversation.conversation_days
  id              UUID PK
  user_id         UUID
  local_date      DATE          -- 최초 계산값 고정, 정체성(UNIQUE 키)이라 절대 재계산하지 않음
  timezone        TEXT          -- 메시지 저장마다 최신 관측 zone으로 갱신
                                 -- local_date와 달리 living 값. ConversationActivityQuery가 이 값을 lastObservedTimezone으로
                                 -- 노출하고, diary day 계산(Journal의 책임)의 입력이 된다.
                                 -- 갱신은 local_date를 건드리지 않으므로 정체성 충돌 위험 없음.
                                 -- 한 메시지의 zone 변화로 local_date 자체가 달라지면(실제 자정 넘김) 새 ConversationDay가
                                 -- 열리는 것을 정상으로 받아들임(하루가 여러 row로 쪼개지는 fragmentation은 accepted trade-off)
  source_revision BIGINT DEFAULT 0  -- 메시지 추가 시 증가
  version         BIGINT        -- @Version 낙관적 락
  opened_at       TIMESTAMPTZ

  UNIQUE (user_id, local_date)  -- 하루에 하나

conversation.ai_usage_daily
  user_id         UUID
  usage_date      DATE
  message_count   INT     -- 유저×날짜 일일 quota 카운터, 호출 "시도" 시점에 증가(재시도 남용 방지)
  input_tokens    BIGINT  -- 현재 미집계(항상 0), 토큰 기준 quota 도입 시 사용 예정
  output_tokens   BIGINT  -- 현재 미집계(항상 0)

  PRIMARY KEY (user_id, usage_date)
```
`AiUsageRepositoryImpl.recordAttempt`는 Spring Data `@Query`/`@Modifying`이 아니라 `EntityManager.createNativeQuery`로 `INSERT ... ON CONFLICT DO UPDATE ... RETURNING message_count`를 직접 실행한다(2026-08-08 결정) — Spring Data는 `@Modifying` 쿼리의 반환값으로 `RETURNING` 결과를 받을 수 없어 원자적 증가-후-값조회를 리포지토리 메서드 시그니처만으로 표현할 수 없기 때문. 동시성 보호는 Postgres의 `ON CONFLICT` unique-index 기반 원자성에 위임하며(낙관적 락 재시도 아님), 20-스레드 동시 호출 통합테스트로 검증(`AiUsageRepositoryIntegrationTest`).

**response_status 사용**
- user 메시지 저장 시 PENDING
- AI 응답 생성 시작 시 PROCESSING
- 응답 저장 완료 시 COMPLETED
- AI 호출 실패 시 FAILED
- "어떤 메시지의 답변이 빠졌는지" 쿼리 가능
- retry/backoff 복잡도가 높아지면 별도 `response_generation_jobs` 테이블로 분리

**client_message_id 사용법**
```json
{
  "clientMessageId": "uuid-generated-on-device",
  "content": "오늘 정말 피곤했다",
  "occurredAt": "2026-07-19T14:30:00+09:00"
}
```
네트워크 단절 후 재시도해도 같은 clientMessageId면 중복 저장하지 않는다.

**이 단계에서 Moment는 추출하지 않는다**
- 지금은 메시지 원본 저장이 전부 (Moment는 Stage 2 "Moment (보류)" 참고)

**이 단계 완료 기준**
- 실제 대화 흐름이 된다
- AI 반응 실패 시 메시지는 저장된 상태를 유지한다
- 메시지에 local_date + timezone이 정상 저장된다
- 동일 clientMessageId 재전송 시 중복 저장되지 않는다

2026-08-08: 위 기준 모두 실기동 스모크 테스트(실제 Google 로그인 + 실제 Gemini API 키)로 충족 확인. 서킷브레이커 fallback(강제 유발), 시사·정치 등 소재 이탈 질문에 대한 `SYSTEM_INSTRUCTION` 가드레일 동작도 함께 확인. Stage 1 완료.

---

## Stage 2: 일기 생성

> 목표: 하루 대화를 일기로 변환하고 확정하는 흐름을 완성한다.

**할 것**

Conversation
- ConversationDay는 메시지 저장 시 자동 생성·갱신된다(Stage 1). 별도의 "마감" 상태나 배치는 없다 — 하루의 경계는 `local_date` + `timezone`으로 항상 계산 가능하므로, 명시적으로 상태를 전환하는 단계 자체가 불필요하다
- Journal이 조회할 수 있도록 publicapi(`ConversationActivityQuery`)로 `findActivities(from, to)`(구간 내 원시 활동)와 `findMessages(userId, from, to)`(구간 원본 조회)를 노출한다. "diary day"나 "생성 대상" 같은 Journal의 개념은 이 인터페이스에 등장하지 않는다

Journal
- `DiaryPreference`(사용자별 `generationTime`)로 "하루를 어디서 끊고 싶은지"를 사용자별로 표현한다
- **GenerationPlanner**가 `plan(from, to)` 하나로 동작 — 구간 내 활동을 각각 현재 timezone+`generationTime`으로 diaryDate에 매핑하고, `distinct(userId, diaryDate)`한 뒤 `generation_jobs`에 PENDING Job을 멱등 insert한다. 이 함수를 "최근 몇 분"(정상 경로)과 "최근 며칠"(reconciliation)로 다른 범위를 주고 반복 호출하는 것으로 정상/복구를 모두 처리한다(자세한 규칙은 아래 "Journal 생성 스케줄러" 참고)
- **GenerationExecutor**가 주기적으로 `scheduledAt`이 지난 PENDING Job을 실행 — `ConversationActivityQuery.findMessages(windowStart, windowEnd)`로 원본을 조회해 서사 생성. Moment는 입력에 없다
- `JournalGenerator`로 일기 초안 생성 — `GeminiJournalGenerator`/`ClaudeJournalGenerator` 둘 다 구현, `moeum.journal.provider`(기본값 `gemini`)로 선택. Claude는 결제 설정 후 전환할 대기 상태
- 구조화 출력 (현재는 `{"title", "body"}` 최소 구조. JSON Schema 검증 등 본격 구조화는 소비처(아카이브 상세, Stage 3)가 생긴 뒤 설계)
- AI 생성 메타데이터 저장
- 일기 확정

**상태 분리 — Journal 생명주기 ≠ 생성 Job 상태**

```
journal.journals
  lifecycle_status TEXT  -- DRAFT | CONFIRMED | OUTDATED
  -- OUTDATED: 확정 후 원본 대화가 추가된 경우

journal.generation_jobs
  generation_status TEXT  -- PENDING | PROCESSING | COMPLETED | FAILED
  UNIQUE (user_id, diary_date) -- 최초 생성 Job 멱등키 (아래 스키마 참고)
  attempt_count INT
```

하나의 status 컬럼에 합치지 않는다.
AI가 PROCESSING 중에도 Journal은 DRAFT 상태를 유지할 수 있다.

**스키마 핵심**
```
journal.journals
  id                    UUID PK
  user_id               UUID
  diary_date            DATE        -- Journal이 계산한 diary day. ConversationDay.local_date와 다른 개념(generation_jobs.diary_date와 동일 정의)
  lifecycle_status      TEXT        -- DRAFT | CONFIRMED | OUTDATED
  title                 TEXT
  content               JSONB
  current_revision      INT DEFAULT 1
  version               BIGINT      -- @Version 낙관적 락
  confirmed_at          TIMESTAMPTZ NULL
  deleted_at            TIMESTAMPTZ NULL
  purge_after           TIMESTAMPTZ NULL

  UNIQUE (user_id, diary_date)  -- generation_jobs와 마찬가지로 V1 한정(재생성 지원 시 재검토)

journal.journal_revisions
  id            UUID PK
  journal_id    UUID
  revision_no   INT
  title         TEXT
  content       JSONB
  edited_by     TEXT    -- 'user' | 'ai'
  created_at    TIMESTAMPTZ

-- journals.title/content는 현재 최신 스냅샷이며,
-- journal_revisions는 변경 이력 보존용이다.
-- 두 값은 동일 트랜잭션에서 갱신한다.

journal.diary_preferences
  user_id         UUID PK
  generation_time TIME          -- 이 시각(사용자 timezone 기준)을 diary day 경계로 본다. 기본값은 "구현하면서 결정" 참고
  created_at      TIMESTAMPTZ
  updated_at      TIMESTAMPTZ

journal.generation_jobs
  id                             UUID PK
  journal_id                     UUID NULL
  user_id                        UUID
  diary_date                     DATE          -- Journal이 계산한 diary day. ConversationDay.local_date와 다른 개념
  window_start                   TIMESTAMPTZ   -- findMessages 조회 구간 시작
  window_end                     TIMESTAMPTZ   -- findMessages 조회 구간 끝
  scheduled_at                   TIMESTAMPTZ   -- 이 시각 이후 Executor가 claim 가능
  timezone_at_scheduling         TEXT          -- Planning 시점 관측 timezone (감사/디버깅용)
  generation_time_at_scheduling  TIME          -- Planning 시점 DiaryPreference.generationTime 스냅샷
  generation_status              TEXT          -- PENDING | PROCESSING | COMPLETED | FAILED
  attempt_count                  INT DEFAULT 0
  provider                       TEXT NULL
  model                          TEXT NULL
  prompt_version                 TEXT NULL
  input_tokens                   INT NULL    -- quota 집계용
  output_tokens                  INT NULL    -- quota 집계용
  generation_id                  UUID NULL
  generated_at                   TIMESTAMPTZ NULL
  error_code                     TEXT NULL
  created_at                     TIMESTAMPTZ

  UNIQUE (user_id, diary_date)  -- V1 최초 생성 Job에 대한 멱등키일 뿐, 영구 도메인 제약은 아니다.
                                 -- 재생성을 지원하게 되면 Job이 "일기 하나"가 아니라 "생성 시도 하나"를 뜻해야 하므로
                                 -- 그때 키 구성이 달라질 수 있다.
```

**Moment (보류)**

Moment(구조화된 기억 조각: 시각/타입/감정)는 Insight(Stage 5, 감정 패턴 근거)와 아카이브(Stage 3, 사용자가 열람하는 "기억 조각")가 공유할 데이터이고, 원본은 저비용 모델이 읽고 비싼 모델에는 Moment를 넘기는 비용 분업 경로이기도 하다.

2026-10-08(#27) 구현과 테이블을 제거했다. 호출부·구독자가 없는 선제 구현이었고, `conversation_day_id`(자정 기준)에 키가 묶여 일기(diary day 기준)와 구간이 어긋났으며, 같은 day의 두 번째 추출이 부분 유니크 인덱스 위반으로 항상 실패하는 버그도 있었다. 하루 경계 통일 이후 처음 쓰는 기능(Stage 3 또는 Stage 5)에서 키 구조·호출 시점·버전 관리(재추출 시 이전 결과 superseded 처리)를 다시 설계한다. 이전 설계는 git 이력(#21, #23)에 남아 있다.

**Journal은 원본만으로 생성한다**

`JournalGenerationRequest(rawTranscript: String, localDate: String)` — Moment는 입력에 없다. narrative화는 원본으로부터 한 번만 일어난다.

**한 유저 하루 대화량 자체가 극단적으로 커지는 경우 (참고, 지금 안 만듦)**

지금 방식(하루 전체를 한 번에 LLM에 투입)은 LLM 컨텍스트 윈도우 자체의 한계는 해결하지 못한다. 그 지점에 도달하면 "한 번에 추출"이 아니라 대화 도중 구간별로 점진적으로 추출하는 방식으로 재설계해야 한다. 지금은 신호가 없으니 만들지 않는다 (트리거 조건은 "구현하면서 결정" 표 참고).

**재생성 식별 (V1 범위 밖)**

`generation_jobs.UNIQUE(user_id, diary_date)`는 diary_date당 최초 생성 Job의 멱등키일 뿐, 재생성 Job을 식별하는 메커니즘이 아직 없다. 아래 "일기 확정 후 메시지 추가 정책"의 OUTDATED 전환과, 그 OUTDATED 상태를 실제로 재생성하는 흐름은 별개다. 후자는 V1 범위 밖이며, 구현 시점에 generation_jobs의 키 구성을 다시 설계해야 한다.

**일기 확정 후 메시지 추가 정책**
- 메시지 추가는 상태와 무관하게 항상 허용된다 (원본 기록 우선)
- Journal이 CONFIRMED 상태였다면 OUTDATED로 변경
- 사용자에게 재생성 여부 제공 (재생성 실행 메커니즘은 V1 범위 밖, 위 참고)

**오프라인 지연 전송과의 상호작용**
- occurredAt을 클라이언트 작성 시각으로 받으므로, Journal이 이미 그 날짜의 일기를 생성한 뒤에 오프라인 큐잉됐던 메시지가 도착하는 상황이 구조적으로 항상 가능하다(비행기 모드 등으로 지연이 몇 시간~며칠까지 벌어질 수 있음)
- 이 경우도 저장은 그대로 허용하고, 위 OUTDATED 전환 정책으로 처리한다

**미확정 일기 정책 (결정 필요)**
- 선택지: N일 후 자동 확정 or 영구 DRAFT 유지
- Insight 집계에 DRAFT 포함 여부

**Journal 생성 스케줄러 — Planner/Executor 분리**

이벤트 리스너가 아니라 Journal 자신의 스케줄러가 주기적으로 대상을 스캔한다. Conversation은 "마감"이라는 신호를 별도로 주지 않으므로, "언제 생성해도 되는지"를 Journal이 스스로 판단해야 한다. 판단(Planning)과 실행(Execution)은 분리한다.

**핵심 invariant**
1. 하나의 Conversation 메시지는 최대 하나의 Journal에 포함된다.
2. 활동이 없는 diary day는 generation_job도 만들지 않는다.
3. 이미 generation_jobs에 존재하는 Job의 window는 이후 preference/timezone 변경으로 수정하지 않는다.
4. 아직 계획되지 않은 활동은, Planner가 그 활동을 처리하는 시점에 유효한 DiaryPreference/timezone으로 diary day를 계산한다.

```kotlin
// GenerationPlanner — from/to는 호출부가 결정한다(Planner가 now()를 스스로 들여다보지 않음).
// 대상을 판단하고 Job을 만들 뿐, LLM을 부르지 않는다.
fun plan(from: Instant, to: Instant) {
    val activities = conversationActivityQuery.findActivities(from, to)
    val diaryDays = activities
        .map { it.userId to diaryDayCalculator.calculate(it, currentPreferenceOf(it.userId)) }
        .distinct()
    diaryDays.forEach { (userId, diaryDate) -> planJobFor(userId, diaryDate) }  // PENDING insert 시도, UNIQUE 충돌은 스킵
}

// 정상 경로: 스케줄 주기보다 lookback을 넉넉히 겹치게 잡아 자체 유실을 막는다
@Scheduled(fixedDelayString = "PT5M")
fun planRecent() {
    val now = clock.instant()
    runCatching { plan(from = now.minus(Duration.ofMinutes(10)), to = now) }
        .onFailure { e -> log.error("Journal 생성 Planning(RECENT) 실패", e) }
}

// 복구 경로: 정상 경로의 lookback보다 긴 장애로 놓친 활동을 회수한다. 같은 plan()을 범위만 넓혀 호출할 뿐,
// generation_jobs와 별도로 비교하는 diff 로직을 두지 않는다 — 멱등 insert가 이미 그 역할을 한다.
@Scheduled(cron = "0 0 4 * * *")
fun planReconcile() {
    val now = clock.instant()
    runCatching { plan(from = now.minus(Duration.ofDays(3)), to = now) }
        .onFailure { e -> log.error("Journal 생성 Planning(RECONCILIATION) 실패", e) }
}

// GenerationExecutor — 이미 내려진 결정을 실행할 뿐이다
@Scheduled(fixedDelayString = "PT1M")
fun execute() {
    runCatching { executeDueJobs() }
        .onFailure { e -> log.error("Journal 생성 Execution 실패", e) }
}

fun executeDueJobs() {
    val dueJobs = generationJobRepository.findPendingDue(now())
    dueJobs.forEach { job -> executeSingleJob(job) }
}
```

Planning이 예정보다 늦게 돌아도(예: 02:00 컷오프인데 02:03에 실행) 문제가 되지 않는다 — 그 시점 활동 기준으로 diaryDate/window를 계산해 Job을 만들 뿐이고, Execution은 `scheduledAt`이 지난 Job만 골라 실행하므로 결과가 달라지지 않는다.

lookback(10분)/reconciliation 범위(3일)·주기는 예시 수치다. 구체 값은 "구현하면서 결정" 표 참고. `plan(from, to)`가 순수하게 범위를 인자로 받으므로, "9/21~24 누락분 재plan" 같은 수동 복구도 같은 함수로 그대로 처리된다 — 별도 복구 기능을 만들 필요가 없다.

**Observability**

`journal.planning.{activities,buckets,jobs.created,jobs.duplicate,duration}` 메트릭을 `planningType`(RECENT | RECONCILIATION) 태그로 남긴다. 로그에도 `planningType`, `from`, `to`, `activityCount`, `bucketCount`, `createdCount`, `duplicateCount`를 남긴다. Reconciliation 실행에서 `jobs.created > 0`이 관측되면 그 자체가 "정상 경로가 최근 며칠간 일부를 놓쳤다"는 신호다.

**Accepted Risk**

Reconciliation이 며칠 전 활동을 뒤늦게 발견하면, 그 활동이 실제 발생했던 시점이 아니라 **발견(Planning) 시점의 현재 DiaryPreference**로 diary day가 계산된다 — preference를 시점별로 이력 관리(effective-dated)하지 않기로 한 결정의 직접적인 결과다. 정상 경로가 정상 동작하는 한 발생 범위는 "정상 Planning 실패 + 그 사이 preference 변경 + reconciliation에서 뒤늦게 발견"이 겹치는 좁은 경우로 제한된다. V1에서는 이 리스크를 감수하고 effective-dated preference를 만들지 않는다.

**Executor의 트랜잭션 분리**

LLM 호출 동안 DB 커넥션을 붙잡지 않는 것이 핵심이다. Planner는 Job을 만들기만 하고 LLM을 부르지 않으므로 이 문제에서 자유롭다 — Executor에서만 신경 쓰면 된다.

```
PENDING Job claim (PROCESSING으로 전이) → 커밋
트랜잭션 밖 → ConversationActivityQuery.findMessages(windowStart, windowEnd) → LLM 호출
새 트랜잭션 → Journal 저장 → Job COMPLETED → 커밋
실패 시     → 새 트랜잭션 → Job FAILED, errorCode, attemptCount 기록 → 커밋
```

**유실 가능성 인지**

정상 경로는 매 회차 조회 구간이 서로 겹치게(lookback ≥ 스케줄 주기) 스캔하므로, 특정 회차가 서버 재시작 등으로 건너뛰어도 다음 회차의 겹치는 구간에서 그대로 다시 잡힌다. Job은 한 번 INSERT되면 UNIQUE 제약으로 중복 없이 보존되고 Executor가 이후 언제든 claim해서 실행한다. 장애가 lookback보다 길어지면 정상 경로만으로는 회수되지 않으므로, 그 상한을 reconciliation(위 참고)이 메운다 — 두 경로가 같은 `plan()`을 쓰므로 "정상 경로는 유실 없음, 장애는 각자 알아서"가 아니라 "관측 범위가 다른 같은 메커니즘이 상한 없이 복구한다"가 된다.

그래도 다음은 필요하다:
- Journal 생성 Job에 FAILED/PENDING 상태 기록 (재시도 대상 판단용)
- 반복 실패(attempt_count 임계치 초과)에 대한 수동 개입 경로

**이 단계 완료 기준**
- 대화 → 일기 생성 → 수정 → 확정 흐름이 작동한다
- AI 생성 실패 시 FAILED 상태로 기록되고 재시도 가능하다
- 모바일/웹 동시 수정 시 @Version 낙관적 락이 충돌을 감지한다
- JournalConfirmed Integration Event가 발행된다 (현재는 in-process)

---

## Stage 3: 아카이브 & 조회

> 목표: 쌓인 일기를 날짜별, 감정별로 탐색할 수 있다.

**할 것**
- 날짜별 일기 목록 API
- 월간 캘린더 뷰 (일기 유무 표시)
- 태그 정확 일치 검색 (`journal.journal_tags` INDEX)
- 제목/본문 기본 LIKE 검색
- 일기 상세 조회 (원본 대화 연결)
- 일기 상세에서 **Moment 드릴다운** (Moment 재설계 포함, Stage 2 "Moment (보류)" 참고) — Journal은 "그날의 서사", Moment는 "추억의 조각". 일기 문장 ↔ 근거 Moment를 연결해 클릭하면 원본 조각(시각/타입/감정)을 볼 수 있게 한다
- 연도별 아카이브

**검색 전략**
- 태그: 정확 일치 (LIKE 사용 안 함)
- 제목/본문: LIKE → 필요 시 pg_trgm → FTS로 점진적 확장
- 벡터 검색 / 전문 검색 엔진: 실제 필요성 확인 후

---

## Stage 4: 게이미피케이션

> 목표: 기록 지속성을 유도하는 보상 구조를 추가한다.
> 이 단계에서 Outbox 패턴을 도입한다 (PointLedger 중복 지급 방지).

**할 것**
- 연속 기록 스트릭
- PointLedger (이벤트 기반, source_event_id UNIQUE로 중복 방지)
- 기본 미션 (첫 기록, 7일 연속 등)
- 업적 배지

**Outbox 도입 기준 (Stage보다 내구성 요구로 판단)**

기준: 이벤트가 유실되면 업무 결과가 틀어지는 첫 시점.
Stage 4가 기본이지만, 이전 단계에서 유실 복구가 어렵다고 판단되면 조기 도입한다.
단순 스케줄러 기반 폴러로 시작 (Debezium, CDC 불필요).

```
gamification.point_ledger
  id UUID PK
  user_id UUID
  amount INT
  reason TEXT
  source_event_id UUID UNIQUE  -- 멱등성 보장
  created_at TIMESTAMPTZ
```

---

## Stage 5: Insight

> 목표: 장기 기록을 바탕으로 패턴과 변화를 보여준다.

**할 것**
- 감정 변화 주간/월간 분석
- 활동 패턴 (Claude Sonnet 4.6)
- 분석 근거 참조 (어떤 일기/Moment 기반인지)
- 불확실성 표시 필수
- 분석 숨기기 / 삭제 / 피드백

**이 단계에서 Read Model 도입 검토**
- 홈 화면에 5개 이상 조회가 필요해지면 HomeDashboardProjection 추가

**반드시 피할 표현**
```
❌ 우울증 위험 72%
✅ 최근 2주간 피곤하다는 기록이 이전보다 많았습니다
```

---

## Stage 6: 상용화

> 목표: 실제 과금과 운영 도구를 추가한다.

**할 것**
- 구독 플랜 / 결제 — 무료 티어의 대화 컨텍스트(핵심 기록 경험)는 깎지 않고, Insight(Claude Sonnet) 같은 고비용 기능을 유료 게이팅하는 방향으로 설계 (2026-07-25 결정)
- 사용량 제한 (Rate Limit) — 요금제별 세분화된 제한. 유저당 최소 quota 안전장치는 Stage 1부터 이미 적용됨, 여기서는 그걸 요금제 단위로 고도화
- 데이터 내보내기 (전체 기록 ZIP)
- 계정 탈퇴 + 파생 데이터 연쇄 삭제
- 관리자 도구
- 모델 라우팅 최적화 (비용 vs 품질)
- AI 학습 사용 동의 처리

---

## 단계별 인프라 도입 계획

| 인프라         | 기본 계획      | 조기 도입 조건                          |
|----------------|----------------|-----------------------------------------|
| PostgreSQL     | Stage 0        | —                                       |
| Redis          | Stage 1 이후   | 세션 필요 시 (초기엔 DB 세션)           |
| Object Storage | 도입 시점 미정 | 이미지·음성 등 첨부 기능 필요 시        |
| Outbox 패턴    | Stage 4        | 이벤트 유실이 업무 손실로 이어지는 시점 |
| Read Model     | Stage 5        | N+1 실측 시                             |
| Kafka          | MSA 분리 시    | Outbox transport 교체                   |

---

## 변경하면 나중에 크게 아픈 것 (절대 초기에 잡기)

1. 메시지 스키마 — occurred_at + timezone + local_date + client_message_id
2. UserId — 내부 UUID, auth ID와 분리
3. 원본/파생 테이블 분리 — conversation vs journal
4. AI 생성 메타데이터 — 모든 AI 결과에 (대화 응답 포함)
5. 데이터 유형별 삭제·보존 정책 — deleted_at + purge_after + Hard Delete 기준 정의
6. PostgreSQL schema 분리 — 모듈별
7. ConversationDay.source_revision — OUTDATED 판단 기준

## 나중에 고쳐도 되는 것

- Clean Architecture 레이어 완성도
- Outbox 도입 시점 (내구성 요구 발생 시)
- 검색 전략 (LIKE → 전문 검색)
- 이벤트 인프라 고도화 (Kafka, CDC)  ← 이벤트 계약 버전 필드는 처음부터
- Projection / CQRS
- 복수 AI 제공자 구현
- response_generation_jobs 테이블 분리 (retry 복잡도 증가 시)

---

## 구현하면서 결정해도 되는 것

실제 사용 흐름을 본 뒤 결정한다. 지금 확정하지 않아도 된다.

| 항목                                                    | 결정 시점                                   |
|---------------------------------------------------------|---------------------------------------------|
| 미확정 일기 자동 확정 여부 (N일 후 vs 영구 DRAFT)       | 사용자 행동 패턴 관찰 후                    |
| Insight 집계에 DRAFT 일기 포함 여부                     | Insight 기능 구현 시                        |
| AI 응답 상태를 Message 컬럼으로 유지 vs 별도 Job 테이블 | retry 복잡도 증가 시                        |
| Moment confidence를 제품 UI에 노출할지                  | UX 설계 시                                  |
| DiaryPreference.generationTime 기본값                   | 사용자 행동 패턴 관찰 후                    |
| GenerationPlanner 정상 경로 폴링 주기 / lookback 구체 수치 | 실제 트래픽 패턴 확인 후                  |
| GenerationPlanner reconciliation 범위(일수) / 주기       | 실제 장애 패턴 확인 후                      |
| 재생성 시 generation_jobs 키 구성 (Job:Journal = N:1 여부) | 재생성 기능 설계 시                       |
| Moment 재도입 시 키 구조·호출 시점 (동기 vs 배치)       | Insight(Stage 5) 또는 아카이브(Stage 3) 중 먼저 구현하는 쪽에서 |
| Redis 도입 여부                                         | 세션/캐시 실제 필요 발생 시                 |
| 무료 티어 quota 구체적 수치 (일일 메시지/토큰 한도)     | 실사용 트래픽 패턴 확인 후                  |
| 검색을 LIKE → pg_trgm → FTS 중 어디까지 발전시킬지      | 검색 품질 불만 발생 시                      |
