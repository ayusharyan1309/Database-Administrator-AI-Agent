#!/bin/bash
# ============================================================
# OptiQuery Concurrent Load Simulator
# Runs multiple psql sessions in parallel to generate realistic load
# Usage: bash scripts/simulate-concurrent-load.sh
# ============================================================

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-optiq_target}"
DB_USER="${DB_USER:-optiq_monitor}"
# Falls back to the same variable docker-compose and the app use.
DB_PASS="${DB_PASS:-${OPTIQ_DB_PASSWORD:-changeme}}"

export PGPASSWORD="$DB_PASS"

PSQL="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -q"

echo "🚀 Starting OptiQuery Load Simulation..."
echo "   Target: $DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
echo ""

# ────────────────────────────────────────────────────────────
# Function: Run slow queries in a loop
# ────────────────────────────────────────────────────────────
run_bad_queries() {
    local thread_id=$1
    local iterations=${2:-10}
    
    for i in $(seq 1 $iterations); do
        $PSQL -c "
            -- Slow: Full table scan + join (no index on user_id)
            SELECT u.name, u.email, COUNT(o.id) as order_count, SUM(o.total_amount) as total_spent
            FROM users u
            LEFT JOIN orders o ON u.id = o.user_id
            WHERE u.email LIKE '%user${i}%'
            GROUP BY u.name, u.email
            ORDER BY total_spent DESC;
        " 2>/dev/null

        $PSQL -c "
            -- Slow: Subquery with aggregate
            SELECT * FROM orders
            WHERE total_amount > (SELECT AVG(total_amount) * ${i} FROM orders)
            AND user_id IN (SELECT id FROM users WHERE name LIKE '%User%')
            ORDER BY created_at DESC;
        " 2>/dev/null

        sleep 0.$(( RANDOM % 3 + 1 ))
    done
}

# ────────────────────────────────────────────────────────────
# Function: Run medium queries
# ────────────────────────────────────────────────────────────
run_medium_queries() {
    local thread_id=$1
    local iterations=${2:-15}
    
    for i in $(seq 1 $iterations); do
        $PSQL -c "
            SELECT category, COUNT(*) as cnt, AVG(price) as avg_price
            FROM products
            GROUP BY category
            HAVING COUNT(*) > ${i};
        " 2>/dev/null

        $PSQL -c "
            SELECT DISTINCT status, COUNT(*)
            FROM orders
            GROUP BY status;
        " 2>/dev/null

        sleep 0.$(( RANDOM % 5 + 1 ))
    done
}

# ────────────────────────────────────────────────────────────
# Function: Run analytical queries (heavy)
# ────────────────────────────────────────────────────────────
run_analytical_queries() {
    local thread_id=$1
    local iterations=${2:-8}
    
    for i in $(seq 1 $iterations); do
        $PSQL -c "
            -- Heavy: Window function + sort
            SELECT u.name, u.email,
                   RANK() OVER (ORDER BY SUM(o.total_amount) DESC) as rank,
                   SUM(o.total_amount) as total_spent,
                   COUNT(o.id) as orders
            FROM users u
            JOIN orders o ON u.id = o.user_id
            GROUP BY u.name, u.email
            ORDER BY rank
            LIMIT 50 OFFSET $(( i * 10 ));
        " 2>/dev/null

        $PSQL -c "
            -- Heavy: Correlated subquery
            SELECT u.name, 
                   (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count,
                   (SELECT MAX(total_amount) FROM orders o WHERE o.user_id = u.id) as max_order
            FROM users u
            WHERE u.id % $(( i + 1 )) = 0;
        " 2>/dev/null

        sleep 1
    done
}

# ────────────────────────────────────────────────────────────
# Reset pg_stat_statements for clean capture
# ────────────────────────────────────────────────────────────
echo "📊 Resetting pg_stat_statements..."
$PSQL -c "SELECT pg_stat_statements_reset();" 2>/dev/null
echo ""

# ────────────────────────────────────────────────────────────
# Launch concurrent threads
# ────────────────────────────────────────────────────────────
echo "🔥 Launching 5 concurrent load threads..."
echo ""

# Thread 1-2: Bad queries (slow, no index)
run_bad_queries 1 12 &
run_bad_queries 2 10 &

# Thread 3-4: Medium queries
run_medium_queries 3 15 &
run_medium_queries 4 12 &

# Thread 5: Heavy analytical queries
run_analytical_queries 5 8 &

echo "   Thread 1-2: Slow JOIN/scan queries (22 total)"
echo "   Thread 3-4: Medium GROUP BY queries (27 total)"
echo "   Thread 5:   Heavy analytical queries (8 total)"
echo ""

# Wait for all threads
wait

echo ""
echo "✅ Load simulation complete!"
echo ""
echo "📊 Checking captured queries..."
echo ""

$PSQL -c "
    SELECT
        queryid,
        LEFT(query, 70) AS query,
        calls,
        ROUND(total_exec_time::numeric, 2) AS total_ms,
        ROUND(mean_exec_time::numeric, 2) AS avg_ms,
        rows
    FROM pg_stat_statements
    WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '$DB_NAME')
      AND query NOT LIKE '%pg_stat_statements%'
    ORDER BY mean_exec_time DESC
    LIMIT 15;
"

echo ""
echo "🎯 Now check OptiQuery dashboard at http://localhost:3000"
echo "   Backend should detect these as slow queries on next poll cycle."
echo ""
