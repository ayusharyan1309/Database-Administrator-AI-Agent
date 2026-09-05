# Development

## Running locally

```bash
# PostgreSQL 16 with pg_stat_statements
brew services start postgresql@16

# Backend — from either the repo root or backend/
cd backend && mvn spring-boot:run

# Frontend
cd frontend && npm install && npm run dev
```

Neither Firebase nor a `.env` is required. Without Firebase the app runs with no
sign-in and no Firestore, which is the fastest way to work on the query pipeline.

### Rebuild the jar, not just the classes

`start.sh` runs `java -jar target/*.jar`. `mvn compile` updates
`target/classes` and leaves the jar untouched, so the running app keeps serving
the old build — usually noticed as a 404 on a route you just added.

```bash
mvn package -DskipTests
```

---

## Conventions

### Configuration goes through `SettingsService`

Every `@Value` outside that class is named `default*` and used only as a
fallback. To make something configurable, add a `getX(fallback)` accessor rather
than reading the property where you need it.

The codebase previously had one threshold in three places and read the wrong one.
See [Configuration](CONFIGURATION.md).

### Presentation logic lives in `frontend/src/lib/`

`query.ts` holds duration formatting, the heat scale, triage states, fix
summarising, schema parsing and EXPLAIN-failure interpretation. Components render;
they do not decide.

`orgs.ts` and the API helpers in `api.ts` follow the same split.

### TypeScript runs with `noUncheckedIndexedAccess`

Array indexing and regex groups return `T | undefined`. Use `?? fallback` rather
than a non-null assertion.

### Colour is semantic

The `heat` ramp (`cool → warm → hot → crit`) means **cost**, and `signal` is the
product accent. Do not use heat colours decoratively — a red row means expensive,
not "important".

### Copy

Sentence case. Name the action: *"Analyze again"*, not *"Retry"*. No tracked-out
all-caps labels. Errors say what happened and what to do about it.

---

## Adding an `AnalysisStatus` value

Three steps, and the third is easy to miss:

1. Add it to `AnalysisStatus.java`
2. Add it to `AnalysisStatus` in `frontend/src/types.ts` and give it a triage
   state in `lib/query.ts`
3. **`ALTER` the database check constraint** — Hibernate never updates it

```sql
ALTER TABLE slow_queries DROP CONSTRAINT slow_queries_status_check;
ALTER TABLE slow_queries ADD CONSTRAINT slow_queries_status_check
  CHECK (status IN (...existing..., 'YOUR_NEW_VALUE'));
```

Skipping step 3 compiles cleanly and fails at runtime with an HTTP 500.

---

## Tests

There is no test harness yet. Verification so far has been done by running the
app on a spare port and exercising the endpoints.

If you add one, start with `EntitlementService`: the reserve-then-refund logic is
the part where a subtle bug costs real money, and it is straightforward to drive
concurrently.

---

## Working against Firebase

To exercise auth and teams you need a Firebase project with Firestore in Native
mode. See [Configuration](CONFIGURATION.md).

Useful during development:

```bash
# Create a throwaway account and get a token
curl -s -X POST \
  "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$VITE_FIREBASE_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"secret123","returnSecureToken":true}'

# Call the API as that user
curl -H "Authorization: Bearer $ID_TOKEN" localhost:8080/api/orgs
```

Delete the account afterwards with `accounts:delete` and the same token — test
users accumulate quickly, and a stale one holding an organization makes later
runs confusing.

### The display name is captured at token mint time

If you set `displayName` after signing up, the token you already hold does not
carry it, and anything recorded with that token shows an email instead of a name.
Sign in again to get a token with the name claim.

---

## Screenshots

`docs/images/` is generated against a local instance with demo data. If the UI
changes materially, regenerate them so the README does not misrepresent the
product.
