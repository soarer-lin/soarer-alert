ALTER TABLE auth_user
    ADD COLUMN password_change_locked BOOLEAN NOT NULL DEFAULT FALSE;
