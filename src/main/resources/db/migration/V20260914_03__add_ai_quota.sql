-- 历史迁移：添加 AI 配额字段。
ALTER TABLE auth_user
    ADD COLUMN ai_quota_limit INT NULL,
    ADD COLUMN ai_quota_used INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT auth_user_ai_quota_limit_check
        CHECK (ai_quota_limit IS NULL OR ai_quota_limit > 0),
    ADD CONSTRAINT auth_user_ai_quota_used_check
        CHECK (ai_quota_used >= 0);
