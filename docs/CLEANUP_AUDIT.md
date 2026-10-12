# R1~R5 코드 정리 점검

2026-10-12 현재 실행 경로를 기준으로 점검했다.
Kotlin 운영 코드 147개 파일과 테스트 50개 파일의 선언·참조를 검색하고,
저빈도 참조 후보의 실제 호출부와 Spring/JPA/직렬화 등록을 확인했다.
Gradle 의존성·버전 카탈로그·실행 역할 설정·초기 Flyway·관련 문서도 점검했다.

## 제거한 잔여 항목

| 항목                                      | 확인한 사용처와 정리                                                                                                                |
|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| SubscriptionSource / COMPLIMENTARY / PAID | 현재 권한·한도 판단에서 사용하지 않음. 도메인·JPA 필드, subscriptions.source, 테스트 입력을 함께 제거                               |
| Message.deletedAt                         | 삭제 작업과 조회 필터 없이 저장·매핑만 수행. 도메인·JPA 필드와 messages.deleted_at 제거                                             |
| Message.localDate                         | RecordDay 귀속과 서버 UTC 사용량으로 교체된 뒤 업무 소비처 없음. 발생 시각·timezone으로 계산 가능하여 필드·컬럼·매핑·생성 인자 제거 |
| ConversationResponse.provider             | 공급자 결과에서 전달만 하고 응답 저장에서 소비하지 않음. 반환 필드·생성 인자·전용 테스트 단언 제거                                  |
| spring-security-test                      | 플랫폼 테스트에서 해당 라이브러리의 API·보안 테스트 어노테이션을 사용하지 않음. 의존성과 카탈로그 항목 제거                         |
| testcontainers-junit-jupiter              | 컨테이너는 프로그램에서 시작하고 ServiceConnection으로 연결. JUnit 컨테이너 확장 사용처가 없어 의존성과 카탈로그 항목 제거          |
| LLM 호출 원장 / platform 스키마 | 호출 진단을 bounded 관측 큐·로그·메트릭으로 교체. 원장 포트·JPA 엔티티·어댑터·테스트·V5와 platform의 JPA 플러그인/의존성 제거 |
| 토큰·작업 생성 메타데이터 | Message/GenerationJob 토큰 컬럼과 전달 인자, 결과와 중복인 작업 provider/model/promptVersion/generationId/generatedAt 제거 |
| LLM 사용자 문맥·생성 전달 계약 | 원장 제거 뒤 소비처가 사라진 LlmRequest.userId와 응답기/일기 생성기 사용자 인자, 공급자·토큰 전달 필드 제거. 테스트도 작업 문맥을 사용 |
| 주석·문서·import                          | 하루 전체 대화 조회 등 교체된 설명과 미사용 import, 이미 정리한 항목의 후속 단계 TODO를 현재 구조로 정리                            |

DB는 미생성 기준으로 V1~V4 초기 파일에 최종 스키마를 반영한다. 호출 원장 전용 V5는 제거했다.
과거 DB 호환용 마이그레이션이나 wrapper를 추가하지 않는다.

## 유지한 실행 경로

- API·대화 워커·일기 워커 역할, 실행 슬롯·DB pool·공급자 예산: 역할별 빈과 실제 scheduler에서 사용한다.
- RecordDay 귀속·마감·입력 동결·Journal 전달: publicapi와 작업 실행에 연결돼 있다.
- 원본 순번·증분 순번·작업 소유권·리스·재시도·사용량: 중복 접수, 순서와 복구를 집행하는 상태다.
- Identity의 사용자 deletedAt: 로그인 거절과 refresh 세션 폐기에 실제 사용한다. 메시지의 미사용 삭제 필드와 다르다.
- Spring 조건/설정/Controller/예외 처리기, Spring Data 메서드, JSON DTO: 프레임워크가 호출하므로 직접 참조 수가 적다는 이유로 제거하지 않는다.
- Gemini/Claude 어댑터와 해당 설정: 공급자 라우팅 설정으로 선택되는 실행 경로다.
- 결과의 generationId/model/promptVersion과 원본 버전: 현재 결과의 생성 정보다. 호출 원장과 중복 토큰/작업 메타데이터는 R5 관측 경로로 교체해 제거했다.

## 단계와 검증

R1~R4에서 제거한 trigger/원본 스캔 planner/날짜 키 경로/구 quota/확정/리비전/이벤트/지급 서비스의
운영 선언·설정·호출 참조를 다시 검색했다. 해당 대체 경로의 추가 미사용 클래스·포트는 확인되지 않았다.

기능 회귀와 새로운 초기 스키마 검증은 [실행 역할](WORKER_ROLES.md)의 현재 결과를 따른다.
확정·리비전의 테스트 전용 객체도 운영 경로에서 제거한 상태다.

R5 관측 경로와 완료 작업 정리는 [관측·보존](OBSERVABILITY_RETENTION.md)을 따른다. R6의 대규모 성능·장애 시험은 이 점검 범위에 포함하지 않는다.
