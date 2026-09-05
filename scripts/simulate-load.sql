-- ============================================================
-- OptiQuery Load Simulation Script
-- Generates slow queries that pg_stat_statements will capture
-- Run: psql -h localhost -U optiq_monitor -d optiq_target -f scripts/simulate-load.sql
-- ============================================================

-- Make sure pg_stat_statements is fresh
SELECT pg_stat_statements_reset();

-- ────────────────────────────────────────────────────────────
-- 1. SLOW QUERY: Full table scan on large dataset (no index)
--    This mimics a bad query with no WHERE clause optimization
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..20 LOOP
        PERFORM * FROM orders o
        JOIN users u ON u.id = o.user_id
        JOIN products p ON p.id = (o.user_id % 500) + 1
        WHERE o.total_amount > (i * 10)
        ORDER BY o.created_at DESC;
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- 2. SLOW QUERY: Cartesian join / nested loop without index
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..15 LOOP
        PERFORM COUNT(*)
        FROM orders o1, orders o2
        WHERE o1.user_id = o2.user_id
          AND o1.total_amount > o2.total_amount * 0.5;
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- 3. SLOW QUERY: LIKE pattern scan (can't use index)
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..20 LOOP
        PERFORM * FROM users
        WHERE email LIKE '%user' || (i % 100) || '%'
        OR name LIKE '%User%';
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- 4. SLOW QUERY: Subquery with no limit
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..15 LOOP
        PERFORM * FROM users u
        WHERE u.id IN (
            SELECT user_id FROM orders
            WHERE total_amount > (SELECT AVG(total_amount) FROM orders)
            AND status = 'completed'
        );
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- 5. SLOW QUERY: Sort without index (ORDER BY on unindexed col)
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..15 LOOP
        PERFORM * FROM orders
        ORDER BY total_amount DESC, created_at ASC
        LIMIT 100 OFFSET i * 50;
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- 6. SLOW QUERY: Aggregate without index
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..10 LOOP
        SELECT category, COUNT(*), AVG(price), SUM(price)
        FROM products
        WHERE price > (i * 5)
        GROUP BY category
        HAVING COUNT(*) > 1;
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- 7. MEDIUM QUERY: Inefficient UPDATE (full scan)
-- ────────────────────────────────────────────────────────────
UPDATE orders
SET status = 'reviewed'
WHERE user_id IN (
    SELECT id FROM users WHERE email LIKE '%user5%'
)
AND created_at < NOW() - INTERVAL '1 day';

-- Reset status back
UPDATE orders SET status = 'pending' WHERE status = 'reviewed';

-- ────────────────────────────────────────────────────────────
-- 8. SLOW QUERY: DISTINCT on large table
-- ────────────────────────────────────────────────────────────
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..10 LOOP
        PERFORM DISTINCT status FROM orders WHERE total_amount > i * 20;
    END LOOP;
END $$;

-- ────────────────────────────────────────────────────────────
-- Check what pg_stat_statements captured
-- ────────────────────────────────────────────────────────────
SELECT
    queryid,
    LEFT(query, 80) AS query_preview,
    calls,
    total_exec_time::numeric(10,2) AS total_ms,
    mean_exec_time::numeric(10,2) AS avg_ms,
    rows
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'optiq_target')
ORDER BY mean_exec_time DESC
LIMIT 15;

-- ============================================================
-- Done! Now check OptiQuery dashboard at http://localhost:3000
-- ============================================================
