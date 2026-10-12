# 자동 일기·구독 수정 계약

R4의 서버 구현 계약이다. 귀속·마감·생성 입력 동결은 [하루 기록](RECORD_DAY.md),
실행 프로세스와 자원 예산은 [실행 역할](WORKER_ROLES.md)을 따른다.

## 결과와 작업

recordDayId마다 현재 일기 결과 하나와 생성 작업 하나를 가진다.
생성 결과가 저장되면 바로 조회할 수 있으며 별도 확정 단계는 없다.
실행 상태 PENDING/PROCESSING/COMPLETED/FAILED는 생성 작업의 상태다.
일기 자체에 DRAFT/CONFIRMED 상태를 두지 않는다.

최초 생성과 실패 복구는 같은 작업·동결된 원본을 사용한다.
완료된 일기의 AI 재작성·문체·길이 설정은 제공하지 않는다.
본문은 TEXT로 저장한다. 공급자의 JSON 응답 해석은 저장 형식과 별개이며 유지한다.

현재 결과는 제목·본문, 수정 version, 동결 sourceRevision,
generationId/model/promptVersion/generatedAt을 보유한다.
version은 최초 생성에서 0이며 사용자가 제목·본문을 실제 변경하면 증가한다.
같은 내용을 다시 저장하는 요청은 version과 현재 결과를 유지한다.
원본·생성 메타데이터는 사용자 수정 시 보존한다.
별도의 편집 카운터·수정 이력·확정 시각을 저장하지 않는다.

## 생성 저장과 수정본 보호

LLM 호출은 트랜잭션 밖에서 실행한다.
결과 저장 트랜잭션에서 먼저 작업 행을 잠그고 현재 PROCESSING과 소유권 version을 검사한다.
리스 회수 또는 완료 후 돌아온 오래된 워커는 일기 INSERT 전에 거절한다.

자동 생성 저장은 INSERT 전용이다.
UNIQUE(user_id,record_day_id) 충돌이면 기존 현재 결과를 반환하고 제목·본문·version을 변경하지 않는다.
충돌 처리에는 해당 멱등키만 지정하며 다른 DB 오류를 중복으로 삼키지 않는다.
기존 결과와 동결 sourceRevision이 다르면 저장을 거절한다.
일기 INSERT와 작업 COMPLETED는 같은 트랜잭션이며 작업 완료 저장 오류 시 INSERT도 롤백된다.

기존 일기를 자동 UPDATE하지 않으므로 사용자 편집 version을 비교한 뒤 덮어쓰는 경로가 필요 없다.
사용자 수정끼리의 경합은 JPA @Version과 클라이언트 expected version으로 막는다.
별도 userEdited 플래그나 이중 쓰기를 두지 않는다.

처음 생성 중에는 수정할 결과가 아직 없다.
현실적인 생성·수정 경합은 만료된 워커의 호출이 남아 있는 동안 다른 워커가 일기를 완성하고 사용자가 수정하는 경우다.
오래된 작업 소유권 차단과 INSERT 전용 저장으로 새 결과·수정본을 보호한다.
결과가 남은 복구 상태에서도 자동 작업은 현재 결과를 교체하지 않는다.

## 서버 권한과 API

Identity의 현재 JOURNAL_EDIT 권한을 확인하며 구독 상태를 JWT에 넣지 않는다.
무료 사용자는 생성·조회·서버 실패 복구가 가능하고 제목·본문 직접 수정은 구독 기능이다.
수정 요청마다 서버에서 권한을 검사한다. 클라이언트 editable은 UI 안내값이며 서버 검사를 대신하지 않는다.

| API | 계약 |
|---|---|
| GET /api/journals?date= | 해당 표시 날짜의 생성된 일기 목록 |
| GET /api/journals/{recordDayId} | recordDayId, journal, generation |
| PUT /api/journals/{recordDayId} | title/body/version으로 현재 내용 수정 |
| POST /api/journals/{recordDayId}/generation-attempts | 실패한 같은 작업의 복구 |

journal 응답은 recordDayId/displayDate/title/body/version/editable이다.
아직 결과가 없으면 상세의 journal은 null이며 generation으로 실행 상태를 확인한다.
표시 날짜가 같은 여러 기록도 별도 결과로 반환한다.

수정은 권한 부재 403, 빈 제목·본문 400, 없는 일기 404, 낡은 version·동시 수정 충돌 409다.
성공 응답의 version을 다음 수정에 사용한다. 다시 확정하지 않는다.

실패 복구는 요금제와 무관하며 대화 횟수를 추가 차감하지 않는다.
PENDING/PROCESSING의 반복 요청은 현재 작업 상태를 반환한다.
COMPLETED는 409, 생성할 작업이 없는 기록은 404다.
입력은 다시 열지 않으며 새 작업을 만들지 않는다.

확정 API·DTO·서비스·이벤트·공통 이벤트 계약과 recorder, 리비전 테이블·포트·모델·어댑터,
JSON 본문 저장 codec, 미사용 운영 구독 지급 경로는 제거했다.
구독 출처 enum/컬럼은 저장하지 않는다. 현재 권한은 유효 기간과 Plan으로 판단한다.
테스트 구독은 테스트 코드에서만 삽입한다. 실제 결제·구독 관리 API를 구현한 것으로 간주하지 않는다.

## 검증 범위

새 PostgreSQL에 V1부터 초기 스키마를 적용해 TEXT 본문과 기록 ID 유일키를 검증한다.
동시 수정의 한 건 승인, 늦은 생성 결과·수정본 보호, 원본 버전 불일치,
원본 버전 거절 후 작업·결과 보존과 무료 실패 재시도의 동일 입력·무차감을 검사한다.
기능 시험의 공급자는 fake다.
독립 JVM 강제 종료·실제 공급자·대규모 마감 집중·6개월 누적 데이터 성능은 R6 범위다.

호출 원장·중복 토큰 메타데이터는 제거했다. 완료 작업 보존·정리는 [관측·보존](OBSERVABILITY_RETENTION.md)을 따른다.
완료 작업이 정리된 뒤에도 일기와 원본 생성 정보는 남으며 상세 응답의 generation은 null일 수 있다. 현재 journal이 있으면 결과를 표시한다. 생성 재시도는 409를 유지한다.
계정 삭제·실제 결제·백업·배포 보안과 클라이언트 화면은 별도 출시 작업이다.
