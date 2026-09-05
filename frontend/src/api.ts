import type { SlowQueriesResponse, SlowQuery, QueryStats, HealthResponse } from './types';
import { getIdToken } from './lib/firebase';

const BASE_URL = '/api';

/**
 * Every call carries the signed-in user's ID token when there is one. The
 * backend rejects unauthenticated requests only once Firebase is configured on
 * its side too, so an unconfigured install keeps working.
 */
async function authHeaders(): Promise<HeadersInit> {
  const token = await getIdToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { ...(await authHeaders()), ...(init.headers ?? {}) },
  });

  if (response.status === 401) {
    throw new Error('Your session has expired. Sign in again.');
  }
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/**
 * `fetch` for callers that need the raw Response (status codes, custom bodies),
 * with the auth header applied. Path is relative to /api.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { ...(await authHeaders()), ...(init.headers ?? {}) },
  });
}

async function fetchJson<T>(path: string): Promise<T> {
  return request<T>(path);
}

async function postJson<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'POST' });
}

// --- Query list ---

export async function fetchSlowQueries(
  page = 0,
  size = 20,
  status?: string,
  sort: 'totalTime' | 'meanTime' = 'totalTime'
): Promise<SlowQueriesResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size), sort });
  if (status) params.set('status', status);
  return fetchJson(`/queries/slow?${params}`);
}

export async function fetchSlowQuery(id: number): Promise<SlowQuery> {
  return fetchJson(`/queries/slow/${id}`);
}

// --- Actions ---

export async function dismissQuery(id: number, note?: string): Promise<SlowQuery> {
  return request(`/queries/${id}/dismiss`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ note: note ?? null }),
  });
}

export async function reanalyzeQuery(id: number): Promise<SlowQuery> {
  return postJson(`/queries/${id}/reanalyze`);
}

// --- Team actions on a query ---

export type ActivityAction = 'APPLIED' | 'DISMISSED' | 'REOPENED' | 'REANALYZED';

export interface ActivityEvent {
  id: string;
  queryId: number;
  action: ActivityAction;
  /** How a person would say it: "applied the fix". */
  phrase: string;
  actorUid: string | null;
  actorEmail: string | null;
  actorName: string | null;
  note: string | null;
  at: string | null;
}

const jsonHeaders = { 'Content-Type': 'application/json' };

/** Record that someone ran the suggested fix. OptiQuery never runs it itself. */
export async function markApplied(id: number, note?: string): Promise<SlowQuery> {
  return request(`/queries/${id}/apply`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ note: note ?? null }),
  });
}

/** Put a dismissed or applied query back into the ranked list. */
export async function reopenQuery(id: number, note?: string): Promise<SlowQuery> {
  return request(`/queries/${id}/reopen`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ note: note ?? null }),
  });
}

export async function fetchActivity(id: number): Promise<ActivityEvent[]> {
  const body = await request<{ activity: ActivityEvent[] }>(`/queries/${id}/activity`);
  return body.activity;
}

/** Latest action per query id, for marking untouched rows in the list. */
export async function fetchTeamActivity(): Promise<Record<string, ActivityEvent>> {
  const body = await request<{ latest: Record<string, ActivityEvent> }>('/queries/activity');
  return body.latest;
}

// --- Stats & Health ---

export async function fetchStats(): Promise<QueryStats> {
  return fetchJson('/queries/stats');
}

export async function fetchHealth(): Promise<HealthResponse> {
  return fetchJson('/health');
}

/** Ask the daemon to sample pg_stat_statements right now. */
export async function pollNow(): Promise<{ success: boolean; message: string }> {
  return postJson('/queries/poll');
}

// --- Auth ---

export interface SessionUser {
  authEnabled: boolean;
  uid?: string;
  email?: string;
  displayName?: string;
  photoURL?: string;
  emailVerified?: boolean;
}

/** Records the sign-in server-side, creating users/{uid} on first visit. */
export async function startSession(): Promise<SessionUser> {
  return postJson('/auth/session');
}
