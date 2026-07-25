CREATE TABLE identity.users (
    id UUID PRIMARY KEY,
    google_id TEXT NOT NULL,
    email TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT uq_identity_users_google_id UNIQUE (google_id)
);
