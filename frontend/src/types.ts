/** Types matching the backend REST API responses */

export type AnalysisStatus =
  | 'PENDING'
  | 'ANALYZING'
  | 'COMPLETED'
  | 'FAILED'
  | 'DISMISSED'
  | 'APPLIED';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export interface SlowQuery {
  id: number;
  queryFingerprint: string;
  rawQuery: string;
  meanExecTimeMs: number;
  callCount: number;
  totalExecTimeMs: number;
  referencedTables: string | null;
  explainPlan: string | null;
  schemaContext: string | null;
  status: AnalysisStatus;
  detectionCount: number;
  detectedAt: string;
  updatedAt: string | null;
  // AI analysis fields (null if no analysis yet)
  rootCause: string | null;
  suggestedSql: string | null;
  confidenceScore: number | null;
  riskLevel: RiskLevel | null;
  modelUsed: string | null;
}

export interface SlowQueriesResponse {
  queries: SlowQuery[];
  totalCount: number;
  page: number;
  size: number;
}

export interface QueryStats {
  totalQueries: number;
  pendingAnalysis: number;
  completedAnalysis: number;
  dismissed: number;
  applied: number;
  failed: number;
}

export interface HealthResponse {
  status: string;
  service: string;
  timestamp: string;
  aiProvider: string;
  slowQueryThresholdMs: number;
}
