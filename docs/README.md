# 모음 개발 문서

모음은 일상 대화를 사용자 설정 시각에 자동으로 일기로 만드는 서비스다.
이 문서 묶음은 현재 서버 구현과 확정 기획을 설명한다. 구현 기본값과 미확정 정책은 별도로 표시한다.

## 읽는 순서

1. [서비스 기획](PRODUCT.md): 서비스 목적, 사용자 경험, 확정 정책과 확인할 정책.
2. [아키텍처](ARCHITECTURE.md): 모듈 소유권, 데이터 접근, 트랜잭션과 실행 경계.
3. [하루 기록](RECORD_DAY.md): 접수 시각, 마지막 장소의 타임존, 마감과 생성 입력.
4. [인증·구독](AUTH.md): 로그인, 토큰과 세션, 구독 권한.
5. [대화 계약](CONVERSATION.md): 접수 한도, 응답 순서, 조회와 재시도.
6. [일기 계약](JOURNAL.md): 자동 생성, 구독 수정, 결과 보호와 조회.
7. [AI 공급자](LLM.md): 용도별 경로, 출력 판정, 장애 격리와 재시도 분류.
8. [실행 역할](WORKER_ROLES.md): 실행 방법, 역할별 자원 상한과 복구.
9. [관측·보존](OBSERVABILITY_RETENTION.md): 로그·메트릭과 작업 정리.
10. [개발 원칙](PRINCIPLES.md): 필수 상태, 자원 재사용, 정리와 검증 기준.
11. [시험 기준](CAPACITY.md), [실행 절차](CAPACITY_RUNBOOK.md), [검증 현황](CAPACITY_RESULTS.md): 재사용 가능한 시험과 확인된 범위.

## 빌드와 회귀 검사

일반 회귀는 apps/backend에서 다음 명령으로 실행한다.

```powershell
.\gradlew.bat test --console=plain --offline --no-daemon
```

일반 회귀는 단위·실제 PostgreSQL 통합·모듈 의존성 검사를 포함한다.
독립 JVM 강제 종료·부하 시험은 capacityTest, 실제 공급자 검증은 providerSmokeTest·journalProviderTest로 실행한다.
절차는 [실행 절차](CAPACITY_RUNBOOK.md)를 따른다. 로컬 DB는 [로컬 개발 인프라](../docker/README.md)를 따른다.

Gradle wrapper 기본 사용자 캐시는 apps/backend/.gradle/user-home이다.
GRADLE_USER_HOME이 지정되면 해당 경로를 우선한다.

## 개발 사례

[개발 사례](engineering/README.md)는 설계 선택의 문제·대안·판단·검증을 설명하는 별도 문서 묶음이다.
기획과 현재 동작의 기준은 위 현행 문서를 따른다.
