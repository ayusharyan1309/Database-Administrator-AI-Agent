# Architecture

## Two databases, two jobs

The distinction that governs everything else:

| | Role |
|---|---|
| **The monitored database** | Postgres. Read-only. `pg_stat_statements`, `EXPLAIN ANALYZE`, `information_schema`. This is the product's subject matter and has no substitute. |
| **OptiQuery's own state** | Teams, members, projects, activity, entitlements. Small documents, read constantly, written by the daemon. This is what Firestore holds. |

### Why they were separated

They used to be the same database. OptiQuery's `slow_queries`, `ai_analyses` and
`app_settings` tables sat inside the customer's database, next to their `orders`
and `users`.

That blocks the product from being sold: it requires write access to a production
database, and it inflates `pg_stat_statements` with the monitoring tool's own
queries. Moving OptiQuery's state to Firestore is what lets it ask for a
**read-only** connection.

---

## The pipeline

```
Poll  ──▶  Context  ──▶  Diagnose  ──▶  Store  ──▶  Notify
```

1. **Poll** — sample `pg_stat_statements` for queries above the threshold. The
   interval, threshold and batch size are re-read from Settings every cycle.
2. **Context** — extract referenced tables, snapshot their columns and indexes,
   run `EXPLAIN ANALYZE` where Postgres will allow it.
3. **Diagnose** — send query text, plan and schema to the configured LLM. This
   step is metered; see *Entitlements* below.
4. **Store** — one record per query fingerprint, so re-detection updates rather
   than duplicates.
5. **Notify** — Slack, Discord, Teams or a plain webhook.

### Ranking by time burned

The list is ordered by `totalExecTimeMs`, not mean time, because that is the cost
a team actually pays. A 20 ms query called fifty thousand times outranks a 900 ms
query called twice.

This has to be a server-side sort. Re-ranking a page fetched in mean-time order
would produce a wrong "top cost" list past page one — `sort=totalTime` is the
default, with `sort=meanTime` still available.

---

## Firestore layout

```
organizations/{orgId}              name, ownerUid, createdAt
  members/{uid}                    role: OWNER | ADMIN | MEMBER
  projects/{projectId}             name, environment, enabled, polling policy
  activity/{eventId}               queryId, action, actor, note, at
  private/entitlement              readable by members, writable only by the server
users/{uid}
  memberships/{orgId}              mirror: role, orgName
invites/{key}                      people added before they had an account
```

### Why it is shaped this way

**The membership mirror is denormalized on purpose.** A subcollection cannot
answer "which organizations is this person in?" without a collection-group
query. The mirror answers it in one read — and must be written in the same
`WriteBatch` as the member document, or roles silently diverge.

**Invites are separate from members.** Someone can be added before they have an
account. The invite is held under their email and redeemed on first sign-in, so
inviting a colleague never depends on them being there first. Someone who joins
by invite does not also get a personal team.

**Entitlements sit under `private/`** so members can read their plan but only the
server can change it.

---

## Authentication

Firebase ID token in `Authorization: Bearer`, verified by `FirebaseAuthFilter`,
with the caller published through a `ThreadLocal` that is cleared in a `finally`
block — otherwise a caller leaks onto the next request off the pool.

Two paths stay open: `/api/health`, because the dashboard renders its "daemon
unreachable" state when signed out, and `/api/admin`, which has its own token.

**Enforcement is conditional on Firebase being configured.** With no project, the
app runs exactly as it did before accounts existed. That is what lets this ship
without a flag day and keeps local development free of a cloud dependency.

---

## Team actions

OptiQuery does not execute SQL against a customer's database. A tool that writes
to a production schema is a different product with a different risk profile.

Instead, a person runs the fix and records that they did:

- `POST /api/queries/{id}/apply` — sets `APPLIED`, records who and an optional note
- `POST /api/queries/{id}/reopen` — undoes an apply or a dismissal
- `GET /api/queries/{id}/activity` — the trail for one query
- `GET /api/queries/activity` — latest action per query, one read for the whole list

`ActivityService.record()` never throws. An audit write failing must not undo the
action the user actually asked for; it logs at WARN.

Any role can act. Reading queries and acting on them is what a `MEMBER` is for;
`ADMIN` governs the team, not the work.

---

## Entitlements

Analysis is metered when OptiQuery is paying for it, and unmetered when the
customer brings their own key.

| Mode | Meaning |
|---|---|
| `BYO` | Customer's own provider and key. Not metered. The default. |
| `HOSTED_TRIAL` | OptiQuery's key, capped by analysis count and expiry. |
| `PAID` | OptiQuery's key, uncapped. |

**Reserve before spending.** The counter increments inside a transaction *before*
the provider call, so ten concurrent analyses cannot each read "99 of 100 used"
and all decide they are the last. A failed provider call refunds.

**Fail open.** Firestore unreachable means analysis proceeds unmetered. A billing
system that can take the product down during its own outage is worse than one
that occasionally lets an analysis through.

**Detection survives the trial.** An exhausted trial still polls, measures and
ranks — the query gets `QUOTA_EXCEEDED` rather than a diagnosis. A customer whose
trial ran out still sees which query costs them most, which is a better place to
ask for a key than an empty screen.

---

## Where this is going

### The customer-side agent

The daemon moves inside the customer's network. It holds the database
credentials and the AI key locally, and pushes findings out to Firestore.

Roughly half the existing backend already *is* the agent:

| Moves into the agent | Stays in the control plane |
|---|---|
| `PostgresMonitorService`, `SlowQueryPoller` | `SlowQueryController`, `SettingsController` |
| `AiDiagnosticService`, `AnalysisOrchestrator` | Teams, members, projects, invites |
| `AiConfig`, `TargetDatabaseConfig`, `PollingScheduleConfig` | Agent enrollment and token minting |

What this buys: no inbound firewall rules, no allowlisted egress IPs, and no
third party holding a production database password.

What it costs: agent identity becomes a real problem. An agent authenticates with
a Firebase custom token scoped to one project, and Firestore rules constrain it
by path — it can write findings for its own project and nothing else.

**The vendor's AI key must never ship to the agent.** It runs on the customer's
machine, where any embedded key can be pulled from config, memory, or a proxied
TLS session. Trial analysis therefore goes through a control-plane proxy that
holds the key server-side and meters exactly.

### Ordered plan

1. ~~Firebase Auth~~ — done
2. ~~Organizations, members, projects~~ — done
3. Move queries and analyses to Firestore; drop OptiQuery's tables out of the
   monitored database
4. Split the agent, add the `/api/agents/analyze` proxy
5. Per-project polling: claim-and-lease over project documents, so work survives
   restarts and one unreachable database backs itself off
6. Custom claims, invitations at scale, restricted projects

### Deliberately deferred

**Trial abuse prevention.** One trial per organization, and gating signup by
verified email domain — otherwise the same person gets unlimited trials from
fresh addresses. Not built; revisit before the first external customer.

### Open questions

- **One agent per database, or one watching several?** One-per-project is
  simpler and matches a container per database. Design enrollment so an agent
  can carry multiple bindings later if needed.
- **Where do Slack alerts fire from?** From the agent keeps the webhook local
  too, but then notification settings are per-agent rather than team-wide.
- **Does the browser read Firestore directly?** Once no customer secrets are in
  Firestore this becomes safe, and gives live dashboard updates with no polling.
  Until then, reads go through the backend, which holds the Admin SDK.
