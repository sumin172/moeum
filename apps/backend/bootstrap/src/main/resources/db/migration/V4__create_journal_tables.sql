-- diary_date: Conversation의 messages.day_date(사용자 하루 경계 기준 하루)와 같은 값.
-- UNIQUE(user_id, diary_date)는 재생성 미지원 단계 한정(재생성 지원 시 재검토, docs/DEVELOPMENT_STAGES.md 참고).
CREATE TABLE journal.journals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    diary_date DATE NOT NULL,
    lifecycle_status TEXT NOT NULL,
    title TEXT NOT NULL,
    content JSONB NOT NULL,
    current_revision INT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    confirmed_at TIMESTAMPTZ NULL,
    deleted_at TIMESTAMPTZ NULL,
    purge_after TIMESTAMPTZ NULL,
    CONSTRAINT uq_journal_journals_user_diary_date UNIQUE (user_id, diary_date)
);

CREATE TABLE journal.journal_revisions (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL,
    revision_no INT NOT NULL,
    title TEXT NOT NULL,
    content JSONB NOT NULL,
    edited_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_journal_journal_revisions_journal
        FOREIGN KEY (journal_id) REFERENCES journal.journals (id)
);

CREATE INDEX idx_journal_journal_revisions_journal_id ON journal.journal_revisions (journal_id);

-- status/attempt_count/next_attempt_at/lease_expires_at/version: 공통 작업 실행 규칙(platform JobState, JobClaimSql)
-- next_attempt_at: 첫 시도는 그 하루가 끝나는 시각, 실패하면 재시도 시각
-- UNIQUE(user_id, diary_date)는 최초 생성 Job에 대한 멱등키일 뿐(재생성 미지원 단계) 영구 제약이 아니다.
CREATE TABLE journal.generation_jobs (
    id UUID PRIMARY KEY,
    journal_id UUID NULL,
    user_id UUID NOT NULL,
    diary_date DATE NOT NULL,
    status TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    last_error_code TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    provider TEXT NULL,
    model TEXT NULL,
    prompt_version TEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    generation_id UUID NULL,
    generated_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_journal_generation_jobs_journal
        FOREIGN KEY (journal_id) REFERENCES journal.journals (id),
    CONSTRAINT uq_journal_generation_jobs_user_diary_date UNIQUE (user_id, diary_date)
);

-- 작업 선점(JobClaimSql) 대상만 담는 부분 인덱스
CREATE INDEX idx_journal_generation_jobs_pending ON journal.generation_jobs (next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_journal_generation_jobs_processing ON journal.generation_jobs (lease_expires_at)
    WHERE status = 'PROCESSING';
