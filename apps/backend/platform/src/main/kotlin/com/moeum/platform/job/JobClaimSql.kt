package com.moeum.platform.job

// 작업 테이블 공통 선점·만료 SQL 조각. 테이블은 JobState에 대응하는 컬럼
// (status, attempt_count, next_attempt_at, version, updated_at)을 가져야 한다.
//
// SQL 식별자(테이블명)는 바인딩 파라미터로 넘길 수 없어서, 각 리포지토리가 자기 테이블명과 이 조각들을
// const val로 이어 붙여 완성된 SQL을 만든다. 실행 시점 문자열 조합이 없으므로 SQL 인젝션 여지가 없다.
//
// 선점 다음 작업 하나 (SKIP LOCKED라 여러 워커가 동시에 호출해도 같은 작업을 잡지 않는다):
//   UPDATE <table> $CLAIM_SET WHERE id = (SELECT id FROM <table> WHERE $DUE_PENDING $PICK_ONE) RETURNING *
//
// 파라미터: :now (선점), :expiredBefore (만료 정리)
object JobClaimSql {

    // 선점: PROCESSING으로 바꾸고 시도 횟수·version을 올린다. updated_at이 리스 기준 시각이 된다.
    const val CLAIM_SET =
        "SET status = 'PROCESSING', attempt_count = attempt_count + 1, version = version + 1, updated_at = :now"

    // 선점 대상: 다음 시도 시각이 지난 PENDING
    const val DUE_PENDING = "status = 'PENDING' AND next_attempt_at <= :now"

    // 선점 대상 중 가장 오래 기다린 하나를 잠근다
    const val PICK_ONE = "ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED"

    // 만료 정리 대상: 선점 후 리스 안에 끝나지 않은 PROCESSING (:expiredBefore = now - lease)
    const val LEASE_EXPIRED = "status = 'PROCESSING' AND updated_at <= :expiredBefore"
}
