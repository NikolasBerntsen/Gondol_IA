/**
 * Tipos del módulo B (dashboards, estadísticas, alertas e IA). Espejan los DTOs de `docs/api-b.md`.
 */
import type {
  AiRunStatus,
  AiRunTrigger,
  AlertStatus,
  AlertType,
  BranchScoped,
  ExpiryBucket,
  RecommendationStatus,
  RecommendationType,
  ReorderStatus,
  SalesPattern,
  Severity,
} from '@/api/types';

/** `ALL` = consolidado de todas las sucursales accesibles, `BRANCH` = una sola (SPEC §3.5). */
export type ScopeLabel = 'ALL' | 'BRANCH';

// ---------------------------------------------------------------------------
// Inicio
// ---------------------------------------------------------------------------

export interface DashboardSummary {
  scope: ScopeLabel;
  branchCount: number;
  productsCount: number;
  expiringSoonCount: number;
  expiredCount: number;
  lowStockCount: number;
  outOfStockCount: number;
  inventoryCostValue: number;
  inventorySaleValue: number;
  openAlertsCount: number;
  pendingRecommendationsCount: number;
  openRecallMatchesCount: number;
  todaySalesUnits: number;
  todaySalesAmount: number;
  lastAiRunAt: string | null;
  today: string;
  asOf: string;
}

export interface SalesStockPoint {
  date: string;
  salesUnits: number;
  salesAmount: number;
  stockUnits: number;
}

export interface BranchComparisonRow extends BranchScoped {
  salesUnits: number;
  salesAmount: number;
  inventoryCostValue: number;
  expiringSoonCount: number;
  lowStockCount: number;
  wasteValue: number;
  openAlertsCount: number;
}

export interface UpcomingExpirationRow extends BranchScoped {
  lotId: number;
  productId: number;
  productName: string;
  lotNumber: string | null;
  expiryDate: string;
  daysLeft: number;
  quantity: number;
  bucket: ExpiryBucket;
}

export interface ReorderRow extends BranchScoped {
  productId: number;
  productName: string;
  brand: string | null;
  sellableStock: number;
  minStock: number;
  suggestedQuantity: number;
  status: ReorderStatus;
  predictedStockoutDate: string | null;
}

// ---------------------------------------------------------------------------
// Estadísticas
// ---------------------------------------------------------------------------

export interface DayPoint {
  date: string;
  units: number;
  amount: number;
}

export interface WeekPoint {
  weekStart: string;
  label: string;
  units: number;
  amount: number;
}

export interface CategoryPoint {
  categoryId: number | null;
  categoryName: string;
  units: number;
  amount: number;
  sharePct: number;
}

export interface BranchPoint extends BranchScoped {
  units: number;
  amount: number;
  sharePct: number;
}

export interface SourcePoint {
  source: string;
  units: number;
  amount: number;
}

export interface ProductPoint {
  productId: number;
  productName: string;
  brand: string | null;
  categoryName: string | null;
  units: number;
  amount: number;
  margin: number;
  abcClass: string | null;
}

export interface AbcBucket {
  abcClass: string;
  products: number;
  units: number;
  amount: number;
  sharePct: number;
}

export interface RotationRow {
  productId: number;
  productName: string;
  units: number;
  avgDailySales: number;
  sellableStock: number;
  daysOfCover: number | null;
  turnoverRatio: number | null;
  pattern: SalesPattern | null;
}

export interface MonthPoint {
  month: string;
  units: number;
  value: number;
}

export interface WasteReason {
  type: string;
  units: number;
  value: number;
}

export interface StatisticsOverview {
  scope: ScopeLabel;
  branchCount: number;
  days: number;
  from: string;
  to: string;
  sales: {
    units: number;
    amount: number;
    cost: number;
    margin: number;
    marginPct: number;
    avgDailyUnits: number;
    avgDailyAmount: number;
    bestDayAmount: number;
    bestDay: string | null;
    byDay: DayPoint[];
    byWeek: WeekPoint[];
    byCategory: CategoryPoint[];
    byBranch: BranchPoint[];
    bySource: SourcePoint[];
  };
  products: {
    top: ProductPoint[];
    bottom: ProductPoint[];
    abc: AbcBucket[];
    rotation: RotationRow[];
    withoutSales: number;
  };
  losses: {
    wasteUnits: number;
    wasteValue: number;
    wasteByMonth: MonthPoint[];
    wasteByReason: WasteReason[];
    lostSales: { units: number; estimatedAmount: number; events: number };
    expiredLots: number;
    expiredUnits: number;
    expiredValue: number;
    expiringRiskValue: number;
  };
  ai: {
    recommendations: {
      total: number;
      pending: number;
      accepted: number;
      discarded: number;
      expired: number;
      acceptanceRatePct: number;
      expectedImpactAccepted: number;
    };
    recoveredSales: {
      units: number;
      amount: number;
      costValue: number;
      avgDiscountPct: number;
      lots: number;
    };
    lastRunAt: string | null;
    productsWithInsights: number;
  };
}

// ---------------------------------------------------------------------------
// Alertas
// ---------------------------------------------------------------------------

export interface AlertRow {
  id: number;
  branchId: number | null;
  branchName: string | null;
  type: AlertType;
  severity: Severity;
  status: AlertStatus;
  productId: number | null;
  productName: string | null;
  lotId: number | null;
  lotNumber: string | null;
  announcementId: number | null;
  title: string;
  message: string | null;
  createdAt: string;
  updatedAt: string;
  handledByName: string | null;
  resolvedAt: string | null;
}

export interface AlertCounts {
  open: number;
  acknowledged: number;
  resolved: number;
  dismissed: number;
  critical: number;
  warning: number;
  info: number;
  byType: Record<string, number>;
}

export interface AlertListParams {
  status?: string;
  type?: string;
  severity?: string;
  q?: string;
  page?: number;
  size?: number;
}

// ---------------------------------------------------------------------------
// Inteligencia IA
// ---------------------------------------------------------------------------

export interface AiRun {
  id: number | null;
  branchId: number;
  branchName: string | null;
  status: AiRunStatus;
  trigger: AiRunTrigger;
  modelVersion: string | null;
  productsAnalyzed: number | null;
  recommendationsCreated: number | null;
  startedAt: string;
  finishedAt: string | null;
  errorMessage: string | null;
}

export interface LotRiskRow {
  branchId: number;
  branchName: string | null;
  productId: number;
  productName: string;
  lotId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  daysToExpiry: number | null;
  quantity: number;
  unitsAtRisk: number;
  riskLevel: string | null;
  recommendedDiscountPct: number | null;
  valueAtRisk: number;
}

export interface InsightsSummary {
  scope: ScopeLabel;
  branchCount: number;
  productsAnalyzed: number;
  lastRunAt: string | null;
  running: boolean;
  aiAvailable: boolean;
  patternCounts: Record<string, number>;
  abcCounts: Record<string, number>;
  pendingRecommendations: number;
  recommendationsByType: Record<string, number>;
  atRiskValue: number;
  predictedStockouts7d: number;
  anomalies7d: number;
  topRisks: LotRiskRow[];
  runs: AiRun[];
}

export interface ProductInsightRow extends BranchScoped {
  productId: number;
  productName: string;
  brand: string | null;
  categoryName: string | null;
  pattern: SalesPattern | null;
  patternDescription: string | null;
  abcClass: string | null;
  xyzClass: string | null;
  avgDailySales: number | null;
  trendPct: number | null;
  daysOfCover: number | null;
  predictedStockoutDate: string | null;
  reorderPoint: number | null;
  safetyStock: number | null;
  suggestedOrderQty: number | null;
  sellableStock: number;
  minStock: number;
  anomaliesCount: number;
  lotsAtRisk: number;
  unitsAtRisk: number;
  updatedAt: string | null;
}

export interface ForecastPoint {
  date: string;
  yhat: number;
  lo?: number | null;
  hi?: number | null;
}

export interface AnomalyPoint {
  date: string;
  quantity: number;
  expected: number;
  score: number;
  kind: string;
}

export interface LotRisk {
  lotId: number;
  daysToExpiry: number | null;
  quantity: number;
  expectedSalesBeforeExpiry: number | null;
  unitsAtRisk: number;
  riskLevel: string | null;
  recommendedDiscountPct: number | null;
  expectedUnitsSoldWithDiscount: number | null;
}

export interface HistoryPoint {
  date: string;
  units: number;
  amount: number;
}

export interface InsightLotRow {
  lotId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  daysToExpiry: number | null;
  quantity: number;
  discountPct: number | null;
  status: string;
  rotationRank: number;
  bucket: ExpiryBucket;
}

export interface ProductInsightDetail {
  insight: ProductInsightRow;
  barcode: string | null;
  salePrice: number | null;
  costPrice: number | null;
  perishable: boolean;
  history: HistoryPoint[];
  weekdayProfile: number[] | null;
  forecast: ForecastPoint[] | null;
  forecastMethod: string | null;
  anomalies: AnomalyPoint[] | null;
  lotRisks: LotRisk[] | null;
  lots: InsightLotRow[];
  recommendations: RecommendationRow[];
}

export interface InsightsListParams {
  pattern?: string;
  abc?: string;
  q?: string;
  page?: number;
  size?: number;
}

export interface RunLaunched {
  message: string;
  runs: AiRun[];
}

// ---------------------------------------------------------------------------
// Recomendaciones
// ---------------------------------------------------------------------------

export interface RecommendationOutcome {
  appliedDiscountPct?: number;
  unitsBefore7d?: number;
  unitsAfter7d?: number;
  lift?: number | null;
  lotUnitsSold?: number;
  lotUnitsRemaining?: number;
  lotUnitsAtAccept?: number;
  discardedQuantity?: number;
  orderedQuantity?: number;
  measuredAt?: string;
}

export interface RecommendationRow {
  id: number;
  branchId: number;
  branchName: string | null;
  type: RecommendationType;
  status: RecommendationStatus;
  productId: number | null;
  productName: string | null;
  brand: string | null;
  lotId: number | null;
  lotNumber: string | null;
  lotExpiryDate: string | null;
  title: string;
  explanation: string;
  suggestedQuantity: number | null;
  suggestedDiscountPct: number | null;
  suggestedDate: string | null;
  priority: number;
  confidence: number | null;
  expectedImpact: number | null;
  createdAt: string;
  decidedAt: string | null;
  decidedByName: string | null;
  decisionNote: string | null;
  outcome: RecommendationOutcome | null;
}

export interface RecommendationDecision {
  recommendation: RecommendationRow;
  message: string;
  appliedDiscountPct: number | null;
  discardedQuantity: number | null;
  orderedQuantity: number | null;
  whatsappText: string | null;
  whatsappUrl: string | null;
}

export interface RecommendationListParams {
  status?: string;
  type?: string;
  productId?: number;
  q?: string;
  page?: number;
  size?: number;
}

export interface AcceptBody {
  note?: string;
  quantity?: number;
  discountPct?: number;
}
