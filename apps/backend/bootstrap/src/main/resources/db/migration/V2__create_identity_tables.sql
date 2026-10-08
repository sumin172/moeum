CREATE TABLE identity.users (
    id UUID PRIMARY KEY,
    google_id TEXT NOT NULL,
    email TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_identity_users_google_id UNIQUE (google_id)
);

-- 기기별 로그인 세션(refresh token). refresh token 값은 "<id>.<비밀값>"이고 비밀값은 해시(SHA-256)로만 저장한다.
-- refresh마다 비밀값을 교체(rotation)하고, 직전 해시는 동시 요청 유예 판단(rotation grace)에만 쓴다.
-- 이 세션의 예전 토큰이 다시 들어오면 재사용(탈취)으로 보고 세션을 폐기한다(revoke_reason = REFRESH_TOKEN_REUSED).
CREATE TABLE identity.auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    refresh_token_hash TEXT NOT NULL,
    previous_refresh_token_hash TEXT NULL,
    rotated_at TIMESTAMPTZ NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    revoke_reason TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_identity_auth_sessions_user
        FOREIGN KEY (user_id) REFERENCES identity.users (id)
);

-- 기기당 활성 세션은 하나 — 같은 기기에서 다시 로그인하면 이전 세션을 폐기한 뒤 새로 연다
CREATE UNIQUE INDEX uq_identity_auth_sessions_active_device
    ON identity.auth_sessions (user_id, device_id)
    WHERE revoked_at IS NULL;
