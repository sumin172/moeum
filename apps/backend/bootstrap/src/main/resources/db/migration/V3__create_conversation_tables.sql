CREATE TABLE conversation.user_record_states (
    user_id UUID PRIMARY KEY,
    close_time TIME NOT NULL,
    active_record_day_id UUID NULL
);
CREATE TABLE conversation.record_days (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    display_date DATE NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    close_date DATE NOT NULL,
    close_time TIME NOT NULL,
    timezone TEXT NOT NULL,
    close_at TIMESTAMPTZ NOT NULL,
    last_user_occurred_at TIMESTAMPTZ NOT NULL,
    stream_sequence BIGINT NOT NULL DEFAULT 0,
    source_revision BIGINT NOT NULL DEFAULT 0,
    closed_at TIMESTAMPTZ NULL,
    close_reason TEXT NOT NULL,
    frozen_source_revision BIGINT NULL,
    delivery_version BIGINT NOT NULL DEFAULT 0,
    delivery_lease_expires_at TIMESTAMPTZ NULL,
    delivery_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_record_interval CHECK (start_at < COALESCE(closed_at,close_at)),
    CONSTRAINT ck_record_freeze CHECK (frozen_source_revision IS NULL OR (closed_at IS NOT NULL AND frozen_source_revision <= source_revision))
);
CREATE UNIQUE INDEX uq_record_open_user ON conversation.record_days(user_id) WHERE closed_at IS NULL;
CREATE INDEX idx_record_user_interval ON conversation.record_days(user_id,start_at);
CREATE INDEX idx_record_user_date ON conversation.record_days(user_id,display_date,start_at);
CREATE INDEX idx_record_close_due ON conversation.record_days(close_at) WHERE closed_at IS NULL;
CREATE INDEX idx_record_delivery ON conversation.record_days(delivery_lease_expires_at,closed_at)
    WHERE closed_at IS NOT NULL AND delivery_acknowledged = FALSE;

CREATE TABLE conversation.messages (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    timezone TEXT NOT NULL,
    local_date DATE NOT NULL,
    record_day_id UUID NOT NULL,
    source_sequence BIGINT NOT NULL CHECK (source_sequence > 0),
    stream_sequence BIGINT NOT NULL CHECK (stream_sequence > 0),
    client_message_id UUID NULL,
    generation_id UUID NULL,
    model TEXT NULL,
    prompt_version TEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_conversation_messages_user_client_message_id UNIQUE (user_id, client_message_id)
);

CREATE INDEX idx_messages_record_page ON conversation.messages (user_id,record_day_id,stream_sequence);
CREATE UNIQUE INDEX uq_messages_stream_sequence ON conversation.messages(record_day_id,stream_sequence);
CREATE INDEX idx_messages_context ON conversation.messages(user_id,record_day_id,source_sequence DESC,role ASC);
CREATE INDEX idx_messages_record_source ON conversation.messages (user_id,record_day_id,occurred_at,source_sequence) WHERE role = 'USER';
CREATE UNIQUE INDEX uq_messages_source_sequence ON conversation.messages (record_day_id,source_sequence) WHERE role = 'USER';

-- 유저 메시지 하나에 대한 AI 응답 생성 작업. 메시지와 같은 트랜잭션에서 만들어진다.
-- status/attempt_count/next_attempt_at/lease_expires_at/version: 공통 작업 실행 규칙(platform JobState, JobClaimSql)
-- failure_reason: 사용자에게 보여주는 실패 분류(GENERATION_FAILED), last_error_code: 기술적 원인
CREATE TABLE conversation.response_jobs (
    id UUID PRIMARY KEY,
    user_message_id UUID NOT NULL,
    user_id UUID NOT NULL,
    record_day_id UUID NOT NULL,
    input_source_revision BIGINT NOT NULL CHECK (input_source_revision > 0),
    status TEXT NOT NULL,
    failure_reason TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    last_error_code TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_conversation_response_jobs_user_message
        FOREIGN KEY (user_message_id) REFERENCES conversation.messages (id),
    CONSTRAINT uq_conversation_response_jobs_user_message UNIQUE (user_message_id)
);

-- 작업 선점(JobClaimSql) 대상만 담는 부분 인덱스
CREATE INDEX idx_conversation_response_jobs_pending ON conversation.response_jobs (next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_conversation_response_jobs_processing ON conversation.response_jobs (lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE UNIQUE INDEX uq_response_stream_source ON conversation.response_jobs(record_day_id,input_source_revision);
CREATE INDEX idx_response_stream_active ON conversation.response_jobs(record_day_id,input_source_revision)
    WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX idx_response_stream_completed ON conversation.response_jobs(record_day_id,input_source_revision) WHERE status='COMPLETED';

-- 현재 UTC 기간 사용량과 서버 접수 속도. 사용자당 한 행만 유지한다.
CREATE TABLE conversation.conversation_usage (
    user_id UUID PRIMARY KEY,
    usage_date DATE NOT NULL,
    message_count INT NOT NULL CHECK (message_count >= 0),
    request_window TIMESTAMPTZ NOT NULL,
    request_count INT NOT NULL CHECK (request_count >= 0)
);
