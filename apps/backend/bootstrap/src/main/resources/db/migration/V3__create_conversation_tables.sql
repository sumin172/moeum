CREATE TABLE conversation.conversation_days (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    local_date DATE NOT NULL,
    timezone TEXT NOT NULL,
    status TEXT NOT NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_conversation_conversation_days_user_local_date UNIQUE (user_id, local_date)
);

CREATE TABLE conversation.messages (
    id UUID PRIMARY KEY,
    conversation_day_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    timezone TEXT NOT NULL,
    local_date DATE NOT NULL,
    client_message_id UUID NULL,
    response_status TEXT NULL,
    generation_id UUID NULL,
    model TEXT NULL,
    prompt_version TEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_conversation_messages_conversation_day
        FOREIGN KEY (conversation_day_id) REFERENCES conversation.conversation_days (id),
    CONSTRAINT uq_conversation_messages_user_client_message_id UNIQUE (user_id, client_message_id)
);

CREATE INDEX idx_conversation_messages_conversation_day_id ON conversation.messages (conversation_day_id);
