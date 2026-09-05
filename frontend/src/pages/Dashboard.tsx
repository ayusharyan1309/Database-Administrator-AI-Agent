import { useState, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Search, ChevronLeft, ChevronRight, RotateCw, Check } from 'lucide-react';
import clsx from 'clsx';
import { fetchSlowQueries, fetchStats, pollNow, fetchTeamActivity, type ActivityEvent } from '../api';
import type { SlowQuery } from '../types';
import {
  formatDuration, durationText, heatFor, heatBar, heatText,
  triageOf, describeFix, oneLine, queryKind, tableList, ledgerOf, hasFix,
  type Triage,
} from '../lib/query';

const PAGE_SIZE = 12;

/** Filters name what the user wants to do next, not the enum they map to. */
const FILTERS: { id: string; label: string; match?: Triage[] }[] = [
  { id: 'all', label: 'All' },
  { id: 'fix', label: 'Has a fix', match: ['fix-ready', 'fix-stale', 'applied'] },
  { id: 'analyzing', label: 'Analyzing', match: ['reviewing'] },
  { id: 'dismissed', label: 'Dismissed', match: ['dismissed'] },
];

export default function Dashboard() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const queryClient = useQueryClient();

  const { data: stats } = useQuery({ queryKey: ['stats'], queryFn: fetchStats });

  // One read for the whole list, rather than one per row.
  const { data: teamActivity } = useQuery({
    queryKey: ['team-activity'],
    queryFn: fetchTeamActivity,
    retry: false,
  });

  const { data, isLoading } = useQuery({
    queryKey: ['slow-queries', page],
    queryFn: () => fetchSlowQueries(page, PAGE_SIZE),
  });

  const poll = useMutation({
    mutationFn: pollNow,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['slow-queries'] });
      queryClient.invalidateQueries({ queryKey: ['stats'] });
    },
  });

  const queries = useMemo(() => data?.queries ?? [], [data]);
  const ledger = useMemo(() => ledgerOf(queries), [queries]);

  const visible = queries.filter((q) => {
    const f = FILTERS.find((x) => x.id === filter);
    if (f?.match && !f.match.includes(triageOf(q).kind)) return false;
    if (search && !q.rawQuery.toLowerCase().includes(search.toLowerCase())) return false;
    return true;
  });

  const totalPages = data ? Math.max(1, Math.ceil(data.totalCount / PAGE_SIZE)) : 1;
  const burnedMs = stats?.totalTimeBurnedMs ?? ledger.totalMs;

  return (
    <div className="max-w-[1120px] mx-auto px-8 py-8 space-y-7">
      <Ledger
        burnedMs={burnedMs}
        queries={queries}
        fixesReady={ledger.fixesReady}
        staleFixes={ledger.staleFixes}
        analyzing={ledger.analyzing}
        failed={ledger.failed}
        untouched={queries.filter(
          (q) => triageOf(q).kind === 'fix-ready' && !teamActivity?.[String(q.id)]
        ).length}
        onCheck={() => poll.mutate()}
        checking={poll.isPending}
        checked={poll.isSuccess && !poll.isPending}
      />

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="head">Ranked by time burned</h2>
          <div className="flex items-center gap-2">
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-faint pointer-events-none" />
              <input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Find a table or column"
                aria-label="Search queries"
                className="field w-56 pl-8 py-1.5 text-tiny"
              />
            </div>
            <div className="flex rounded-md border border-line overflow-hidden">
              {FILTERS.map((f) => (
                <button
                  key={f.id}
                  onClick={() => setFilter(f.id)}
                  aria-pressed={filter === f.id}
                  className={clsx(
                    'px-2.5 py-1.5 text-tiny transition-colors border-r border-line last:border-r-0',
                    filter === f.id
                      ? 'bg-raised text-ink font-medium'
                      : 'text-muted hover:text-ink hover:bg-raised/50'
                  )}
                >
                  {f.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="panel overflow-hidden">
          {isLoading ? (
            <SkeletonRows />
          ) : visible.length === 0 ? (
            <Empty hasQueries={queries.length > 0} onClear={() => { setFilter('all'); setSearch(''); }} />
          ) : (
            <ul>
              {visible.map((q, i) => (
                <QueryRow
                  key={q.id}
                  query={q}
                  maxMs={ledger.maxMs}
                  rank={i}
                  lastAction={teamActivity?.[String(q.id)]}
                />
              ))}
            </ul>
          )}

          {totalPages > 1 && (
            <div className="flex items-center justify-between px-4 py-2.5 border-t border-line">
              <p className="text-micro text-faint">
                Showing {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, data?.totalCount ?? 0)} of{' '}
                {data?.totalCount ?? 0}
              </p>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  aria-label="Previous page"
                  className="btn px-2 py-1"
                >
                  <ChevronLeft className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                  aria-label="Next page"
                  className="btn px-2 py-1"
                >
                  <ChevronRight className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * The ledger: one sentence naming the cost, and a bar showing who spent it.
 * ------------------------------------------------------------------------- */

function Ledger({
  burnedMs, queries, fixesReady, staleFixes, analyzing, failed, untouched, onCheck, checking, checked,
}: {
  burnedMs: number;
  queries: SlowQuery[];
  fixesReady: number;
  untouched: number;
  staleFixes: number;
  analyzing: number;
  failed: number;
  onCheck: () => void;
  checking: boolean;
  checked: boolean;
}) {
  const live = queries.filter((q) => q.status !== 'DISMISSED');
  const total = live.reduce((s, q) => s + q.totalExecTimeMs, 0);
  const maxMs = live.reduce((m, q) => Math.max(m, q.totalExecTimeMs), 0);
  const { value, unit } = formatDuration(burnedMs);

  // Segments only read as distinct above ~1.5% of the bar; fold the rest.
  const segments = live
    .map((q) => ({ q, share: total > 0 ? q.totalExecTimeMs / total : 0 }))
    .filter((s) => s.share >= 0.015);
  const remainder = 1 - segments.reduce((s, x) => s + x.share, 0);

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-title font-semibold text-ink">
            {live.length === 0 ? (
              'Nothing is slowing your database down'
            ) : (
              <>
                Your database burned{' '}
                <span className="tnum text-heat-hot">{value} {unit}</span> on{' '}
                {live.length} {live.length === 1 ? 'query' : 'queries'}
              </>
            )}
          </h1>
          <p className="sub mt-1">
            {live.length === 0
              ? 'Every detected query is either fixed or dismissed.'
              : 'Time your database spent executing the statements below, across every recorded call.'}
          </p>
        </div>
        <button onClick={onCheck} disabled={checking} className="btn shrink-0">
          {checked && !checking ? (
            <Check className="w-3.5 h-3.5 text-heat-cool" />
          ) : (
            <RotateCw className={clsx('w-3.5 h-3.5', checking && 'animate-spin')} />
          )}
          {checking ? 'Checking' : checked ? 'Up to date' : 'Check now'}
        </button>
      </div>

      {live.length > 0 && (
        <div className="space-y-3">
          <div className="flex h-2.5 rounded-full overflow-hidden bg-raised" role="presentation">
            {segments.map(({ q, share }) => (
              <div
                key={q.id}
                style={{ width: `${share * 100}%` }}
                className={clsx(
                  heatBar[heatFor(q.totalExecTimeMs, maxMs)],
                  'origin-left animate-fill border-r border-abyss/60 last:border-r-0'
                )}
                title={`${oneLine(q.rawQuery).slice(0, 80)} — ${durationText(q.totalExecTimeMs)}`}
              />
            ))}
            {remainder > 0.005 && (
              <div style={{ width: `${remainder * 100}%` }} className="bg-line origin-left animate-fill" />
            )}
          </div>

          <div className="flex flex-wrap items-center gap-x-5 gap-y-1.5">
            {segments.slice(0, 4).map(({ q, share }) => (
              <Link
                key={q.id}
                to={`/query/${q.id}`}
                className="group flex items-center gap-2 text-tiny rounded-sm"
              >
                <span
                  className={clsx(
                    'w-2 h-2 rounded-xs shrink-0',
                    heatBar[heatFor(q.totalExecTimeMs, maxMs)]
                  )}
                />
                <span className="text-muted group-hover:text-ink transition-colors truncate max-w-[220px]">
                  {tableList(q.referencedTables).join(', ') || queryKind(q.rawQuery)}
                </span>
                <span className="tnum text-faint">{Math.round(share * 100)}%</span>
              </Link>
            ))}
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-x-6 gap-y-1 text-tiny rule pt-3">
        {fixesReady > 0 && (
          <Note count={fixesReady} tone="text-heat-cool" one="fix ready to apply" many="fixes ready to apply" />
        )}
        {untouched > 0 && (
          <Note
            count={untouched}
            tone="text-heat-warm"
            one="nobody has acted on yet"
            many="nobody has acted on yet"
          />
        )}
        {staleFixes > 0 && (
          <Note count={staleFixes} tone="text-heat-warm" one="fix from an earlier run" many="fixes from an earlier run" />
        )}
        {analyzing > 0 && (
          <Note count={analyzing} tone="text-signal" one="query still analyzing" many="queries still analyzing" />
        )}
        {failed > 0 && (
          <Note count={failed} tone="text-heat-crit" one="query has no diagnosis" many="queries have no diagnosis" />
        )}
        {fixesReady + staleFixes + analyzing + failed === 0 && (
          <span className="text-muted">Nothing needs your attention.</span>
        )}
      </div>
    </section>
  );
}

function Note({ count, tone, one, many }: { count: number; tone: string; one: string; many: string }) {
  return (
    <span className="text-muted">
      <span className={clsx('tnum font-semibold', tone)}>{count}</span>{' '}
      {count === 1 ? one : many}
    </span>
  );
}

/* ---------------------------------------------------------------------------
 * A row is a bar. Width is cost, so reading the list is reading a profile.
 * ------------------------------------------------------------------------- */

function QueryRow({ query: q, maxMs, rank, lastAction }: {
  query: SlowQuery; maxMs: number; rank: number; lastAction?: ActivityEvent;
}) {
  const heat = heatFor(q.totalExecTimeMs, maxMs);
  const triage = triageOf(q);
  const fix = describeFix(q.suggestedSql);
  const { value, unit } = formatDuration(q.totalExecTimeMs);
  const share = maxMs > 0 ? q.totalExecTimeMs / maxMs : 0;
  const dimmed = q.status === 'DISMISSED';
  const tables = tableList(q.referencedTables);

  return (
    <li className="border-b border-line last:border-b-0">
      <Link
        to={`/query/${q.id}`}
        className={clsx(
          'relative block px-4 py-3.5 transition-colors hover:bg-raised/70',
          dimmed && 'opacity-45'
        )}
      >
        {/* The cost bar sits behind the row, so length is readable at a glance. */}
        <div
          aria-hidden
          style={{ width: `${Math.max(share * 100, 1.5)}%`, animationDelay: `${rank * 35}ms` }}
          className={clsx(
            'absolute left-0 top-0 bottom-0 origin-left animate-fill opacity-[0.09]',
            heatBar[heat]
          )}
        />

        <div className="relative flex items-baseline gap-4">
          <div className="w-[92px] shrink-0 text-right">
            <span className={clsx('tnum text-mid font-semibold', heatText[heat])}>{value}</span>
            <span className="text-micro text-faint ml-1">{unit}</span>
          </div>

          <div className="min-w-0 flex-1">
            <p className="font-mono text-tiny text-ink truncate">{oneLine(q.rawQuery)}</p>
            <p className="text-micro text-faint mt-1">
              {q.callCount.toLocaleString()} {q.callCount === 1 ? 'call' : 'calls'} at{' '}
              {durationText(q.meanExecTimeMs)} each
              {tables.length > 0 && <span className="text-muted"> · {tables.join(', ')}</span>}
            </p>
          </div>

          <div className="w-[232px] shrink-0 text-right">
            <span className={clsx('inline-flex items-center gap-1.5 text-tiny', triage.text)}>
              <span className={clsx('w-1.5 h-1.5 rounded-full', triage.dot)} />
              {triage.label}
            </span>
            {lastAction ? (
              <p className="text-micro text-muted mt-1 leading-snug truncate">
                {(lastAction.actorName || lastAction.actorEmail || 'Someone').split('@')[0]}{' '}
                {lastAction.phrase}
              </p>
            ) : fix && hasFix(triage.kind) ? (
              <p className="text-micro text-muted mt-1 leading-snug line-clamp-2">{fix}</p>
            ) : null}
          </div>
        </div>
      </Link>
    </li>
  );
}

/* ------------------------------------------------------------------------- */

function SkeletonRows() {
  return (
    <div className="divide-y divide-line">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="flex items-center gap-4 px-4 py-4">
          <div className="w-[92px] h-3.5 rounded-xs bg-raised" />
          <div className="flex-1 h-3.5 rounded-xs bg-raised" style={{ maxWidth: `${70 - i * 9}%` }} />
          <div className="w-[120px] h-3.5 rounded-xs bg-raised" />
        </div>
      ))}
    </div>
  );
}

function Empty({ hasQueries, onClear }: { hasQueries: boolean; onClear: () => void }) {
  if (hasQueries) {
    return (
      <div className="px-6 py-16 text-center">
        <p className="text-base text-ink">No query matches this view</p>
        <p className="sub mt-1">Try a different filter or search term.</p>
        <button onClick={onClear} className="btn mt-4">Show all queries</button>
      </div>
    );
  }
  return (
    <div className="px-6 py-16 text-center">
      <p className="text-base text-ink">No slow queries yet</p>
      <p className="sub mt-1 max-w-md mx-auto">
        OptiQuery samples pg_stat_statements on a schedule. Anything slower than your
        threshold lands here with a diagnosis and a fix.
      </p>
      <Link to="/settings" className="btn mt-4">Adjust the threshold</Link>
    </div>
  );
}
