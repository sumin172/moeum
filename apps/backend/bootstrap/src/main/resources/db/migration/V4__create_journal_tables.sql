CREATE TABLE journal.diary_preferences (
    user_id UUID PRIMARY KEY,
    generation_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- diary_date: Journal이 계산한 diary day. ConversationDay.local_date와 다른 개념(generation_jobs.diary_date와 동일 정의).
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

-- window_start/window_end/timezone_at_scheduling/generation_time_at_scheduling: Planning 시점에 확정되어
-- 이후 preference/timezone 변경으로 재계산되지 않는다(docs/ARCHITECTURE.md invariant 3 참고).
-- UNIQUE(user_id, diary_date)는 최초 생성 Job에 대한 멱등키일 뿐(재생성 미지원 단계) 영구 제약이 아니다.
CREATE TABLE journal.generation_jobs (
    id UUID PRIMARY KEY,
    journal_id UUID NULL,
    user_id UUID NOT NULL,
    diary_date DATE NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    timezone_at_scheduling TEXT NOT NULL,
    generation_time_at_scheduling TIME NOT NULL,
    generation_status TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    provider TEXT NULL,
    model TEXT NULL,
    prompt_version TEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    generation_id UUID NULL,
    generated_at TIMESTAMPTZ NULL,
    error_code TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_journal_generation_jobs_journal
        FOREIGN KEY (journal_id) REFERENCES journal.journals (id),
    CONSTRAINT uq_journal_generation_jobs_user_diary_date UNIQUE (user_id, diary_date)
);

-- GenerationExecutor의 "scheduledAt <= now()인 PENDING Job" 조회 패턴에 맞춘 부분 인덱스.
CREATE INDEX idx_journal_generation_jobs_pending_scheduled_at
    ON journal.generation_jobs (scheduled_at)
    WHERE generation_status = 'PENDING';
