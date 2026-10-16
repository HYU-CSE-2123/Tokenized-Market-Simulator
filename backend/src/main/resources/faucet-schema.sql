CREATE TABLE IF NOT EXISTS faucet_policy_versions (
 version VARCHAR(64) PRIMARY KEY, content_hash VARCHAR(64) NOT NULL,
 content VARCHAR(512) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS faucet_account_usage (
 user_id BIGINT PRIMARY KEY, grant_count BIGINT NOT NULL CHECK(grant_count>=0), last_day DATE
);
CREATE TABLE IF NOT EXISTS faucet_budget_usage (
 id INTEGER PRIMARY KEY CHECK(id=1), active_version VARCHAR(64) NOT NULL,
 grant_count BIGINT NOT NULL CHECK(grant_count>=0), spent DECIMAL(30,18) NOT NULL CHECK(spent>=0), initialized BOOLEAN NOT NULL
);
CREATE TABLE IF NOT EXISTS faucet_claims (
 grant_id VARCHAR(36) PRIMARY KEY REFERENCES faucet_grants(id), user_id BIGINT NOT NULL,
 key_fingerprint VARCHAR(64) NOT NULL, request_fingerprint VARCHAR(64) NOT NULL,
 policy_version VARCHAR(64) NOT NULL REFERENCES faucet_policy_versions(version),
 received_amount DECIMAL(30,18) NOT NULL, balance DECIMAL(30,18) NOT NULL, granted_at TIMESTAMP NOT NULL,
 UNIQUE(user_id,key_fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_faucet_grant_user_time ON faucet_grants(user_id,created_at,id);
