import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  AlertTriangle, CheckCircle, Clock, XCircle, TrendingUp,
  Search, ChevronLeft, ChevronRight, Loader2, Zap
} from 'lucide-react';
import clsx from 'clsx';
import { fetchSlowQueries, fetchStats } from '../api';
import type { AnalysisStatus, RiskLevel } from '../types';

const statusColors: Record<AnalysisStatus, string> = {
  PENDING: 'bg-yellow-500/20 text-yellow-400',
  ANALYZING: 'bg-blue-500/20 text-blue-400',
  COMPLETED: 'bg-green-500/20 text-green-400',
  FAILED: 'bg-red-500/20 text-red-400',
  DISMISSED: 'bg-gray-500/20 text-gray-400',
  APPLIED: 'bg-emerald-500/20 text-emerald-400',
};

const riskColors: Record<RiskLevel, string> = {
  LOW: 'text-green-400',
  MEDIUM: 'text-yellow-400',
  HIGH: 'text-red-400',
};

export default function Dashboard() {
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState<AnalysisStatus | ''>('');
  const [searchTerm, setSearchTerm] = useState('');
  const size = 15;

  const { data: stats, isLoading: statsLoading } = useQuery({
    queryKey: ['stats'],
    queryFn: fetchStats,
  });

  const { data: queryData, isLoading: queriesLoading } = useQuery({
    queryKey: ['slow-queries', page, statusFilter],
    queryFn: () => fetchSlowQueries(page, size, statusFilter || undefined),
  });

  const filteredQueries = queryData?.queries.filter((q) =>
    !searchTerm || q.rawQuery.toLowerCase().includes(searchTerm.toLowerCase())
  ) ?? [];

  const totalPages = queryData ? Math.ceil(queryData.totalCount / size) : 0;

  return (
    <div className="p-8 space-y-8">
      {/* Header */}
      <div>
        <h2 className="text-2xl font-bold text-white">Query Monitor</h2>
        <p className="text-gray-500 mt-1">
          Real-time slow query detection and AI-powered optimization
        </p>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
        <StatCard
          label="Total Queries"
          value={stats?.totalQueries ?? 0}
          icon={TrendingUp}
          color="text-optiq-400"
          loading={statsLoading}
        />
        <StatCard
          label="Pending Analysis"
          value={stats?.pendingAnalysis ?? 0}
          icon={Clock}
          color="text-yellow-400"
          loading={statsLoading}
        />
        <StatCard
          label="Analyzed"
          value={stats?.completedAnalysis ?? 0}
          icon={CheckCircle}
          color="text-green-400"
          loading={statsLoading}
        />
        <StatCard
          label="Dismissed"
          value={stats?.dismissed ?? 0}
          icon={XCircle}
          color="text-gray-400"
          loading={statsLoading}
        />
        <StatCard
          label="Failed"
          value={stats?.failed ?? 0}
          icon={AlertTriangle}
          color="text-red-400"
          loading={statsLoading}
        />
      </div>

      {/* Filters & Search */}
      <div className="flex items-center gap-4">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
          <input
            type="text"
            placeholder="Search queries..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-10 pr-4 py-2.5 bg-gray-900 border border-gray-800 rounded-lg text-sm
                       text-gray-200 placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-optiq-500"
          />
        </div>

        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value as AnalysisStatus | ''); setPage(0); }}
          className="px-4 py-2.5 bg-gray-900 border border-gray-800 rounded-lg text-sm text-gray-200
                     focus:outline-none focus:ring-2 focus:ring-optiq-500"
        >
          <option value="">All Statuses</option>
          <option value="PENDING">Pending</option>
          <option value="COMPLETED">Completed</option>
          <option value="DISMISSED">Dismissed</option>
          <option value="FAILED">Failed</option>
        </select>
      </div>

      {/* Query Table */}
      <div className="bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
        {queriesLoading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-6 h-6 text-optiq-400 animate-spin" />
          </div>
        ) : filteredQueries.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-gray-500">
            <Zap className="w-10 h-10 mb-3 text-gray-700" />
            <p className="font-medium">No slow queries detected yet</p>
            <p className="text-sm mt-1">The monitor is running and watching for queries slower than your threshold.</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-800 text-gray-500 text-left">
                  <th className="px-4 py-3 font-medium">Query</th>
                  <th className="px-4 py-3 font-medium text-right">Mean Time</th>
                  <th className="px-4 py-3 font-medium text-right">Calls</th>
                  <th className="px-4 py-3 font-medium">Tables</th>
                  <th className="px-4 py-3 font-medium">Risk</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800/50">
                {filteredQueries.map((q) => (
                  <tr
                    key={q.id}
                    className="hover:bg-gray-800/50 transition-colors"
                  >
                    <td className="px-4 py-3 max-w-md">
                      <Link
                        to={`/query/${q.id}`}
                        className="text-optiq-400 hover:text-optiq-300 font-mono text-xs line-clamp-2 block"
                      >
                        {q.rawQuery.length > 120
                          ? q.rawQuery.substring(0, 120) + '…'
                          : q.rawQuery}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-xs text-orange-400">
                      {q.meanExecTimeMs.toFixed(1)} ms
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-xs text-gray-400">
                      {q.callCount.toLocaleString()}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-400">
                      {q.referencedTables ?? '—'}
                    </td>
                    <td className="px-4 py-3">
                      {q.riskLevel ? (
                        <span className={clsx('text-xs font-medium', riskColors[q.riskLevel])}>
                          {q.riskLevel}
                        </span>
                      ) : (
                        <span className="text-gray-600 text-xs">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={clsx(
                          'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
                          statusColors[q.status]
                        )}
                      >
                        {q.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-gray-800">
            <p className="text-xs text-gray-500">
              Page {page + 1} of {totalPages} · {queryData?.totalCount} total
            </p>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="p-1.5 rounded-md bg-gray-800 text-gray-400 hover:text-gray-200 disabled:opacity-30"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="p-1.5 rounded-md bg-gray-800 text-gray-400 hover:text-gray-200 disabled:opacity-30"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// --- Stat Card Component ---

function StatCard({
  label,
  value,
  icon: Icon,
  color,
  loading,
}: {
  label: string;
  value: number;
  icon: React.ComponentType<{ className?: string }>;
  color: string;
  loading: boolean;
}) {
  return (
    <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
      <div className="flex items-center justify-between">
        <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">{label}</p>
        <Icon className={clsx('w-4 h-4', color)} />
      </div>
      <p className={clsx('text-2xl font-bold mt-2', loading ? 'text-gray-700' : 'text-white')}>
        {loading ? '—' : value.toLocaleString()}
      </p>
    </div>
  );
}
