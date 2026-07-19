# 모음 (Moeum) — 아키텍처 결정 문서

> 최종 업데이트: 2026-07-19 (v2)

---

## 서비스 한 줄 정의

AI와 일상 대화를 통해 자연스럽게 기록하고, 하루가 끝날 때 일기로 정리해 아카이브하는 플랫폼.
경쟁력은 가장 똑똑한 AI가 아니라 **사용자의 삶을 가장 잘 기록하고 연결하는 경험**에 있다.

---

## 핵심 데이터 철학

```
사용자 메시지 (Message, role=user)  = 원본 기록, 절대 불변, 가장 중요한 자산
AI 대화 응답 (Message, role=assistant) = 사용자 경험상 대화이지만 기술적으로는 AI 파생 결과
Moment                               = Conversation이 소유하는 핵심 도메인 데이터
AI 생성 결과 (Journal, Insight)      = 언제든 재생성 가능한 파생 데이터
```

AI가 틀리거나 모델이 교체되어도 원본 기록은 반드시 보존된다.
AI 대화 응답도 생성 메타데이터(model, generation_id)를 가져야 디버깅과 모델 교체가 가능하다.

---

## 기술 스택

| 영역 | 선택 | 이유 |
|------|------|------|
| 백엔드 | Kotlin + Spring Boot + Java 21 | 도메인 표현력, 트랜잭션, JPA, 스케줄러 통합 |
| 빌드 | Gradle Kotlin DSL | |
| 웹 | Next.js | 아카이브 UI + 얇은 BFF |
| 모바일 | Flutter (또는 RN+Expo) | 팀 숙련도에 따라 결정 |
| DB | PostgreSQL | 단일 클러스터로 시작 |
| 캐시 | Redis | 세션·Rate Limit (초기에는 생략 가능) |
| 파일 | Object Storage | 이미지·음성 |

### AI 모델 라우팅

| 용도 | 모델 | 이유 |
|------|------|------|
| 일상 대화 반응 | Gemini 2.5 Flash | 빈도 높음, 무료 티어로 개발 가능 |
| Moment 추출 (배치) | Gemini 2.5 Flash | 구조화 출력, 저비용 |
| 일기 생성 초안 | Claude Haiku 4.5 | 하루 1회, 한국어 품질 |
| Insight 생성 | Claude Sonnet 4.6 | 주·월 1회, 품질 우선 |

**비용 제어 원칙**
- 대화 반응: 최근 8~10개 메시지만 컨텍스트로 사용
- 응답 토큰 상한: 일상 반응 150 토큰
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
│  ├─ conversation/     # 메시지, ConversationDay, Moment
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
└─ shared-kernel/       # UserId, Money, DomainEvent, TimeProvider
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
interface ConversationJournalSourceQuery {
    fun getJournalSource(conversationDayId: ConversationDayId): JournalSourceSnapshot
}
// 나중에 HTTP 클라이언트로 구현을 교체해도 호출부 코드는 변경 없음
```

---

## Bounded Context & 데이터 소유권

각 데이터는 **하나의 컨텍스트만 원본 소유자**다.

| 컨텍스트 | 소유 데이터 |
|----------|------------|
| Identity | User, Account, Device, Consent, Subscription |
| Conversation | Message, ConversationDay, Moment, Attachment |
| Journal | Journal, JournalRevision, JournalSection, JournalTag |
| Insight | EmotionObservation, DailyInsight, WeeklyInsight, Pattern |
| Gamification | Mission, Streak, Achievement, PointLedger, Reward |
| Notification | NotificationLog, NotificationTemplate |

**Moment 생성 책임**
Moment는 Conversation 컨텍스트가 소유하고 생성한다.
Journal 모듈은 Moment를 생성하거나 conversation 테이블에 저장하지 않는다.

Moment 추출은 LLM 배치 호출이므로 하루 마감 API와 분리한다. 마감 API가 LLM 속도에 묶이면 안 된다.

```
ConversationDay 마감 (즉시 반환)
  → ConversationDayClosed 발행
  → Conversation이 Moment 추출 Job 시작 (비동기)
  → Moment 추출 완료
  → MomentsPrepared 발행 (Moment 스냅샷 포함)
  → Journal이 MomentsPrepared를 구독해 일기 생성
```

MomentsPrepared 페이로드에 Moment 스냅샷을 포함해 Journal이 Conversation API를 재호출하지 않도록 한다. 서비스 분리 시에도 Journal이 Conversation DB를 볼 필요가 없다.

**모듈 의존 방향 (단방향 엄수)**

```
identity        ← 독립 (다른 모듈에 의존하지 않음)
conversation    → shared-kernel(UserId)  [Identity API는 필요 시만]
journal         → (MomentsPrepared 이벤트 기반이면 conversation 직접 의존 불필요)
insight         → journal integration event
gamification    → journal integration event
notification    → 여러 모듈의 integration event
```

**Identity 의존 최소화**

대부분의 모듈은 인증된 UserId만 필요하다. JWT 검증 후 SecurityContext에서 UserId를 꺼내면 Identity API 호출이 필요 없다. JWT claim에 userId, subscriptionTier 등 자주 쓰는 컨텍스트를 포함해 모듈 간 컴파일 의존을 줄인다.

Identity publicapi 호출이 필요한 경우만:
- 사용자 탈퇴 여부 확인
- 구독 상태 실시간 검증
- 동의 정보 조회

JWT claim 구조는 `platform/security`에서 정의한다.

순환 의존 금지: `conversation → journal`, `journal → conversation` 양방향 불가.
Journal 결과를 Conversation이 알아야 한다면 이벤트로 역방향 전달.

**다른 컨텍스트의 데이터가 필요할 때**
- ID 참조만 보관 (크로스 schema FK 없음)
- 원본 수정 권한 없음
- publicapi 또는 Integration Event 경유

---

## PostgreSQL Schema 분리

```sql
identity.users
identity.accounts

conversation.messages
conversation.conversation_days
conversation.moments

journal.journals
journal.journal_revisions

insight.emotion_observations
insight.patterns

gamification.point_ledger
gamification.streaks
```

하나의 DB 인스턴스라도 schema를 분리해 논리적 소유권을 강제한다.

---

## 필수 스키마 규칙 (Day 1)

### 1. 시간/날짜 — 모든 사용자 입력에

```sql
occurred_at  TIMESTAMPTZ  -- UTC 저장
timezone     TEXT         -- 'Asia/Seoul'
local_date   DATE         -- 사용자 현지 날짜
```

### 2. UserId — auth provider와 분리

```sql
-- identity.users
id          UUID PRIMARY KEY  -- 내부 식별자
google_id   TEXT              -- auth 연결은 별도 칼럼
```

### 3. AI 생성 결과 — 메타데이터 필수 (대화 응답 포함)

AI가 생성한 모든 결과에 적용한다. 대화 응답(message.role=assistant)도 포함.

```sql
-- 일기/Insight: 별도 generation_log 테이블
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
```

### 4. 삭제 정책 — 데이터 유형별 정의

Soft Delete를 모든 것에 일괄 적용하지 않는다.

| 유형 | 정책 |
|------|------|
| 일반 UI 삭제 | Soft Delete (deleted_at), 복구 창 내 복원 가능 |
| 계정 탈퇴 / 영구 삭제 | 유예 기간(예: 30일) 후 Hard Delete 또는 비가역 익명화 |
| PointLedger / 결제 기록 | 법적·회계 보존 정책에 따라 별도 처리 |

```sql
deleted_at       TIMESTAMPTZ NULL   -- Soft Delete
purge_after      TIMESTAMPTZ NULL   -- 이 시각 이후 물리 삭제 예정
```

---

## AI 추상화 인터페이스

```kotlin
// platform/llm
interface ConversationResponder {
    suspend fun respond(context: ConversationContext): ConversationResponse
}

interface JournalGenerator {
    suspend fun generate(day: ConversationDaySummary): JournalDraft
}

interface InsightGenerator {
    suspend fun analyze(period: AnalysisPeriod): InsightReport
}
```

모델명과 공급자는 도메인 코드에 직접 등장하지 않는다.

---

## 이벤트 계약

이벤트는 이미 발생한 사실이다. 명령이 아니다.

### Domain Event (모듈 내부)

Aggregate가 발생시키는 이벤트. 타입 안전. 모듈 외부로 직접 노출하지 않는다.

```kotlin
sealed interface JournalDomainEvent

data class JournalConfirmed(
    val journalId: JournalId,
    val userId: UserId,
    val revision: Long,
    val confirmedAt: Instant
) : JournalDomainEvent
```

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
ConversationDayClosed       — 하루가 마감됐다는 사실 (빠른 반환, Moment 미포함)
MomentsPrepared             — Moment 추출 완료, 스냅샷 페이로드 포함 (Journal 구독 대상)
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
conversation/application/publicapi/events/MomentsPreparedV1.kt
```

계약 파일 (JSON Schema)은 `contracts/events/`에 별도 보관한다.

**계약 파일 위치**
```
contracts/events/
├─ journal-confirmed-v1.json
├─ conversation-day-closed-v1.json
└─ ...
```

---

## 장애 허용 범위

| 허용 가능 | 허용 불가 |
|----------|----------|
| AI 반응 지연 | 사용자 기록 유실 |
| 일기 생성 지연 | 다른 사용자 데이터 노출 |
| 알림 실패 | 중복 보상 지급 |
| Insight 생성 실패 | 삭제한 데이터 재노출 |

LLM 장애가 메시지 저장에 영향을 주지 않아야 한다.

사용자 메시지 저장이 먼저 성공해야 한다.
AI 응답 생성은 메시지 저장 트랜잭션과 분리하며,
실패해도 사용자 메시지 저장을 롤백하지 않는다.

---

## 핵심 관측 식별자

```
traceId       — HTTP 요청 단위
correlationId — 전체 업무 흐름
causationId   — 이전 이벤트 ID
eventId       — 현재 이벤트
userId        — 사용자
journalId     — 일기
generationId  — AI 생성 단위
promptVersion — 프롬프트 버전
```
