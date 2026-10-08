package com.moeum.platform.job

// 작업 테이블 공통 선점 SQL 조각. 테이블은 JobState에 대응하는 컬럼
// (status, attempt_count, next_attempt_at, lease_expires_at, version, updated_at)을 가져야 한다.
//
// SQL 식별자(테이블명)는 바인딩 파라미터로 넘길 수 없어서, 각 리포지토리가 자기 테이블명과 이 조각들을
// const val로 이어 붙여 완성된 SQL을 만든다. 실행 시점 문자열 조합이 없으므로 SQL 인젝션 여지가 없다.
//
// 선점 다음 작업 하나 (SKIP LOCKED라 여러 워커가 동시에 호출해도 같은 작업을 잡지 않는다):
//   UPDATE <table> $CLAIM_SET WHERE id = (SELECT id FROM <table> WHERE $CLAIMABLE $PICK_ONE) RETURNING *
// 특정 작업 선점 (이미 다른 워커가 잡았으면 아무것도 반환하지 않는다):
//   UPDATE <table> $CLAIM_SET WHERE $CLAIMABLE_BY_ID RETURNING *
//
// 파라미터: :now, :leaseExpiresAt (CLAIMABLE_BY_ID는 :id 추가)
object JobClaimSql {

    // 선점: PROCESSING으로 바꾸고 시도 횟수·version을 올리며 리스를 잡는다
    const val CLAIM_SET =
        "SET status = 'PROCESSING', attempt_count = attempt_count + 1, lease_expires_at = :leaseExpiresAt, " +
            "version = version + 1, updated_at = :now"

    // 선점 대상: 다음 시도 시각이 지난 PENDING, 또는 리스가 만료된(워커가 죽은) PROCESSING
    const val CLAIMABLE =
        "(status = 'PENDING' AND next_attempt_at <= :now) OR (status = 'PROCESSING' AND lease_expires_at <= :now)"

    // 선점 대상 중 가장 오래 기다린 하나를 잠근다
    const val PICK_ONE = "ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED"

    // 특정 작업이 지금 바로 실행할 수 있는 상태일 때만
    const val CLAIMABLE_BY_ID = "id = :id AND status = 'PENDING' AND next_attempt_at <= :now"
}
