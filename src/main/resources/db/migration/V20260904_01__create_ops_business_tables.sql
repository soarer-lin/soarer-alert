-- 创建运维业务表的数据库迁移。
CREATE TABLE ops_document (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    file_name TEXT NOT NULL,
    storage_key TEXT,
    storage_url TEXT,
    file_path TEXT,
    content_hash TEXT NOT NULL,
    media_type TEXT NOT NULL,
    byte_size BIGINT NOT NULL,
    status TEXT NOT NULL,
    document_version INTEGER NOT NULL DEFAULT 1,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    indexed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX ops_document_content_hash_uidx
    ON ops_document (content_hash);
CREATE UNIQUE INDEX ops_document_storage_key_uidx
    ON ops_document (storage_key) WHERE storage_key IS NOT NULL;
CREATE INDEX ops_document_status_idx
    ON ops_document (status);
CREATE INDEX ops_document_created_at_idx
    ON ops_document (created_at DESC);

CREATE TABLE ops_document_chunk (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    document_id UUID NOT NULL REFERENCES ops_document (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    title TEXT,
    chunk_type TEXT,
    start_index INTEGER NOT NULL,
    end_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ops_document_chunk_unique_document_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX ops_document_chunk_document_idx
    ON ops_document_chunk (document_id, chunk_index);
CREATE INDEX ops_document_chunk_type_idx
    ON ops_document_chunk (chunk_type);

CREATE TABLE ops_chat_session (
    session_id TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ops_chat_message (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    session_id TEXT NOT NULL REFERENCES ops_chat_session (session_id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ops_chat_message_unique_sequence UNIQUE (session_id, sequence)
);

CREATE INDEX ops_chat_message_session_idx
    ON ops_chat_message (session_id, sequence);

CREATE TABLE ops_diagnosis_run (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    request_text TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ops_diagnosis_run_status_idx
    ON ops_diagnosis_run (status);
CREATE INDEX ops_diagnosis_run_started_at_idx
    ON ops_diagnosis_run (started_at DESC);

CREATE TABLE ops_agent_step (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    diagnosis_run_id UUID NOT NULL REFERENCES ops_diagnosis_run (id) ON DELETE CASCADE,
    step_index INTEGER NOT NULL,
    agent_name TEXT NOT NULL,
    step_type TEXT NOT NULL,
    instruction TEXT,
    input_text TEXT,
    output_text TEXT,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ops_agent_step_unique_run_index UNIQUE (diagnosis_run_id, step_index)
);

CREATE INDEX ops_agent_step_run_idx
    ON ops_agent_step (diagnosis_run_id, step_index);

CREATE TABLE ops_tool_invocation (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    diagnosis_run_id UUID NOT NULL REFERENCES ops_diagnosis_run (id) ON DELETE CASCADE,
    agent_step_id UUID REFERENCES ops_agent_step (id) ON DELETE SET NULL,
    tool_name TEXT NOT NULL,
    arguments JSONB,
    result JSONB,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ops_tool_invocation_run_idx
    ON ops_tool_invocation (diagnosis_run_id);
CREATE INDEX ops_tool_invocation_tool_idx
    ON ops_tool_invocation (tool_name);
CREATE INDEX ops_tool_invocation_status_idx
    ON ops_tool_invocation (status);

CREATE TABLE ops_alert (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    diagnosis_run_id UUID REFERENCES ops_diagnosis_run (id) ON DELETE SET NULL,
    alert_name TEXT NOT NULL,
    severity TEXT,
    service_name TEXT,
    environment TEXT,
    status TEXT,
    first_triggered_at TIMESTAMPTZ,
    last_triggered_at TIMESTAMPTZ,
    raw_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ops_alert_run_idx
    ON ops_alert (diagnosis_run_id);
CREATE INDEX ops_alert_service_idx
    ON ops_alert (service_name, environment);

CREATE TABLE ops_evidence (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    diagnosis_run_id UUID NOT NULL REFERENCES ops_diagnosis_run (id) ON DELETE CASCADE,
    evidence_type TEXT NOT NULL,
    source TEXT,
    content TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ops_evidence_run_idx
    ON ops_evidence (diagnosis_run_id, evidence_type);

CREATE TABLE ops_diagnosis_report (
    id UUID PRIMARY KEY DEFAULT public.uuid_generate_v4(),
    diagnosis_run_id UUID NOT NULL UNIQUE REFERENCES ops_diagnosis_run (id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    report_url TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ops_diagnosis_report_status_idx
    ON ops_diagnosis_report (status);
