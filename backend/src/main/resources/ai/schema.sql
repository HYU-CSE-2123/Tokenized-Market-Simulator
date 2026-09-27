-- AI 전용 DB에서만 명시적으로 실행. 거래 schema.sql/JPA와 독립적이다.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS ai;
CREATE TABLE IF NOT EXISTS ai.indexes (
    id varchar(64) PRIMARY KEY, model text NOT NULL, dimensions integer NOT NULL CHECK (dimensions=1536),
    active boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ai_one_active_index ON ai.indexes(active) WHERE active;
CREATE TABLE IF NOT EXISTS ai.documents (
    index_id varchar(64) NOT NULL REFERENCES ai.indexes(id), path text NOT NULL,
    source_hash varchar(64) NOT NULL, version text NOT NULL, title text NOT NULL,
    minimum_role text NOT NULL CHECK (minimum_role IN ('USER','ADMIN')),
    domain text NOT NULL, type text NOT NULL, updated_at text NOT NULL,
    PRIMARY KEY(index_id,path)
);
CREATE TABLE IF NOT EXISTS ai.chunks (
    index_id varchar(64) NOT NULL, id varchar(64) NOT NULL, path text NOT NULL,
    heading text NOT NULL, content text NOT NULL, embedding vector(1536) NOT NULL,
    PRIMARY KEY(index_id,id), FOREIGN KEY(index_id,path) REFERENCES ai.documents(index_id,path)
);

