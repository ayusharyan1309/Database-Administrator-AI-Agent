import type { SlowQueriesResponse, SlowQuery, QueryStats, HealthResponse } from './types';

const BASE_URL = '/api';

async function fetchJson<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`);
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

async function postJson<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, { method: 'POST' });
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

// --- Query list ---

export async function fetchSlowQueries(
  page = 0,
  size = 20,
  status?: string
): Promise<SlowQueriesResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set('status', status);
  return fetchJson(`/queries/slow?${params}`);
}

export async function fetchSlowQuery(id: number): Promise<SlowQuery> {
  return fetchJson(`/queries/slow/${id}`);
}

// --- Actions ---

export async function dismissQuery(id: number): Promise<SlowQuery> {
  return postJson(`/queries/${id}/dismiss`);
}

export async function reanalyzeQuery(id: number): Promise<SlowQuery> {
  return postJson(`/queries/${id}/reanalyze`);
}

// --- Stats & Health ---

export async function fetchStats(): Promise<QueryStats> {
  return fetchJson('/queries/stats');
}

export async function fetchHealth(): Promise<HealthResponse> {
  return fetchJson('/health');
}
