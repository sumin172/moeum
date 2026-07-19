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
- `conversation` — 메시지, ConversationDay, Moment
- `journal` — 일기 생성, 확정
- `platform` — LLM 추상화, 보안, 관측가능성, 공통 인프라
- `shared-kernel` — UserId, Money, DomainEvent, TimeProvider

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
- Domain Event / Integration Event 타입 기반 구조 정의
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
- 사용자 가입 / OAuth 로그인
- UserId 생성 (내부 UUID, auth ID와 분리)
- JWT 발급

Conversation
- 메시지 수신 및 저장 (occurred_at, timezone, local_date 포함)
- ConversationDay 자동 생성 및 관리
- AI 반응 호출 (Gemini 2.5 Flash, 최근 8개 메시지 컨텍스트)
- AI 응답 저장
- 오늘 대화 조회 API

**스키마 핵심**
```sql
conversation.messages
  id                UUID PK
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
  deleted_at        TIMESTAMPTZ NULL

  UNIQUE (user_id, client_message_id)  -- 중복 메시지 방지

conversation.conversation_days
  id              UUID PK
  user_id         UUID
  local_date      DATE
  timezone        TEXT          -- 최초 생성 시 고정
  status          TEXT          -- OPEN | CLOSED
  source_revision BIGINT DEFAULT 0  -- 메시지 추가·마감 시 증가
  version         BIGINT        -- @Version 낙관적 락
  opened_at       TIMESTAMPTZ
  closed_at       TIMESTAMPTZ NULL

  UNIQUE (user_id, local_date)  -- 하루에 하나
```

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
- Moment 추출은 ConversationDay 마감 시 Conversation 모듈이 담당
- 지금은 메시지 원본 저장이 전부

**이 단계 완료 기준**
- 실제 대화 흐름이 된다
- AI 반응 실패 시 메시지는 저장된 상태를 유지한다
- 메시지에 local_date + timezone이 정상 저장된다
- 동일 clientMessageId 재전송 시 중복 저장되지 않는다

---

## Stage 2: 일기 생성

> 목표: 하루 대화를 일기로 변환하고 확정하는 흐름을 완성한다.

**할 것**

Conversation
- ConversationDay 마감 트리거 (수동 or 스케줄러, 즉시 반환)
- ConversationDayClosed 발행 (source_revision 포함)
- 비동기로 Moment 추출 Job 시작 (LLM 배치 호출)
- Moment 추출 완료 → MomentsPrepared 발행 (Moment 스냅샷 + source_revision 포함)

Journal
- **MomentsPrepared 구독** → 일기 생성 Job 생성 (ConversationDayClosed 직접 구독 안 함)
- 페이로드의 Moment 스냅샷 사용 (Conversation API 재호출 없음)
- Claude Haiku로 일기 초안 생성
- 구조화 출력 (JSON Schema 검증)
- AI 생성 메타데이터 저장
- 일기 확정

**상태 분리 — Journal 생명주기 ≠ 생성 Job 상태**

```sql
journal.journals
  lifecycle_status TEXT  -- DRAFT | CONFIRMED | OUTDATED
  -- OUTDATED: 확정 후 원본 대화가 추가된 경우

journal.generation_jobs
  generation_status TEXT  -- PENDING | PROCESSING | COMPLETED | FAILED
  request_key TEXT UNIQUE -- conversationDayId + type + promptVersion
  attempt_count INT
```

하나의 status 컬럼에 합치지 않는다.
AI가 PROCESSING 중에도 Journal은 DRAFT 상태를 유지할 수 있다.

**스키마 핵심**
```sql
journal.journals
  id                    UUID PK
  user_id               UUID
  conversation_day_id   UUID        -- FK 없음, ID 참조만
  local_date            DATE
  lifecycle_status      TEXT        -- DRAFT | CONFIRMED | OUTDATED
  title                 TEXT
  content               JSONB
  current_revision      INT DEFAULT 1
  version               BIGINT      -- @Version 낙관적 락
  confirmed_at          TIMESTAMPTZ NULL
  deleted_at            TIMESTAMPTZ NULL
  purge_after           TIMESTAMPTZ NULL

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

journal.generation_jobs
  id                  UUID PK
  journal_id          UUID NULL
  conversation_day_id UUID
  source_revision     BIGINT        -- 어떤 ConversationDay 버전 기준으로 생성했는지
  request_key         TEXT UNIQUE   -- 멱등키: {conversationDayId}:{sourceRevision}:{type}:{promptVersion}
  generation_status   TEXT          -- PENDING | PROCESSING | COMPLETED | FAILED
  attempt_count       INT DEFAULT 0
  provider            TEXT NULL
  model               TEXT NULL
  prompt_version      TEXT NULL
  generation_id       UUID NULL
  generated_at        TIMESTAMPTZ NULL
  error_code          TEXT NULL
  created_at          TIMESTAMPTZ

conversation.moment_sets
  id                  UUID PK
  conversation_day_id UUID
  source_revision     BIGINT        -- 어떤 ConversationDay 버전 기준으로 추출했는지
  generation_id       UUID
  model               TEXT
  prompt_version      TEXT
  is_current          BOOLEAN DEFAULT true
  superseded_at       TIMESTAMPTZ NULL
  created_at          TIMESTAMPTZ

conversation.moments
  id              UUID PK
  moment_set_id   UUID              -- MomentSet FK
  conversation_day_id UUID
  type            TEXT              -- 'MEAL', 'WORK', 'EXERCISE', ...
  summary         TEXT
  emotion         TEXT NULL
  confidence      FLOAT NULL
  occurred_at     TIMESTAMPTZ NULL
  deleted_at      TIMESTAMPTZ NULL
```

**request_key 구성**
마감 후 메시지가 추가되면 source_revision이 증가하므로 같은 날짜라도 새 Job이 생성된다.
동일 조건 재시도 → 기존 키로 멱등 처리. 내용 변경 재생성 → 새 키로 새 Job.

**ConversationDay 마감 후 메시지 추가 정책 (Option 3 채택)**
- CLOSED 상태에서도 메시지 추가 허용 (원본 기록 우선)
- Journal이 CONFIRMED 상태였다면 OUTDATED로 변경
- 사용자에게 재생성 여부 제공
- 재생성 요청은 generation_jobs의 request_key로 중복 방지

**미확정 일기 정책 (결정 필요)**
- 선택지: N일 후 자동 확정 or 영구 DRAFT 유지
- Insight 집계에 DRAFT 포함 여부

**@TransactionalEventListener + @Async 구현 주의사항**

Stage 2 기본: `@TransactionalEventListener(AFTER_COMMIT) + @Async`로 in-process 처리.
마감 트랜잭션 커밋 후 별도 스레드에서 Moment 추출이 시작되므로, 마감 API가 LLM을 기다리지 않는다.

```kotlin
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
fun handle(event: ConversationDayClosedV1) {
    runCatching {
        momentExtractionProcessor.process(event)
    }.onFailure { e ->
        // markFailed 자체가 실패해도 로그는 남아야 한다
        runCatching { momentJobFailureHandler.markFailed(event, e) }
            .onFailure { log.error("Moment extraction failure handler failed", it) }
    }
}
```

**리스너 내 트랜잭션 구조 (핵심)**

AFTER_COMMIT 시점에는 원래 트랜잭션이 이미 닫혔다. DB 저장 시 `REQUIRES_NEW`로 새 트랜잭션 명시적 시작.
LLM 호출 동안 DB 커넥션을 붙잡지 않는 것이 핵심이다.

```
새 트랜잭션 → MomentExtractionJob PENDING 저장 → 커밋
트랜잭션 밖 → LLM 호출
새 트랜잭션 → MomentSet 저장 → Job COMPLETED → MomentsPrepared 발행 → 커밋
실패 시     → 새 트랜잭션 → Job FAILED, errorCode, attemptCount 기록 → 커밋
```

**유실 가능성 인지**

in-process 이벤트는 신뢰성 있는 메시지 큐가 아니다.
서버 종료 타이밍에 따라 ConversationDay가 CLOSED됐지만 Moment 추출이 시작되지 않을 수 있다.

최소 복구 통로로 다음을 제공한다:
- `MomentExtractionJob`에 FAILED/PENDING 상태 기록
- MomentSet이 없는 CLOSED ConversationDay 조회 API 또는 스케줄러
- 수동 재처리 트리거

유실이 제품상 허용되지 않는 시점 → Outbox 도입.

**Outbox 조기 도입 기준**
ConversationDayClosed 유실로 일기가 생성되지 않고 사용자가 인지 못하는 상황이 실제로 문제라면 Stage 2에서 조기 도입한다.

**이 단계 완료 기준**
- 대화 → Moment 추출(Conversation) → 일기 생성 → 수정 → 확정 흐름이 작동한다
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

```sql
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
- 구독 플랜 / 결제
- 사용량 제한 (Rate Limit)
- 데이터 내보내기 (전체 기록 ZIP)
- 계정 탈퇴 + 파생 데이터 연쇄 삭제
- 관리자 도구
- 모델 라우팅 최적화 (비용 vs 품질)
- AI 학습 사용 동의 처리

---

## 단계별 인프라 도입 계획

| 인프라 | 기본 계획 | 조기 도입 조건 |
|--------|----------|--------------|
| PostgreSQL | Stage 0 | — |
| Redis | Stage 1 이후 | 세션 필요 시 (초기엔 DB 세션) |
| Outbox 패턴 | Stage 4 | 이벤트 유실이 업무 손실로 이어지는 시점 |
| Read Model | Stage 5 | N+1 실측 시 |
| Kafka | MSA 분리 시 | Outbox transport 교체 |

---

## 변경하면 나중에 크게 아픈 것 (절대 초기에 잡기)

1. 메시지 스키마 — occurred_at + timezone + local_date + client_message_id
2. UserId — 내부 UUID, auth ID와 분리
3. 원본/파생 테이블 분리 — conversation vs journal
4. AI 생성 메타데이터 — 모든 AI 결과에 (대화 응답 포함)
5. 데이터 유형별 삭제·보존 정책 — deleted_at + purge_after + Hard Delete 기준 정의
6. PostgreSQL schema 분리 — 모듈별
7. ConversationDay.source_revision — OUTDATED 판단 기준
8. MomentSet 구조 — source_revision 기반 버전 관리

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

| 항목 | 결정 시점 |
|------|----------|
| 미확정 일기 자동 확정 여부 (N일 후 vs 영구 DRAFT) | 사용자 행동 패턴 관찰 후 |
| Insight 집계에 DRAFT 일기 포함 여부 | Insight 기능 구현 시 |
| AI 응답 상태를 Message 컬럼으로 유지 vs 별도 Job 테이블 | retry 복잡도 증가 시 |
| Outbox를 Stage 2에 조기 도입할지 | 이벤트 유실 허용 여부 판단 시 |
| Moment confidence를 제품 UI에 노출할지 | UX 설계 시 |
| Redis 도입 여부 | 세션/캐시 실제 필요 발생 시 |
| 검색을 LIKE → pg_trgm → FTS 중 어디까지 발전시킬지 | 검색 품질 불만 발생 시 |
