-- 사용자별 "하루" 경계. 대화 컨텍스트, 일기, quota가 모두 이 경계로 나뉜 하루(messages.day_date)를 쓴다.
-- 설정을 바꾼 적 없는 사용자는 row가 없고 기본값(DayPreference.DEFAULT_DAY_START_TIME)을 쓴다.
-- pending_*: 변경은 다음 하루(pending_effective_from)부터 적용된다.
CREATE TABLE conversation.day_preferences (
    user_id UUID PRIMARY KEY,
    day_start_time TIME NOT NULL,
    pending_day_start_time TIME NULL,
    pending_effective_from DATE NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- local_date: 달력상 현지 날짜(원본 사실).
-- day_date: 사용자 하루 경계로 계산한 논리적 하루. 저장 시점에 확정되고 이후 재계산하지 않는다.
-- created_at: 서버 저장 시각. 오프라인 지연 전송이면 occurred_at보다 한참 뒤일 수 있다.
CREATE TABLE conversation.messages (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    timezone TEXT NOT NULL,
    local_date DATE NOT NULL,
    day_date DATE NOT NULL,
    client_message_id UUID NULL,
    response_status TEXT NULL,
    generation_id UUID NULL,
    model TEXT NULL,
    prompt_version TEXT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_conversation_messages_user_client_message_id UNIQUE (user_id, client_message_id)
);

-- 하루 단위 조회(대화 화면 커서 페이지네이션, AI 컨텍스트, 일기 원본)
CREATE INDEX idx_conversation_messages_user_day_date_id ON conversation.messages (user_id, day_date, id);

-- Journal GenerationPlanner가 "최근 저장된 메시지가 있는 하루"를 찾는 범위 스캔(ConversationActivityQuery.findActiveDays)
CREATE INDEX idx_conversation_messages_created_at ON conversation.messages (created_at);

-- usage_date: messages.day_date와 같은 사용자 하루
CREATE TABLE conversation.ai_usage_daily (
    user_id UUID NOT NULL,
    usage_date DATE NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);
