ALTER TABLE auth_user
    ADD COLUMN demo_login_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN demo_password_hash VARCHAR(100) NULL;
