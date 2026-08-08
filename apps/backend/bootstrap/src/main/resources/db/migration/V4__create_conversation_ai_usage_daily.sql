CREATE TABLE conversation.ai_usage_daily (
    user_id UUID NOT NULL,
    usage_date DATE NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);
