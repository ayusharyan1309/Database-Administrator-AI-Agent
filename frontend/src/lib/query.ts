/**
 * Domain helpers for presenting slow queries.
 *
 * The spine of the UI is *time burned* (totalExecTimeMs) rather than status
 * counts: it is the number a team actually pays for, and the number that
 * decides which query to fix first.
 */
import type { SlowQuery, AnalysisStatus } from '../types';

// --- Time ------------------------------------------------------------------

/** Human duration from milliseconds: 84 ms, 1.4 s, 3.2 min, 1.1 h. */
export function formatDuration(ms: number): { value: string; unit: string } {
  if (!isFinite(ms) || ms < 0) return { value: '—', unit: '' };
  if (ms < 1) return { value: ms.toFixed(2), unit: 'ms' };
  if (ms < 1000) return { value: ms < 10 ? ms.toFixed(1) : Math.round(ms).toLocaleString(), unit: 'ms' };
  if (ms < 60_000) return { value: (ms / 1000).toFixed(ms < 10_000 ? 2 : 1), unit: 's' };
  if (ms < 3_600_000) return { value: (ms / 60_000).toFixed(1), unit: 'min' };
  return { value: (ms / 3_600_000).toFixed(1), unit: 'h' };
}

export function durationText(ms: number): string {
  const { value, unit } = formatDuration(ms);
  return unit ? `${value} ${unit}` : value;
}

/** Compact relative age: "4 min ago", "3 days ago". */
export function timeAgo(iso: string | null): string {
  if (!iso) return 'never';
  const then = new Date(iso).getTime();
  if (isNaN(then)) return 'unknown';
  const secs = Math.max(0, (Date.now() - then) / 1000);
  if (secs < 60) return 'just now';
  const mins = secs / 60;
  if (mins < 60) return `${Math.round(mins)} min ago`;
  const hours = mins / 60;
  if (hours < 24) return `${Math.round(hours)} ${Math.round(hours) === 1 ? 'hour' : 'hours'} ago`;
  const days = Math.round(hours / 24);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}

// --- Heat ------------------------------------------------------------------

export type Heat = 'cool' | 'warm' | 'hot' | 'crit';

/** Colour encodes cost: a query's share of the slowest query in view. */
export function heatFor(totalMs: number, maxMs: number): Heat {
  const share = maxMs > 0 ? totalMs / maxMs : 0;
  if (share >= 0.6) return 'crit';
  if (share >= 0.3) return 'hot';
  if (share >= 0.1) return 'warm';
  return 'cool';
}

export const heatBar: Record<Heat, string> = {
  cool: 'bg-heat-cool',
  warm: 'bg-heat-warm',
  hot: 'bg-heat-hot',
  crit: 'bg-heat-crit',
};

export const heatText: Record<Heat, string> = {
  cool: 'text-heat-cool',
  warm: 'text-heat-warm',
  hot: 'text-heat-hot',
  crit: 'text-heat-crit',
};

// --- Triage ----------------------------------------------------------------

export type Triage =
  | 'fix-ready'
  | 'fix-stale'
  | 'reviewing'
  | 'no-fix'
  | 'needs-key'
  | 'failed'
  | 'dismissed'
  | 'applied';

export interface TriageInfo {
  kind: Triage;
  /** What the user does next, in their words. */
  label: string;
  dot: string;
  text: string;
}

const TRIAGE: Record<Triage, Omit<TriageInfo, 'kind'>> = {
  'fix-ready': { label: 'Fix ready',    dot: 'bg-heat-cool',    text: 'text-heat-cool' },
  'fix-stale': { label: 'Fix from last run', dot: 'bg-heat-warm', text: 'text-heat-warm' },
  reviewing:   { label: 'Analyzing',    dot: 'bg-signal',       text: 'text-signal' },
  'no-fix':    { label: 'No fix found', dot: 'bg-muted',        text: 'text-muted' },
  'needs-key': { label: 'Not analyzed',  dot: 'bg-heat-warm',    text: 'text-heat-warm' },
  failed:      { label: 'Analysis failed', dot: 'bg-heat-crit', text: 'text-heat-crit' },
  dismissed:   { label: 'Dismissed',    dot: 'bg-faint',        text: 'text-faint' },
  applied:     { label: 'Applied',      dot: 'bg-heat-cool',    text: 'text-heat-cool' },
};

export function triageOf(q: Pick<SlowQuery, 'status' | 'suggestedSql'>): TriageInfo {
  const kind = triageKind(q.status, q.suggestedSql);
  return { kind, ...TRIAGE[kind] };
}

function triageKind(status: AnalysisStatus, suggestedSql: string | null): Triage {
  switch (status) {
    case 'APPLIED': return 'applied';
    case 'DISMISSED': return 'dismissed';
    // A failed re-run must not discard a working diagnosis from an earlier one.
    case 'FAILED': return suggestedSql?.trim() ? 'fix-stale' : 'failed';
    // Detected and measured, but the trial had nothing left to spend.
    case 'QUOTA_EXCEEDED': return suggestedSql?.trim() ? 'fix-stale' : 'needs-key';
    case 'PENDING':
    case 'ANALYZING': return 'reviewing';
    case 'COMPLETED': return suggestedSql?.trim() ? 'fix-ready' : 'no-fix';
    default: return 'reviewing';
  }
}

// --- Reading the SQL -------------------------------------------------------

/** Collapse whitespace and strip leading comments so a query fits one line. */
export function oneLine(sql: string): string {
  return sql
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('--'))
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Remove the indentation the query was captured with, so a statement pulled out
 * of application code does not read as if it starts half way across the panel.
 */
export function dedent(sql: string): string {
  const lines = sql.replace(/\t/g, '  ').split('\n');
  const indents = lines
    .filter((l) => l.trim())
    .map((l) => l.match(/^ */)?.[0].length ?? 0);
  const common = indents.length ? Math.min(...indents) : 0;
  return lines.map((l) => l.slice(common)).join('\n').trim();
}

/** The SQL verb that opens the statement — SELECT, UPDATE, DO, WITH… */
export function queryKind(sql: string): string {
  const first = oneLine(sql).match(/^[A-Za-z]+/);
  return first ? first[0].toUpperCase() : 'SQL';
}

/**
 * A one-line plain description of what the fix does, so a query can be triaged
 * from the list without opening it.
 */
export function describeFix(suggestedSql: string | null): string | null {
  if (!suggestedSql?.trim()) return null;
  const sql = oneLine(suggestedSql);

  const index = sql.match(/CREATE\s+(?:UNIQUE\s+)?INDEX(?:\s+CONCURRENTLY)?(?:\s+IF\s+NOT\s+EXISTS)?\s+(?:[\w."]+\s+)?ON\s+([\w."]+)\s*(?:USING\s+\w+\s*)?\(([^)]*)\)/i);
  if (index) {
    const table = bareName(index[1] ?? '');
    const cols = (index[2] ?? '')
      .split(',')
      .map((c) => c.trim().split(/\s+/)[0] ?? '')
      .filter(Boolean)
      .join(', ');
    const more = /CREATE\s+(?:UNIQUE\s+)?INDEX/gi;
    const count = (sql.match(more) || []).length;
    const suffix = count > 1 ? ` (+${count - 1} more)` : '';
    return `Add an index on ${table} (${cols})${suffix}`;
  }
  if (/ALTER\s+TABLE/i.test(sql)) return `Alter ${bareName(sql.match(/ALTER\s+TABLE\s+([\w."]+)/i)?.[1] ?? 'a table')}`;
  if (/VACUUM|ANALYZE\s+\w/i.test(sql)) return 'Run maintenance on the table';
  if (/CREATE\s+MATERIALIZED\s+VIEW/i.test(sql)) return 'Add a materialized view';
  if (/^SELECT|^WITH/i.test(sql)) return 'Rewrite the query';
  return 'Apply the suggested change';
}

function bareName(qualified: string): string {
  return qualified.replace(/"/g, '').split('.').pop() ?? qualified;
}

/** Split a multi-statement suggestion into schema changes vs. a query rewrite. */
export function splitFix(suggestedSql: string | null): { ddl: string[]; rewrite: string | null } {
  if (!suggestedSql?.trim()) return { ddl: [], rewrite: null };
  const statements = suggestedSql
    .split(/;\s*(?:\n|$)/)
    .map((s) => s.trim())
    .filter(Boolean);
  const ddl: string[] = [];
  let rewrite: string | null = null;
  for (const s of statements) {
    if (/^(SELECT|WITH)\b/i.test(s)) rewrite = rewrite ? `${rewrite};\n\n${s}` : s;
    else ddl.push(s.endsWith(';') ? s : `${s};`);
  }
  return { ddl, rewrite };
}

export function tableList(referencedTables: string | null): string[] {
  if (!referencedTables) return [];
  return referencedTables.split(',').map((t) => t.trim()).filter(Boolean);
}

// --- Execution plan --------------------------------------------------------

export interface PlanState {
  ok: boolean;
  /** Present when the plan ran. */
  plan?: string;
  /** Plain explanation of why there is no plan, when it did not. */
  reason?: string;
  detail?: string;
}

/**
 * The backend stores EXPLAIN failures as the raw JDBC error string. Showing that
 * verbatim makes a working product look broken, so translate the known cases.
 */
export function readPlan(explainPlan: string | null, rawQuery: string): PlanState {
  if (!explainPlan?.trim()) {
    return {
      ok: false,
      reason: 'No execution plan captured yet',
      detail: 'The plan is collected the next time this query is analyzed.',
    };
  }
  if (!/^EXPLAIN failed/i.test(explainPlan.trim())) {
    return { ok: true, plan: explainPlan };
  }

  const detail = explainPlan.replace(/^EXPLAIN failed:\s*/i, '');

  if (/bind message supplies 0 parameters|requires \d+ parameters?/i.test(detail)) {
    return {
      ok: false,
      reason: 'This query uses bind parameters',
      detail:
        'Postgres needs real values for $1, $2… before it will plan the statement, and OptiQuery does not guess production values. The diagnosis below uses the query text and your live schema instead.',
    };
  }
  if (/^\s*DO\s+\$\$/im.test(rawQuery) || /bad SQL grammar/i.test(detail)) {
    return {
      ok: false,
      reason: 'Postgres cannot plan this statement',
      detail:
        'EXPLAIN only accepts a single planned statement, so procedural blocks and utility commands have no plan of their own. The diagnosis below uses the query text and your live schema instead.',
    };
  }
  return {
    ok: false,
    reason: 'The execution plan could not be captured',
    detail: firstLine(detail),
  };
}

function firstLine(s: string): string {
  const line = (s.split('\n')[0] ?? '').trim();
  return line.length > 220 ? `${line.slice(0, 220)}…` : line;
}

// --- Schema evidence -------------------------------------------------------

export interface TableFacts {
  name: string;
  estimatedRows: number | null;
  columns: { name: string; type: string; nullable: boolean }[];
  indexes: { name: string; definition: string; columns: string }[];
}

/**
 * Parse the plain-text schema snapshot the analyzer sends to the model.
 * Rendering it as facts — row counts, which columns are actually indexed — is
 * what lets someone check the diagnosis instead of taking it on faith.
 */
export function parseSchema(schemaContext: string | null): TableFacts[] {
  if (!schemaContext?.trim()) return [];
  const tables: TableFacts[] = [];
  let current: TableFacts | null = null;
  let section: 'columns' | 'indexes' | null = null;

  for (const rawLine of schemaContext.split('\n')) {
    const line = rawLine.trim();
    if (!line) continue;

    const table = line.match(/^TABLE:\s*(.+)$/i);
    if (table) {
      current = { name: (table[1] ?? '').trim(), estimatedRows: null, columns: [], indexes: [] };
      tables.push(current);
      section = null;
      continue;
    }
    if (!current) continue;

    const rows = line.match(/^Estimated rows:\s*([\d.]+)/i);
    if (rows) { current.estimatedRows = Number(rows[1] ?? 0); continue; }

    if (/^COLUMNS:/i.test(line)) { section = 'columns'; continue; }
    if (/^INDEXES:/i.test(line)) { section = 'indexes'; continue; }

    if (!line.startsWith('-')) continue;
    const entry = line.replace(/^-\s*/, '');

    if (section === 'columns') {
      const name = entry.split(/\s+/)[0] ?? '';
      if (!name) continue;
      const nullable = !/\bNOT NULL\b/i.test(entry);
      const type = entry
        .slice(name.length)
        .replace(/\b(NOT NULL|NULL)\b.*$/i, '')
        .trim() || 'unknown';
      current.columns.push({ name, type, nullable });
    } else if (section === 'indexes') {
      const [namePart = entry, ...rest] = entry.split(':');
      const definition = rest.join(':').trim();
      const cols = definition.match(/\(([^)]*)\)\s*$/)?.[1] ?? '';
      current.indexes.push({ name: namePart.trim(), definition, columns: cols });
    }
  }
  return tables;
}

/** True when a fix exists and can be copied, however it was produced. */
export function hasFix(kind: Triage): boolean {
  return kind === 'fix-ready' || kind === 'fix-stale' || kind === 'applied';
}

/** Columns the query filters, joins or sorts on that no index covers. */
export function unindexedColumns(t: TableFacts): string[] {
  const indexed = new Set(
    t.indexes.flatMap((i) =>
      i.columns.split(',').map((c) => (c.trim().split(/\s+/)[0] ?? '').replace(/"/g, '').toLowerCase())
    )
  );
  return t.columns.filter((c) => !indexed.has(c.name.toLowerCase())).map((c) => c.name);
}

// --- Aggregates ------------------------------------------------------------

export interface Ledger {
  totalMs: number;
  maxMs: number;
  fixesReady: number;
  staleFixes: number;
  analyzing: number;
  failed: number;
}

export function ledgerOf(queries: SlowQuery[]): Ledger {
  const live = queries.filter((q) => q.status !== 'DISMISSED');
  return {
    totalMs: live.reduce((sum, q) => sum + q.totalExecTimeMs, 0),
    maxMs: live.reduce((max, q) => Math.max(max, q.totalExecTimeMs), 0),
    fixesReady: queries.filter((q) => triageOf(q).kind === 'fix-ready').length,
    staleFixes: queries.filter((q) => triageOf(q).kind === 'fix-stale').length,
    analyzing: queries.filter((q) => triageOf(q).kind === 'reviewing').length,
    failed: queries.filter((q) => triageOf(q).kind === 'failed').length,
  };
}
