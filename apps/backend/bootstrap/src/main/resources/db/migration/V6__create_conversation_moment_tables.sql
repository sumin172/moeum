-- Moment 추출 Job 상태 추적 (journal.generation_jobs와 동일 패턴).
-- request_key 멱등키: {conversationDayId}:{sourceRevision}
CREATE TABLE conversation.moment_extraction_jobs (
    id UUID PRIMARY KEY,
    conversation_day_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    request_key TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    error_code TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_conversation_moment_extraction_jobs_conversation_day
        FOREIGN KEY (conversation_day_id) REFERENCES conversation.conversation_days (id),
    CONSTRAINT uq_conversation_moment_extraction_jobs_request_key UNIQUE (request_key)
);

CREATE INDEX idx_conversation_moment_extraction_jobs_conversation_day_id
    ON conversation.moment_extraction_jobs (conversation_day_id);

-- MomentSet: 한 source_revision 시점 기준 추출 결과 단위.
-- raw_snapshot_key는 원본 대화 전체를 담은 Object Storage 참조 (Claim-Check 패턴, 이벤트/DB엔 원본 텍스트를 직접 싣지 않는다).
CREATE TABLE conversation.moment_sets (
    id UUID PRIMARY KEY,
    conversation_day_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    generation_id UUID NOT NULL,
    model TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    raw_snapshot_key TEXT NULL,
    raw_snapshot_purge_after TIMESTAMPTZ NULL,
    is_current BOOLEAN NOT NULL DEFAULT true,
    superseded_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_conversation_moment_sets_conversation_day
        FOREIGN KEY (conversation_day_id) REFERENCES conversation.conversation_days (id)
);

CREATE INDEX idx_conversation_moment_sets_conversation_day_id
    ON conversation.moment_sets (conversation_day_id);

-- 하루당 "현재 유효한" MomentSet은 하나뿐이어야 한다 (마감 후 메시지 추가로 재추출되면 이전 것은 is_current=false로 전환).
CREATE UNIQUE INDEX uq_conversation_moment_sets_current_per_day
    ON conversation.moment_sets (conversation_day_id)
    WHERE is_current = true;

CREATE TABLE conversation.moments (
    id UUID PRIMARY KEY,
    moment_set_id UUID NOT NULL,
    conversation_day_id UUID NOT NULL,
    type TEXT NOT NULL,
    summary TEXT NOT NULL,
    emotion TEXT NULL,
    confidence FLOAT NULL,
    occurred_at TIMESTAMPTZ NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_conversation_moments_moment_set
        FOREIGN KEY (moment_set_id) REFERENCES conversation.moment_sets (id),
    CONSTRAINT fk_conversation_moments_conversation_day
        FOREIGN KEY (conversation_day_id) REFERENCES conversation.conversation_days (id)
);

CREATE INDEX idx_conversation_moments_moment_set_id ON conversation.moments (moment_set_id);
CREATE INDEX idx_conversation_moments_conversation_day_id ON conversation.moments (conversation_day_id);
