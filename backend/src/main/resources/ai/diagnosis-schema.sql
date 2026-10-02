-- Explicit initialization on the AI database ONLY, never trading JPA startup.
CREATE SCHEMA IF NOT EXISTS ai;
CREATE TABLE IF NOT EXISTS ai.diagnoses (
 id bigserial PRIMARY KEY, source_namespace varchar(64) NOT NULL, event_key varchar(64) UNIQUE NOT NULL,
 transaction_id bigint NOT NULL, order_id bigint, tx_hash varchar(66),
 trigger_type text NOT NULL DEFAULT 'REVIEW_REQUIRED', trigger_policy_version integer NOT NULL DEFAULT 1,
 job_status text NOT NULL CHECK(job_status IN ('QUEUED','RUNNING','COMPLETED','FAILED','INTERRUPTED','SKIPPED')),
 detected_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, completed_at timestamptz,
 available_at timestamptz NOT NULL DEFAULT now(), claim_token varchar(36), claimed_by varchar(36), lease_until timestamptz,
 admission_attempts integer NOT NULL DEFAULT 0, actor_user_id bigint,
 target_stale boolean NOT NULL DEFAULT false, error_code varchar(80),
 result jsonb CHECK(result IS NULL OR octet_length(result::text)<=65536), retention_expires_at timestamptz
);
CREATE INDEX IF NOT EXISTS ai_diagnoses_queue ON ai.diagnoses(job_status,available_at,id);
CREATE TABLE IF NOT EXISTS ai.diagnosis_daily_usage (
 usage_date date PRIMARY KEY, starts integer NOT NULL DEFAULT 0 CHECK(starts>=0)
);
