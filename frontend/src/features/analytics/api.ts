/**
 * Llamadas y query keys del módulo B. Todo lo que devuelve el backend respeta el alcance de sucursales
 * (header `X-Branch-Id`), así que las keys terminan con la sucursal elegida (`useBranchQueryKey`).
 */
import { apiGet, apiPost } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type {
  AcceptBody,
  AiRun,
  AlertCounts,
  AlertListParams,
  AlertRow,
  BranchComparisonRow,
  DashboardSummary,
  InsightsListParams,
  InsightsSummary,
  ProductInsightDetail,
  ProductInsightRow,
  RecommendationDecision,
  RecommendationListParams,
  RecommendationRow,
  ReorderRow,
  RunLaunched,
  SalesStockPoint,
  StatisticsOverview,
  UpcomingExpirationRow,
} from './types';

export const dashboardApi = {
  summary: () => apiGet<DashboardSummary>('/tenant/dashboard/summary'),
  salesStockTrend: (days: number) =>
    apiGet<SalesStockPoint[]>('/tenant/dashboard/sales-stock-trend', { days }),
  branchComparison: (days: number) =>
    apiGet<BranchComparisonRow[]>('/tenant/dashboard/branch-comparison', { days }),
  upcomingExpirations: (limit: number) =>
    apiGet<UpcomingExpirationRow[]>('/tenant/dashboard/upcoming-expirations', { limit }),
  reorder: (limit: number) => apiGet<ReorderRow[]>('/tenant/dashboard/reorder', { limit }),
};

export const statisticsApi = {
  overview: (days: number) => apiGet<StatisticsOverview>('/tenant/statistics/overview', { days }),
};

export const alertsApi = {
  list: (params: AlertListParams) => apiGet<PageResponse<AlertRow>>('/tenant/alerts', params),
  counts: () => apiGet<AlertCounts>('/tenant/alerts/counts'),
  acknowledge: (id: number) => apiPost<AlertRow>(`/tenant/alerts/${id}/acknowledge`),
  resolve: (id: number) => apiPost<AlertRow>(`/tenant/alerts/${id}/resolve`),
  dismiss: (id: number) => apiPost<AlertRow>(`/tenant/alerts/${id}/dismiss`),
};

export const insightsApi = {
  summary: () => apiGet<InsightsSummary>('/tenant/insights/summary'),
  products: (params: InsightsListParams) =>
    apiGet<PageResponse<ProductInsightRow>>('/tenant/insights/products', params),
  productDetail: (productId: number, branchId?: number | null) =>
    apiGet<ProductInsightDetail>(`/tenant/insights/products/${productId}`, {
      branchId: branchId ?? undefined,
    }),
  latestRuns: () => apiGet<AiRun[]>('/tenant/insights/runs/latest'),
  run: () => apiPost<RunLaunched>('/tenant/insights/run'),
};

export const recommendationsApi = {
  list: (params: RecommendationListParams) =>
    apiGet<PageResponse<RecommendationRow>>('/tenant/recommendations', params),
  accept: (id: number, body: AcceptBody) =>
    apiPost<RecommendationDecision>(`/tenant/recommendations/${id}/accept`, body),
  discard: (id: number, note?: string) =>
    apiPost<RecommendationDecision>(`/tenant/recommendations/${id}/discard`, { note }),
};

/** Prefijos de React Query. Invalidá siempre por el prefijo: alcanza a todas las sucursales. */
export const analyticsKeys = {
  dashboard: ['dashboard'] as const,
  statistics: ['statistics'] as const,
  alerts: ['alerts'] as const,
  insights: ['insights'] as const,
  recommendations: ['recommendations'] as const,
};
