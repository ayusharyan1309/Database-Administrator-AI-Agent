#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════
#  OptiQuery — One-command launcher (using screen)
# ═══════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/backend"
FRONTEND_DIR="$SCRIPT_DIR/frontend"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "${CYAN}[optiq]${NC} $*"; }
ok()   { echo -e "${GREEN}[optiq]${NC} ✅ $*"; }
warn() { echo -e "${YELLOW}[optiq]${NC} ⚠️  $*"; }
err()  { echo -e "${RED}[optiq]${NC} ❌ $*"; }

stop_existing() {
    # Kill any existing screen sessions
    screen -S optiq-backend -X quit 2>/dev/null || true
    screen -S optiq-frontend -X quit 2>/dev/null || true
    # Kill any orphaned java/vite processes on our ports
    kill $(lsof -ti :8080) 2>/dev/null || true
    kill $(lsof -ti :3000) 2>/dev/null || true
    sleep 1
}

echo ""
echo -e "${BOLD}⚡ OptiQuery — AI-DBA${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ── 0. Stop existing services ──
log "Stopping any existing services..."
stop_existing

# ── 1. Check & start PostgreSQL ──
PG_BIN=""
if [[ -d /opt/homebrew/opt/postgresql@16/bin ]]; then
    PG_BIN="/opt/homebrew/opt/postgresql@16/bin"
elif command -v pg_isready &>/dev/null; then
    PG_BIN="$(dirname "$(which pg_isready)")"
fi

if [[ -z "$PG_BIN" ]]; then
    err "PostgreSQL not found. Install: brew install postgresql@16"
    exit 1
fi

if ! brew services list 2>/dev/null | grep -q "started.*postgresql"; then
    log "Starting PostgreSQL..."
    brew services start postgresql@16 2>/dev/null || true
    sleep 3
fi

PG_USER="$(whoami)"

# Enable pg_stat_statements if needed
if ! "$PG_BIN/psql" -U "$PG_USER" -d postgres -c "SELECT 1 FROM pg_stat_statements LIMIT 0" &>/dev/null; then
    log "Enabling pg_stat_statements..."
    CONF_FILE=$("$PG_BIN/psql" -U "$PG_USER" -d postgres -t -c "SHOW config_file" 2>/dev/null | xargs)
    if [[ -n "$CONF_FILE" ]] && [[ -f "$CONF_FILE" ]]; then
        sed -i '' "s/^#shared_preload_libraries = .*/shared_preload_libraries = 'pg_stat_statements'/" "$CONF_FILE" 2>/dev/null || true
        brew services restart postgresql@16 2>/dev/null || true
        sleep 3
    fi
fi

# Create DB if needed
if ! "$PG_BIN/psql" -U "$PG_USER" -d postgres -t -c "SELECT 1 FROM pg_database WHERE datname='optiq_target'" 2>/dev/null | grep -q 1; then
    log "Creating database..."
    "$PG_BIN/createuser" optiq_monitor 2>/dev/null || true
    "$PG_BIN/createdb" optiq_target -O optiq_monitor 2>/dev/null || true
    "$PG_BIN/psql" -U "$PG_USER" -d optiq_target -c "
        CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
        CREATE TABLE IF NOT EXISTS users (id SERIAL PRIMARY KEY, email VARCHAR(255) NOT NULL, name VARCHAR(100), created_at TIMESTAMP DEFAULT NOW());
        CREATE TABLE IF NOT EXISTS orders (id SERIAL PRIMARY KEY, user_id INTEGER NOT NULL, total_amount DECIMAL(10,2), status VARCHAR(50) DEFAULT 'pending', created_at TIMESTAMP DEFAULT NOW());
        CREATE TABLE IF NOT EXISTS products (id SERIAL PRIMARY KEY, name VARCHAR(255), price DECIMAL(10,2), category VARCHAR(100), created_at TIMESTAMP DEFAULT NOW());
        INSERT INTO users (email, name) SELECT 'user'||g||'@example.com','User '||g FROM generate_series(1,1000) g ON CONFLICT DO NOTHING;
        INSERT INTO orders (user_id, total_amount, status) SELECT (random()*999+1)::int,(random()*500)::decimal(10,2),CASE WHEN random()>0.5 THEN 'completed' ELSE 'pending' END FROM generate_series(1,5000) g ON CONFLICT DO NOTHING;
        INSERT INTO products (name, price, category) SELECT 'Product '||g,(random()*100)::decimal(10,2),CASE (g%5) WHEN 0 THEN 'Electronics' WHEN 1 THEN 'Books' WHEN 2 THEN 'Clothing' WHEN 3 THEN 'Food' WHEN 4 THEN 'Toys' END FROM generate_series(1,500) g ON CONFLICT DO NOTHING;
    " 2>&1 | tail -3
fi
ok "PostgreSQL ready"

# ── 2. Build backend JAR ──
if [[ ! -f "$BACKEND_DIR/target/optiq-backend-0.1.0-SNAPSHOT.jar" ]]; then
    log "Building backend JAR..."
    (cd "$BACKEND_DIR" && mvn package -DskipTests -q) || { err "Build failed"; exit 1; }
    ok "Backend JAR built"
fi

# ── 3. Create .env if missing ──
if [[ ! -f "$SCRIPT_DIR/.env" ]]; then
    cp "$SCRIPT_DIR/.env.example" "$SCRIPT_DIR/.env" 2>/dev/null || true
    warn "Created .env — edit it to add your OPENAI_API_KEY"
fi

# ── 4. Start Backend in screen ──
log "Starting backend..."
screen -dmS optiq-backend bash -c "cd $BACKEND_DIR && java -jar target/*.jar > /tmp/optiq-backend.log 2>&1"

# Wait for backend
for i in $(seq 1 30); do
    if curl -s http://localhost:8080/api/health &>/dev/null; then
        ok "Backend ready on http://localhost:8080"
        break
    fi
    sleep 1
done

# ── 5. Install frontend deps if needed ──
if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    log "Installing frontend dependencies..."
    (cd "$FRONTEND_DIR" && npm install --silent) || { err "npm install failed"; exit 1; }
fi

# ── 6. Start Frontend in screen ──
log "Starting frontend..."
screen -dmS optiq-frontend bash -c "cd $FRONTEND_DIR && npx vite --host > /tmp/optiq-frontend.log 2>&1"
sleep 3
ok "Frontend ready on http://localhost:3000"

# ── Done ──
echo ""
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}${BOLD}⚡ All services running!${NC}"
echo ""
echo -e "  ${BOLD}Dashboard:${NC}  http://localhost:3000"
echo -e "  ${BOLD}Backend API:${NC} http://localhost:8080"
echo -e "  ${BOLD}Settings:${NC}   http://localhost:3000 (click Settings)"
echo ""
echo -e "  ${BOLD}Manage:${NC}"
echo "    screen -r optiq-backend    # View backend logs"
echo "    screen -r optiq-frontend   # View frontend logs"
echo "    screen -list               # List all sessions"
echo ""
echo -e "  Logs: /tmp/optiq-backend.log /tmp/optiq-frontend.log"
echo -e "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
