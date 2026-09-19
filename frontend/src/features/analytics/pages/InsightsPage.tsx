import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { TooltipProps } from 'recharts';
import { AlertTriangle, ArrowLeft, Boxes, RefreshCw, Sparkles, TrendingDown, TrendingUp } from 'lucide-react';
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  Skeleton,
  StatCard,
  Table,
  Tabs,
  pageInfo,
} from '@/components/ui';
import type { TableColumn } from '@/components/ui';
import { ExpiryChip, LotRankChip, StatusPill } from '@/components/gondola';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { useCurrentUser } from '@/auth/AuthContext';
import { useAccess } from '@/auth/useAccess';
import { isApiError } from '@/api/client';
import { RECOMMENDATION_TYPE_LABELS, SALES_PATTERN_LABELS } from '@/api/types';
import type { RecommendationType, SalesPattern } from '@/api/types';
import { useDebounce } from '@/lib/useDebounce';
import {
  formatDate,
  formatDateTime,
  formatMoney,
  formatNumber,
  formatPercent,
  formatRelative,
  formatShortDate,
} from '@/lib/format';
import { cn } from '@/lib/cn';
import { insightsApi, recommendationsApi } from '../api';
import type { AiRun, ProductInsightRow } from '../types';
import { RecommendationCard } from '../components/RecommendationCard';
import type { AcceptValues } from '../components/RecommendationCard';
import { showDecisionToast } from '../components/decisionToast';
import {
  AXIS_TICK,
  AXIS_TICK_MONO,
  ChartFrame,
  ChartLegend,
  ChartTooltipBox,
  CURSOR,
  GRID_STROKE,
  SERIES_COLORS,
} from '../components/chart';

const PAGE_SIZE = 20;

const PATTERN_OPTIONS = [
  { value: 'ALL', label: 'Todos los patrones' },
  ...(Object.keys(SALES_PATTERN_LABELS) as SalesPattern[]).map((pattern) => ({
    value: pattern,
    label: SALES_PATTERN_LABELS[pattern],
  })),
];

const ABC_OPTIONS = [
  { value: 'ALL', label: 'Todas las clases' },
  { value: 'A', label: 'Clase A' },
  { value: 'B', label: 'Clase B' },
  { value: 'C', label: 'Clase C' },
];

type RiskTone = 'crit' | 'warn' | 'info';

/** Niveles de riesgo por lote de la IA (docs/ai-service.md): `EXPIRED` es un lote que ya venció. */
const RISK_LEVELS: Record<string, { label: string; tone: RiskTone }> = {
  EXPIRED: { label: 'Vencido', tone: 'crit' },
  HIGH: { label: 'Alto', tone: 'crit' },
  MEDIUM: { label: 'Medio', tone: 'warn' },
  LOW: { label: 'Bajo', tone: 'info' },
};

/**
 * Etiqueta y tono de un nivel de riesgo. Un lote con vencimiento pasado es "Vencido" aunque el análisis sea anterior; un
 * nivel desconocido nunca se muestra como "Bajo".
 */
function riskLevel(level: string | null, daysToExpiry: number | null): { label: string; tone: RiskTone } {
  if (daysToExpiry != null && daysToExpiry < 0) return RISK_LEVELS.EXPIRED;
  return (level && RISK_LEVELS[level]) || { label: 'En riesgo', tone: 'warn' };
}

type InsightsTab = 'patrones' | 'recomendaciones';

const RECOMMENDATIONS_PAGE_SIZE = 12;

const RECOMMENDATION_STATUS_OPTIONS = [
  { value: 'PENDING', label: 'Pendientes' },
  { value: 'ACCEPTED', label: 'Aceptadas' },
  { value: 'DISCARDED', label: 'Descartadas' },
  { value: 'EXPIRED', label: 'Vencidas' },
  { value: 'ALL', label: 'Todos los estados' },
];

/** Aceptar y descartar recomendaciones, con los refrescos de todo lo que depende de ellas. */
function useRecommendationDecisions() {
  const queryClient = useQueryClient();
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['insights'] });
    void queryClient.invalidateQueries({ queryKey: ['recommendations'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };
  const accept = useMutation({
    mutationFn: ({ id, values }: { id: number; values: AcceptValues }) => recommendationsApi.accept(id, values),
    onSuccess: (decision) => {
      showDecisionToast(decision);
      refresh();
    },
  });
  const discard = useMutation({
    mutationFn: ({ id, note }: { id: number; note?: string }) => recommendationsApi.discard(id, note),
    onSuccess: (decision) => {
      toast.success(decision.message);
      refresh();
    },
  });
  return { accept, discard, busy: accept.isPending || discard.isPending };
}

function runTone(run: AiRun): 'ok' | 'warn' | 'crit' | 'info' {
  if (run.status === 'OK') return 'ok';
  if (run.status === 'RUNNING') return 'info';
  return 'crit';
}

function RunStatus({ runs }: { runs: AiRun[] }) {
  if (!runs.length) return null;
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-2 text-sm">
      {runs.map((run) => (
        <li key={run.branchId} className="flex items-center gap-2">
          <StatusPill tone={runTone(run)}>
            {run.status === 'OK' ? 'Analizada' : run.status === 'RUNNING' ? 'Analizando…' : 'Con error'}
          </StatusPill>
          <span className="text-muted-foreground">
            {run.branchName}
            {run.status === 'OK' && run.finishedAt ? ` · ${formatRelative(run.finishedAt)}` : ''}
            {run.status === 'OK' && run.productsAnalyzed != null
              ? ` · ${formatNumber(run.productsAnalyzed)} productos`
              : ''}
            {run.status === 'ERROR' && run.errorMessage ? ` · ${run.errorMessage}` : ''}
          </span>
        </li>
      ))}
    </ul>
  );
}

interface SeriesPoint {
  date: string;
  units: number | null;
  yhat: number | null;
  /** Banda de confianza `[lo, hi]` del pronóstico (recharts dibuja el área entre los dos valores). */
  band: [number, number] | null;
}

function DetailTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  const point = payload[0]?.payload as SeriesPoint | undefined;
  if (!point) return null;
  return (
    <ChartTooltipBox
      title={formatDate(point.date)}
      lines={[
        ...(point.units != null
          ? [{ label: 'Vendido', value: `${formatNumber(point.units)} u.`, color: SERIES_COLORS[5], shape: 'line' as const }]
          : []),
        ...(point.yhat != null
          ? [
              {
                label: 'Pronóstico',
                value: `${formatNumber(point.yhat, { decimals: 1 })} u.`,
                color: SERIES_COLORS[0],
                shape: 'line' as const,
              },
            ]
          : []),
      ]}
    />
  );
}

function ProductDetail({
  productId,
  branchId,
  onBack,
  backLabel,
  readOnly,
}: {
  productId: number;
  branchId: number | null;
  onBack: () => void;
  backLabel: string;
  readOnly: boolean;
}) {
  const me = useCurrentUser();
  const { canOpen } = useAccess();
  const rotation = me.tenant?.stockRotation ?? 'FIFO';
  const query = useQuery({
    queryKey: useBranchQueryKey('insights', 'detail', productId, branchId),
    queryFn: () => insightsApi.productDetail(productId, branchId),
  });

  const { accept, discard, busy } = useRecommendationDecisions();

  const detail = query.data;
  const series = useMemo<SeriesPoint[]>(() => {
    if (!detail) return [];
    const history: SeriesPoint[] = detail.history.map((point) => ({
      date: point.date,
      units: point.units,
      yhat: null,
      band: null,
    }));
    const forecast: SeriesPoint[] = (detail.forecast ?? []).map((point) => ({
      date: point.date,
      units: null,
      yhat: point.yhat,
      band: point.lo != null && point.hi != null ? [point.lo, point.hi] : null,
    }));
    // El último día real también arranca la línea del pronóstico, así no queda un hueco entre las dos series.
    const bridge = history.length ? history[history.length - 1] : undefined;
    if (bridge && forecast.length) bridge.yhat = bridge.units;
    return [...history, ...forecast];
  }, [detail]);

  const anomalies = detail?.anomalies ?? [];
  const historyDates = useMemo(() => new Set((detail?.history ?? []).map((point) => point.date)), [detail]);

  if (query.isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-10 w-48" />
        <Skeleton className="h-[280px] w-full" />
      </div>
    );
  }
  if (isApiError(query.error, 'NOT_FOUND')) {
    // Producto sin análisis en esa sucursal (p. ej. un pedido anotado a mano antes de que corra la IA).
    const productPath = `/app/products/${productId}`;
    return (
      <div className="flex flex-col gap-5">
        <div>
          <Button variant="ghost" size="sm" leftIcon={<ArrowLeft aria-hidden="true" />} onClick={onBack}>
            {backLabel}
          </Button>
        </div>
        <EmptyState
          bordered
          icon={Sparkles}
          title="Todavía no hay análisis de este producto"
          description="La IA lo analiza cuando tiene ventas en la sucursal: el próximo análisis corre esta noche."
          action={
            canOpen(productPath) ? (
              <ButtonLink to={productPath} variant="outline">
                Ver la ficha del producto
              </ButtonLink>
            ) : undefined
          }
        />
      </div>
    );
  }
  if (query.isError || !detail) {
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  }

  const insight = detail.insight;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Button variant="ghost" size="sm" leftIcon={<ArrowLeft aria-hidden="true" />} onClick={onBack}>
          {backLabel}
        </Button>
      </div>

      <Card>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h2 className="font-display text-lg font-semibold tracking-[-0.01em] text-foreground">
              {insight.productName}
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {insight.brand ? `${insight.brand} · ` : ''}
              {insight.categoryName ?? 'Sin categoría'} · {insight.branchName}
              {detail.barcode ? ` · ${detail.barcode}` : ''}
            </p>
            {insight.patternDescription ? (
              <p className="mt-2 text-base text-foreground">{insight.patternDescription}</p>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {insight.pattern ? <Badge tone="info">{SALES_PATTERN_LABELS[insight.pattern]}</Badge> : null}
            {insight.abcClass ? <Badge tone="neutral">Clase {insight.abcClass}</Badge> : null}
            {insight.xyzClass ? <Badge tone="neutral">Variabilidad {insight.xyzClass}</Badge> : null}
          </div>
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Venta diaria"
          value={`${formatNumber(insight.avgDailySales ?? 0, { decimals: 1 })} u.`}
          icon={insight.trendPct != null && insight.trendPct < 0 ? TrendingDown : TrendingUp}
          tone="primary"
          hint={insight.trendPct != null ? `tendencia ${formatPercent(insight.trendPct)}` : 'promedio del período'}
        />
        <StatCard
          label="Stock vendible"
          value={formatNumber(insight.sellableStock)}
          icon={Boxes}
          tone={insight.sellableStock <= insight.minStock ? 'crit' : 'ok'}
          hint={`mínimo ${formatNumber(insight.minStock)}`}
        />
        <StatCard
          label="Cobertura"
          value={insight.daysOfCover != null ? `${formatNumber(insight.daysOfCover, { decimals: 1 })} días` : '—'}
          icon={Sparkles}
          tone="info"
          hint={
            insight.predictedStockoutDate
              ? `quiebre previsto ${formatDate(insight.predictedStockoutDate)}`
              : 'sin quiebre previsto'
          }
        />
        <StatCard
          label="Sugerencia de pedido"
          value={insight.suggestedOrderQty != null ? `${formatNumber(insight.suggestedOrderQty)} u.` : '—'}
          icon={TrendingUp}
          tone="warn"
          hint={
            insight.reorderPoint != null
              ? `punto de pedido ${formatNumber(insight.reorderPoint)} u.`
              : 'sin punto de pedido'
          }
        />
      </div>

      <Card padding="none">
        <CardHeader
          className="p-4 sm:p-5"
          title="Historia y pronóstico"
          description="90 días de ventas reales y la proyección de la IA"
          actions={
            <ChartLegend
              items={[
                { label: 'Vendido', color: SERIES_COLORS[5], shape: 'line' },
                { label: 'Pronóstico', color: SERIES_COLORS[0], shape: 'line' },
              ]}
            />
          }
        />
        <div className="border-t border-border px-3 pb-4 pt-2 sm:px-4">
          {series.length > 1 ? (
            <ChartFrame summary={`Ventas diarias de ${insight.productName} y pronóstico de la IA.`}>
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={series} margin={{ top: 8, right: 8, bottom: 0, left: 4 }}>
                  <defs>
                    <linearGradient id="gd-forecast-band" x1="0" x2="0" y1="0" y2="1">
                      <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.18} />
                      <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid vertical={false} stroke={GRID_STROKE} />
                  <XAxis
                    dataKey="date"
                    tickFormatter={(value: string) => formatShortDate(value)}
                    tickLine={false}
                    axisLine={{ stroke: GRID_STROKE }}
                    tick={AXIS_TICK_MONO}
                    minTickGap={32}
                    tickMargin={8}
                  />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    width={40}
                    tick={AXIS_TICK}
                    tickFormatter={(value: number) => formatNumber(value)}
                  />
                  <Tooltip content={<DetailTooltip />} cursor={CURSOR} />
                  <Area
                    type="monotone"
                    dataKey="band"
                    stroke="none"
                    fill="url(#gd-forecast-band)"
                    isAnimationActive={false}
                    connectNulls
                  />
                  <Line
                    type="monotone"
                    dataKey="units"
                    stroke={SERIES_COLORS[5]}
                    strokeWidth={2}
                    dot={false}
                    isAnimationActive={false}
                    connectNulls={false}
                  />
                  <Line
                    type="monotone"
                    dataKey="yhat"
                    stroke={SERIES_COLORS[0]}
                    strokeWidth={2}
                    strokeDasharray="5 4"
                    dot={false}
                    isAnimationActive={false}
                    connectNulls
                  />
                  {anomalies.map((anomaly) =>
                    historyDates.has(anomaly.date) ? (
                      <ReferenceDot
                        key={anomaly.date}
                        x={anomaly.date}
                        y={anomaly.quantity}
                        r={5}
                        fill="hsl(var(--crit))"
                        stroke="hsl(var(--card))"
                        strokeWidth={2}
                        isFront
                      />
                    ) : null,
                  )}
                </ComposedChart>
              </ResponsiveContainer>
            </ChartFrame>
          ) : (
            <EmptyState
              size="sm"
              icon={Sparkles}
              title="Sin historia suficiente"
              description="Todavía no hay ventas de este producto en la sucursal."
            />
          )}
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Lotes y riesgo"
            description="En el orden en que se van a vender"
          />
          <ul className="divide-y divide-border border-t border-border">
            {detail.lots.length ? (
              detail.lots.map((lot) => {
                const risk = detail.lotRisks?.find((item) => item.lotId === lot.lotId);
                const level = risk ? riskLevel(risk.riskLevel, risk.daysToExpiry ?? lot.daysToExpiry) : null;
                const expired = level === RISK_LEVELS.EXPIRED;
                return (
                  <li key={lot.lotId} className="flex flex-wrap items-center justify-between gap-3 p-4">
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <LotRankChip rank={lot.rotationRank} rotation={rotation} discounted={!!lot.discountPct} />
                        <span className="font-mono text-sm text-foreground">{lot.lotNumber ?? `#${lot.lotId}`}</span>
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        {lot.expiryDate ? <ExpiryChip expiry={lot.expiryDate} bucket={lot.bucket} showDays /> : null}
                        <span className="text-sm text-muted-foreground">{formatNumber(lot.quantity)} u.</span>
                      </div>
                    </div>
                    {risk && level && risk.unitsAtRisk > 0 ? (
                      <div className="text-right">
                        <Badge tone={level.tone}>
                          {formatNumber(risk.unitsAtRisk)} u. {expired ? 'vencidas' : 'en riesgo'}
                        </Badge>
                        {risk.recommendedDiscountPct ? (
                          <div className="mt-1 text-xs text-muted-foreground">
                            Sugerido: {risk.recommendedDiscountPct}% de descuento
                          </div>
                        ) : null}
                      </div>
                    ) : (
                      <StatusPill tone="ok">Se vende a tiempo</StatusPill>
                    )}
                  </li>
                );
              })
            ) : (
              <li>
                <EmptyState size="sm" icon={Boxes} title="Sin lotes vivos" description="El producto no tiene stock en esta sucursal." />
              </li>
            )}
          </ul>
        </Card>

        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Anomalías detectadas"
            description="Días que se salieron del patrón esperado"
          />
          <ul className="divide-y divide-border border-t border-border">
            {anomalies.length ? (
              anomalies.map((anomaly) => (
                <li key={anomaly.date} className="flex items-center justify-between gap-3 p-4">
                  <div>
                    <div className="font-semibold text-foreground">{formatDate(anomaly.date)}</div>
                    <div className="text-sm text-muted-foreground">
                      Vendió {formatNumber(anomaly.quantity)} u. y se esperaban{' '}
                      {formatNumber(anomaly.expected, { decimals: 1 })} u.
                    </div>
                  </div>
                  <Badge tone={anomaly.kind === 'SPIKE' ? 'warn' : 'info'}>
                    {anomaly.kind === 'SPIKE' ? 'Pico' : 'Caída'}
                  </Badge>
                </li>
              ))
            ) : (
              <li>
                <EmptyState
                  size="sm"
                  icon={AlertTriangle}
                  title="Sin anomalías"
                  description="Las ventas de este producto siguieron el patrón esperado."
                />
              </li>
            )}
          </ul>
        </Card>
      </div>

      <Card padding="none">
        <CardHeader
          className="p-4 sm:p-5"
          icon={Sparkles}
          title="Recomendaciones de este producto"
          description="Lo que la IA sugiere hacer en esta sucursal"
        />
        {detail.recommendations.length ? (
          <ul className="grid grid-cols-1 divide-y divide-border border-t border-border lg:grid-cols-2 lg:divide-x lg:divide-y-0">
            {detail.recommendations.map((recommendation) => (
              <RecommendationCard
                key={recommendation.id}
                recommendation={recommendation}
                readOnly={readOnly}
                showBranch={false}
                busy={busy}
                onAccept={(values) => accept.mutate({ id: recommendation.id, values })}
                onDiscard={(note) => discard.mutate({ id: recommendation.id, note })}
              />
            ))}
          </ul>
        ) : (
          <EmptyState
            className="border-t border-border"
            size="sm"
            icon={Sparkles}
            title="Sin recomendaciones"
            description="La IA no tiene nada para sugerir sobre este producto por ahora."
          />
        )}
      </Card>
    </div>
  );
}

/**
 * Todas las recomendaciones del alcance (SPEC §6.5): el Inicio muestra las 3 más prioritarias y esta lista permite
 * revisar el resto, filtrar por estado y tipo (la sucursal sale del selector de la barra) y decidir sobre cada una.
 */
function RecommendationsPanel({
  readOnly,
  byType,
  onOpenProduct,
}: {
  readOnly: boolean;
  /** Pendientes por tipo (resumen de la IA) para los contadores del filtro. */
  byType: Record<string, number> | undefined;
  onOpenProduct: (productId: number, branchId: number) => void;
}) {
  const { isAll } = useBranch();
  const [status, setStatus] = useState('PENDING');
  const [type, setType] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const q = useDebounce(search, 300);
  const { accept, discard, busy } = useRecommendationDecisions();

  const params = { status, type, q: q || undefined, page, size: RECOMMENDATIONS_PAGE_SIZE };
  const query = useQuery({
    queryKey: useBranchQueryKey('recommendations', 'list', params),
    queryFn: () => recommendationsApi.list(params),
    placeholderData: keepPreviousData,
  });

  const typeOptions = useMemo(
    () => [
      { value: 'ALL', label: 'Todos los tipos' },
      ...(Object.keys(RECOMMENDATION_TYPE_LABELS) as RecommendationType[]).map((value) => ({
        value,
        label:
          status === 'PENDING' && byType
            ? `${RECOMMENDATION_TYPE_LABELS[value]} (${formatNumber(byType[value] ?? 0)})`
            : RECOMMENDATION_TYPE_LABELS[value],
      })),
    ],
    [status, byType],
  );

  const data = query.data;
  const rows = data?.content ?? [];
  const filtered = type !== 'ALL' || status !== 'PENDING' || !!q;

  return (
    <Card padding="none" aria-labelledby="recomendaciones-titulo">
      <CardHeader
        className="p-4 sm:p-5"
        titleId="recomendaciones-titulo"
        icon={Sparkles}
        title="Recomendaciones de la IA"
        description={
          data
            ? `${formatNumber(data.totalElements)} ${data.totalElements === 1 ? 'recomendación' : 'recomendaciones'}${
                status === 'PENDING' ? ' para revisar' : ''
              } · primero las más prioritarias`
            : 'Reposición, descuentos por vencimiento, retiro de vencidos, anomalías y compras a reducir'
        }
      />
      <div className="flex flex-col gap-3 border-t border-border p-4 md:flex-row">
        <SearchInput
          value={search}
          onValueChange={(value) => {
            setSearch(value);
            setPage(0);
          }}
          placeholder="Buscar producto o recomendación…"
          className="md:max-w-xs"
        />
        <Select
          aria-label="Estado de la recomendación"
          value={status}
          options={RECOMMENDATION_STATUS_OPTIONS}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(0);
          }}
          className="md:w-[200px]"
        />
        <Select
          aria-label="Tipo de recomendación"
          value={type}
          options={typeOptions}
          onChange={(event) => {
            setType(event.target.value);
            setPage(0);
          }}
          className="md:w-[220px]"
        />
      </div>
      {query.isPending ? (
        <div className="grid grid-cols-1 gap-4 border-t border-border p-4 md:grid-cols-2 xl:grid-cols-3">
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : query.isError ? (
        <ErrorState className="border-t border-border" error={query.error} onRetry={() => void query.refetch()} />
      ) : rows.length ? (
        <ul
          className={cn(
            'grid grid-cols-1 gap-3 border-t border-border bg-muted/40 p-3 sm:gap-4 sm:p-4 md:grid-cols-2 xl:grid-cols-3',
            query.isPlaceholderData && 'opacity-70',
          )}
        >
          {rows.map((recommendation) => (
            <RecommendationCard
              key={recommendation.id}
              className="rounded-panel border border-border bg-card"
              recommendation={recommendation}
              readOnly={readOnly}
              showBranch={isAll}
              busy={busy}
              onAccept={(values) => accept.mutate({ id: recommendation.id, values })}
              onDiscard={(note) => discard.mutate({ id: recommendation.id, note })}
              onOpenProduct={
                recommendation.productId != null
                  ? () => onOpenProduct(recommendation.productId!, recommendation.branchId)
                  : undefined
              }
            />
          ))}
        </ul>
      ) : (
        <EmptyState
          className="border-t border-border"
          icon={Sparkles}
          title={filtered ? 'Sin recomendaciones con esos filtros' : 'Sin recomendaciones pendientes'}
          description={
            filtered
              ? 'Probá con otro estado o tipo, o quitá el buscador.'
              : 'La IA vuelve a analizar las ventas y el stock esta noche. Si algo cambia, te avisamos.'
          }
        />
      )}
      {data && data.totalPages > 1 ? <Pagination {...pageInfo(data)} onPageChange={setPage} /> : null}
    </Card>
  );
}

export default function InsightsPage() {
  const me = useCurrentUser();
  const { can } = useAccess();
  const { isAll, scopeLabel, currentBranch } = useBranch();
  const queryClient = useQueryClient();
  // Recalcular la IA y decidir sobre sus recomendaciones: jefe y administrador (SPEC §3.3).
  const readOnly = !can('recommendations.decide');
  const canRun = can('insights.run');
  const branchColumn = useBranchColumn<ProductInsightRow>();

  const [selected, setSelected] = useState<{ productId: number; branchId: number } | null>(null);
  // Pestaña en la URL (?tab=recomendaciones): el Inicio enlaza directo a la lista completa de recomendaciones.
  const [searchParams, setSearchParams] = useSearchParams();
  const tab: InsightsTab = searchParams.get('tab') === 'recomendaciones' ? 'recomendaciones' : 'patrones';
  const changeTab = (next: InsightsTab) => {
    setSelected(null);
    setSearchParams(
      (current) => {
        const params = new URLSearchParams(current);
        if (next === 'patrones') params.delete('tab');
        else params.set('tab', next);
        return params;
      },
      { replace: true },
    );
  };
  const [pattern, setPattern] = useState('ALL');
  const [abc, setAbc] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const q = useDebounce(search, 300);

  const summaryQuery = useQuery({
    queryKey: useBranchQueryKey('insights', 'summary'),
    queryFn: insightsApi.summary,
    refetchInterval: (query) => (query.state.data?.running ? 4000 : false),
  });

  const params = { pattern, abc, q: q || undefined, page, size: PAGE_SIZE };
  const listQuery = useQuery({
    queryKey: useBranchQueryKey('insights', 'products', params),
    queryFn: () => insightsApi.products(params),
    placeholderData: keepPreviousData,
    enabled: selected == null && tab === 'patrones',
  });

  const run = useMutation({
    mutationFn: insightsApi.run,
    onSuccess: (launched) => {
      toast.success(launched.message);
      void queryClient.invalidateQueries({ queryKey: ['insights'] });
    },
  });

  const summary = summaryQuery.data;
  const running = summary?.running ?? false;

  // Cuando termina un análisis se refrescan las listas para ver los patrones nuevos.
  useEffect(() => {
    if (!running) {
      void queryClient.invalidateQueries({ queryKey: ['insights', 'products'] });
      void queryClient.invalidateQueries({ queryKey: ['recommendations'] });
    }
  }, [running, queryClient]);

  const columns: Array<TableColumn<ProductInsightRow> | null> = [
    {
      id: 'producto',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <div className="font-semibold text-foreground">{row.productName}</div>
          <div className="text-xs text-muted-foreground">{row.brand ?? row.categoryName ?? 'Sin categoría'}</div>
        </div>
      ),
    },
    branchColumn,
    {
      id: 'patron',
      header: 'Patrón',
      mobile: 'aside',
      cell: (row) =>
        row.pattern ? (
          <Badge tone={row.pattern === 'SIN_MOVIMIENTO' || row.pattern === 'EN_DECLIVE' ? 'warn' : 'info'}>
            {SALES_PATTERN_LABELS[row.pattern]}
          </Badge>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: 'abc',
      header: 'ABC',
      align: 'center',
      mobile: 'field',
      hideBelow: 'lg',
      cell: (row) => <span className="font-semibold tabular-nums">{row.abcClass ?? '—'}</span>,
    },
    {
      id: 'venta',
      header: 'Venta diaria',
      align: 'right',
      mobile: 'field',
      cell: (row) => (
        <span className="tabular-nums">{formatNumber(row.avgDailySales ?? 0, { decimals: 1 })}</span>
      ),
    },
    {
      id: 'cobertura',
      header: 'Cobertura',
      align: 'right',
      mobile: 'field',
      cell: (row) =>
        row.daysOfCover == null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <span className={cn('tabular-nums', row.daysOfCover <= 7 && 'font-semibold text-warn-ink')}>
            {formatNumber(row.daysOfCover, { decimals: 1 })} días
          </span>
        ),
    },
    {
      id: 'riesgo',
      header: 'Riesgo',
      mobile: 'aside',
      cell: (row) => (
        <div className="flex flex-wrap gap-1.5">
          {row.predictedStockoutDate ? (
            <Badge tone="crit">Quiebre {formatDate(row.predictedStockoutDate)}</Badge>
          ) : null}
          {row.unitsAtRisk > 0 ? <Badge tone="warn">{formatNumber(row.unitsAtRisk)} u. por vencer</Badge> : null}
          {row.anomaliesCount > 0 ? <Badge tone="neutral">{row.anomaliesCount} anomalías</Badge> : null}
        </div>
      ),
    },
  ];

  const data = listQuery.data;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Inteligencia IA"
        description={
          isAll
            ? 'Patrones de venta, pronósticos y riesgo por lote de cada sucursal.'
            : `Patrones de venta, pronósticos y riesgo por lote de ${scopeLabel}.`
        }
        icon={Sparkles}
        actions={
          !canRun ? (
            <StatusPill tone="info">Modo lectura</StatusPill>
          ) : (
            <Button
              leftIcon={<RefreshCw aria-hidden="true" className={cn(running && 'animate-spin')} />}
              loading={run.isPending}
              disabled={running || !summary?.aiAvailable}
              onClick={() => run.mutate()}
            >
              {running ? 'Analizando…' : 'Recalcular IA'}
            </Button>
          )
        }
      >
        <Tabs
          ariaLabel="Secciones de Inteligencia IA"
          value={tab}
          onChange={changeTab}
          tabs={[
            { value: 'patrones', label: 'Patrones y riesgo', icon: Boxes },
            {
              value: 'recomendaciones',
              label: 'Recomendaciones',
              icon: Sparkles,
              count: summary?.pendingRecommendations,
            },
          ]}
        />
      </PageHeader>

      {summary && !summary.aiAvailable ? (
        <Alert tone="warn" title="El servicio de IA no responde">
          Los patrones y las recomendaciones que ves son los del último análisis. Reintentamos automáticamente; si
          sigue así, avisale a soporte.
        </Alert>
      ) : null}

      {summaryQuery.isError ? (
        <ErrorState error={summaryQuery.error} onRetry={() => void summaryQuery.refetch()} />
      ) : null}

      {selected ? (
        <ProductDetail
          productId={selected.productId}
          branchId={selected.branchId}
          readOnly={readOnly}
          backLabel={tab === 'recomendaciones' ? 'Volver a las recomendaciones' : 'Volver a los patrones'}
          onBack={() => setSelected(null)}
        />
      ) : null}

      {tab === 'recomendaciones' ? (
        // Queda montada (oculta) mientras se ve la ficha de un producto: al volver conserva filtros y página.
        <div hidden={selected != null}>
          <RecommendationsPanel
            readOnly={readOnly}
            byType={summary?.recommendationsByType}
            onOpenProduct={(productId, branchId) => setSelected({ productId, branchId })}
          />
        </div>
      ) : selected ? null : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard
              label="Productos analizados"
              value={formatNumber(summary?.productsAnalyzed ?? 0)}
              icon={Boxes}
              tone="primary"
              hint={
                summary?.lastRunAt
                  ? `último análisis ${formatRelative(summary.lastRunAt)}`
                  : 'todavía sin análisis'
              }
              loading={summaryQuery.isPending}
            />
            <StatCard
              label="Valor en riesgo"
              value={formatMoney(summary?.atRiskValue ?? 0)}
              icon={AlertTriangle}
              tone="warn"
              hint="stock que vencería sin venderse"
              loading={summaryQuery.isPending}
            />
            <StatCard
              label="Quiebres previstos"
              value={formatNumber(summary?.predictedStockouts7d ?? 0)}
              icon={TrendingDown}
              tone="crit"
              hint="en los próximos 7 días"
              loading={summaryQuery.isPending}
            />
            <StatCard
              label="Recomendaciones"
              value={formatNumber(summary?.pendingRecommendations ?? 0)}
              icon={Sparkles}
              tone="ok"
              hint="sin responder"
              loading={summaryQuery.isPending}
            />
          </div>

          {summary && summary.runs.length ? (
            <Card>
              <CardHeader
                title="Estado del análisis"
                description={
                  summary.lastRunAt
                    ? `Último resultado: ${formatDateTime(summary.lastRunAt)}. Se recalcula todas las noches a las 03:00.`
                    : 'Todavía no corrió ningún análisis en este alcance.'
                }
              />
              <div className="mt-3">
                <RunStatus runs={summary.runs} />
              </div>
            </Card>
          ) : null}

          {summary && summary.topRisks.length ? (
            <Card padding="none">
              <CardHeader
                className="p-4 sm:p-5"
                title="Lotes con más riesgo"
                description="Stock que la simulación de la IA no llega a vender antes del vencimiento"
              />
              <ul className="divide-y divide-border border-t border-border">
                {summary.topRisks.map((risk) => (
                  <li key={risk.lotId} className="flex flex-wrap items-center justify-between gap-3 p-4">
                    <div className="min-w-0">
                      <button
                        type="button"
                        className="text-left font-semibold text-foreground underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        onClick={() => setSelected({ productId: risk.productId, branchId: risk.branchId })}
                      >
                        {risk.productName}
                      </button>
                      <div className="mt-0.5 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                        <span className="font-mono">{risk.lotNumber ?? `#${risk.lotId}`}</span>
                        {isAll ? <span>· {risk.branchName}</span> : null}
                        {risk.expiryDate ? (
                          <span>
                            · {risk.daysToExpiry != null && risk.daysToExpiry < 0 ? 'venció' : 'vence'}{' '}
                            {formatDate(risk.expiryDate)}
                          </span>
                        ) : null}
                      </div>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="text-right">
                        <div className="font-semibold tabular-nums text-foreground">{formatMoney(risk.valueAtRisk)}</div>
                        <div className="text-xs text-muted-foreground">
                          {formatNumber(risk.unitsAtRisk)} de {formatNumber(risk.quantity)} u.
                        </div>
                      </div>
                      <Badge tone={riskLevel(risk.riskLevel, risk.daysToExpiry).tone}>
                        {riskLevel(risk.riskLevel, risk.daysToExpiry).label}
                      </Badge>
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}

          <Card padding="none">
            <CardHeader
              className="p-4 sm:p-5"
              title="Patrones por producto"
              description={
                currentBranch
                  ? `Una fila por producto en ${currentBranch.name}`
                  : 'Una fila por producto y sucursal · tocá una para ver la ficha'
              }
            />
            <div className="flex flex-col gap-3 border-t border-border p-4 md:flex-row">
              <SearchInput
                value={search}
                onValueChange={(value) => {
                  setSearch(value);
                  setPage(0);
                }}
                placeholder="Buscar producto, marca o código…"
                className="md:max-w-xs"
              />
              <Select
                aria-label="Patrón de venta"
                value={pattern}
                options={PATTERN_OPTIONS}
                onChange={(event) => {
                  setPattern(event.target.value);
                  setPage(0);
                }}
                className="md:w-[248px]"
              />
              <Select
                aria-label="Clase ABC"
                value={abc}
                options={ABC_OPTIONS}
                onChange={(event) => {
                  setAbc(event.target.value);
                  setPage(0);
                }}
                className="md:w-[188px]"
              />
            </div>
            <Table
              className="border-t border-border"
              columns={columns}
              data={data?.content}
              rowKey={(row) => `${row.branchId}:${row.productId}`}
              loading={listQuery.isPending}
              error={listQuery.error}
              onRetry={() => void listQuery.refetch()}
              onRowClick={(row) => setSelected({ productId: row.productId, branchId: row.branchId })}
              caption="Patrones de venta detectados por la IA"
              empty={{
                icon: Sparkles,
                title: summary?.productsAnalyzed ? 'Sin resultados con esos filtros' : 'Todavía no hay análisis',
                description: summary?.productsAnalyzed
                  ? 'Probá con otro patrón o quitá el buscador.'
                  : `Cargá mercadería y registrá ventas: la IA analiza todas las noches. ${
                      canRun ? 'También podés recalcularla ahora con el botón de arriba.' : ''
                    }`,
              }}
              footer={data && data.totalPages > 1 ? <Pagination {...pageInfo(data)} onPageChange={setPage} /> : undefined}
            />
          </Card>

          <p className="text-xs text-muted-foreground">
            Modelo entrenado con las ventas de cada sucursal de {me.tenant?.name ?? 'tu comercio'}. La rotación
            configurada es {me.tenant?.stockRotation ?? 'FIFO'}.
          </p>
        </>
      )}
    </div>
  );
}
