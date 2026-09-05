# Troubleshooting

Every entry here is a failure that has actually happened, with the symptom that
made it hard to recognise.

---

## The app dies at startup with a Hibernate `NullPointerException`

```
Could not obtain connection to query metadata
java.lang.NullPointerException: Cannot invoke
  "org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(...)"
  because the return value of "JdbcIsolationDelegate.sqlExceptionHelper()" is null
```

**PostgreSQL is not running.** Hibernate 6.5 throws while trying to *report* the
connection failure, so the real `Connection refused` never reaches the log. The
stack trace looks like a Hibernate bug and tells you nothing useful.

```bash
brew services list | grep postgres      # look for "none" or "stopped"
brew services start postgresql@16
```

Check the database before reading the trace. `start.sh` starts it for you.

---

## `/api/orgs` or `/api/auth/me` returns 404

**You are running a stale jar.** `start.sh` runs `java -jar target/*.jar`, and
`mvn compile` only updates `target/classes` — it never rebuilds the jar.

```bash
cd backend && mvn package -DskipTests
```

A 404 means the endpoint does not exist in the running build. A **401** means it
exists and you are not signed in, which is correct.

---

## `The 'bg-abyss' class does not exist`

**Stale frontend dev server.** Vite loads `tailwind.config.js` through PostCSS at
startup and does not always pick up a replaced config. The config is fine —
`npm run build` will compile it.

Restart `npm run dev`.

---

## Adding a teammate appears to do nothing

They do not have an account yet, so they became a **pending invite** rather than
a member. Pending invites are listed under the member list on **Team**, with a
dashed avatar and an × to withdraw. They become a full member the first time
they sign in.

If nothing appears there either, check that Firestore is configured.

---

## A new `AnalysisStatus` value causes a 500

```
new row for relation "slow_queries" violates check constraint
  "slow_queries_status_check"
```

**Hibernate never updates enum `CHECK` constraints.** `ddl-auto: update` created
the constraint listing the values that existed when the table was made, and adding
a value to the Java enum does not alter it. The code compiles, passes review, and
fails at runtime as an HTTP 500 rather than a validation error.

```sql
ALTER TABLE slow_queries DROP CONSTRAINT slow_queries_status_check;
ALTER TABLE slow_queries ADD CONSTRAINT slow_queries_status_check
  CHECK (status IN ('PENDING','ANALYZING','COMPLETED','FAILED',
                    'DISMISSED','APPLIED','QUOTA_EXCEEDED'));
```

Any future status value needs the same `ALTER`.

---

## Firestore looks healthy but nothing is stored

`/api/health` reporting `entitlement: BYO` does **not** prove Firestore works.
`EntitlementService` fails open by design: when Firestore is unreachable it
returns the unmetered default and logs at WARN, so a completely dead Firestore
looks identical to a healthy installation with no trial.

Check the log instead:

```bash
grep -E "Could not read the entitlement|Firestore unavailable|NOT_FOUND" backend.log
```

The commonest cause is a Firebase project where **Firestore was never created**.
Registering a web app does not provision it — you must create the database in
Native mode from the console.

---

## Sign-in fails with `CONFIGURATION_NOT_FOUND`

Firebase **Authentication has never been initialised** on the project. Creating
the project and registering a web app is not enough. Open
**Authentication → Get started** in the console and enable at least one provider.

Related errors:

| Error | Meaning |
|---|---|
| `auth/operation-not-allowed` | That provider is not enabled |
| `auth/unauthorized-domain` | Add the domain under Authentication → Settings → Authorized domains |
| `auth/popup-blocked` | The browser blocked the Google sign-in window |

---

## EXPLAIN shows "unavailable" for a query

Expected, not a fault. Postgres cannot plan every statement:

- **`DO` blocks and utility commands** have no plan of their own
- **Statements with bind parameters** need real values for `$1`, `$2`, and
  OptiQuery will not guess production values

The diagnosis falls back to the query text and your live schema, and the UI says
which case applies. The **Tables** tab still shows the schema evidence.

---

## Polling changes are ignored

If a change in Settings does not take effect on the next cycle, something is
reading a property directly rather than going through `SettingsService`. Every
`@Value` outside that class should be named `default*` and used only as a
fallback. See [Configuration](CONFIGURATION.md).

---

## Nothing is detected at all

1. Is `pg_stat_statements` installed **in the monitored database**?
   ```sql
   SELECT count(*) FROM pg_stat_statements;
   ```
2. Is your threshold higher than anything the database actually does? The
   sidebar shows the live value — *"Flags queries over 100 ms"*.
3. Has a poll run yet? Press **Check now** on the dashboard rather than waiting
   for the interval.
