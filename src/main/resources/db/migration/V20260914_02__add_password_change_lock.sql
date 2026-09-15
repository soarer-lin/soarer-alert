-- 历史迁移：添加密码修改锁。
ALTER TABLE auth_user
    ADD COLUMN password_change_locked BOOLEAN NOT NULL DEFAULT FALSE;
