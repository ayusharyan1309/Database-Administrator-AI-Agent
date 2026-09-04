-- Enable the pg_stat_statements extension (required by OptiQuery)
-- This must be loaded via shared_preload_libraries in postgresql.conf,
-- but we can also try to create the extension here.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Create some sample tables for demo purposes
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    total_amount DECIMAL(10, 2),
    status VARCHAR(50) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255),
    price DECIMAL(10, 2),
    category VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW()
);

-- Insert sample data
INSERT INTO users (email, name)
SELECT
    'user' || g || '@example.com',
    'User ' || g
FROM generate_series(1, 1000) AS g
ON CONFLICT DO NOTHING;

INSERT INTO orders (user_id, total_amount, status)
SELECT
    (random() * 999 + 1)::int,
    (random() * 500)::decimal(10,2),
    CASE WHEN random() > 0.5 THEN 'completed' ELSE 'pending' END
FROM generate_series(1, 5000) AS g
ON CONFLICT DO NOTHING;

INSERT INTO products (name, price, category)
SELECT
    'Product ' || g,
    (random() * 100)::decimal(10,2),
    CASE (g % 5)
        WHEN 0 THEN 'Electronics'
        WHEN 1 THEN 'Books'
        WHEN 2 THEN 'Clothing'
        WHEN 3 THEN 'Food'
        WHEN 4 THEN 'Toys'
    END
FROM generate_series(1, 500) AS g
ON CONFLICT DO NOTHING;

-- NOTE: pg_stat_statements requires shared_preload_libraries.
-- For local dev with Docker, you may need to add this to postgresql.conf:
--   shared_preload_libraries = 'pg_stat_statements'
-- The Docker postgres image doesn't support this directly,
-- so for production use, mount a custom postgresql.conf.
