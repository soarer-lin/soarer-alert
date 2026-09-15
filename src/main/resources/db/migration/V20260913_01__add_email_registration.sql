-- 历史迁移：添加邮箱注册字段。
ALTER TABLE auth_user
    ALTER COLUMN username TYPE VARCHAR(254);

ALTER TABLE auth_user
    ADD COLUMN email VARCHAR(254) NULL,
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX auth_user_email_key
    ON auth_user (email)
    WHERE email IS NOT NULL;

CREATE TABLE auth_email_verification (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    email VARCHAR(254) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT auth_email_verification_email_key UNIQUE (email)
);

CREATE INDEX auth_email_verification_expires_at_idx
    ON auth_email_verification (expires_at);
