# 로컬 개발 인프라

각 서비스 디렉토리에서 독립적으로 실행합니다.

## DB (PostgreSQL)
```bash
cd docker/db
docker-compose up -d
```

## 향후 추가 예정
- `redis/` — 캐시 / 세션 (Stage 3+)
- `kafka/` — 이벤트 브로커 (MSA 전환 시)
