ALTER TABLE ops_agent_step
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
