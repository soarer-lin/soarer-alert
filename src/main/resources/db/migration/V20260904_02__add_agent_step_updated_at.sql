-- 为智能体步骤表补充更新时间的数据库迁移。
ALTER TABLE ops_agent_step
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
