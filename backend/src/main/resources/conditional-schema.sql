CREATE TABLE IF NOT EXISTS conditional_orders (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 side VARCHAR(10) NOT NULL CHECK (side IN ('BUY','SELL')),
 input_amount NUMERIC(30,18) NOT NULL CHECK (input_amount>0),
 target_price_e8 NUMERIC(78,0) NOT NULL CHECK (target_price_e8>0),
 state VARCHAR(30) NOT NULL CHECK (state IN ('WAITING','PREPARING','EXECUTING','SUCCEEDED','FAILED','CANCELLED','EXPIRED','REVIEW_REQUIRED')),
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 next_evaluation_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 execution_order_id BIGINT UNIQUE REFERENCES orders(id),
 worker_owner VARCHAR(36), reason_code VARCHAR(80),
 request_key_hash VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(user_id,request_key_hash)
);
CREATE INDEX IF NOT EXISTS idx_conditional_due ON conditional_orders(state,next_evaluation_at,created_at,id);
CREATE INDEX IF NOT EXISTS idx_conditional_owner ON conditional_orders(user_id,created_at,id);
