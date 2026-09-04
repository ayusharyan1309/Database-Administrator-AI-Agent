# ⚡ OptiQuery — AI-DBA

**Autonomous database observability daemon.** Detects slow PostgreSQL queries, extracts execution plans, and uses LLMs to recommend optimization strategies.

> *"A Junior DBA in a box"* — reduces database debugging time from hours to minutes.

![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat&logo=openjdk&logoColor=white)
![Spring Boot 3](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F?style=flat&logo=springboot&logoColor=white)
![React 18](https://img.shields.io/badge/React-18-61DAFB?style=flat&logo=react&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.6-3178C6?style=flat&logo=typescript&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat&logo=postgresql&logoColor=white)
![LangChain4j](https://img.shields.io/badge/LangChain4j-0.35-412991?style=flat)

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        OptiQuery System                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐    ┌──────────────────┐    ┌──────────────┐   │
│  │  PostgreSQL  │───▶│  Spring Boot     │───▶│  LLM (GPT/Claude) │
│  │  (Target DB) │    │  Daemon          │    │  via LangChain4j   │
│  │  pg_stat_    │◀───│  ┌────────────┐  │    └──────────────┘   │
│  │  statements  │    │  │ Scheduler  │  │                        │
│  └─────────────┘    │  │ (5 min)    │  │    ┌──────────────┐   │
│                      │  └────────────┘  │───▶│  Slack        │   │
│                      │  ┌────────────┐  │    │  Webhook     │   │
│                      │  │ Schema     │  │    └──────────────┘   │
│                      │  │ Extractor  │  │                        │
│                      │  └────────────┘  │    ┌──────────────┐   │
│                      │  ┌────────────┐  │───▶│  REST API     │   │
│                      │  │ AI Engine  │  │    │  /api/queries │   │
│                      │  └────────────┘  │    └───────┬──────┘   │
│                      └──────────────────┘            │           │
│                                                      ▼           │
│                                              ┌──────────────┐   │
│                                              │  React        │   │
│                                              │  Dashboard    │   │
│                                              │  (TypeScript) │   │
│                                              └──────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites
- Java 21+
- Node.js 20+
- Docker & Docker Compose (recommended)
- An OpenAI or Anthropic API key

### Option 1: Docker Compose (recommended)

```bash
# Clone and enter the project
git clone https://github.com/yourname/optiq.git
cd optiq

# Set up environment
cp .env.example .env
# Edit .env with your API keys

# Start everything
docker compose up -d

# Open the dashboard
open http://localhost:3000
```

### Option 2: Local Development

```bash
# Start PostgreSQL (make sure pg_stat_statements is enabled)
docker run -d --name optiq-pg \
  -e POSTGRES_DB=optiq_target \
  -e POSTGRES_USER=optiq_monitor \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  postgres:16-alpine

# Start the backend
cd backend
export OPENAI_API_KEY=sk-...
mvn spring-boot:run

# Start the frontend
cd frontend
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000)

---

## 📦 Project Structure

```
optiq/
├── backend/                          # Java 21 + Spring Boot 3.3
│   ├── pom.xml
│   └── src/main/java/com/optiq/
│       ├── OptiQueryApplication.java # Entry point
│       ├── config/
│       │   ├── AiConfig.java         # LLM provider toggle (OpenAI/Anthropic)
│       │   └── TargetDatabaseConfig.java  # Target DB connection
│       ├── model/
│       │   ├── SlowQuery.java        # Detected slow query entity
│       │   ├── AiAnalysis.java       # AI diagnosis entity
│       │   ├── AnalysisResult.java   # LLM structured response DTO
│       │   ├── RiskLevel.java        # LOW/MEDIUM/HIGH
│       │   └── AnalysisStatus.java   # PENDING→ANALYZING→COMPLETED
│       ├── repository/
│       │   └── SlowQueryRepository.java
│       ├── service/
│       │   ├── PostgresMonitorService.java  # pg_stat_statements polling, EXPLAIN
│       │   ├── AiDiagnosticService.java     # Prompt engineering + LLM call
│       │   ├── AnalysisOrchestrator.java    # Full pipeline orchestration
│       │   └── NotificationService.java     # Slack webhook alerts
│       ├── scheduler/
│       │   └── SlowQueryPoller.java   # @Scheduled daemon
│       ├── controller/
│       │   ├── SlowQueryController.java  # REST API
│       │   └── HealthController.java
│       └── dto/
│           ├── SlowQueryDto.java
│           └── SlowQueriesResponse.java
│
├── frontend/                         # React 18 + TypeScript + Vite
│   ├── package.json
│   ├── src/
│   │   ├── types.ts                  # TypeScript interfaces
│   │   ├── api.ts                    # API client
│   │   ├── App.tsx                   # Router + Layout
│   │   └── pages/
│   │       ├── Dashboard.tsx         # Overview: stats + query table
│   │       ├── QueryDetail.tsx       # Split-screen: SQL + AI analysis
│   │       └── SettingsPage.tsx      # System health + config
│
├── scripts/
│   └── init-pgstat.sql               # DB setup + sample data
├── docker-compose.yml
└── .env.example
```

---

## 🔧 Configuration

| Variable | Default | Description |
|---|---|---|
| `OPTIQ_AI_PROVIDER` | `openai` | `openai` or `anthropic` |
| `OPENAI_API_KEY` | — | Required if using OpenAI |
| `ANTHROPIC_API_KEY` | — | Required if using Anthropic |
| `OPTIQ_POLL_INTERVAL_MS` | `300000` | Polling interval (5 min) |
| `OPTIQ_SLOW_THRESHOLD_MS` | `500` | Slow query threshold |
| `OPTIQ_SLACK_WEBHOOK_URL` | — | Slack incoming webhook |
| `OPTIQ_SLACK_ENABLED` | `false` | Enable/disable Slack alerts |

---

## 🔒 Safety Features

- **Read-only database connection** — The daemon connects with a read-only PostgreSQL user, preventing accidental data modification.
- **Never sends PII to LLMs** — Only table schemas (column names/types) and SQL structure are sent. No row data.
- **No automatic DDL execution** — Suggestions are displayed for human review. Never auto-applies `CREATE INDEX`.
- **Idempotent alerts** — Identical queries are fingerprinted and grouped. One alert per unique slow query, preventing notification fatigue.

---

## 🧠 AI Diagnostic Engine

The core innovation: a carefully engineered system prompt that forces the LLM to return structured JSON:

```json
{
  "root_cause": "Sequential scan detected on users table because user_id column lacks an index.",
  "suggested_sql": "CREATE INDEX CONCURRENTLY idx_users_email ON users (email);",
  "confidence_score": 92,
  "risk_level": "LOW"
}
```

**Prompt engineering highlights:**
- System prompt defines strict JSON schema
- Temperature set to 0.2 for deterministic, DBA-style advice
- Low confidence scores + HIGH risk flags → human review required
- All responses are stored for audit trail

---

## 🎯 Portfolio Pitch

**For interviews and resumes:**

> Built **OptiQuery**, an autonomous database observability daemon that proactively detects slow PostgreSQL queries using `pg_stat_statements`, extracts execution plans, and leverages LLMs (GPT-4o/Claude) via LangChain4j to recommend specific optimization strategies. The system demonstrates safe LLM orchestration (RAG for infrastructure), database internals expertise (execution plans, index design), and production-grade safety (read-only connections, no PII leakage, human-in-the-loop approval). Built with Java 21, Spring Boot 3.3, React/TypeScript, and Docker.

---

## 📊 API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/queries/slow` | List slow queries (paginated) |
| `GET` | `/api/queries/slow/:id` | Query detail + AI analysis |
| `POST` | `/api/queries/:id/dismiss` | Dismiss a query |
| `POST` | `/api/queries/:id/reanalyze` | Re-trigger AI analysis |
| `GET` | `/api/queries/stats` | Overview statistics |
| `GET` | `/api/health` | Backend health check |

---

## 💰 Monetization Ideas

Package the Spring Boot daemon as a Docker container:
1. `docker pull optiq/daemon`
2. Plug in PostgreSQL URL + Slack Webhook + API key
3. $29/month license key for AI diagnostic engine
4. Self-hosted, no data leaves the customer's infrastructure

---

## 📝 License

MIT
