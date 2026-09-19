import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  ArrowRight,
  Bell,
  CalendarClock,
  Check,
  Package,
  PackageX,
  ShieldAlert,
  Sparkles,
  Store,
  Upload,
  Wallet,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import {
  Button,
  ButtonLink,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  Skeleton,
  StatCard,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRoot,
  TableRow,
} from '@/components/ui';
import type { TableColumn } from '@/components/ui';
import { ExpiryChip, SeverityItem, SeverityRow, Sparkline, StatusPill, StockStatusPill } from '@/components/gondola';
import type { StripeSeverity } from '@/components/gondola';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { Link } from 'react-router-dom';
import { useCurrentUser } from '@/auth/AuthContext';
import { useAccess } from '@/auth/useAccess';
import { EXPIRY_BUCKET_LABELS } from '@/api/types';
import {
  capitalize,
  formatDateTime,
  formatDaysLeft,
  formatLongDate,
  formatMoney,
  formatNumber,
  pluralize,
} from '@/lib/format';
import { cn } from '@/lib/cn';
import { dashboardApi, recommendationsApi } from '../api';
import type { ReorderRow, UpcomingExpirationRow } from '../types';
import { showDecisionToast } from '../components/decisionToast';
import { RecommendationCard } from '../components/RecommendationCard';
import type { AcceptValues } from '../components/RecommendationCard';
import { SalesStockChart } from '../components/SalesStockChart';

const TREND_DAYS = 30;
const LIST_LIMIT = 8;
/** Recomendaciones que entran en el Inicio; el resto se revisa en Inteligencia IA › Recomendaciones. */
const RECOMMENDATIONS_SHOWN = 3;
/** Lista completa de recomendaciones (pestaña de `InsightsPage`, filtrable por tipo y estado). */
const RECOMMENDATIONS_PATH = '/app/insights?tab=recomendaciones';

const BUCKET_SEVERITY: Record<string, StripeSeverity> = {
  EXPIRED: 'crit',
  CRITICAL: 'crit',
  WARNING: 'warn',
  UPCOMING: 'info',
  OK: 'none',
};

const REORDER_SEVERITY: Record<string, StripeSeverity> = {
  SIN_STOCK: 'crit',
  CRITICO: 'crit',
  BAJO: 'warn',
};

interface AttentionItem {
  id: string;
  severity: Exclude<StripeSeverity, 'none'>;
  icon: LucideIcon;
  title: string;
  detail: string;
  action: JSX.Element;
}

function scrollToId(id: string) {
  const element = document.getElementById(id);
  if (!element) return;
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  element.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
  element.focus({ preventScroll: true });
}

/** Nombre del producto; con link a su ficha si el rol la puede abrir. */
function ProductName({ productId, name, linked }: { productId: number | null; name: string; linked: boolean }) {
  if (!linked || productId == null) return <div className="font-semibold text-foreground">{name}</div>;
  return (
    <Link
      to={`/app/products/${productId}`}
      className="block font-semibold text-foreground underline-offset-2 hover:underline"
      onClick={(event) => event.stopPropagation()}
    >
      {name}
    </Link>
  );
}

export default function DashboardPage() {
  const me = useCurrentUser();
  const rotation = me.tenant?.stockRotation ?? 'FIFO';
  const { can } = useAccess();
  const { isAll, scopeLabel, branches } = useBranch();
  const queryClient = useQueryClient();
  // Aceptar o descartar recomendaciones: jefe y administrador (SPEC §3.3).
  const readOnly = !can('recommendations.decide');
  const canViewProducts = can('products.view');
  const branchColumn = useBranchColumn<UpcomingExpirationRow>();
  const reorderBranchColumn = useBranchColumn<ReorderRow>();

  const summaryQuery = useQuery({
    queryKey: useBranchQueryKey('dashboard', 'summary'),
    queryFn: dashboardApi.summary,
  });
  const trendQuery = useQuery({
    queryKey: useBranchQueryKey('dashboard', 'trend', TREND_DAYS),
    queryFn: () => dashboardApi.salesStockTrend(TREND_DAYS),
  });
  const comparisonQuery = useQuery({
    queryKey: useBranchQueryKey('dashboard', 'comparison', TREND_DAYS),
    queryFn: () => dashboardApi.branchComparison(TREND_DAYS),
    enabled: isAll && branches.length > 1,
  });
  const expirationsQuery = useQuery({
    queryKey: useBranchQueryKey('dashboard', 'expirations', LIST_LIMIT),
    queryFn: () => dashboardApi.upcomingExpirations(LIST_LIMIT),
  });
  const reorderQuery = useQuery({
    queryKey: useBranchQueryKey('dashboard', 'reorder', LIST_LIMIT),
    queryFn: () => dashboardApi.reorder(LIST_LIMIT),
  });
  const recommendationsQuery = useQuery({
    queryKey: useBranchQueryKey('recommendations', 'list', { status: 'PENDING', size: RECOMMENDATIONS_SHOWN }),
    queryFn: () => recommendationsApi.list({ status: 'PENDING', size: RECOMMENDATIONS_SHOWN }),
  });

  const invalidateAll = () => {
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    void queryClient.invalidateQueries({ queryKey: ['recommendations'] });
    void queryClient.invalidateQueries({ queryKey: ['insights'] });
    void queryClient.invalidateQueries({ queryKey: ['alerts'] });
  };

  const accept = useMutation({
    mutationFn: ({ id, values }: { id: number; values: AcceptValues }) => recommendationsApi.accept(id, values),
    onSuccess: (decision) => {
      showDecisionToast(decision);
      invalidateAll();
    },
  });

  // "Comprar N": queda registrado en el backend (acepta la REORDER pendiente de la IA o anota una nueva), así la fila
  // sigue mostrando el pedido al recargar, hasta que entre la mercadería.
  const order = useMutation({
    mutationFn: (row: ReorderRow) =>
      recommendationsApi.reorder({ branchId: row.branchId, productId: row.productId, quantity: row.suggestedQuantity }),
    onSuccess: (decision) => {
      showDecisionToast(decision);
      invalidateAll();
    },
  });

  const discard = useMutation({
    mutationFn: ({ id, note }: { id: number; note?: string }) => recommendationsApi.discard(id, note),
    onSuccess: (decision) => {
      toast.success(decision.message);
      invalidateAll();
    },
  });

  const summary = summaryQuery.data;
  const trend = trendQuery.data ?? [];
  const expirations = expirationsQuery.data ?? [];
  const reorder = reorderQuery.data ?? [];
  const recommendations = recommendationsQuery.data?.content ?? [];
  const pendingTotal = recommendationsQuery.data?.totalElements ?? summary?.pendingRecommendationsCount ?? 0;
  const comparison = comparisonQuery.data ?? [];

  const stockSpark = useMemo(() => trend.map((point) => point.stockUnits), [trend]);

  const attention: AttentionItem[] = [];
  if (summary) {
    if (summary.openRecallMatchesCount > 0) {
      attention.push({
        id: 'recall',
        severity: 'crit',
        icon: ShieldAlert,
        title: `${pluralize(summary.openRecallMatchesCount, 'lote')} alcanzado${
          summary.openRecallMatchesCount === 1 ? '' : 's'
        } por un recall`,
        detail: 'Están en cuarentena: no se venden hasta que resuelvas el retiro.',
        action: (
          <ButtonLink to="/app/recalls" size="sm" variant="destructive">
            Ver recalls
          </ButtonLink>
        ),
      });
    }
    if (summary.expiredCount > 0) {
      attention.push({
        id: 'expired',
        severity: 'crit',
        icon: CalendarClock,
        title: `${pluralize(summary.expiredCount, 'lote')} vencido${
          summary.expiredCount === 1 ? '' : 's'
        } sin descartar`,
        detail: can('expirations.discard')
          ? 'Los lotes vencidos no se venden. Descartalos para que el stock refleje la realidad.'
          : 'Los lotes vencidos no se venden. Hay que descartarlos para que el stock refleje la realidad.',
        action: can('expirations.view') ? (
          <ButtonLink to="/app/expirations" size="sm" variant="outline">
            Ir a vencimientos
          </ButtonLink>
        ) : (
          <Button size="sm" variant="outline" onClick={() => scrollToId('vencimientos')}>
            Ver vencimientos
          </Button>
        ),
      });
    }
    if (summary.outOfStockCount > 0) {
      attention.push({
        id: 'out',
        severity: 'crit',
        icon: PackageX,
        title: `${pluralize(summary.outOfStockCount, 'producto')} sin stock`,
        detail: 'Cada venta que no podés cobrar es plata que se va. Revisá la reposición.',
        action: (
          <Button size="sm" variant="outline" onClick={() => scrollToId('reponer')}>
            Ver reposición
          </Button>
        ),
      });
    }
    if (summary.expiringSoonCount > 0) {
      attention.push({
        id: 'expiring',
        severity: 'warn',
        icon: CalendarClock,
        title: `${pluralize(summary.expiringSoonCount, 'lote')} por vencer`,
        // Con FIFO los lotes salen por orden de ingreso, no por vencimiento (SPEC §4.2): solo un descuento los adelanta.
        detail:
          rotation === 'FEFO'
            ? 'Con FEFO se venden primero. Un descuento a tiempo evita la merma.'
            : 'Con FIFO salen por orden de ingreso, no por vencimiento: un descuento los adelanta y evita la merma.',
        action: (
          <Button size="sm" variant="outline" onClick={() => scrollToId('vencimientos')}>
            Ver vencimientos
          </Button>
        ),
      });
    }
    if (summary.openAlertsCount > 0) {
      attention.push({
        id: 'alerts',
        severity: 'warn',
        icon: Bell,
        title: `${pluralize(summary.openAlertsCount, 'alerta')} sin atender`,
        detail: 'Vencimientos, stock bajo, quiebres previstos y anomalías de venta.',
        action: (
          <ButtonLink to="/app/alerts" size="sm" variant="outline">
            Ver alertas
          </ButtonLink>
        ),
      });
    }
    if (summary.pendingRecommendationsCount > 0) {
      attention.push({
        id: 'ia',
        severity: 'info',
        icon: Sparkles,
        title: `${pluralize(
          summary.pendingRecommendationsCount,
          'recomendación',
          'recomendaciones',
        )} de la IA ${readOnly ? 'para revisar' : 'sin responder'}`,
        detail: 'Reposición, descuentos por vencimiento, anomalías y compras a reducir.',
        // Si hay más de las que entran en el Inicio, "Revisar" abre la lista completa.
        action:
          summary.pendingRecommendationsCount > RECOMMENDATIONS_SHOWN ? (
            <ButtonLink to={RECOMMENDATIONS_PATH} size="sm" variant="outline">
              Revisar
            </ButtonLink>
          ) : (
            <Button size="sm" variant="outline" onClick={() => scrollToId('ia')}>
              Revisar
            </Button>
          ),
      });
    }
  }

  const expirationColumns: Array<TableColumn<UpcomingExpirationRow> | null> = [
    {
      id: 'producto',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <ProductName productId={row.productId} name={row.productName} linked={canViewProducts} />
          <div className="text-xs text-muted-foreground">{formatNumber(row.quantity)} u.</div>
        </div>
      ),
    },
    branchColumn,
    {
      id: 'lote',
      header: 'Lote',
      hideBelow: 'lg',
      cell: (row) => <span className="font-mono text-sm">{row.lotNumber ?? '—'}</span>,
    },
    {
      id: 'vence',
      header: 'Vence',
      mobile: 'aside',
      cell: (row) => <ExpiryChip expiry={row.expiryDate} bucket={row.bucket} />,
    },
    {
      id: 'dias',
      header: 'Días',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="whitespace-nowrap tabular-nums">{formatDaysLeft(row.daysLeft)}</span>,
    },
    {
      id: 'estado',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) => (
        <StatusPill tone={row.bucket === 'UPCOMING' ? 'info' : row.bucket === 'WARNING' ? 'warn' : 'crit'}>
          {EXPIRY_BUCKET_LABELS[row.bucket]}
        </StatusPill>
      ),
    },
  ];

  const buyLabel = (row: ReorderRow) => `Comprar ${formatNumber(row.suggestedQuantity)}`;

  const orderingKey =
    order.isPending && order.variables ? `${order.variables.branchId}:${order.variables.productId}` : null;

  const reorderColumns: Array<TableColumn<ReorderRow> | null> = [
    {
      id: 'producto',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <ProductName productId={row.productId} name={row.productName} linked={canViewProducts} />
          {row.brand ? <div className="text-xs text-muted-foreground">{row.brand}</div> : null}
        </div>
      ),
    },
    reorderBranchColumn,
    {
      id: 'stock',
      header: 'Stock',
      align: 'right',
      mobile: 'field',
      cell: (row) => (
        <span className={cn('font-semibold tabular-nums', row.sellableStock === 0 && 'text-crit-ink')}>
          {formatNumber(row.sellableStock)}
        </span>
      ),
    },
    {
      id: 'minimo',
      header: 'Mínimo',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums text-muted-foreground">{formatNumber(row.minStock)}</span>,
    },
    {
      id: 'sugerencia',
      header: 'Sugerencia',
      mobile: 'actions',
      cell: (row) => {
        const key = `${row.branchId}:${row.productId}`;
        if (row.orderedQuantity != null) {
          return (
            <span
              className="inline-flex h-8 items-center gap-1.5 whitespace-nowrap text-sm font-semibold text-ok-ink"
              title={row.orderedAt ? `Anotado el ${formatDateTime(row.orderedAt)}` : undefined}
            >
              <Check className="size-4" aria-hidden="true" />
              Pedido: {formatNumber(row.orderedQuantity)} u.
            </span>
          );
        }
        if (readOnly) return <span className="whitespace-nowrap font-medium">{buyLabel(row)}</span>;
        return (
          <Button
            size="sm"
            variant="outline"
            className="whitespace-nowrap"
            loading={orderingKey === key}
            disabled={order.isPending && orderingKey !== key}
            onClick={() => order.mutate(row)}
          >
            {buyLabel(row)}
          </Button>
        );
      },
    },
    {
      id: 'estado',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) => <StockStatusPill status={row.status} />,
    },
  ];

  if (summaryQuery.isError) {
    return (
      <>
        <PageHeader title="Resumen del negocio" />
        <ErrorState error={summaryQuery.error} onRetry={() => void summaryQuery.refetch()} />
      </>
    );
  }

  const isNewShop = summary != null && summary.productsCount === 0;

  return (
    <div className="flex flex-col gap-5 lg:gap-6">
      <PageHeader
        title="Resumen del negocio"
        description={`¡Hola, ${me.fullName.split(' ')[0]}! Esto es lo que pasa hoy en ${
          isAll ? `tus ${pluralize(branches.length, 'sucursal', 'sucursales')}` : scopeLabel
        }.`}
        actions={
          <div className="flex flex-col items-start gap-1 sm:items-end">
            <span className="text-base font-semibold text-foreground">
              {capitalize(formatLongDate(summary?.today ?? new Date()))}
            </span>
            {readOnly ? <StatusPill tone="info">Modo lectura</StatusPill> : null}
          </div>
        }
      />

      {isNewShop ? (
        <Card padding="none">
          <EmptyState
            icon={Store}
            title="Todavía no cargaste productos"
            description={
              can('imports.use') || can('intake.use')
                ? 'Empezá por traer tu catálogo desde un Excel o CSV, o cargá la primera mercadería con el escáner. En cuanto haya stock y ventas, acá vas a ver los vencimientos, la reposición y las recomendaciones de la IA.'
                : 'Cuando el administrador cargue el catálogo y la mercadería, acá vas a ver los vencimientos, la reposición y las recomendaciones de la IA.'
            }
            action={
              can('imports.use') || can('intake.use') ? (
                <div className="flex flex-col gap-2 sm:flex-row">
                  {can('imports.use') ? (
                    <ButtonLink to="/app/imports" leftIcon={<Upload aria-hidden="true" />}>
                      Importar Excel o CSV
                    </ButtonLink>
                  ) : null}
                  {can('intake.use') ? (
                    <ButtonLink to="/app/intake" variant={can('imports.use') ? 'outline' : 'primary'}>
                      Cargar mercadería
                    </ButtonLink>
                  ) : null}
                </div>
              ) : undefined
            }
          />
        </Card>
      ) : null}

      {/* Para hoy */}
      {!isNewShop ? (
        <Card padding="none" aria-labelledby="para-hoy">
          <CardHeader
            className="p-4 sm:p-5"
            titleId="para-hoy"
            title="Para hoy"
            description={
              summaryQuery.isPending
                ? 'Buscando lo que necesita tu atención…'
                : attention.length
                  ? `${pluralize(attention.length, 'tema')} ${
                      attention.length === 1 ? 'necesita' : 'necesitan'
                    } tu atención, por urgencia`
                  : 'No hay nada urgente. Buen día para revisar precios.'
            }
          />
          {summaryQuery.isPending ? (
            <div className="flex flex-col gap-3 border-t border-border p-4 sm:p-5">
              <Skeleton className="h-6 w-2/3" />
              <Skeleton className="h-6 w-1/2" />
            </div>
          ) : attention.length ? (
            <ul className="divide-y divide-border border-t border-border">
              {attention.map((item) => (
                <SeverityItem
                  key={item.id}
                  severity={item.severity}
                  className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3 pr-4 sm:pr-5"
                >
                  <span
                    className={cn(
                      'hidden size-8 shrink-0 place-items-center rounded-control sm:grid [&_svg]:size-4',
                      item.severity === 'crit'
                        ? 'bg-crit-soft text-crit-ink'
                        : item.severity === 'warn'
                          ? 'bg-warn-soft text-warn-ink'
                          : 'bg-info-soft text-info-ink',
                    )}
                    aria-hidden="true"
                  >
                    <item.icon />
                  </span>
                  <div className="min-w-0 flex-1 basis-[240px]">
                    <div className="text-base font-semibold text-foreground">{item.title}</div>
                    <p className="mt-0.5 text-sm text-muted-foreground">{item.detail}</p>
                  </div>
                  <div className="shrink-0">{item.action}</div>
                </SeverityItem>
              ))}
            </ul>
          ) : (
            <EmptyState
              className="border-t border-border"
              icon={Check}
              size="sm"
              title="Todo en orden"
              description="No hay vencimientos críticos, faltantes ni alertas sin atender en este alcance."
            />
          )}
        </Card>
      ) : null}

      {/* KPIs */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Productos"
          value={formatNumber(summary?.productsCount ?? 0)}
          icon={Package}
          tone="primary"
          hint="registrados en el catálogo"
          loading={summaryQuery.isPending}
        />
        <StatCard
          label="Por vencer"
          value={formatNumber(summary?.expiringSoonCount ?? 0)}
          icon={CalendarClock}
          tone="warn"
          hint={
            summary?.expiredCount
              ? `${formatNumber(summary.expiredCount)} ya vencidos sin descartar`
              : 'lotes dentro del aviso de vencimiento'
          }
          loading={summaryQuery.isPending}
        />
        <StatCard
          label="Stock bajo"
          value={formatNumber(summary?.lowStockCount ?? 0)}
          icon={PackageX}
          tone="crit"
          hint={`${formatNumber(summary?.outOfStockCount ?? 0)} sin stock · mínimo por sucursal`}
          loading={summaryQuery.isPending}
        />
        <StatCard
          label="Valor inventario"
          value={formatMoney(summary?.inventoryCostValue ?? 0)}
          icon={Wallet}
          tone="ok"
          hint="a costo · stock físico"
          sparkline={stockSpark.length > 1 ? <Sparkline data={stockSpark} tone="ok" width={56} height={24} /> : undefined}
          loading={summaryQuery.isPending}
        />
      </div>

      {/* Tendencia + sucursales */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-12">
        <Card padding="none" className={isAll && branches.length > 1 ? 'xl:col-span-7' : 'xl:col-span-12'}>
          <CardHeader
            className="p-4 sm:p-5"
            title="Ventas y stock"
            description={`Tendencia de ventas netas e inventario · últimos ${TREND_DAYS} días`}
            actions={
              <div className="flex flex-wrap items-center gap-4 text-sm text-muted-foreground" aria-hidden="true">
                <span className="flex items-center gap-1.5">
                  <span className="h-0.5 w-4 rounded bg-foreground" />
                  Ventas (u.)
                </span>
                <span className="flex items-center gap-1.5">
                  <span className="h-3 w-4 rounded-[2px] border-t-2 border-primary bg-primary/20" />
                  Stock total (u.)
                </span>
              </div>
            }
          />
          <div className="border-t border-border px-3 pb-4 pt-2 sm:px-4">
            {trendQuery.isPending ? (
              <Skeleton className="h-[240px] w-full sm:h-[280px]" />
            ) : trendQuery.isError ? (
              <ErrorState size="sm" error={trendQuery.error} onRetry={() => void trendQuery.refetch()} />
            ) : trend.length > 1 ? (
              <SalesStockChart data={trend} />
            ) : (
              <EmptyState
                size="sm"
                icon={Sparkles}
                title="Todavía no hay historia para dibujar"
                description="En cuanto registres ventas durante unos días vas a ver acá la curva."
              />
            )}
          </div>
        </Card>

        {isAll && branches.length > 1 ? (
          <Card padding="none" className="xl:col-span-5" aria-labelledby="sucursales">
            <CardHeader
              className="p-4 sm:p-5"
              titleId="sucursales"
              title="Sucursales"
              description={`Ventas netas y riesgo de los últimos ${TREND_DAYS} días`}
            />
            {comparisonQuery.isPending ? (
              <div className="flex flex-col gap-3 border-t border-border p-4">
                <Skeleton className="h-6 w-full" />
                <Skeleton className="h-6 w-full" />
              </div>
            ) : comparisonQuery.isError ? (
              <ErrorState
                size="sm"
                className="border-t border-border"
                error={comparisonQuery.error}
                onRetry={() => void comparisonQuery.refetch()}
              />
            ) : (
              <div className="gd-scroll overflow-x-auto border-t border-border">
                <TableRoot>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="pl-4">Sucursal</TableHead>
                      <TableHead className="text-right">Ventas</TableHead>
                      <TableHead className="text-right">Vence</TableHead>
                      <TableHead className="pr-4 text-right">Bajo</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {comparison.map((row) => (
                      <SeverityRow key={row.branchId} severity={row.openAlertsCount > 0 ? 'warn' : 'none'}>
                        <TableCell>
                          <div className="font-semibold text-foreground">{row.branchName}</div>
                          <div className="text-xs text-muted-foreground">
                            {formatNumber(row.salesUnits)} u. · merma {formatMoney(row.wasteValue)}
                          </div>
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-right font-medium tabular-nums">
                          {formatMoney(row.salesAmount)}
                        </TableCell>
                        <TableCell className="text-right tabular-nums text-warn-ink">
                          {formatNumber(row.expiringSoonCount)}
                        </TableCell>
                        <TableCell className="pr-4 text-right tabular-nums text-crit-ink">
                          {formatNumber(row.lowStockCount)}
                        </TableCell>
                      </SeverityRow>
                    ))}
                  </TableBody>
                </TableRoot>
              </div>
            )}
          </Card>
        ) : null}
      </div>

      {/* Detalle */}
      <div className="grid grid-cols-1 gap-5 2xl:grid-cols-2">
        <Card id="vencimientos" tabIndex={-1} padding="none" className="scroll-mt-4 focus:outline-none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Próximos vencimientos"
            description={
              rotation === 'FEFO'
                ? 'Lotes vendibles de los próximos 30 días · con FEFO salen primero'
                : 'Lotes vendibles de los próximos 30 días · con FIFO salen por orden de ingreso'
            }
            actions={
              can('expirations.view') ? (
                <ButtonLink to="/app/expirations" size="sm" variant="ghost" rightIcon={<ArrowRight aria-hidden="true" />}>
                  Ver todos
                </ButtonLink>
              ) : undefined
            }
          />
          <Table
            className="border-t border-border"
            columns={expirationColumns}
            data={expirations}
            rowKey={(row) => row.lotId}
            loading={expirationsQuery.isPending}
            error={expirationsQuery.error}
            onRetry={() => void expirationsQuery.refetch()}
            rowSeverity={(row) => BUCKET_SEVERITY[row.bucket] ?? 'none'}
            caption="Lotes por vencer en el alcance elegido"
            empty={{
              icon: CalendarClock,
              title: 'Sin vencimientos cercanos',
              description: 'Ningún lote vence en los próximos 30 días.',
            }}
          />
        </Card>

        <Card id="reponer" tabIndex={-1} padding="none" className="scroll-mt-4 focus:outline-none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Artículos a reponer"
            description="Una fila por producto y sucursal · sugerencia según las ventas recientes"
            actions={
              canViewProducts ? (
                <ButtonLink
                  to="/app/inventory?stockStatus=LOW"
                  size="sm"
                  variant="ghost"
                  rightIcon={<ArrowRight aria-hidden="true" />}
                >
                  Ver todos
                </ButtonLink>
              ) : undefined
            }
          />
          <Table
            className="border-t border-border"
            columns={reorderColumns}
            data={reorder}
            rowKey={(row) => `${row.branchId}:${row.productId}`}
            loading={reorderQuery.isPending}
            error={reorderQuery.error}
            onRetry={() => void reorderQuery.refetch()}
            rowSeverity={(row) => REORDER_SEVERITY[row.status] ?? 'none'}
            caption="Productos por debajo del mínimo"
            empty={{
              icon: Package,
              title: 'Nada para reponer',
              description: 'Todos los productos están por encima de su stock mínimo.',
            }}
          />
        </Card>
      </div>

      {/* IA */}
      <Card id="ia" tabIndex={-1} padding="none" className="scroll-mt-4 focus:outline-none">
        <CardHeader
          className="p-4 sm:p-5"
          icon={Sparkles}
          title="Recomendaciones de la IA"
          description={
            pendingTotal > RECOMMENDATIONS_SHOWN
              ? `Las ${RECOMMENDATIONS_SHOWN} más prioritarias de ${formatNumber(pendingTotal)} pendientes · reposición, descuentos por vencimiento y anomalías`
              : 'Reposición, descuentos por vencimiento y anomalías, calculadas con la historia de ventas de cada sucursal'
          }
          actions={
            <ButtonLink to={RECOMMENDATIONS_PATH} size="sm" variant="ghost" rightIcon={<ArrowRight aria-hidden="true" />}>
              {pendingTotal > RECOMMENDATIONS_SHOWN ? `Ver las ${formatNumber(pendingTotal)}` : 'Ver todas'}
            </ButtonLink>
          }
        />
        {recommendationsQuery.isPending ? (
          <div className="grid grid-cols-1 gap-4 border-t border-border p-4 lg:grid-cols-3">
            <Skeleton className="h-40 w-full" />
            <Skeleton className="h-40 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : recommendationsQuery.isError ? (
          <ErrorState
            className="border-t border-border"
            error={recommendationsQuery.error}
            onRetry={() => void recommendationsQuery.refetch()}
          />
        ) : recommendations.length ? (
          <ul className="grid grid-cols-1 divide-y divide-border border-t border-border lg:grid-cols-3 lg:divide-x lg:divide-y-0">
            {recommendations.slice(0, RECOMMENDATIONS_SHOWN).map((recommendation) => (
              <RecommendationCard
                key={recommendation.id}
                recommendation={recommendation}
                readOnly={readOnly}
                showBranch={isAll}
                busy={accept.isPending || discard.isPending}
                onAccept={(values) => accept.mutate({ id: recommendation.id, values })}
                onDiscard={(note) => discard.mutate({ id: recommendation.id, note })}
              />
            ))}
          </ul>
        ) : (
          <EmptyState
            className="border-t border-border"
            icon={Sparkles}
            title="Sin recomendaciones pendientes"
            description="La IA vuelve a analizar las ventas y el stock esta noche. Si algo cambia, te avisamos."
          />
        )}
      </Card>

    </div>
  );
}
