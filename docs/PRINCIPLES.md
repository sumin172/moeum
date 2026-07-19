# 모음 — 개발 원칙

> 이 원칙들은 "언제 도입할지" 기준으로 분류되어 있다.
> 전부 Day 1에 구현하는 것이 목표가 아니다.

---

## Day 1 필수 원칙 (코드로 강제)

**1. 각 데이터는 하나의 Bounded Context만 원본 소유자다**
- 다른 Context는 ID 참조만 보관
- 원본 수정은 반드시 소유 모듈의 Application API 경유

**2. 다른 모듈의 테이블을 직접 조회하지 않는다**
```kotlin
// ❌ Journal 모듈에서 conversation 테이블 직접 접근
conversationMessageRepository.findByConversationDayId(id)

// ✅ Conversation 모듈의 Application Service 경유
conversationQueryService.getMessagesForJournalGeneration(id)
```

**3. 한 트랜잭션은 원칙적으로 한 Context 안에서 끝난다**
- 일기 확정 + 포인트 지급을 하나의 DB 트랜잭션으로 묶지 않는다
- 각 단계는 독립적으로 성공하거나 실패할 수 있다

**4. 모든 사용자 입력에 timezone과 local_date를 저장한다**
- 나중에 추가하면 과거 데이터 전부 재계산

**5. UserId는 auth provider ID와 분리한다**
- 내부 UUID를 별도 생성
- OAuth 제공자 교체 시 UserId가 흔들리지 않아야 한다

**6. 원본 기록과 AI 생성 결과는 별도 테이블로 분리한다**
- conversation.messages (role=user) = 사용자 원본, 불변
- conversation.messages (role=assistant) = AI 대화 응답, 생성 메타데이터 필요
- journal.journals = AI 파생, 언제든 재생성 가능

**7. 모든 AI 생성 결과에 메타데이터를 저장한다 (대화 응답 포함)**
```
provider, model, prompt_version, generation_id, generated_at
```
대화 응답(message.role=assistant)에도 generation_id, model, prompt_version을 기록한다.

**8. API 멱등성은 Stage 1부터 적용한다**
- 메시지 생성: `UNIQUE (user_id, client_message_id)`
- 일기 생성 Job: `UNIQUE (request_key)` — `{conversationDayId}:{sourceRevision}:{type}:{promptVersion}`
- 모바일 재시도로 인한 중복은 서버에서 막는다

**9. 삭제 정책은 데이터 유형별로 정의한다**

| 유형 | 정책 |
|------|------|
| 일반 UI 삭제 | Soft Delete (deleted_at), 복구 창 내 복원 가능 |
| 계정 탈퇴 / 영구 삭제 | 유예 기간 후 Hard Delete 또는 비가역 익명화 |
| 재무/법적 기록 | 법적 보존 정책에 따라 별도 처리 |

"모든 것에 Soft Delete"가 아니다. 유형마다 보존 기간과 물리 삭제 정책을 정의한다.

---

## 단계별 도입 원칙

**Stage 0에 구조 정의: Domain Event vs Integration Event**
- Domain Event: 모듈 내부 sealed interface, 강타입
- Integration Event: 모듈 외부 공개 계약, 명시적 버전, 생산자 모듈이 소유
- `Map<String, Any>` payload는 Outbox 직렬화에만, 애플리케이션 코드에서는 금지
- Integration Event 계약 버전 필드(eventVersion)는 처음부터 포함 (인프라 고도화와 무관)

**Stage 4에 도입: Outbox + 이벤트 소비자 멱등성**
- PointLedger: `source_event_id UNIQUE`로 중복 지급 방지
- Outbox: 단순 스케줄러 폴러로 시작 (Debezium 불필요)
- 이벤트 유실이 업무 손실로 이어지면 조기 도입

**이벤트 계약 변경 원칙 (계약 생성 시부터)**
- 이벤트는 이미 발생한 사실 (과거형 동사)
- 기존 필드 삭제 금지, 새 필드는 optional 추가
- 파괴적 변경은 새 버전 (V2)으로

**실제 문제 발생 시 도입: Read Model**
- 홈 화면에 5개 이상 조회 → HomeDashboardProjection
- 통계 화면 → 사전 집계 Projection
- N+1 실측 전에는 API Composition으로 충분

---

## JPA 설계 원칙

JPA `@Entity`는 `infrastructure` 레이어에만 존재한다.
`domain/` 패키지의 클래스는 JPA 어노테이션을 포함하지 않는다.
Entity ↔ Domain 변환은 두 모델이 실질적으로 다를 때만 명시적 매퍼(`toDomain()` / `toEntity()`)를 만든다.

`@OneToMany`를 사용하지 않는다.
N+1은 `JOIN FETCH`, `@EntityGraph`, `@BatchSize` 같은 쿼리 기법으로 제어하지 않고, 모델 구조로 원천 차단한다.

컬렉션이 필요한 곳은 다음 두 가지로만 처리한다.

- **JSONB**: 항상 부모와 함께 로딩하는 소규모 값 객체 (Journal 섹션, 감정 점수 등)
- **명시적 Repository 쿼리**: 독립 생명주기가 있거나 페이지네이션이 필요한 경우

이 원칙은 도메인 특성에서 도출된 것이 아니라, 런타임 규율보다 모델 구조로 문제를 막는 것이 더 신뢰할 수 있다는 설계 철학에서 출발한다. 도메인이 달라져도 동일하게 적용한다.

---

## 영구 금지 원칙

- `@OneToMany`를 사용한다
- 다른 Context의 Entity를 직접 수정하지 않는다
- 크로스 schema JOIN 쿼리를 작성하지 않는다
- 다른 모듈의 `domain/`, `infrastructure/`를 직접 import한다 (`publicapi`만 허용)
- Shared Kernel에 도메인 enum, 업무 로직, Integration Event를 넣는다
- 심리 분석을 의료 진단처럼 표현한다
- AI 결과를 원본 기록처럼 취급한다
- Journal 모듈이 Moment를 생성하거나 conversation 테이블에 저장한다
- 모듈 간 순환 의존 (conversation ↔ journal)
- ConversationDayClosed 이벤트에 Moment 추출을 동기 결합시킨다 (마감 API가 LLM에 묶임)

---

## Shared Kernel 허용 목록

```kotlin
// 이것만 Shared Kernel에 둔다
data class UserId(val value: UUID)
data class Money(val amount: Long, val currency: String)
interface TimeProvider
abstract class DomainEvent
data class EventEnvelope(...)
```

User Entity, Journal Entity, 도메인 enum 전체 → 각 모듈 내부에

---

## AI 사용 원칙

- 모델명과 공급자를 도메인 코드에 직접 쓰지 않는다
- ConversationResponder / JournalGenerator 인터페이스 뒤에 구현을 숨긴다
- LLM 호출 실패는 메시지 저장 실패로 이어지지 않는다
- LLM 장애 격리: Timeout + 제한적 Retry + Circuit Breaker

---

## 심리 분석 경계

```
✅ 최근 2주간 피곤하다는 기록이 이전보다 많았습니다.
✅ 이번 달 운동 이야기가 8회 등장했습니다.

❌ 우울증 위험이 72%입니다.
❌ 번아웃 상태입니다.
```

모든 분석 결과에 필수 포함:
- 분석 대상 기간
- 근거가 된 기록
- 불확실성 표시
- 숨기기 / 삭제 / 피드백 기능

---

## 삭제 원칙

삭제는 DB 한 행 제거가 아니라 업무 흐름이다.

```
UserDataDeletionRequested
→ Conversation 삭제
→ Journal 삭제 또는 익명화
→ Insight 삭제
→ Media 삭제
→ 완료 상태 집계
```

원본 삭제 후 파생 데이터(Insight, Journal)가 남아 있으면 안 된다.
