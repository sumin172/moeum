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
- 비용 완화는 컨텍스트를 깎는 방식이 아니라 프롬프트/컨텍스트 캐싱(Gemini/Claude 공통 지원)으로 해결한다 — 반복 재전송되는 앞부분 처리 비용을 낮추는 용도.
- 무료 티어라도 대화 컨텍스트 품질(핵심 기록 경험)은 깎지 않는다 — 비용 통제는 quota(위)와 캐싱으로, 수익화는 Stage 6에서 Insight 같은 고비용 기능 게이팅으로 한다.

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
  timezone        TEXT          -- 메시지 저장마다 최신 관측 zone으로 갱신 (2026-08-02 결정)
                                 -- local_date와 달리 living 값. 마감 배치가 "이 zone 기준 자정 지났는지" 판단하는 근거.
                                 -- 갱신은 local_date를 건드리지 않으므로 정체성 충돌 위험 없음.
                                 -- 한 메시지의 zone 변화로 local_date 자체가 달라지면(실제 자정 넘김) 새 ConversationDay가
                                 -- 열리는 것을 정상으로 받아들임(하루가 여러 row로 쪼개지는 fragmentation은 accepted trade-off)
  status          TEXT          -- OPEN | CLOSED
  source_revision BIGINT DEFAULT 0  -- 메시지 추가·마감 시 증가
  version         BIGINT        -- @Version 낙관적 락
  opened_at       TIMESTAMPTZ
  closed_at       TIMESTAMPTZ NULL

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
- Moment 추출은 ConversationDay 마감 시 Conversation 모듈이 담당
- 지금은 메시지 원본 저장이 전부

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
- ConversationDay 마감 트리거 — 스케줄 배치 채택 (2026-08-02 결정)
  - 후보 비교: 배치(주기적 스캔) / 조회 시 lazy 처리 / 쓰기 경로에 끼워넣기 → 배치 채택
  - 이유: 유저가 이탈해도(다시 접속 안 해도) 열린 day가 언젠가 반드시 마감되는 걸 보장하는 유일한 방식이고, 기존 command/query 경로에 부작용을 안 얹어 장애 격리가 됨
  - 판단 기준은 유저 단위의 "마지막 zone"이 아니라 **각 ConversationDay row 자신의 timezone** — day마다 독립적으로 자정 여부 판단 (동시에 여러 day가 열려 있어도 서로 안 기다림)
  - 매 배치 스캔마다 zone 계산을 반복하지 않도록, day의 timezone이 갱신될 때 `closesAt`(그 zone 기준 자정에 해당하는 UTC Instant)을 같이 계산해 저장하는 안 검토 — 배치 쿼리를 `WHERE status='OPEN' AND closes_at <= now()` 인덱스 스캔으로 단순화
  - 마감은 원자적 조건부 UPDATE(`WHERE id=? AND status='OPEN'`)로 먼저 확정한 뒤 메시지를 읽어 일기를 생성 — 마감 순간 도착하는 메시지와의 레이스 방지
  - 배치 자체는 멱등(이미 CLOSED인 row는 조건에 안 걸림) — 중간에 죽고 재실행돼도 안전
- ConversationDayClosed 발행 (source_revision 포함) — ⚠️ 2026-09-21 기준 develop 브랜치의 실제 `ConversationDayClosedV1`엔 `source_revision` 필드가 누락돼 있음. 아래 "Moment 추출·전달 시 데이터 전송 전략" 반영 시 반드시 같이 추가할 것.
- Moment 추출 Job은 이벤트에 실린 데이터가 아니라 `source_revision` 고정 조회로 원본 메시지를 직접 읽어 처리 (같은 모듈 내부라 라이브 조회에 문제 없음, 상세는 아래 섹션)
- Moment 추출 완료 → 원본 스냅샷을 Object Storage에 저장 → MomentsPrepared 발행 (Moment 스냅샷은 직접 포함 + 원본은 Object Storage 참조 키만 포함, source_revision 포함)

Journal
- **MomentsPrepared 구독** → 일기 생성 Job 생성 (ConversationDayClosed 직접 구독 안 함)
- 페이로드의 Moment 스냅샷(구조화 사실) + 원본 대화(Object Storage 참조로 읽음)를 함께 사용 — Moment만으로 생성하면 "압축 위에 서사화"가 되어 퀄리티가 떨어지므로 원본에서 직접 서사화한다 (Conversation API 실시간 재호출은 아님, 이벤트가 가리키는 스냅샷을 읽을 뿐)
- Claude Haiku로 일기 초안 생성
- 구조화 출력 (JSON Schema 검증)
- AI 생성 메타데이터 저장
- 일기 확정

**상태 분리 — Journal 생명주기 ≠ 생성 Job 상태**

```
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
```
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
  input_tokens        INT NULL    -- quota 집계용
  output_tokens       INT NULL    -- quota 집계용
  generation_id       UUID NULL
  generated_at        TIMESTAMPTZ NULL
  error_code          TEXT NULL
  created_at          TIMESTAMPTZ

conversation.moment_sets
  id                       UUID PK
  conversation_day_id      UUID
  source_revision          BIGINT        -- 어떤 ConversationDay 버전 기준으로 추출했는지
  generation_id            UUID
  model                    TEXT
  prompt_version           TEXT
  raw_snapshot_key         TEXT NULL     -- 원본 대화 스냅샷의 Object Storage 참조 키 (Journal 생성용)
  raw_snapshot_purge_after TIMESTAMPTZ NULL  -- 스냅샷 보존 기한 (캐시성 데이터, 원본은 conversation.messages에 영구 보존)
  is_current               BOOLEAN DEFAULT true
  superseded_at            TIMESTAMPTZ NULL
  created_at               TIMESTAMPTZ

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

**Moment 추출·전달 시 데이터 전송 전략 (2026-09-21 결정)**

Moment 관련 데이터는 두 홉으로 나뉘고, 각 홉의 성격이 달라 전송 방식도 다르게 가져간다.

1. **마감 → Moment 추출** (Conversation 모듈 내부, 앞으로도 절대 물리적으로 갈라지지 않을 관계)
   - `ConversationDayClosedV1`은 트리거 역할만 한다: `conversationDayId`, `userId`, `localDate`, `sourceRevision`만 포함
   - 실제 메시지는 Moment 추출 워커가 `source_revision` 고정 조회로 자기 소유 테이블(`conversation.messages`)에서 직접 읽는다 — 같은 모듈 내부라 "다른 모듈 DB 조회" 문제가 아니다
   - 데이터가 아무리 많아져도 이 이벤트를 무겁게 만들 필요가 없다. 오히려 무겁게 만들면 (인프로세스든 향후 Outbox/큐든) 메모리·저장 비용만 늘어난다

2. **Moment 추출 → Journal 생성** (서로 다른 모듈 경계, 미래 물리 분리 후보)
   - `MomentsPreparedV1`엔 Moment 스냅샷(구조화된 사실 목록)을 **직접 포함** — 구조화돼 있어 크기 상한이 있다
   - 원본 대화 전체(raw transcript)는 **이벤트에 직접 넣지 않는다.** 대화량에 비례해 무제한으로 커질 수 있기 때문. 대신 Object Storage에 저장하고 참조 키(`raw_snapshot_key`)만 이벤트/DB에 담는다 (Claim-Check 패턴)
   - 판단 기준은 "원본이냐 가공이냐"가 아니라 **"크기가 원천적으로 제한돼 있는가, 무제한으로 커질 수 있는가"**다. 같은 이유로 잘 압축된 서사 요약본이라도 크기가 작다면 이벤트에 직접 넣어도 무방하다 — 다만 이 파이프라인엔 그런 중간 요약 단계 자체가 없다(아래 참고)
   - 이 원칙은 지금의 인프로세스 이벤트뿐 아니라 향후 Kafka/SQS 도입 시에도 동일하게 적용된다. 오히려 실제 브로커는 메시지 크기 제한(SQS 256KB, Kafka 기본 1MB)이 있어 더 엄격히 지켜야 한다

**Journal이 Moment 스냅샷만으로 생성하지 않는 이유**

Moment 추출(구조화 압축)과 Journal 생성(서사화)을 둘 다 Moment 위에서만 하면 "압축 위에 압축"이 되어 원문 뉘앙스가 누적 손실된다. narrative화는 Journal 생성 단계에서 원본으로부터 **딱 한 번만** 일어나야 하므로, 별도의 "서사 요약본" 중간 산출물을 만들지 않는다 — Journal은 Moment(사실 골격) + 원본(뉘앙스)을 함께 참고해 서사를 짠다.

**Moment 추출을 하는 진짜 이유 (토큰 절약이 아니다)**

Moment 추출도 결국 그날 원본 전체를 한 번은 읽어야 하므로, Journal 생성 하나만 놓고 보면 총 토큰량은 줄지 않는다(호출이 2번이라 오히려 늘 수도 있다). 실제 이유:
1. **모델 비용 분업** — 큰 원본은 저비용 모델(Gemini)이 읽고, 비싼 모델(Claude Haiku)의 입력 일부를 구조화된 Moment로 대체할 수 있는 경로를 만든다
2. **재사용** — 같은 Moment를 Journal뿐 아니라 Insight(Stage 5, 감정 패턴 분석 근거)와 아카이브(Stage 3, 사용자가 직접 열람하는 "기억 조각")가 공유한다

**원본 스냅샷(Object Storage) 보존 정책 (2026-09-22 확정)**
- 저장 위치: Object Storage. DB 컬럼에 큰 blob을 쌓지 않는다 — 백업/복제/vacuum 부담 회피
  - 로컬/운영(안정화 전) 모두 **Garage**(Docker, EC2 자체 운영)로 시작하고, 안정화 후 AWS S3로 전환한다. S3 호환 API라 전환 시 코드 변경 없이 설정(엔드포인트/자격증명)만 바꾸면 된다
  - 2026-09-22엔 MinIO를 전제했으나, MinIO 커뮤니티 에디션이 2026-04-25부로 GitHub 저장소 archived, 신규 이미지 배포 중단된 것을 확인해 채택 철회(2026-09-28). RustFS도 검토했으나 운영 최소 요구 메모리가 공식 문서 기준 128GB로 소규모 서버에 부적합해 제외. Garage는 Rust 단일 바이너리, 외부 의존성 없음, 최소 RAM 1GB로 가장 가벼움
  - Garage 단일 노드 자체 운영은 내구성이 없다(복제 없음)는 리스크가 있지만, 이 스냅샷은 `conversation.messages`에서 언제든 동일하게 재구성 가능한 **캐시**라 유실이 곧 영구 손실로 이어지지 않는다 → 이 데이터에 한해 리스크 수용. 나중에 이미지/음성처럼 재구성 불가능한 데이터를 얹게 되면 재검토
- 압축 저장
- 보존 기간: **일기 생성 성공(`generation_status` → `COMPLETED`) 직후 즉시 삭제.** 재시도 중(FAILED)엔 아직 필요하므로 삭제하지 않는다. 원본은 `conversation.messages`에 `source_revision` 고정 조회로 언제든 동일하게 재구성 가능하므로 캐시를 오래 들고 있을 이유가 없다
  - 삭제 호출은 "Job COMPLETED" DB 트랜잭션 밖에서 수행 (LLM 호출을 트랜잭션 밖에 두는 것과 동일한 이유 — 외부 I/O가 DB 커넥션을 붙잡지 않게)
  - 삭제 호출이 실패할 경우를 위해 `purge_after`를 안전망으로 둔다 — 정상 흐름에선 즉시 삭제가 기본이고, `purge_after`는 삭제 실패 시에만 폴백(정리 스케줄러가 나중에 훑어서 지움)으로 작동한다
- 재시도 시 이벤트 없이도 추적 가능하도록 `conversation.moment_sets.raw_snapshot_key`에도 같이 저장한다

**한 유저 하루 대화량 자체가 극단적으로 커지는 경우 (참고, 지금 안 만듦)**

위 전략은 "하루 전체를 한 번에 LLM에 투입"하는 지금 모델의 전송 방식을 최적화한 것이지, LLM 컨텍스트 윈도우 자체의 한계는 해결하지 못한다. 그 지점에 도달하면 "마감 후 배치 1회 추출"이 아니라 대화 도중 구간별로 점진적으로 추출하는 방식으로 재설계해야 한다. 지금은 신호가 없으니 만들지 않는다 (트리거 조건은 "구현하면서 결정" 표 참고).

**request_key 구성**
마감 후 메시지가 추가되면 source_revision이 증가하므로 같은 날짜라도 새 Job이 생성된다.
동일 조건 재시도 → 기존 키로 멱등 처리. 내용 변경 재생성 → 새 키로 새 Job.

**ConversationDay 마감 후 메시지 추가 정책 (Option 3 채택)**
- CLOSED 상태에서도 메시지 추가 허용 (원본 기록 우선)
- Journal이 CONFIRMED 상태였다면 OUTDATED로 변경
- 사용자에게 재생성 여부 제공
- 재생성 요청은 generation_jobs의 request_key로 중복 방지

**오프라인 지연 전송과의 상호작용 (2026-08-02 재확인)**
- occurredAt을 클라이언트 작성 시각으로 받는 설계(Stage 1) 때문에, 배치가 실제 흐르는 서버 시각 기준으로 이미 day를 CLOSED한 뒤에 오프라인 큐잉됐던 메시지가 도착하는 상황이 구조적으로 항상 가능하다(비행기 모드 등으로 지연이 몇 시간~며칠까지 벌어질 수 있음)
- 위 Option 3 정책 그대로 저장은 허용하되, 얼마나 자주 발생하는지 관측하기 위해 `SaveMessageService`에서 CLOSED day에 메시지가 붙을 때 경고 로그를 남긴다 (2026-08-02 적용) — 재오픈/일기 재생성 자동화 여부는 이 로그로 빈도를 확인한 뒤 결정

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
- 일기 상세에서 **Moment 드릴다운** (2026-09-21 결정) — Journal은 "그날의 서사", Moment는 "추억의 조각". Journal만 요약본으로 노출하면 "그날의 감성 총평" 하나로 끝나버려 기획 의도(조각 모음)와 어긋난다는 판단 하에, Moment를 Journal의 내부 부산물이 아니라 사용자가 직접 열람 가능한 1급 데이터로 승격한다. 일기 문장 ↔ 근거 Moment를 연결해 클릭하면 원본 조각(시각/타입/감정)을 볼 수 있게 한다
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

| 인프라         | 기본 계획    | 조기 도입 조건                                                                                                                           |
|----------------|--------------|------------------------------------------------------------------------------------------------------------------------------------------|
| PostgreSQL     | Stage 0      | —                                                                                                                                        |
| Redis          | Stage 1 이후 | 세션 필요 시 (초기엔 DB 세션)                                                                                                            |
| Object Storage | Stage 2      | Moment 추출 시 원본 대화 스냅샷 저장용 (Claim-Check). 로컬/운영(안정화 전) 모두 Garage, 안정화 후 AWS S3로 전환 (2026-09-28, MinIO 대체) |
| Outbox 패턴    | Stage 4      | 이벤트 유실이 업무 손실로 이어지는 시점                                                                                                  |
| Read Model     | Stage 5      | N+1 실측 시                                                                                                                              |
| Kafka          | MSA 분리 시  | Outbox transport 교체 (도입 시에도 큰 페이로드는 계속 Object Storage 참조로, 메시지엔 안 실음)                                           |

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

| 항목                                                    | 결정 시점                                   |
|---------------------------------------------------------|---------------------------------------------|
| 미확정 일기 자동 확정 여부 (N일 후 vs 영구 DRAFT)       | 사용자 행동 패턴 관찰 후                    |
| Insight 집계에 DRAFT 일기 포함 여부                     | Insight 기능 구현 시                        |
| AI 응답 상태를 Message 컬럼으로 유지 vs 별도 Job 테이블 | retry 복잡도 증가 시                        |
| Outbox를 Stage 2에 조기 도입할지                        | 이벤트 유실 허용 여부 판단 시               |
| Moment confidence를 제품 UI에 노출할지                  | UX 설계 시                                  |
| 점진적(구간별) Moment 추출로 전환할 시점                | 하루 대화량이 LLM 컨텍스트 한계에 근접할 때 |
| Garage(운영)에서 AWS S3로 전환할 구체 시점              | 서비스 안정화 판단 후                       |
| Redis 도입 여부                                         | 세션/캐시 실제 필요 발생 시                 |
| 무료 티어 quota 구체적 수치 (일일 메시지/토큰 한도)     | 실사용 트래픽 패턴 확인 후                  |
| 프롬프트/컨텍스트 캐싱 도입 시점                        | 실제 API 연동 시 비용 실측 후               |
| 검색을 LIKE → pg_trgm → FTS 중 어디까지 발전시킬지      | 검색 품질 불만 발생 시                      |
