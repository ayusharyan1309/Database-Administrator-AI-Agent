import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft, Copy, CheckCircle, XCircle, RefreshCw,
  AlertTriangle, Clock, Loader2, Zap
} from 'lucide-react';
import { useState } from 'react';
import clsx from 'clsx';
import { fetchSlowQuery, dismissQuery, reanalyzeQuery } from '../api';
import type { RiskLevel } from '../types';

const riskColors: Record<RiskLevel, { bg: string; text: string; border: string }> = {
  LOW: { bg: 'bg-green-500/10', text: 'text-green-400', border: 'border-green-500/30' },
  MEDIUM: { bg: 'bg-yellow-500/10', text: 'text-yellow-400', border: 'border-yellow-500/30' },
  HIGH: { bg: 'bg-red-500/10', text: 'text-red-400', border: 'border-red-500/30' },
};

export default function QueryDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [copied, setCopied] = useState(false);

  const { data: query, isLoading } = useQuery({
    queryKey: ['slow-query', id],
    queryFn: () => fetchSlowQuery(Number(id)),
    enabled: !!id,
  });

  const dismissMutation = useMutation({
    mutationFn: () => dismissQuery(Number(id)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['slow-query', id] });
      queryClient.invalidateQueries({ queryKey: ['slow-queries'] });
      queryClient.invalidateQueries({ queryKey: ['stats'] });
    },
  });

  const reanalyzeMutation = useMutation({
    mutationFn: () => reanalyzeQuery(Number(id)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['slow-query', id] });
      queryClient.invalidateQueries({ queryKey: ['slow-queries'] });
    },
  });

  const handleCopySql = () => {
    if (query?.suggestedSql) {
      navigator.clipboard.writeText(query.suggestedSql);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="w-8 h-8 text-optiq-400 animate-spin" />
      </div>
    );
  }

  if (!query) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-gray-500">
        <AlertTriangle className="w-10 h-10 mb-3" />
        <p className="font-medium">Query not found</p>
        <Link to="/" className="text-optiq-400 text-sm mt-2 hover:underline">← Back to dashboard</Link>
      </div>
    );
  }

  const risk = query.riskLevel ? riskColors[query.riskLevel] : null;

  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <header className="flex items-center justify-between px-6 py-4 border-b border-gray-800 bg-gray-900/50">
        <div className="flex items-center gap-4">
          <Link
            to="/"
            className="p-2 rounded-lg text-gray-400 hover:text-gray-200 hover:bg-gray-800 transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
          </Link>
          <div>
            <h2 className="text-lg font-bold text-white">Query #{query.id}</h2>
            <p className="text-xs text-gray-500">
              Detected {new Date(query.detectedAt).toLocaleString()} · {query.detectionCount}× detected
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={() => reanalyzeMutation.mutate()}
            disabled={reanalyzeMutation.isPending}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-gray-800 text-gray-300 text-sm
                       hover:bg-gray-700 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={clsx('w-3.5 h-3.5', reanalyzeMutation.isPending && 'animate-spin')} />
            Re-analyze
          </button>
          <button
            onClick={() => dismissMutation.mutate()}
            disabled={dismissMutation.isPending || query.status === 'DISMISSED'}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-gray-800 text-gray-300 text-sm
                       hover:bg-gray-700 transition-colors disabled:opacity-50"
          >
            <XCircle className="w-3.5 h-3.5" />
            Dismiss
          </button>
        </div>
      </header>

      {/* Metrics bar */}
      <div className="flex items-center gap-6 px-6 py-3 border-b border-gray-800 bg-gray-900/30 text-sm">
        <div className="flex items-center gap-2">
          <Clock className="w-4 h-4 text-orange-400" />
          <span className="text-gray-400">Mean Time:</span>
          <span className="font-mono text-orange-400 font-medium">{query.meanExecTimeMs.toFixed(1)} ms</span>
        </div>
        <div className="flex items-center gap-2">
          <Zap className="w-4 h-4 text-blue-400" />
          <span className="text-gray-400">Calls:</span>
          <span className="font-mono text-blue-400">{query.callCount.toLocaleString()}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-gray-400">Tables:</span>
          <span className="font-mono text-gray-300">{query.referencedTables ?? '—'}</span>
        </div>
        {risk && (
          <div className="flex items-center gap-2">
            <span className="text-gray-400">Risk:</span>
            <span className={clsx('font-medium', risk.text)}>{query.riskLevel}</span>
          </div>
        )}
      </div>

      {/* Split Screen */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left Panel: Raw SQL + Explain Plan */}
        <div className="flex-1 flex flex-col border-r border-gray-800 overflow-auto">
          <div className="px-6 py-3 border-b border-gray-800 bg-gray-900/50">
            <h3 className="text-sm font-semibold text-gray-300 uppercase tracking-wide">Slow Query</h3>
          </div>
          <div className="p-6">
            <pre className="bg-gray-950 rounded-lg p-4 text-xs font-mono text-gray-300 overflow-x-auto whitespace-pre-wrap border border-gray-800">
              {query.rawQuery}
            </pre>
          </div>

          {query.explainPlan && (
            <>
              <div className="px-6 py-3 border-b border-gray-800 border-t bg-gray-900/50">
                <h3 className="text-sm font-semibold text-gray-300 uppercase tracking-wide">EXPLAIN ANALYZE</h3>
              </div>
              <div className="p-6">
                <pre className="bg-gray-950 rounded-lg p-4 text-xs font-mono text-green-400/80 overflow-x-auto whitespace-pre-wrap border border-gray-800">
                  {query.explainPlan}
                </pre>
              </div>
            </>
          )}
        </div>

        {/* Right Panel: AI Analysis */}
        <div className="flex-1 flex flex-col overflow-auto">
          <div className="px-6 py-3 border-b border-gray-800 bg-gray-900/50">
            <h3 className="text-sm font-semibold text-gray-300 uppercase tracking-wide">AI Diagnosis</h3>
          </div>

          {query.status === 'ANALYZING' ? (
            <div className="flex items-center justify-center py-20">
              <Loader2 className="w-8 h-8 text-optiq-400 animate-spin" />
              <span className="ml-3 text-gray-400">AI is analyzing...</span>
            </div>
          ) : query.status === 'FAILED' ? (
            <div className="flex flex-col items-center justify-center py-20 text-red-400">
              <AlertTriangle className="w-10 h-10 mb-3" />
              <p className="font-medium">Analysis failed</p>
              <p className="text-sm text-gray-500 mt-1">Try re-analyzing or check backend logs</p>
            </div>
          ) : query.rootCause ? (
            <div className="p-6 space-y-6">
              {/* Root Cause */}
              <div>
                <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Root Cause</h4>
                <p className="text-sm text-gray-300 leading-relaxed bg-gray-900 rounded-lg p-4 border border-gray-800">
                  {query.rootCause}
                </p>
              </div>

              {/* Confidence Score */}
              <div>
                <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Confidence</h4>
                <div className="flex items-center gap-3">
                  <div className="flex-1 h-2 bg-gray-800 rounded-full overflow-hidden">
                    <div
                      className={clsx(
                        'h-full rounded-full transition-all',
                        (query.confidenceScore ?? 0) >= 80 ? 'bg-green-500' :
                        (query.confidenceScore ?? 0) >= 50 ? 'bg-yellow-500' : 'bg-red-500'
                      )}
                      style={{ width: `${query.confidenceScore ?? 0}%` }}
                    />
                  </div>
                  <span className="text-sm font-mono text-gray-400">{query.confidenceScore}%</span>
                </div>
              </div>

              {/* Suggested SQL */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Suggested Fix</h4>
                  <button
                    onClick={handleCopySql}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-md bg-gray-800 text-gray-400 text-xs
                               hover:bg-gray-700 hover:text-gray-200 transition-colors"
                  >
                    {copied ? <CheckCircle className="w-3 h-3 text-green-400" /> : <Copy className="w-3 h-3" />}
                    {copied ? 'Copied!' : 'Copy SQL'}
                  </button>
                </div>
                <pre className="bg-gray-950 rounded-lg p-4 text-xs font-mono text-optiq-400 overflow-x-auto whitespace-pre-wrap border border-optiq-800/30">
                  {query.suggestedSql || 'No fix suggested — manual review recommended.'}
                </pre>
              </div>

              {/* Model Info */}
              <div className="text-xs text-gray-600 pt-2 border-t border-gray-800">
                Analyzed by <span className="text-gray-500">{query.modelUsed}</span>
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center py-20 text-gray-500">
              <Zap className="w-10 h-10 mb-3 text-gray-700" />
              <p className="font-medium">No analysis yet</p>
              <p className="text-sm mt-1">Click "Re-analyze" to trigger AI diagnosis</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
