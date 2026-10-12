CREATE TABLE journal.journals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    record_day_id UUID NOT NULL,
    display_date DATE NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    source_revision BIGINT NOT NULL,
    generation_id UUID NOT NULL,
    model TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_journal_journals_user_record_day UNIQUE (user_id, record_day_id)
);

CREATE INDEX idx_journal_user_display_date ON journal.journals (user_id,display_date);

CREATE TABLE journal.generation_jobs (
    id UUID PRIMARY KEY,
    journal_id UUID NULL,
    user_id UUID NOT NULL,
    record_day_id UUID NOT NULL,
    display_date DATE NOT NULL,
    status TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    last_error_code TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_journal_generation_jobs_journal
        FOREIGN KEY (journal_id) REFERENCES journal.journals (id),
    CONSTRAINT uq_journal_generation_jobs_user_record_day UNIQUE (user_id, record_day_id)
);

CREATE INDEX idx_journal_generation_jobs_pending ON journal.generation_jobs (next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_journal_generation_jobs_processing ON journal.generation_jobs (lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_journal_generation_jobs_retention ON journal.generation_jobs (updated_at,id) WHERE status='COMPLETED';
