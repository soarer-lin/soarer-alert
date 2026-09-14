-- The pgvector extension is created here so later Flyway migrations only
-- need to manage tables and indexes.
CREATE EXTENSION IF NOT EXISTS vector;
