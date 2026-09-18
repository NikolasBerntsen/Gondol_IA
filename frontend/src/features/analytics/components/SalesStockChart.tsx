import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { TooltipProps } from 'recharts';
import { formatDate, formatNumber, formatShortDate } from '@/lib/format';
import type { SalesStockPoint } from '../types';
import { AXIS_TICK, AXIS_TICK_MONO, ChartFrame, ChartTooltipBox, CURSOR, GRID_STROKE } from './chart';

interface Point extends SalesStockPoint {
  label: string;
}

function SalesStockTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  const point = payload[0]?.payload as Point | undefined;
  if (!point) return null;
  return (
    <ChartTooltipBox
      title={formatDate(point.date)}
      lines={[
        {
          label: 'Ventas',
          value: `${formatNumber(point.salesUnits)} u.`,
          color: 'hsl(var(--foreground))',
          shape: 'line',
        },
        {
          label: 'Stock total',
          value: `${formatNumber(point.stockUnits)} u.`,
          color: 'hsl(var(--primary) / 0.4)',
        },
      ]}
    />
  );
}

/**
 * "Ventas y stock" del Inicio (SPEC §1.1): stock total como área sobre el eje izquierdo y ventas netas
 * como línea sobre el eje derecho, con el último día destacado.
 */
export function SalesStockChart({ data }: { data: SalesStockPoint[] }) {
  if (data.length < 2) return null;
  const points: Point[] = data.map((row) => ({ ...row, label: formatShortDate(row.date) }));
  const stocks = points.map((p) => p.stockUnits);
  const sales = points.map((p) => p.salesUnits);
  const stockMax = Math.max(...stocks, 1);
  const salesMax = Math.max(...sales, 1);
  const last = points.length - 1;
  const step = Math.max(1, Math.floor(last / 4));
  const ticks = Array.from(new Set([0, step, step * 2, step * 3, last])).map((i) => points[Math.min(i, last)]!.label);

  return (
    <ChartFrame
      summary={`Ventas netas entre ${formatNumber(Math.min(...sales))} y ${formatNumber(
        Math.max(...sales),
      )} unidades por día; stock total de hoy ${formatNumber(points[last]!.stockUnits)} unidades.`}
    >
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={points} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
          <defs>
            <linearGradient id="gd-stock-fill" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.24} />
              <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke={GRID_STROKE} />
          <XAxis
            dataKey="label"
            ticks={ticks}
            tickLine={false}
            axisLine={{ stroke: GRID_STROKE }}
            tick={AXIS_TICK_MONO}
            tickMargin={8}
            interval="preserveStartEnd"
          />
          <YAxis
            yAxisId="stock"
            domain={[0, Math.ceil(stockMax * 1.08)]}
            tickCount={5}
            tickLine={false}
            axisLine={false}
            width={52}
            tick={AXIS_TICK}
            tickFormatter={(value: number) => formatNumber(value)}
          />
          <YAxis
            yAxisId="sales"
            orientation="right"
            domain={[0, Math.ceil(salesMax * 1.2)]}
            tickCount={5}
            tickLine={false}
            axisLine={false}
            width={36}
            tick={AXIS_TICK}
            tickFormatter={(value: number) => formatNumber(value)}
          />
          <Tooltip content={<SalesStockTooltip />} cursor={CURSOR} />
          <Area
            yAxisId="stock"
            type="monotone"
            dataKey="stockUnits"
            name="Stock total"
            stroke="hsl(var(--primary))"
            strokeWidth={1.75}
            fill="url(#gd-stock-fill)"
            isAnimationActive={false}
            activeDot={{ r: 4, fill: 'hsl(var(--primary))', stroke: 'hsl(var(--card))', strokeWidth: 2 }}
          />
          <Line
            yAxisId="sales"
            type="monotone"
            dataKey="salesUnits"
            name="Ventas"
            stroke="hsl(var(--foreground))"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
            activeDot={{ r: 4, fill: 'hsl(var(--foreground))', stroke: 'hsl(var(--card))', strokeWidth: 2 }}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </ChartFrame>
  );
}
