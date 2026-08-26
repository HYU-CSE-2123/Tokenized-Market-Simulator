-- 삼성전자 가격 추종 토큰 거래소 스키마 (기획서 §11)

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    login_id VARCHAR(50) UNIQUE,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    google_sub VARCHAR(255) UNIQUE,
    nickname VARCHAR(100) NOT NULL,
    wallet_address VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_users_login_method CHECK (
        (login_id IS NOT NULL AND password_hash IS NOT NULL)
        OR google_sub IS NOT NULL
    )
);

-- 기존 Phase 0 스키마로 생성된 로컬 DB 호환. 신규 DB에서는 위 CREATE 정의가 적용된다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_id VARCHAR(50);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS google_sub VARCHAR(255);
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_login_id ON users (login_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_google_sub ON users (google_sub);

CREATE TABLE IF NOT EXISTS assets (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    contract_address VARCHAR(255),
    decimals INT NOT NULL
);

CREATE TABLE IF NOT EXISTS price_ticks (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    price DECIMAL(30, 8) NOT NULL,
    source VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    input_amount DECIMAL(30, 18) NOT NULL,
    expected_output_amount DECIMAL(30, 18),
    status VARCHAR(30) NOT NULL,
    tx_hash VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS trades (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price DECIMAL(30, 8) NOT NULL,
    base_amount DECIMAL(30, 18) NOT NULL,
    quote_amount DECIMAL(30, 18) NOT NULL,
    fee DECIMAL(30, 18) NOT NULL,
    tx_hash VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS blockchain_transactions (
    id BIGSERIAL PRIMARY KEY,
    tx_hash VARCHAR(255) UNIQUE NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    block_number BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP
);

-- 초기 자산 데이터 (기획서 §11.2)
INSERT INTO assets (symbol, name, contract_address, decimals)
VALUES ('mKRW', 'Mock Korean Won', NULL, 18)
ON CONFLICT (symbol) DO NOTHING;

INSERT INTO assets (symbol, name, contract_address, decimals)
VALUES ('mSEC', 'Samsung Electronics Price-Tracking Token', NULL, 18)
ON CONFLICT (symbol) DO NOTHING;
