-- PostgreSQL 初始化脚本，启用 pgvector 扩展。
-- The pgvector extension is created here so later Flyway migrations only
-- need to manage tables and indexes.
CREATE EXTENSION IF NOT EXISTS vector;
