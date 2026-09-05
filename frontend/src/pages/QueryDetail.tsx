import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft, Copy, Check, RotateCw, Archive, Info, TriangleAlert, CheckCheck, Undo2, Loader2,
} from 'lucide-react';
import clsx from 'clsx';
import {
  fetchSlowQuery, dismissQuery, reanalyzeQuery, markApplied, reopenQuery, fetchActivity,
  type ActivityEvent,
} from '../api';
import type { SlowQuery, RiskLevel } from '../types';
import {
  formatDuration, timeAgo, triageOf, splitFix, readPlan, hasFix, dedent,
  parseSchema, unindexedColumns, tableList, type TableFacts,
} from '../lib/query';

/** Risk describes what applying the fix costs you, so say that plainly. */
const RISK: Record<RiskLevel, { label: string; tone: string; note: string }> = {
  LOW: { label: 'Low risk', tone: 'text-heat-cool', note: 'Additive change. Safe to run on a live database.' },
  MEDIUM: { label: 'Medium risk', tone: 'text-heat-warm', note: 'Review on a replica before running in production.' },
  HIGH: { label: 'High risk', tone: 'text-heat-crit', note: 'Changes behaviour or locks. Test before running.' },
};

export default function QueryDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();

  const { data: query, isLoading } = useQuery({
    queryKey: ['slow-query', id],
    queryFn: () => fetchSlowQuery(Number(id)),
    enabled: !!id,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['slow-query', id] });
    queryClient.invalidateQueries({ queryKey: ['slow-queries'] });
    queryClient.invalidateQueries({ queryKey: ['stats'] });
    queryClient.invalidateQueries({ queryKey: ['activity', id] });
    queryClient.invalidateQueries({ queryKey: ['team-activity'] });
  };

  const dismiss = useMutation({ mutationFn: () => dismissQuery(Number(id)), onSuccess: refresh });
  const reanalyze = useMutation({ mutationFn: () => reanalyzeQuery(Number(id)), onSuccess: refresh });
  const applied = useMutation({ mutationFn: () => markApplied(Number(id)), onSuccess: refresh });
  const reopen = useMutation({ mutationFn: () => reopenQuery(Number(id)), onSuccess: refresh });

  const { data: activity } = useQuery({
    queryKey: ['activity', id],
    queryFn: () => fetchActivity(Number(id)),
    enabled: !!id,
  });

  if (isLoading) {
    return (
      <div className="max-w-[1000px] mx-auto px-8 py-8 space-y-4">
        <div className="h-4 w-40 rounded-xs bg-raised" />
        <div className="h-24 rounded-lg bg-surface border border-line" />
        <div className="h-56 rounded-lg bg-surface border border-line" />
      </div>
    );
  }

  if (!query) {
    return (
      <div className="max-w-[560px] mx-auto px-8 py-24 text-center">
        <h1 className="text-lead font-semibold text-ink">This query is no longer on record</h1>
        <p className="sub mt-2">
          It may have been removed when the daemon rebuilt its history.
        </p>
        <Link to="/" className="btn mt-5">Back to all queries</Link>
      </div>
    );
  }

  const triage = triageOf(query);
  const tables = tableList(query.referencedTables);

  return (
    <div className="max-w-[1000px] mx-auto px-8 py-7 space-y-6">
      <header className="space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <Link
              to="/"
              className="inline-flex items-center gap-1.5 text-tiny text-muted hover:text-ink transition-colors rounded-sm"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              All queries
            </Link>
            <h1 className="text-title font-semibold text-ink mt-2">
              {tables.length > 0
                ? `Slow query on ${tables.join(', ')}`
                : `Slow query #${query.id}`}
            </h1>
            <p className="sub mt-1">
              Seen {query.detectionCount} {query.detectionCount === 1 ? 'time' : 'times'}, first{' '}
              {timeAgo(query.detectedAt)}. Last analyzed {timeAgo(query.updatedAt)}.
            </p>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {query.status === 'APPLIED' || query.status === 'DISMISSED' ? (
              <button onClick={() => reopen.mutate()} disabled={reopen.isPending} className="btn">
                <Undo2 className="w-3.5 h-3.5" />
                Reopen
              </button>
            ) : (
              <button
                onClick={() => applied.mutate()}
                disabled={applied.isPending}
                title="Record that you ran the suggested fix"
                className="btn-primary"
              >
                {applied.isPending
                  ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  : <CheckCheck className="w-3.5 h-3.5" />}
                I ran this
              </button>
            )}
            <button
              onClick={() => reanalyze.mutate()}
              disabled={reanalyze.isPending}
              className="btn"
            >
              <RotateCw className={clsx('w-3.5 h-3.5', reanalyze.isPending && 'animate-spin')} />
              {reanalyze.isPending ? 'Analyzing' : 'Analyze again'}
            </button>
            <button
              onClick={() => dismiss.mutate()}
              disabled={dismiss.isPending || query.status === 'DISMISSED'}
              className="btn"
            >
              <Archive className="w-3.5 h-3.5" />
              {query.status === 'DISMISSED' ? 'Dismissed' : 'Dismiss'}
            </button>
          </div>
        </div>

        <Measurements query={query} />
      </header>

      {hasFix(triage.kind) ? (
        <Verdict query={query} stale={triage.kind === 'fix-stale'} />
      ) : (
        <Pending query={query} onAnalyze={() => reanalyze.mutate()} busy={reanalyze.isPending} />
      )}

      <Activity events={activity ?? []} status={query.status} />

      <Evidence query={query} />
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * What the team has already done — so nobody runs the same index twice.
 * ------------------------------------------------------------------------- */

function Activity({ events, status }: { events: ActivityEvent[]; status: string }) {
  const actionable = status !== 'DISMISSED';

  if (events.length === 0) {
    return (
      <section className="space-y-3">
        <h2 className="head">Team activity</h2>
        <div className="panel px-5 py-4 flex items-start gap-3">
          <Info className="w-4 h-4 text-muted mt-0.5 shrink-0" />
          <p className="text-base text-muted max-w-[62ch] leading-relaxed">
            {actionable
              ? 'Nobody has acted on this yet. If you run the fix, mark it with "I ran this" so the rest of the team knows it is handled.'
              : 'No recorded actions on this query.'}
          </p>
        </div>
      </section>
    );
  }

  return (
    <section className="space-y-3">
      <h2 className="head">Team activity</h2>
      <ul className="panel divide-y divide-line">
        {events.map((e) => (
          <li key={e.id} className="flex items-baseline gap-3 px-5 py-3">
            <span
              className={clsx(
                'w-1.5 h-1.5 rounded-full shrink-0 translate-y-[-2px]',
                e.action === 'APPLIED' ? 'bg-heat-cool'
                  : e.action === 'DISMISSED' ? 'bg-faint'
                  : e.action === 'REOPENED' ? 'bg-heat-warm' : 'bg-signal'
              )}
            />
            <div className="min-w-0 flex-1">
              <p className="text-base text-ink">
                <span className="font-medium">{e.actorName || e.actorEmail || 'Someone'}</span>{' '}
                <span className="text-muted">{e.phrase}</span>
              </p>
              {e.note && <p className="text-tiny text-muted mt-0.5">{e.note}</p>}
            </div>
            <span className="text-micro text-faint shrink-0">{timeAgo(e.at)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/* ---------------------------------------------------------------------------
 * Measurements: the four numbers that decide whether this is worth your time.
 * ------------------------------------------------------------------------- */

function Measurements({ query }: { query: SlowQuery }) {
  const total = formatDuration(query.totalExecTimeMs);
  const mean = formatDuration(query.meanExecTimeMs);

  return (
    <dl className="flex flex-wrap items-end gap-x-9 gap-y-3 rule pt-4">
      <Measure label="Time burned in total" value={total.value} unit={total.unit} accent />
      <Measure label="Each call" value={mean.value} unit={mean.unit} />
      <Measure label="Calls recorded" value={query.callCount.toLocaleString()} unit="" />
      <Measure
        label="Detected"
        value={String(query.detectionCount)}
        unit={query.detectionCount === 1 ? 'time' : 'times'}
      />
    </dl>
  );
}

function Measure({ label, value, unit, accent }: { label: string; value: string; unit: string; accent?: boolean }) {
  return (
    <div>
      <dt className="text-micro text-faint">{label}</dt>
      <dd className="mt-0.5">
        <span className={clsx('tnum text-lead font-semibold', accent ? 'text-heat-hot' : 'text-ink')}>
          {value}
        </span>
        {unit && <span className="text-tiny text-muted ml-1">{unit}</span>}
      </dd>
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * The verdict: what is wrong, how sure we are, and the SQL that fixes it.
 * ------------------------------------------------------------------------- */

function Verdict({ query, stale }: { query: SlowQuery; stale: boolean }) {
  const { ddl, rewrite } = splitFix(query.suggestedSql);
  const risk = query.riskLevel ? RISK[query.riskLevel] : null;
  const confidence = query.confidenceScore ?? 0;

  return (
    <section className="panel overflow-hidden">
      {stale && (
        <div className="flex items-start gap-2.5 px-5 py-3 border-b border-line bg-heat-warm/[0.07]">
          <TriangleAlert className="w-3.5 h-3.5 text-heat-warm mt-0.5 shrink-0" />
          <p className="text-tiny text-muted leading-relaxed">
            <span className="text-ink">The latest analysis did not finish.</span> This diagnosis is
            from the run on {new Date(query.updatedAt ?? query.detectedAt).toLocaleDateString()} and
            may not reflect recent schema changes. Check your AI provider settings, then analyze again.
          </p>
        </div>
      )}
      <div className="px-5 pt-5 pb-4">
        <h2 className="head">What is slowing it down</h2>
        <p className="text-mid text-ink/90 mt-2 max-w-[68ch] leading-relaxed">{query.rootCause}</p>

        <div className="flex flex-wrap items-center gap-x-7 gap-y-3 mt-5">
          <div className="flex items-center gap-2.5">
            <span className="text-micro text-faint">Confidence</span>
            <span className="flex gap-[3px]" role="img" aria-label={`Confidence ${confidence} out of 100`}>
              {Array.from({ length: 10 }).map((_, i) => (
                <span
                  key={i}
                  className={clsx(
                    'w-3.5 h-1.5 rounded-xs',
                    i < Math.floor(confidence / 10)
                      ? confidence >= 80 ? 'bg-heat-cool' : confidence >= 50 ? 'bg-heat-warm' : 'bg-heat-crit'
                      : 'bg-raised'
                  )}
                />
              ))}
            </span>
            <span className="tnum text-tiny text-muted">{confidence}</span>
          </div>

          {risk && (
            <div className="flex items-baseline gap-2">
              <span className={clsx('text-tiny font-medium', risk.tone)}>{risk.label}</span>
              <span className="text-micro text-faint">{risk.note}</span>
            </div>
          )}
        </div>
      </div>

      <div className="bg-abyss/60 border-t border-line px-5 py-5 space-y-5">
        {ddl.length > 0 && (
          <Fix
            title={ddl.length > 1 ? 'Run these statements' : 'Run this statement'}
            note="Adds the index the planner is missing."
            sql={ddl.join('\n')}
          />
        )}
        {rewrite && (
          <Fix
            title="Then replace the query with this"
            note="Same result, without the per-row subqueries."
            sql={rewrite}
          />
        )}
        {ddl.length === 0 && !rewrite && (
          <p className="text-base text-muted">
            No statement was suggested. Analyze again, or review the plan below by hand.
          </p>
        )}
        <p className="text-micro text-faint">
          Diagnosed by {query.modelUsed ?? 'the configured model'}. Always review before running
          against production.
        </p>
      </div>
    </section>
  );
}

function Fix({ title, note, sql }: { title: string; note: string; sql: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(sql);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div>
      <div className="flex items-baseline justify-between gap-4 mb-2">
        <div className="flex items-baseline gap-2.5 min-w-0">
          <h3 className="text-base font-semibold text-ink">{title}</h3>
          <span className="text-micro text-faint truncate">{note}</span>
        </div>
        <button onClick={copy} className={clsx('btn shrink-0 py-1.5', copied && 'border-heat-cool')}>
          {copied ? <Check className="w-3.5 h-3.5 text-heat-cool" /> : <Copy className="w-3.5 h-3.5" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="code text-signal-soft border-signal/25">{sql}</pre>
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * States where there is no fix yet — each says what happens next.
 * ------------------------------------------------------------------------- */

function Pending({ query, onAnalyze, busy }: { query: SlowQuery; onAnalyze: () => void; busy: boolean }) {
  const triage = triageOf(query);

  const copy: Record<string, { title: string; body: string; action: boolean }> = {
    reviewing: {
      title: 'Diagnosis in progress',
      body: 'OptiQuery is reading the execution plan and your schema. This usually takes a few seconds.',
      action: false,
    },
    failed: {
      title: 'The diagnosis did not complete',
      body: 'The model call failed or returned something unusable. Check your AI provider settings, then run it again.',
      action: true,
    },
    'no-fix': {
      title: 'No safe fix found',
      body: 'The query was analyzed but no change could be recommended with confidence. The evidence below is still worth reading.',
      action: true,
    },
    'needs-key': {
      title: 'Not analyzed \u2014 the trial has no analyses left',
      body: 'OptiQuery still detects this query and measures what it costs you, but diagnosing it needs an AI provider. Add your own API key in Settings and analyze it again.',
      action: false,
    },
    dismissed: {
      title: 'You dismissed this query',
      body: 'It stays out of your ranked list and no longer counts toward time burned. Analyze it again to bring it back.',
      action: true,
    },
  };

  const state = copy[triage.kind] ?? copy.reviewing!;

  return (
    <section className="panel px-5 py-6">
      <div className="flex items-start gap-3 max-w-[62ch]">
        {triage.kind === 'failed' ? (
          <TriangleAlert className="w-4 h-4 text-heat-crit mt-0.5 shrink-0" />
        ) : (
          <Info className="w-4 h-4 text-muted mt-0.5 shrink-0" />
        )}
        <div>
          <h2 className="head">{state.title}</h2>
          <p className="text-base text-muted mt-1.5 leading-relaxed">{state.body}</p>
          {state.action && (
            <button onClick={onAnalyze} disabled={busy} className="btn-primary mt-4">
              <RotateCw className={clsx('w-3.5 h-3.5', busy && 'animate-spin')} />
              {busy ? 'Analyzing' : 'Analyze again'}
            </button>
          )}
          {triage.kind === 'needs-key' && (
            <Link to="/settings" className="btn-primary mt-4">Add an API key</Link>
          )}
        </div>
      </div>
    </section>
  );
}

/* ---------------------------------------------------------------------------
 * Evidence: the facts behind the verdict, so it can be checked rather than
 * taken on faith.
 * ------------------------------------------------------------------------- */

type Tab = 'sql' | 'plan' | 'schema';

function Evidence({ query }: { query: SlowQuery }) {
  const [tab, setTab] = useState<Tab>('sql');
  const plan = readPlan(query.explainPlan, query.rawQuery);
  const tables = parseSchema(query.schemaContext);

  const tabs: { id: Tab; label: string; hint?: string }[] = [
    { id: 'sql', label: 'The query' },
    { id: 'plan', label: 'Execution plan', hint: plan.ok ? undefined : 'unavailable' },
    { id: 'schema', label: 'Tables', hint: tables.length ? String(tables.length) : undefined },
  ];

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between gap-4">
        <h2 className="head">Evidence</h2>
        <div className="flex rounded-md border border-line overflow-hidden">
          {tabs.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              aria-pressed={tab === t.id}
              className={clsx(
                'px-3 py-1.5 text-tiny transition-colors border-r border-line last:border-r-0',
                tab === t.id ? 'bg-raised text-ink font-medium' : 'text-muted hover:text-ink hover:bg-raised/50'
              )}
            >
              {t.label}
              {t.hint && <span className="text-faint ml-1.5">{t.hint}</span>}
            </button>
          ))}
        </div>
      </div>

      <div className="panel p-5">
        {tab === 'sql' && <pre className="code text-ink/85">{dedent(query.rawQuery)}</pre>}

        {tab === 'plan' &&
          (plan.ok ? (
            <pre className="code text-ink/85">{plan.plan}</pre>
          ) : (
            <div className="flex items-start gap-3 max-w-[68ch]">
              <Info className="w-4 h-4 text-muted mt-0.5 shrink-0" />
              <div>
                <p className="text-base text-ink">{plan.reason}</p>
                <p className="text-tiny text-muted mt-1.5 leading-relaxed">{plan.detail}</p>
              </div>
            </div>
          ))}

        {tab === 'schema' &&
          (tables.length === 0 ? (
            <p className="text-base text-muted">
              No schema snapshot was captured for this query yet.
            </p>
          ) : (
            <div className="space-y-6">
              {tables.map((t) => (
                <TableCard key={t.name} table={t} />
              ))}
            </div>
          ))}
      </div>
    </section>
  );
}

function TableCard({ table }: { table: TableFacts }) {
  const bare = unindexedColumns(table);
  const onlyPrimaryKey =
    table.indexes.length > 0 && table.indexes.every((i) => /_pkey$/.test(i.name));

  return (
    <div>
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h3 className="font-mono text-base font-medium text-ink">{table.name}</h3>
        {table.estimatedRows !== null && (
          <span className="text-tiny text-muted tnum">
            about {table.estimatedRows.toLocaleString()} rows
          </span>
        )}
        <span
          className={clsx(
            'text-tiny',
            onlyPrimaryKey || table.indexes.length === 0 ? 'text-heat-warm' : 'text-muted'
          )}
        >
          {table.indexes.length === 0
            ? 'no indexes'
            : onlyPrimaryKey
              ? 'primary key only'
              : `${table.indexes.length} indexes`}
        </span>
      </div>

      <ul className="mt-2.5 flex flex-wrap gap-1.5">
        {table.columns.map((c) => {
          const unindexed = bare.includes(c.name);
          return (
            <li
              key={c.name}
              title={`${c.type}${c.nullable ? '' : ', not null'}${unindexed ? ' — no index covers this column' : ''}`}
              className={clsx(
                'font-mono text-micro px-2 py-1 rounded-xs border',
                unindexed
                  ? 'border-line bg-abyss text-muted'
                  : 'border-signal/30 bg-signal-wash text-signal-soft'
              )}
            >
              {c.name}
            </li>
          );
        })}
      </ul>

      {table.indexes.length > 0 && (
        <p className="text-micro text-faint mt-2">
          Indexed: {table.indexes.map((i) => i.columns || i.name).join(' · ')}
        </p>
      )}
    </div>
  );
}
