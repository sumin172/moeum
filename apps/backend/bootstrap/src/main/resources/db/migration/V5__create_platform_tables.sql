-- LLM 호출 원장(append-only). 성공·실패 모두 남긴다 — 비용·사용량 집계, 토큰 기준 quota, 모델·프롬프트 비교의 원천.
-- purpose: moeum.llm.routes의 용도 이름(conversation-response, journal-generation …)
-- generation_id: 성공한 호출의 생성 결과 ID(messages.generation_id, generation_jobs.generation_id와 같은 값)
-- finish_reason: 응답이 끝난 이유(STOP, MAX_TOKENS 등) — 토큰 상한으로 잘린 비율 관측
-- error_code: 실패의 근본 원인 예외 이름
CREATE TABLE platform.llm_invocations (
    id UUID PRIMARY KEY,
    purpose TEXT NOT NULL,
    user_id UUID NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    generation_id UUID NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    cached_input_tokens INT NOT NULL,
    latency_ms BIGINT NOT NULL,
    succeeded BOOLEAN NOT NULL,
    finish_reason TEXT NULL,
    error_code TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- 사용자별 기간 사용량 집계(토큰 quota, 사용자 비용)
CREATE INDEX idx_platform_llm_invocations_user_created_at ON platform.llm_invocations (user_id, created_at);
-- 전체 기간 집계(용도·모델별 비용, 실패율)
CREATE INDEX idx_platform_llm_invocations_created_at ON platform.llm_invocations (created_at);
