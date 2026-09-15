-- 历史迁移：移除邮箱注册字段。
DROP TABLE IF EXISTS auth_email_verification;

DROP INDEX IF EXISTS auth_user_email_key;

ALTER TABLE auth_user
    DROP COLUMN IF EXISTS email,
    DROP COLUMN IF EXISTS email_verified;
