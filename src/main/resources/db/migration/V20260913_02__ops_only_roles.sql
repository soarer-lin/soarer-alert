UPDATE auth_user
SET role = 'OPS',
    updated_at = now()
WHERE role = 'USER';

ALTER TABLE auth_user
    ADD CONSTRAINT auth_user_role_check
    CHECK (role IN ('ADMIN', 'OPS'));
