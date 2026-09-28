# 로컬 개발 인프라

각 서비스 디렉토리에서 독립적으로 실행합니다.

## DB (PostgreSQL)
```bash
cd docker/db
docker-compose up -d
```

## Object Storage (Garage — Moment 원본 스냅샷, 2026-09-28)

`--single-node --default-bucket` 플래그로 기동 시 access key/secret/bucket이 환경변수 값 그대로 자동 생성되어, 별도 `garage layout`/`bucket create`/`key create` CLI 절차 없이 바로 S3 API를 쓸 수 있다.

```bash
cd docker/garage
docker-compose up -d
```

S3 API 엔드포인트: `http://localhost:3900` (path-style). MinIO 대신 Garage를 쓰는 이유는 `docs/ARCHITECTURE.md` "원본 스냅샷(Object Storage) 보존 정책" 참고 — MinIO 커뮤니티 에디션이 2026년 archived되어 신규 이미지 배포가 끊겼다.

## 향후 추가 예정
- `redis/` — 캐시 / 세션 (Stage 3+)
- `kafka/` — 이벤트 브로커 (MSA 전환 시)
