-- 创建认证相关表的数据库迁移。
CREATE TABLE auth_user (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ NULL,
    password_updated_at TIMESTAMPTZ NULL,
    last_login_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT auth_user_username_key UNIQUE (username)
);

CREATE INDEX auth_user_role_status_idx
    ON auth_user (role, status);

CREATE TABLE auth_login_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NULL REFERENCES auth_user (id) ON DELETE SET NULL,
    username VARCHAR(64),
    success BOOLEAN NOT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(500),
    fail_reason VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX auth_login_audit_created_at_idx
    ON auth_login_audit (created_at DESC);
CREATE INDEX auth_login_audit_username_idx
    ON auth_login_audit (username, created_at DESC);

ALTER TABLE ops_chat_session
    ADD COLUMN user_id UUID NULL REFERENCES auth_user (id) ON DELETE SET NULL;
ALTER TABLE ops_document
    ADD COLUMN created_by UUID NULL REFERENCES auth_user (id) ON DELETE SET NULL;
ALTER TABLE ops_diagnosis_run
    ADD COLUMN created_by UUID NULL REFERENCES auth_user (id) ON DELETE SET NULL;

CREATE INDEX ops_chat_session_user_idx
    ON ops_chat_session (user_id, updated_at DESC);
CREATE INDEX ops_diagnosis_run_created_by_idx
    ON ops_diagnosis_run (created_by, started_at DESC);
