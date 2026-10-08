# 모음 (Moeum) — 아키텍처 결정 문서

> 최종 업데이트: 2026-10-08

---

## 서비스 한 줄 정의

AI와 일상 대화를 통해 자연스럽게 기록하고, 하루가 끝날 때 일기로 정리해 아카이브하는 플랫폼.
경쟁력은 가장 똑똑한 AI가 아니라 **사용자의 삶을 가장 잘 기록하고 연결하는 경험**에 있다.

---

## 핵심 데이터 철학

```
사용자 메시지 (Message, role=user)  = 원본 기록, 절대 불변, 가장 중요한 자산
AI 대화 응답 (Message, role=assistant) = 사용자 경험상 대화이지만 기술적으로는 AI 파생 결과
Moment                               = Conversation이 소유할 구조화된 기억 조각 (구현 보류, 아래 "Moment (보류)" 참고)
AI 생성 결과 (Journal, Insight)      = 언제든 재생성 가능한 파생 데이터
```

AI가 틀리거나 모델이 교체되어도 원본 기록은 반드시 보존된다.
AI 대화 응답도 생성 메타데이터(model, generation_id)를 가져야 디버깅과 모델 교체가 가능하다.

---

## 기술 스택

| 영역   | 선택                           | 이유                                                                                                            |
|--------|--------------------------------|-----------------------------------------------------------------------------------------------------------------|
| 백엔드 | Kotlin + Spring Boot + Java 21 | 도메인 표현력, 트랜잭션, JPA, 스케줄러 통합                                                                     |
| 빌드   | Gradle Kotlin DSL              |                                                                                                                 |
| 웹     | Next.js                        | 아카이브 UI + 얇은 BFF                                                                                          |
| 모바일 | Flutter (또는 RN+Expo)         | 팀 숙련도에 따라 결정                                                                                           |
| DB     | PostgreSQL                     | 단일 클러스터로 시작                                                                                            |
| 캐시   | Redis                          | 세션·Rate Limit (초기에는 생략 가능)                                                                            |
| 파일   | Object Storage                 | 이미지·음성 (도입 시점 미정, 필요해지면 결정)                                                                   |

### AI 모델 라우팅

| 용도               | 모델                | 이유                             |
|--------------------|---------------------|----------------------------------|
| 일상 대화 반응     | Gemini Flash (계열) | 빈도 높음, 무료 티어로 개발 가능 |
| Moment 추출 (보류) | Gemini Flash (계열) | 구조화 출력, 저비용              |
| 일기 생성 초안     | Claude Haiku 4.5    | 하루 1회, 한국어 품질            |

일기 생성은 Claude Haiku가 목표 모델이지만, 결제 설정 전까지 기본값은 Gemini다(`moeum.journal.provider=gemini`). 두 구현 모두 존재하며 설정값만 바꾸면 전환된다.
| Insight 생성       | Claude Sonnet 4.6   | 주·월 1회, 품질 우선             |

모델 계열명(Flash)만 여기서 고정하고 구체 버전 alias는 설정값(`moeum.gemini.model`)으로 둔다 — Gemini 모델 alias는 구세대가 조기 폐기되는 경우가 있어(2026-08-08: `gemini-2.5-flash`가 신규 API 키에 404, `gemini-flash-latest`로 교체) 문서에 특정 버전을 못박지 않는다.

**비용 제어 원칙 (2026-07-25 갱신)**
- 대화 반응: 고정된 "최근 N개" 대신 사용자 하루(day_date, 아래 "하루 경계" 참고)의 전체 메시지를 컨텍스트로 사용 — LLM API가 무상태라 컨텍스트를 매번 재전송해야 하며, 하루 단위 자연 경계를 그대로 씀
- 컨텍스트 재전송 비용은 Gemini 2.5+의 암묵적 캐싱(implicit caching)이 자동으로 완화한다 — 별도 구현 필요 없음, 요청 최소 2,048 토큰 이상이고 이전 요청과 동일한 prefix일 때 자동 히트. 우리 요청 구조(고정 시스템 지시 + 계속 자라나는 대화 이력을 매번 그대로 재전송)가 이미 이 조건에 맞는 형태라 별도 코드 변경이 필요 없다. `GeminiConversationResponder`가 응답의 `cachedContentTokenCount`를 로그로 남겨 실제 히트 여부를 관측한다. 컨텍스트 자체를 깎지 않음(무료 티어도 핵심 기록 경험은 동일하게 유지)
- 유저당 일일 메시지/토큰 quota를 하드 캡으로 둠(요금제와 무관, 어뷰징·버그로 인한 비용 폭주 방지 — Stage 6 요금제별 Rate Limit과는 별개의 안전장치)
- 수익화는 컨텍스트 축소가 아니라 Insight(Claude Sonnet) 같은 고비용 기능 게이팅으로 함
- 응답 토큰 상한: 일상 반응 150 토큰 (출력 측 제어, 위 컨텍스트 정책과 별개 축) — 아직 미구현(요청에 `maxOutputTokens` 미설정)
- 시스템 프롬프트 최소화
- 개발 중 전체를 Gemini 무료 티어로 처리

---

## 아키텍처 전략

### 모듈러 모놀리스 → 필요 시 MSA

초기에는 하나의 애플리케이션으로 운영한다.
논리적 경계는 MSA와 동일하게 설계하되, 물리적 분리는 실제 필요가 생길 때만 한다.

**MSA 분리 검토 조건 (2개 이상 충족 시)**
- 특정 모듈만 독립 스케일링이 필요
- 장애 반경을 격리해야 하는 상황 발생
- 팀 소유권이 실제로 나뉨
- 배포 주기가 명확히 달라짐
- 다른 런타임 필요 (Python GPU 등)
- 보안 또는 데이터 정책이 달라짐

**우선 분리 후보 (나중에)**
```
Notification → 기록 기능과 장애 격리 필요
Insight      → Python 분석 모델 필요 시
LLM Gateway  → 독립 스케일링 필요 시
```

---

## 모듈 구조

```
backend/
├─ bootstrap/           # 앱 진입점, 설정 조합
├─ modules/
│  ├─ identity/         # 사용자, 계정, 인증, 구독
│  ├─ conversation/     # 메시지, 하루 경계(DayPreference), Moment
│  ├─ journal/          # 일기 생성, 수정, 확정
│  ├─ insight/          # 감정 분석, 패턴, 회고
│  ├─ gamification/     # 미션, 스트릭, 포인트 원장
│  └─ notification/     # 알림 발송
├─ platform/
│  ├─ llm/              # AI 제공자 추상화
│  ├─ database/         # JPA 공통 설정
│  ├─ messaging/        # 이벤트 발행/구독 인프라
│  ├─ security/         # JWT, 인증 필터
│  └─ observability/    # 로깅, 메트릭, 트레이싱
└─ shared-kernel/       # UserId, TimeProvider (허용 목록은 PRINCIPLES.md 참고)
```

### 각 모듈 내부 레이어

```
{module}/
├─ domain/         # 엔티티, 값객체, 도메인 이벤트, 레포지터리 인터페이스
├─ application/
│  ├─ command/     # 명령 유즈케이스
│  ├─ query/       # 조회 유즈케이스
│  └─ publicapi/   # 다른 모듈에 공개하는 인터페이스 (유일한 의존 진입점)
├─ infrastructure/ # JPA 구현, 외부 API 클라이언트, 이벤트 퍼블리셔
└─ interfaces/     # REST 컨트롤러, DTO, 요청/응답 매핑
```

다른 모듈은 `domain`, `infrastructure`에 접근하지 않고 `publicapi`만 의존한다.

```kotlin
// conversation/application/publicapi
interface ConversationActivityQuery {
    fun findActiveDays(from: Instant, to: Instant): List<ActiveDay>
    fun findMessages(userId: UserId, dayDate: LocalDate): List<MessageSnapshot>
}
// 나중에 HTTP 클라이언트로 구현을 교체해도 호출부 코드는 변경 없음
```

---

## Bounded Context & 데이터 소유권

각 데이터는 **하나의 컨텍스트만 원본 소유자**다.

| 컨텍스트     | 소유 데이터                                              |
|--------------|----------------------------------------------------------|
| Identity     | User, Account, Device, Consent, Subscription             |
| Conversation | Message, DayPreference, Moment, Attachment               |
| Journal      | Journal, JournalRevision, JournalSection, JournalTag     |
| Insight      | EmotionObservation, DailyInsight, WeeklyInsight, Pattern |
| Gamification | Mission, Streak, Achievement, PointLedger, Reward        |
| Notification | NotificationLog, NotificationTemplate                    |

**Moment 생성 책임**
Moment는 Conversation 컨텍스트가 소유하고 생성한다.
Journal 모듈은 Moment를 생성하거나 conversation 테이블에 저장하지 않는다.

**하루 경계 — Conversation이 소유한다 (2026-10-08, #29)**

"하루"는 자정이 아니라 사용자별 하루 시작 시각(`DayPreference.dayStartTime`, 기본 02:00)으로 나뉜다. 대화 컨텍스트, 일기, quota가 모두 이 하나의 하루를 쓴다.

- 메시지를 저장할 때 `occurredAt` + 메시지 timezone + 하루 경계로 `messages.day_date`를 계산해 함께 저장하고, 이후 재계산하지 않는다.
- 하루 시작 시각 변경은 **다음 하루부터** 적용된다(`pending_day_start_time`, `pending_effective_from`). 진행 중인 하루가 중간에 늘거나 줄지 않고, 이미 끝난 하루로 새 메시지가 다시 들어가지 않는다. 대신 바꾼 다음 날 하루는 한 번 길어지거나 짧아진다.

이전에는 Conversation이 자정 기준 `ConversationDay`를, Journal이 `DiaryPreference.generationTime` 기준 diary day를 따로 계산했다. 그 결과 (1) 자정을 넘기면 AI가 대화 맥락을 잃었고, (2) 일기와 대화(Moment 포함)의 하루 구간이 어긋났으며, (3) 메시지마다 ConversationDay row를 낙관적 락으로 갱신하는 병목이 있었다. 대화 컨텍스트에도 같은 경계가 필요하므로 원본을 소유한 Conversation으로 옮겼다(journal → conversation 단방향 의존 유지).

**Journal — 원본 대화로 직접 서사를 생성한다**

Journal은 Moment를 입력으로 쓰지 않는다. Conversation은 "마감"이라는 별도 상태나 이벤트를 갖지 않는다 — 하루가 언제 끝나는지는 하루 경계로 항상 계산 가능하다. Journal은 자체 스케줄러로 새 활동이 생긴 하루를 찾아 그 하루가 끝나는 시각에 생성 Job을 예약하고, Conversation의 publicapi로 그 하루의 원본 대화를 조회해 서사를 쓴다. Journal의 `diary_date`는 Conversation의 `day_date`와 같은 값이다.

```kotlin
// conversation/application/publicapi
interface ConversationActivityQuery {
    fun findActiveDays(from: Instant, to: Instant): List<ActiveDay>
    fun findMessages(userId: UserId, dayDate: LocalDate): List<MessageSnapshot>
}
// ActiveDay(userId, dayDate, dayEnd) — [from, to)에 새 메시지가 "저장된"(created_at) 하루.
// 저장 시각 기준이라 오프라인으로 늦게 도착해 과거 하루에 들어간 메시지도 도착 시점에 잡힌다.
// dayEnd는 하루 경계와 그 하루에서 가장 최근 관측된 timezone으로 계산한다.
```

**핵심 invariant**
1. 메시지 하나는 정확히 하나의 하루(`day_date`)에 속하고, 일기는 (user, 하루)당 하나다 — 따라서 메시지는 최대 하나의 Journal에 포함된다.
2. 활동이 없는 하루는 generation_job도 만들지 않는다.
3. 메시지의 `day_date`는 저장 시점에 확정되며, 이후 하루 경계 변경으로 재계산하지 않는다.
4. 하루 경계 변경은 다음 하루부터 적용된다 — 이미 시작된 하루의 끝(= 예약된 Job의 `scheduled_at`)은 바뀌지 않는다.

**GenerationPlanner — 하나의 순수 함수를 서로 다른 관측 범위로 호출한다**

```
GenerationPlanner.plan(from: Instant, to: Instant):
  → ConversationActivityQuery.findActiveDays(from, to)로 새 활동이 생긴 (사용자, 하루) 조회
  → journal.generation_jobs에 PENDING Job 멱등 insert (scheduled_at = 하루가 끝나는 시각,
    UNIQUE(user_id, diary_date) 충돌은 스킵)
```

"최근 활동 빠르게 반영"과 "장애로 놓친 것 회수"는 별개 시스템이 아니라, 같은 `plan()`을 다른 스케줄/범위로 호출하는 것뿐이다.

```
Normal      : 5분마다        plan(from = now - 10m, to = now)
Reconciliation : 하루 1회    plan(from = now - 3d,  to = now)
```

lookback(10분)은 스케줄 주기보다 넉넉히 겹치게 잡아 정상 경로 자체의 유실을 막고, reconciliation은 정상 경로가 그 lookback보다 긴 장애로 활동을 놓쳤을 때 조용히 사라지지 않도록 회수한다 — overlapping polling을 선택한 이유(장애 후 재조회로 스스로 복구)가 lookback 길이로 상한이 걸리지 않게 하기 위함이다. `from`/`to`를 인자로 받으므로 "9/21~24 누락분 재plan" 같은 수동 복구도 같은 함수로 처리된다.

**GenerationExecutor**

```
GenerationExecutor (주기적 실행)
  → generation_jobs 중 scheduledAt <= now()인 PENDING Job을 claim
  → ConversationActivityQuery.findMessages(userId, diaryDate)로 그 하루의 원본 조회
  → JournalGenerator 호출 → 서사 생성 → Journal 저장, Job을 COMPLETED로 표시
```

`generation_jobs`의 `UNIQUE(user_id, diary_date)`는 영구적인 도메인 제약이 아니라 **V1 최초 생성 Job에 대한 멱등성 키**다. 재생성(사용자 요청, 실패 재시도 등)을 지원하게 되면 Job이 "일기 하나"가 아니라 "생성 시도 하나"를 뜻하도록 바뀌어야 하므로, 그 시점에 키 구성이 달라질 수 있다.

**Observability**

`journal.planning.{days,jobs.created,jobs.duplicate,duration}` 메트릭을 `planningType`(RECENT | RECONCILIATION) 태그로 구분해 남긴다(현재는 같은 값을 로그로만 남기고, 메트릭은 미구현). Reconciliation 실행에서 `jobs.created > 0`이 나오면 그 자체가 "정상 경로가 최근 며칠간 일부를 놓쳤다"는 신호이므로, 태그를 붙여두면 나중에 이 값에 알림을 거는 것도 코드 변경 없이 가능하다.

**Accepted Risk**

하루 경계는 "현재 값 + 다음 하루부터 적용될 예약 값" 하나만 보관하고 시점별 이력(effective-dated)은 두지 않는다. 그래서 며칠 늦게 도착한 오프라인 메시지는 실제 발화 시점이 아니라 **도착(저장) 시점에 유효한 경계**로 `day_date`가 계산된다 — 그 사이 경계를 바꾼 경우에만 차이가 나는 좁은 경우라 감수한다. (이전 구조의 "Planning 시점 preference로 재계산되는" 리스크는 `day_date`를 저장 시점에 확정하면서 사라졌다.)

**Moment (보류)**

Moment는 원본 대화에서 뽑아낸 구조화된 "기억 조각"(시각, 타입, 감정)이다. 필요한 이유는 두 가지다.
1. **모델 비용 분업**: 원본은 저비용 모델(Gemini Flash)이 읽고, 비싼 모델의 입력 일부를 구조화된 Moment로 대체할 수 있는 경로를 제공한다.
2. **재사용**: 같은 Moment를 Insight(감정 패턴 분석 근거)와 아카이브(사용자가 직접 열람하는 "기억 조각")가 공유한다.

2026-10-08(#27) 구현을 제거했다. 호출부·구독자가 없는 선제 구현이었고, `conversation_day_id`(자정 기준)에 키가 묶여 있어 일기(diary day 기준)와 구간이 맞지 않았다. 하루 경계가 통일(#29)됐으므로 재도입 시 (user_id, day_date)를 기준으로 삼고, 실제로 처음 쓰는 기능(Stage 3 아카이브 드릴다운 또는 Stage 5 Insight)에서 키 구조와 호출 시점을 다시 설계한다. 그때도 지킬 원칙: Conversation이 소유·생성하고, 게이팅(구독 여부 등)은 호출하는 쪽의 책임이다.

**모듈 의존 방향 (단방향 엄수)**

```
identity        ← 독립 (다른 모듈에 의존하지 않음)
conversation    → shared-kernel(UserId)  [Identity API는 필요 시만]
journal         → conversation.publicapi (ConversationActivityQuery) — 자체 스케줄러로 호출, 이벤트 구독 없음
insight         → journal integration event, conversation의 Moment 조회 publicapi (Moment 재도입 시)
gamification    → journal integration event
notification    → 여러 모듈의 integration event
```

**Identity 의존 최소화**

대부분의 모듈은 인증된 UserId만 필요하다. JWT 검증 후 SecurityContext에서 UserId를 꺼내면 Identity API 호출이 필요 없다. JWT claim에는 자주 안 바뀌고 stale해도 피해가 작은 값(userId 등)만 포함한다. 구독 등급처럼 자주 바뀌고 stale하면 매출/신뢰 문제가 되는 값은 authorities든 plain claim이든 JWT에 넣지 않고, 사용 시점에 살아있는 소스(DB/캐시)에서 조회한다 (2026-07-25 결정, subscriptionTier를 JwtClaims에서 제거함).

Identity publicapi 호출이 필요한 경우만:
- 사용자 탈퇴 여부 확인
- 구독 상태 실시간 검증
- 동의 정보 조회

JWT claim 구조는 `platform/security`에서 정의한다.

**로그인 방식: Google ID Token 검증 (2026-07-25 결정)**

리다이렉트 기반 `oauth2Login()`(서버 세션 기반, 웹 전용) 대신 ID Token 검증 방식을 택함 — 클라이언트(웹/모바일)가 Google 로그인 SDK로 직접 로그인해 ID Token을 받고, `POST /api/auth/google`로 보내면 서버가 Google 공개키로 서명 검증 후 우리 JWT를 발급한다. 웹/Flutter 모바일에 동일하게 쓸 수 있고, 별도 필터체인/콜백 핸들러 없이 REST 엔드포인트 하나로 끝난다. Client Secret 불필요(Client ID만 필요). 구현: `identity/domain/GoogleIdTokenVerifierPort` + `identity/infrastructure/google/GoogleIdTokenVerifierAdapter`(`com.google.api-client` 사용).

순환 의존 금지: `conversation → journal`, `journal → conversation` 양방향 불가.
Journal 결과를 Conversation이 알아야 한다면 이벤트로 역방향 전달.

**다른 컨텍스트의 데이터가 필요할 때**
- ID 참조만 보관 (크로스 schema FK 없음)
- 원본 수정 권한 없음
- publicapi 또는 Integration Event 경유

---

## PostgreSQL Schema 분리

```
identity.users
identity.accounts

conversation.messages
conversation.day_preferences
conversation.moments          -- Moment 재도입 시

journal.journals
journal.journal_revisions
journal.generation_jobs

insight.emotion_observations
insight.patterns

gamification.point_ledger
gamification.streaks
```

하나의 DB 인스턴스라도 schema를 분리해 논리적 소유권을 강제한다.

---

## 필수 스키마 규칙 (Day 1)

### 1. 시간/날짜 — 모든 사용자 입력에

```
occurred_at  TIMESTAMPTZ  -- UTC 저장
timezone     TEXT         -- 'Asia/Seoul'
local_date   DATE         -- 사용자 현지 날짜(달력)
day_date     DATE         -- 사용자 하루 경계 기준 하루 (대화 메시지)
```

### 2. UserId — auth provider와 분리

```
-- identity.users
id          UUID PRIMARY KEY  -- 내부 식별자
google_id   TEXT              -- auth 연결은 별도 칼럼
```

### 3. AI 생성 결과 — 메타데이터 필수 (대화 응답 포함)

AI가 생성한 모든 결과에 적용한다. 대화 응답(message.role=assistant)도 포함.

```
-- 일기/Insight: 생성 Job 테이블에 기록 (현재 journal.generation_jobs)
-- 별도 통합 generation_log(호출 원장) 테이블은 아직 없음 — LLM 계층 재편 시 검토
generation_id    UUID
provider         TEXT        -- 'anthropic', 'google'
model            TEXT        -- 'claude-haiku-4-5'
prompt_version   TEXT
generated_at     TIMESTAMPTZ
attempt_count    INT
latency_ms       INT NULL
input_tokens     INT NULL
output_tokens    INT NULL
error_code       TEXT NULL

-- 대화 응답: messages 테이블에 컬럼 추가
generation_id    UUID NULL   -- AI 응답에만 값 있음
model            TEXT NULL
prompt_version   TEXT NULL
input_tokens     INT NULL    -- AI 응답에만 값 있음, quota 집계용
output_tokens    INT NULL    -- AI 응답에만 값 있음, quota 집계용
```

### 4. 삭제 정책 — 데이터 유형별 정의

Soft Delete를 모든 것에 일괄 적용하지 않는다.

| 유형                    | 정책                                                  |
|-------------------------|-------------------------------------------------------|
| 일반 UI 삭제            | Soft Delete (deleted_at), 복구 창 내 복원 가능        |
| 계정 탈퇴 / 영구 삭제   | 유예 기간(예: 30일) 후 Hard Delete 또는 비가역 익명화 |
| PointLedger / 결제 기록 | 법적·회계 보존 정책에 따라 별도 처리                  |

```
deleted_at       TIMESTAMPTZ NULL   -- Soft Delete
purge_after      TIMESTAMPTZ NULL   -- 이 시각 이후 물리 삭제 예정
```

---

## AI 추상화 인터페이스

provider 축(Gemini/Claude)이 이미 정해져 있어 `platform/llm/conversation`, `platform/llm/journal`로 서브패키지 분리(2026-07-25). `platform/llm/moment`는 2026-09-22 추가됐다가 #27에서 Moment와 함께 제거. `suspend` 아님 — 실제 구현 시 필요해지면 그때 추가.

두 서브패키지 모두 `RestClient` + Resilience4j `@CircuitBreaker` 구조로 구현되어 있다: `platform/llm/conversation`은 `GeminiConversationResponder`(Stage 1, #17), `platform/llm/journal`은 `GeminiJournalGenerator`/`ClaudeJournalGenerator` 둘 다 구현돼 있고 `moeum.journal.provider`(기본값 `gemini`)로 선택한다(Claude는 결제 설정 후 전환할 임시 대기 상태). 상세는 `DEVELOPMENT_STAGES.md` 참고.

```kotlin
// platform/llm/conversation
interface ConversationResponder {
    fun respond(request: ConversationRequest): ConversationResponse
}
// ConversationRequest(messages: List<LlmMessage>) — 시스템 지시는 구현체가 소유
// ConversationResponse(generationId, content, model, provider, promptVersion, inputTokens, outputTokens)

// platform/llm/journal
interface JournalGenerator {
    fun generate(request: JournalGenerationRequest): JournalGenerationResponse
}
// JournalGenerationRequest(rawTranscript: String, localDate: String) — Moment는 입력에 없다
// rawTranscript는 ConversationActivityQuery.findMessages(userId, diaryDate)로 조회한 원본 그대로
// JournalGenerationResponse(generationId, title, content, model, provider, promptVersion, inputTokens, outputTokens)

// InsightGenerator — Stage 5에서 정의 (아직 없음)
```

모델명과 공급자는 도메인 코드에 직접 등장하지 않는다.

---

## 이벤트 계약

이벤트는 이미 발생한 사실이다. 명령이 아니다.

### Domain Event (모듈 내부)

Aggregate가 발생시키는 이벤트. 타입 안전. 모듈 외부로 직접 노출하지 않는다.

```kotlin
sealed class JournalDomainEvent

data class JournalConfirmed(
    val journalId: JournalId,
    val userId: UserId,
    val revision: Long,
    val confirmedAt: Instant
) : JournalDomainEvent()
```

이벤트 공통 베이스(eventId, occurredAt 등)는 아직 정의하지 않는다. Stage 0에 만들어 둔 shared-kernel의 `DomainEvent`/`EventEnvelope`는 사용처 없이 남아 있어 #27에서 제거했다. 첫 실제 이벤트(JournalConfirmed)나 Stage 4 Outbox를 구현할 때, 그 요구(직렬화 형태, 시각·ID 생성을 TimeProvider/UUIDv7로 주입)에 맞춰 정의한다.

### Integration Event (모듈 간 공개 계약)

다른 모듈 또는 향후 다른 서비스에 공개하는 계약. 명시적 버전 관리.

```kotlin
data class JournalConfirmedV1(
    val eventId: UUID,
    val eventType: String = "JournalConfirmed",
    val eventVersion: Int = 1,
    val occurredAt: Instant,
    val correlationId: UUID,
    val causationId: UUID?,
    val journalId: UUID,
    val userId: UUID,
    val revision: Long,
    val localDate: LocalDate
)
```

`Map<String, Any>`는 Outbox 직렬화 결과에만 사용. 애플리케이션 코드에서는 타입 있는 이벤트를 사용한다.

**계약 변경 원칙**
- 기존 필드 삭제 금지
- 새 필드는 optional로 추가
- 파괴적 변경은 새 버전 (V2)으로

**핵심 이벤트 목록**

```
MomentsPrepared             — Moment 추출 완료 (Moment 재도입 시)
JournalGenerationRequested
JournalGenerated
JournalConfirmed
InsightGenerated
RewardGranted
UserDataDeletionRequested
```

**Integration Event 소유 위치**

Integration Event는 생산자 모듈이 소유한다. shared-kernel에 업무 이벤트를 두지 않는다.

```
journal/application/publicapi/events/JournalConfirmedV1.kt
```

---

## 장애 허용 범위

| 허용 가능         | 허용 불가               |
|-------------------|-------------------------|
| AI 반응 지연      | 사용자 기록 유실        |
| 일기 생성 지연    | 다른 사용자 데이터 노출 |
| 알림 실패         | 중복 보상 지급          |
| Insight 생성 실패 | 삭제한 데이터 재노출    |

LLM 장애가 메시지 저장에 영향을 주지 않아야 한다.

사용자 메시지 저장이 먼저 성공해야 한다.
AI 응답 생성은 메시지 저장 트랜잭션과 분리하며,
실패해도 사용자 메시지 저장을 롤백하지 않는다.

---

## 핵심 관측 식별자

```
traceId       — HTTP 요청 단위 (아직 미도입 — 별도 필터/MDC 설정 필요)
correlationId — 전체 업무 흐름. 이벤트 생성 시 기본값으로 새로 채우지 않는다 — 호출부가
                자기 작업 단위(배치 실행, 요청 등)를 식별하는 값을 그대로 넘겨야 실제로
                연결된다. 기본값을 두면 매번 새 값이 생겨 아무것도 추적할 수 없다.
causationId   — 이전 이벤트 ID. 이벤트가 아니라 직접 호출로 트리거된 흐름(예: 사용자 요청으로 시작된 생성)은
                이전 이벤트가 없으므로 null이 맞다
eventId       — 현재 이벤트. 이건 매번 새로 생성하는 게 맞다(이 이벤트 자신의 식별자)
userId        — 사용자
journalId     — 일기
generationId  — AI 생성 단위
promptVersion — 프롬프트 버전
```
