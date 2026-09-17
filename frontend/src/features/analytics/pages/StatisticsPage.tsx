import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { TooltipProps } from 'recharts';
import { BarChart3, Boxes, PackageX, Recycle, Sparkles, TrendingUp } from 'lucide-react';
import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  Select,
  Skeleton,
  StatCard,
  Table,
  Tabs,
} from '@/components/ui';
import type { TableColumn } from '@/components/ui';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { MOVEMENT_TYPE_LABELS, SALES_PATTERN_LABELS } from '@/api/types';
import type { MovementType } from '@/api/types';
import { formatDate, formatMoney, formatNumber, formatPercent, formatShortDate } from '@/lib/format';
import { statisticsApi } from '../api';
import type {
  AbcBucket,
  CategoryPoint,
  DayPoint,
  ProductPoint,
  RotationRow,
  StatisticsOverview,
} from '../types';
import {
  AXIS_TICK,
  AXIS_TICK_MONO,
  ChartFrame,
  ChartTooltipBox,
  CURSOR,
  GRID_STROKE,
  SERIES_COLORS,
} from '../components/chart';

type TabValue = 'ventas' | 'rotacion' | 'perdidas';

const DAY_OPTIONS = [
  { value: '30', label: 'Últimos 30 días' },
  { value: '90', label: 'Últimos 90 días' },
  { value: '180', label: 'Últimos 180 días' },
  { value: '365', label: 'Último año' },
];

const ABC_DESCRIPTION: Record<string, string> = {
  A: 'Los que hacen el 80% de la facturación',
  B: 'El 15% siguiente',
  C: 'La cola larga: el 5% restante',
};

function DayTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  const point = payload[0]?.payload as DayPoint | undefined;
  if (!point) return null;
  return (
    <ChartTooltipBox
      title={formatDate(point.date)}
      lines={[
        { label: 'Facturación', value: formatMoney(point.amount), color: SERIES_COLORS[0], shape: 'line' },
        { label: 'Unidades', value: formatNumber(point.units) },
      ]}
    />
  );
}

function CategoryTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  const point = payload[0]?.payload as CategoryPoint | undefined;
  if (!point) return null;
  return (
    <ChartTooltipBox
      title={point.categoryName}
      lines={[
        { label: 'Facturación', value: formatMoney(point.amount) },
        { label: 'Participación', value: formatPercent(point.sharePct) },
        { label: 'Unidades', value: formatNumber(point.units) },
      ]}
    />
  );
}

function MonthTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  const point = payload[0]?.payload as { month: string; value: number; units: number } | undefined;
  if (!point) return null;
  return (
    <ChartTooltipBox
      title={point.month}
      lines={[
        { label: 'Merma', value: formatMoney(point.value), color: SERIES_COLORS[3] },
        { label: 'Unidades', value: formatNumber(point.units) },
      ]}
    />
  );
}

function SalesTab({ stats }: { stats: StatisticsOverview }) {
  const { sales } = stats;
  const productColumns: Array<TableColumn<ProductPoint>> = [
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
    {
      id: 'unidades',
      header: 'Unidades',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.units)}</span>,
    },
    {
      id: 'facturacion',
      header: 'Facturación',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="font-semibold tabular-nums">{formatMoney(row.amount)}</span>,
    },
    {
      id: 'margen',
      header: 'Margen',
      align: 'right',
      mobile: 'field',
      hideBelow: 'lg',
      cell: (row) => <span className="tabular-nums text-muted-foreground">{formatMoney(row.margin)}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Facturación" value={formatMoney(sales.amount)} icon={TrendingUp} tone="ok" hint="ventas netas de anulaciones" />
        <StatCard label="Unidades" value={formatNumber(sales.units)} icon={Boxes} tone="primary" hint={`${formatNumber(sales.avgDailyUnits)} por día`} />
        <StatCard
          label="Margen"
          value={formatMoney(sales.margin)}
          icon={BarChart3}
          tone="info"
          hint={`${formatPercent(sales.marginPct)} sobre la venta`}
        />
        <StatCard
          label="Mejor día"
          value={sales.bestDay ? formatDate(sales.bestDay) : '—'}
          icon={Sparkles}
          tone="warn"
          hint={formatMoney(sales.bestDayAmount)}
        />
      </div>

      <Card padding="none">
        <CardHeader className="p-4 sm:p-5" title="Ventas por día" description="Facturación diaria, neta de anulaciones" />
        <div className="border-t border-border px-3 pb-4 pt-2 sm:px-4">
          {sales.byDay.length > 1 ? (
            <ChartFrame summary={`Facturación diaria entre ${formatDate(stats.from)} y ${formatDate(stats.to)}.`}>
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={sales.byDay} margin={{ top: 8, right: 8, bottom: 0, left: 4 }}>
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
                    width={64}
                    tick={AXIS_TICK}
                    tickFormatter={(value: number) => formatNumber(value)}
                  />
                  <Tooltip content={<DayTooltip />} cursor={CURSOR} />
                  <Line
                    type="monotone"
                    dataKey="amount"
                    stroke={SERIES_COLORS[0]}
                    strokeWidth={2}
                    dot={false}
                    isAnimationActive={false}
                    activeDot={{ r: 4, fill: SERIES_COLORS[0], stroke: 'hsl(var(--card))', strokeWidth: 2 }}
                  />
                </LineChart>
              </ResponsiveContainer>
            </ChartFrame>
          ) : (
            <EmptyState size="sm" icon={BarChart3} title="Todavía no hay ventas" description="Registrá ventas para ver la curva." />
          )}
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card padding="none">
          <CardHeader className="p-4 sm:p-5" title="Ventas por categoría" description="Participación en la facturación" />
          <div className="border-t border-border p-4">
            {sales.byCategory.length ? (
              <ChartFrame summary="Participación de cada categoría en la facturación del período.">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={sales.byCategory}
                      dataKey="amount"
                      nameKey="categoryName"
                      innerRadius="52%"
                      outerRadius="82%"
                      paddingAngle={2}
                      isAnimationActive={false}
                      stroke="hsl(var(--card))"
                      strokeWidth={2}
                    >
                      {sales.byCategory.map((row, index) => (
                        <Cell key={row.categoryName} fill={SERIES_COLORS[index % SERIES_COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip content={<CategoryTooltip />} />
                  </PieChart>
                </ResponsiveContainer>
              </ChartFrame>
            ) : (
              <EmptyState size="sm" icon={Boxes} title="Sin ventas por categoría" description="No hubo ventas en el período." />
            )}
            <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1.5 text-sm">
              {sales.byCategory.slice(0, 6).map((row, index) => (
                <li key={row.categoryName} className="flex items-center gap-1.5 text-muted-foreground">
                  <span
                    className="size-3 shrink-0 rounded-[2px]"
                    style={{ backgroundColor: SERIES_COLORS[index % SERIES_COLORS.length] }}
                    aria-hidden="true"
                  />
                  {row.categoryName}
                  <span className="font-semibold tabular-nums text-foreground">{formatPercent(row.sharePct)}</span>
                </li>
              ))}
            </ul>
          </div>
        </Card>

        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Ventas por sucursal"
            description="Cómo se reparte la facturación del alcance"
          />
          <div className="border-t border-border p-4">
            {sales.byBranch.length ? (
              <ul className="flex flex-col gap-3">
                {sales.byBranch.map((row, index) => (
                  <li key={row.branchId} className="flex flex-col gap-1">
                    <div className="flex items-baseline justify-between gap-3">
                      <span className="font-semibold text-foreground">{row.branchName}</span>
                      <span className="tabular-nums text-muted-foreground">
                        {formatMoney(row.amount)} · {formatPercent(row.sharePct)}
                      </span>
                    </div>
                    <span className="h-2 w-full overflow-hidden rounded-full bg-muted" aria-hidden="true">
                      <span
                        className="block h-full rounded-full"
                        style={{
                          width: `${Math.min(100, row.sharePct)}%`,
                          backgroundColor: SERIES_COLORS[index % SERIES_COLORS.length],
                        }}
                      />
                    </span>
                    <span className="text-xs text-muted-foreground">{formatNumber(row.units)} unidades</span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState size="sm" icon={Boxes} title="Sin ventas en el período" description="Probá con un rango más largo." />
            )}
            {sales.bySource.length ? (
              <div className="mt-4 border-t border-border pt-3">
                <div className="gd-eyebrow mb-2 text-muted-foreground">Origen de las ventas</div>
                <ul className="flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-muted-foreground">
                  {sales.bySource.map((row) => (
                    <li key={row.source}>
                      {row.source} <span className="font-semibold tabular-nums text-foreground">{formatNumber(row.units)} u.</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card padding="none">
          <CardHeader className="p-4 sm:p-5" title="Los que más venden" description="Top 10 por facturación" />
          <Table
            className="border-t border-border"
            columns={productColumns}
            data={stats.products.top}
            rowKey={(row) => row.productId}
            caption="Productos con más facturación"
            empty={{ icon: TrendingUp, title: 'Sin ventas', description: 'No hubo ventas en el período.' }}
          />
        </Card>
        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Los que menos venden"
            description={`${formatNumber(stats.products.withoutSales)} productos no vendieron nada en el período`}
          />
          <Table
            className="border-t border-border"
            columns={productColumns}
            data={stats.products.bottom}
            rowKey={(row) => row.productId}
            caption="Productos con menos facturación"
            empty={{ icon: PackageX, title: 'Sin datos', description: 'No hubo ventas en el período.' }}
          />
        </Card>
      </div>
    </div>
  );
}

function RotationTab({ stats }: { stats: StatisticsOverview }) {
  const { abc, rotation } = stats.products;
  const columns: Array<TableColumn<RotationRow>> = [
    {
      id: 'producto',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => <span className="font-semibold text-foreground">{row.productName}</span>,
    },
    {
      id: 'patron',
      header: 'Patrón',
      mobile: 'aside',
      cell: (row) =>
        row.pattern ? <Badge tone="info">{SALES_PATTERN_LABELS[row.pattern]}</Badge> : <span className="text-muted-foreground">—</span>,
    },
    {
      id: 'promedio',
      header: 'Venta diaria',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.avgDailySales, { decimals: 1 })}</span>,
    },
    {
      id: 'stock',
      header: 'Stock',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.sellableStock)}</span>,
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
          <span className="tabular-nums">{formatNumber(row.daysOfCover, { decimals: 1 })} días</span>
        ),
    },
    {
      id: 'rotacion',
      header: 'Rotación',
      align: 'right',
      mobile: 'field',
      hideBelow: 'lg',
      cell: (row) =>
        row.turnoverRatio == null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <span className="tabular-nums">{formatNumber(row.turnoverRatio, { decimals: 2 })}×</span>
        ),
    },
  ];

  const abcColumns: Array<TableColumn<AbcBucket>> = [
    {
      id: 'clase',
      header: 'Clase',
      mobile: 'title',
      cell: (row) => (
        <div>
          <div className="font-display text-lg font-semibold text-foreground">{row.abcClass}</div>
          <div className="text-xs text-muted-foreground">{ABC_DESCRIPTION[row.abcClass] ?? ''}</div>
        </div>
      ),
    },
    {
      id: 'productos',
      header: 'Productos',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.products)}</span>,
    },
    {
      id: 'facturacion',
      header: 'Facturación',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="font-semibold tabular-nums">{formatMoney(row.amount)}</span>,
    },
    {
      id: 'participacion',
      header: 'Participación',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatPercent(row.sharePct)}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <Card padding="none">
        <CardHeader
          className="p-4 sm:p-5"
          title="Clasificación ABC"
          description="Por facturación acumulada del período (80 / 15 / 5)"
        />
        <Table
          className="border-t border-border"
          columns={abcColumns}
          data={abc}
          rowKey={(row) => row.abcClass}
          caption="Clases ABC por facturación"
          empty={{ icon: BarChart3, title: 'Sin ventas para clasificar', description: 'No hubo ventas en el período.' }}
        />
      </Card>

      <Card padding="none">
        <CardHeader
          className="p-4 sm:p-5"
          title="Rotación por producto"
          description="Cuántos días de stock quedan al ritmo de venta actual"
        />
        <Table
          className="border-t border-border"
          columns={columns}
          data={rotation}
          rowKey={(row) => row.productId}
          rowSeverity={(row) => (row.daysOfCover != null && row.daysOfCover <= 7 ? 'warn' : 'none')}
          caption="Rotación y cobertura por producto"
          empty={{ icon: Boxes, title: 'Sin rotación para mostrar', description: 'Todavía no hay ventas suficientes.' }}
        />
      </Card>
    </div>
  );
}

function LossesTab({ stats }: { stats: StatisticsOverview }) {
  const { losses, ai } = stats;
  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Merma"
          value={formatMoney(losses.wasteValue)}
          icon={Recycle}
          tone="crit"
          hint={`${formatNumber(losses.wasteUnits)} unidades descartadas`}
        />
        <StatCard
          label="Ventas perdidas"
          value={formatMoney(losses.lostSales.estimatedAmount)}
          icon={PackageX}
          tone="warn"
          hint={`${formatNumber(losses.lostSales.units)} u. que no pudiste cobrar`}
        />
        <StatCard
          label="En riesgo"
          value={formatMoney(losses.expiringRiskValue)}
          icon={Boxes}
          tone="orange"
          hint="stock por vencer, a costo"
        />
        <StatCard
          label="Recuperado con descuentos"
          value={formatMoney(ai.recoveredSales.amount)}
          icon={Sparkles}
          tone="ok"
          hint={`${formatNumber(ai.recoveredSales.units)} u. de lotes en liquidación`}
        />
      </div>

      <Card padding="none">
        <CardHeader className="p-4 sm:p-5" title="Merma por mes" description="Valor a costo de lo que se descartó" />
        <div className="border-t border-border px-3 pb-4 pt-2 sm:px-4">
          {losses.wasteByMonth.length ? (
            <ChartFrame summary="Valor de la merma registrada mes a mes.">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={losses.wasteByMonth} margin={{ top: 8, right: 8, bottom: 0, left: 4 }}>
                  <CartesianGrid vertical={false} stroke={GRID_STROKE} />
                  <XAxis dataKey="month" tickLine={false} axisLine={{ stroke: GRID_STROKE }} tick={AXIS_TICK_MONO} tickMargin={8} />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    width={64}
                    tick={AXIS_TICK}
                    tickFormatter={(value: number) => formatNumber(value)}
                  />
                  <Tooltip content={<MonthTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
                  <Bar dataKey="value" fill={SERIES_COLORS[3]} radius={[4, 4, 0, 0]} isAnimationActive={false} maxBarSize={48} />
                </BarChart>
              </ResponsiveContainer>
            </ChartFrame>
          ) : (
            <EmptyState size="sm" icon={Recycle} title="Sin merma registrada" description="No descartaste mercadería en el período." />
          )}
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title="De dónde viene la merma" description="Vencidos y dañados del período" />
          <ul className="mt-3 flex flex-col gap-2 text-sm">
            {losses.wasteByReason.length ? (
              losses.wasteByReason.map((row) => (
                <li key={row.type} className="flex items-baseline justify-between gap-3 border-b border-border pb-2 last:border-0">
                  <span className="text-muted-foreground">
                    {MOVEMENT_TYPE_LABELS[row.type as MovementType] ?? row.type}
                  </span>
                  <span className="tabular-nums">
                    <span className="font-semibold text-foreground">{formatMoney(row.value)}</span>{' '}
                    <span className="text-muted-foreground">({formatNumber(row.units)} u.)</span>
                  </span>
                </li>
              ))
            ) : (
              <li className="text-muted-foreground">No hubo descartes en el período.</li>
            )}
            <li className="flex items-baseline justify-between gap-3 pt-1">
              <span className="text-muted-foreground">Lotes vencidos sin descartar</span>
              <span className="tabular-nums">
                <span className="font-semibold text-foreground">{formatNumber(losses.expiredLots)}</span>{' '}
                <span className="text-muted-foreground">({formatMoney(losses.expiredValue)})</span>
              </span>
            </li>
          </ul>
        </Card>

        <Card>
          <CardHeader
            icon={Sparkles}
            title="Impacto de la IA"
            description="Qué pasó con lo que recomendó y aceptaste"
          />
          <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted-foreground">Recomendaciones</dt>
            <dd className="text-right font-semibold tabular-nums">{formatNumber(ai.recommendations.total)}</dd>
            <dt className="text-muted-foreground">Aceptadas</dt>
            <dd className="text-right font-semibold tabular-nums text-ok-ink">
              {formatNumber(ai.recommendations.accepted)}
            </dd>
            <dt className="text-muted-foreground">Descartadas</dt>
            <dd className="text-right tabular-nums">{formatNumber(ai.recommendations.discarded)}</dd>
            <dt className="text-muted-foreground">Pendientes</dt>
            <dd className="text-right tabular-nums">{formatNumber(ai.recommendations.pending)}</dd>
            <dt className="text-muted-foreground">Tasa de aceptación</dt>
            <dd className="text-right font-semibold tabular-nums">{formatPercent(ai.recommendations.acceptanceRatePct)}</dd>
            <dt className="text-muted-foreground">Impacto estimado</dt>
            <dd className="text-right font-semibold tabular-nums">{formatMoney(ai.recommendations.expectedImpactAccepted)}</dd>
            <dt className="text-muted-foreground">Descuento promedio</dt>
            <dd className="text-right tabular-nums">{formatPercent(ai.recoveredSales.avgDiscountPct)}</dd>
            <dt className="text-muted-foreground">Lotes liquidados</dt>
            <dd className="text-right tabular-nums">{formatNumber(ai.recoveredSales.lots)}</dd>
          </dl>
        </Card>
      </div>
    </div>
  );
}

export default function StatisticsPage() {
  const { isAll, scopeLabel } = useBranch();
  const [tab, setTab] = useState<TabValue>('ventas');
  const [days, setDays] = useState('90');

  const query = useQuery({
    queryKey: useBranchQueryKey('statistics', 'overview', days),
    queryFn: () => statisticsApi.overview(Number(days)),
  });

  const stats = query.data;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Estadísticas"
        description={
          isAll
            ? 'Ventas, rotación y pérdidas del consolidado de tus sucursales.'
            : `Ventas, rotación y pérdidas de ${scopeLabel}.`
        }
        icon={BarChart3}
        actions={
          <Select
            aria-label="Período"
            value={days}
            options={DAY_OPTIONS}
            onChange={(event) => setDays(event.target.value)}
            className="sm:w-[200px]"
          />
        }
      >
        <Tabs
          ariaLabel="Secciones de estadísticas"
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'ventas', label: 'Ventas', icon: TrendingUp },
            { value: 'rotacion', label: 'Rotación y ABC', icon: Boxes },
            { value: 'perdidas', label: 'Pérdidas e impacto', icon: Recycle },
          ]}
        />
      </PageHeader>

      {query.isPending ? (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-28 w-full" />
          </div>
          <Skeleton className="h-[320px] w-full" />
        </div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : stats == null || (stats.sales.units === 0 && stats.losses.wasteUnits === 0) ? (
        <EmptyState
          bordered
          icon={BarChart3}
          title="Todavía no hay movimiento para analizar"
          description="En cuanto registres ventas y cargues mercadería vas a ver acá la facturación, la rotación y las pérdidas."
        />
      ) : (
        <>
          <p className="text-sm text-muted-foreground">
            Del {formatDate(stats.from)} al {formatDate(stats.to)} · {formatNumber(stats.branchCount)}{' '}
            {stats.branchCount === 1 ? 'sucursal' : 'sucursales'} · las ventas descuentan las anulaciones.
          </p>
          {tab === 'ventas' ? <SalesTab stats={stats} /> : null}
          {tab === 'rotacion' ? <RotationTab stats={stats} /> : null}
          {tab === 'perdidas' ? <LossesTab stats={stats} /> : null}
        </>
      )}
    </div>
  );
}
