# 로컬 개발 인프라

## PostgreSQL

```powershell
cd docker/db
docker compose up -d
```

현재 Compose는 로컬 기능 개발용이다. 운영 용량이나 성장 부하 검증 사양을 뜻하지 않는다.

## 스키마 재작성 정책

개발 중 스키마는 신규 DB 기준으로 Flyway V1부터 다시 구성한다.
기존 로컬 volume에는 과거 Flyway 이력이 남을 수 있으므로 재작성한 파일을 그대로 적용하면 검증 오류가 날 수 있다.
통합 테스트는 독립적인 새 PostgreSQL에서 실행한다.
로컬 데이터 삭제나 volume 초기화는 사용자가 명시적으로 요청한 경우에만 수행한다.

Redis·Kafka·Object Storage는 현재 필수 인프라가 아니다.
작업 전달에 메시지 브로커를 쓰지 않는 근거와 재검토 조건은 [DB 작업 큐](../docs/engineering/job-queue.md)에 있다.
실행 역할과 자원 격리는 [아키텍처](../docs/ARCHITECTURE.md),
부하 시험 기준은 [시험 기준](../docs/CAPACITY.md)을 참고한다.
현재 API·대화 워커·일기 워커 실행 방법은 [실행 역할](../docs/WORKER_ROLES.md)을 따른다.
