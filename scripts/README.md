# Scripts

## `init-pgstat.sql`

Runs automatically on first start of the Docker Postgres container. Enables
`pg_stat_statements` and creates the demo `users` / `orders` / `products`
tables that the load simulators query.

It only runs when the data volume is empty. To re-run it:

```bash
docker compose down -v && docker compose up -d
```

> `CREATE EXTENSION` is not enough on its own — `pg_stat_statements` must also
> be preloaded. `docker-compose.yml` passes
> `-c shared_preload_libraries=pg_stat_statements` for exactly this reason.
> Without it the view exists but stays permanently empty, which looks like
> OptiQuery detecting nothing rather than a configuration mistake.

## `simulate-load.sql`

A single batch of deliberately awful queries — correlated subqueries, a
self-join with no supporting index, sorts on unindexed columns.

```bash
psql -h localhost -U optiq_monitor -d optiq_target -f scripts/simulate-load.sql
```

## `simulate-concurrent-load.sh`

The same shapes run from several parallel sessions, so call counts climb and
the dashboard's *time burned* ranking has something real to sort.

```bash
bash scripts/simulate-concurrent-load.sh
```

Connection details come from the environment, with defaults matching
`docker-compose.yml`:

| Variable | Default |
|---|---|
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `optiq_target` |
| `DB_USER` | `optiq_monitor` |
| `DB_PASS` | `$OPTIQ_DB_PASSWORD`, else `changeme` |

Afterwards, press **Check now** on the dashboard rather than waiting for the
polling interval.

### Resetting the statistics

`pg_stat_statements` accumulates. To start from a clean slate:

```sql
SELECT pg_stat_statements_reset();
```
