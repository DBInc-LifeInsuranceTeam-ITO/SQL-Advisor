CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS awr_report (
    id BIGSERIAL PRIMARY KEY,
    filename TEXT NOT NULL,
    db_name TEXT,
    instance_name TEXT,
    snap_begin TEXT,
    snap_end TEXT,
    elapsed_time TEXT,
    db_time TEXT,
    raw_file_path TEXT,
    text_path TEXT,
    raw_text_preview TEXT,
    status TEXT NOT NULL,
    warnings JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS awr_section (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES awr_report(id),
    section_name TEXT NOT NULL,
    section_order INTEGER NOT NULL,
    raw_text TEXT,
    parsed_json JSONB
);

CREATE TABLE IF NOT EXISTS awr_sql_metric (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES awr_report(id),
    sql_id TEXT NOT NULL,
    section_name TEXT NOT NULL,
    rank_no INTEGER,
    elapsed_time NUMERIC,
    cpu_time NUMERIC,
    buffer_gets BIGINT,
    disk_reads BIGINT,
    executions BIGINT,
    rows_processed BIGINT,
    plan_hash_value BIGINT,
    module TEXT,
    sql_text TEXT,
    score NUMERIC,
    interpretation_hint TEXT
);

CREATE TABLE IF NOT EXISTS awr_wait_event (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES awr_report(id) ON DELETE CASCADE,
    wait_class TEXT,
    event_name TEXT NOT NULL,
    total_wait_time_sec NUMERIC,
    avg_wait_ms NUMERIC,
    db_time_percent NUMERIC
);

CREATE TABLE IF NOT EXISTS rag_chunk (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES awr_report(id),
    section_name TEXT,
    sql_id TEXT,
    chunk_text TEXT NOT NULL,
    chunk_type TEXT NOT NULL,
    metric_json JSONB,
    embedding VECTOR(1536),
    embedding_provider TEXT,
    embedding_model TEXT,
    embedding_dimension INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS analysis_result (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES awr_report(id),
    question TEXT,
    answer_json JSONB NOT NULL,
    result_type TEXT DEFAULT 'analysis',
    model TEXT,
    citations JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS feedback (
    id BIGSERIAL PRIMARY KEY,
    analysis_id BIGINT REFERENCES analysis_result(id),
    rating INTEGER,
    comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_awr_sql_metric_report_sql
    ON awr_sql_metric(report_id, sql_id);

CREATE INDEX IF NOT EXISTS idx_awr_wait_event_report
    ON awr_wait_event(report_id);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_report_sql
    ON rag_chunk(report_id, sql_id);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_report_type
    ON rag_chunk(report_id, chunk_type);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding
    ON rag_chunk USING ivfflat (embedding vector_cosine_ops);
