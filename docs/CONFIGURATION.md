# Configuration

OptiQuery has three places a value can come from. Which one you use is not a
matter of taste — it decides whether the value is authoritative, and whether it
ends up in your git history.

| Source | Authoritative? | Committed? | Use for |
|---|---|---|---|
| **Settings**, in the app | Yes, at runtime | Stored in the database | Anything a user changes |
| `application.yml` | Only as a first-run seed | **Yes**, and packaged into the jar | Non-secret defaults and paths |
| Environment variables | Yes, where nothing overrides them | No | Secrets |

> **Never put a secret in `application.yml`.** `src/main/resources` is tracked by
> git *and* baked into the jar, so a key there ships to anyone who gets the
> build.

---

## Settings

Everything the product actually runs on lives here, editable in the app under
**Settings**, and stored in the database.

| Key | Meaning |
|---|---|
| `db.type`, `db.host`, `db.port`, `db.name` | The database being watched |
| `db.username`, `db.password` | Read-only credentials for it |
| `db.jdbc_url` | Full JDBC URL, overriding the parts above |
| `ai.provider`, `ai.base_url`, `ai.model`, `ai.api_key` | Any OpenAI-compatible endpoint |
| `notify.enabled`, `notify.type`, `notify.webhook_url` | Slack, Discord, Teams, or a plain webhook |
| `polling.interval_ms` | How often to sample `pg_stat_statements` |
| `polling.slow_threshold_ms` | The mean time above which a query is flagged |
| `polling.max_queries_per_poll` | Most queries to analyze in one cycle |

### Changes apply without a restart

The polling loop re-reads its interval, threshold and batch size from Settings
**after every completed cycle**. Save a change and the next check uses it.

This is why the scheduler is a `SchedulingConfigurer` with a dynamic `Trigger`
rather than `@Scheduled(fixedDelayString = ...)`. That annotation resolves its
interval once, when the bean is created, so a change would not apply until the
next restart.

A 1-second floor stops a bad value from spinning against your database.

---

## `application.yml`

`backend/src/main/resources/application.yml` holds non-secret defaults, all of
them overridable by an environment variable:

```yaml
optiq:
  firebase:
    credentials-file: ${OPTIQ_FIREBASE_CREDENTIALS:...json}
    project-id:       ${OPTIQ_FIREBASE_PROJECT_ID:...}
  admin:
    token:            ${OPTIQ_ADMIN_TOKEN:}      # secret — supply via env
  polling:
    interval-ms:              ${OPTIQ_POLL_INTERVAL_MS:300000}
    slow-query-threshold-ms:  ${OPTIQ_SLOW_THRESHOLD_MS:500}
    max-queries-per-poll:     ${OPTIQ_MAX_QUERIES_PER_POLL:10}
```

The polling values here are **seeds**. They populate Settings on first run and
are never read again once the rows exist — editing them later changes nothing.

### A rule for contributors

Every `@Value` outside `SettingsService` is named `default*` and used only as a
fallback. Do not read a configurable value from a property directly; add a
`getX(fallback)` accessor to `SettingsService` instead.

This convention exists because the codebase previously had the same threshold in
three places, and the one the user edited was the one nothing read. The daemon
filtered at 500 ms while the UI reported 500 ms and Settings said 100 ms.

---

## Environment variables

Only secrets, and only the ones you need:

| Variable | Purpose |
|---|---|
| `OPTIQ_ADMIN_TOKEN` | Guards `/api/admin/**`. **Unset means those routes return 404** rather than sitting unprotected. |
| `OPTIQ_FIREBASE_CREDENTIALS` | Path to the service account JSON |
| `OPTIQ_FIREBASE_PROJECT_ID` | Firebase project id |
| `OPENAI_API_KEY` | First-run seed only; the real key lives in Settings |

There is no `.env` for the backend. Docker Compose reads one if present — that
is the only path by which such a file reaches the app, since `mvn spring-boot:run`
does not read `.env` at all.

### Where the service account file may live

The path may be relative. It is resolved against the working directory, its
parent, and `./backend`, so the app starts whether you run from the repository
root or from `backend/`. The file it loaded is logged at startup:

```
Loading Firebase credentials from /path/to/serviceAccountKey.json
Firestore connected (project: your-project)
```

An absolute path is used as given.

---

## Firebase

Both halves are optional and independently gated.

**Frontend** — `frontend/.env`, read at build time by Vite:

```bash
VITE_FIREBASE_API_KEY=
VITE_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=your-project
VITE_FIREBASE_APP_ID=
```

These are public by design. They identify the project; they do not grant access.
Firestore security rules and the backend enforce that. Leave them unset and the
app runs with no sign-in at all.

**Backend** — the service account. Leave it unset and Firestore is disabled: no
login required, teams unavailable, and analysis unmetered. The app logs
`Firestore disabled — no Firebase credentials configured` and carries on.

### Hosted trials

With `OPTIQ_ADMIN_TOKEN` set, the vendor-side endpoints become available:

```bash
curl -X POST "localhost:8080/api/admin/entitlement/grant?analyses=50&days=14&by=you" \
     -H "X-Optiq-Admin-Token: $OPTIQ_ADMIN_TOKEN"
```

Also `/extend?days=7&analyses=0`, `/revoke`, and `GET /entitlement`.

Two behaviours worth knowing:

- **Reserve before spending.** The counter increments in a transaction *before*
  the provider call, so concurrent analyses cannot each decide they are the last
  one. A failed provider call refunds the reservation.
- **Fail open, never closed.** If Firestore is unreachable, analysis proceeds
  unmetered. A billing system that takes the product down during its own outage
  is worse than one that occasionally lets an analysis through.

When a trial runs out, detection keeps running. Queries are still polled,
measured and ranked — they get `QUOTA_EXCEEDED` instead of a diagnosis, so you
still see which query costs you most, just not the fix.
