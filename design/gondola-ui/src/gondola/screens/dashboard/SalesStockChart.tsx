import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"
import type { TooltipProps } from "recharts"
import { formatDate, formatNumber } from "../../format"
import type { TrendPoint } from "../../data"

function ChartTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null
  const p = payload[0].payload as TrendPoint
  return (
    <div className="rounded-control border bg-card px-3 py-2 text-sm shadow-pop">
      <div className="mb-1 font-mono text-xs text-muted-foreground">{formatDate(p.iso)}</div>
      <div className="flex items-center justify-between gap-6">
        <span className="flex items-center gap-1.5">
          <span aria-hidden="true" className="h-0.5 w-3 rounded bg-foreground" />
          Ventas
        </span>
        <span className="font-semibold tabular-nums">{formatNumber(p.ventas)} u.</span>
      </div>
      <div className="flex items-center justify-between gap-6">
        <span className="flex items-center gap-1.5">
          <span aria-hidden="true" className="h-2.5 w-3 rounded-[2px] bg-primary/40" />
          Stock total
        </span>
        <span className="font-semibold tabular-nums">{formatNumber(p.stock)} u.</span>
      </div>
    </div>
  )
}

/** Ventas (línea, eje derecho) y stock total (área, eje izquierdo), 30 días. */
export function SalesStockChart({ data }: { data: TrendPoint[] }) {
  const stocks = data.map((d) => d.stock)
  const ventas = data.map((d) => d.ventas)
  const sMin = Math.floor((Math.min(...stocks) * 0.92) / 500) * 500
  const sMax = Math.ceil((Math.max(...stocks) * 1.03) / 500) * 500
  const vMax = Math.ceil((Math.max(...ventas) * 1.15) / 100) * 100
  const last = data.length - 1
  const ticks = [0, 7, 14, 21, last].map((i) => data[i].label)
  const summary = `Ventas entre ${formatNumber(Math.min(...ventas))} y ${formatNumber(Math.max(...ventas))} unidades por día; stock total al cierre de hoy ${formatNumber(data[last].stock)} unidades.`

  return (
    <figure className="m-0">
      <div className="flex items-baseline justify-between px-1 pb-1 text-xs text-muted-foreground" aria-hidden="true">
        <span>Stock total (u.)</span>
        <span>Ventas (u./día)</span>
      </div>
      <div className="h-[260px] w-full sm:h-[280px]">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 4 }}>
            <defs>
              <linearGradient id="gd-stock-fill" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.24} />
                <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid vertical={false} stroke="hsl(var(--border))" strokeDasharray="0" />
            <XAxis
              dataKey="label"
              ticks={ticks}
              tickLine={false}
              axisLine={{ stroke: "hsl(var(--border))" }}
              tick={{ fill: "hsl(var(--muted-foreground))", fontSize: 12, fontFamily: "var(--font-mono)" }}
              tickMargin={8}
              interval="preserveStartEnd"
            />
            <YAxis
              yAxisId="stock"
              domain={[sMin, sMax]}
              tickCount={5}
              tickLine={false}
              axisLine={false}
              width={52}
              tick={{ fill: "hsl(var(--muted-foreground))", fontSize: 12 }}
              tickFormatter={(v: number) => formatNumber(v)}
            />
            <YAxis
              yAxisId="ventas"
              orientation="right"
              domain={[0, vMax]}
              tickCount={5}
              tickLine={false}
              axisLine={false}
              width={36}
              tick={{ fill: "hsl(var(--muted-foreground))", fontSize: 12 }}
              tickFormatter={(v: number) => formatNumber(v)}
            />
            <Tooltip content={<ChartTooltip />} cursor={{ stroke: "hsl(var(--input))", strokeWidth: 1 }} />
            <Area
              yAxisId="stock"
              type="monotone"
              dataKey="stock"
              name="Stock total"
              stroke="hsl(var(--primary))"
              strokeWidth={1.75}
              fill="url(#gd-stock-fill)"
              isAnimationActive={false}
              activeDot={{ r: 4, fill: "hsl(var(--primary))", stroke: "hsl(var(--card))", strokeWidth: 2 }}
            />
            <Line
              yAxisId="ventas"
              type="monotone"
              dataKey="ventas"
              name="Ventas"
              stroke="hsl(var(--foreground))"
              strokeWidth={2}
              isAnimationActive={false}
              dot={(props: { cx?: number; cy?: number; index?: number }) =>
                props.index === last ? (
                  <circle key="last" cx={props.cx} cy={props.cy} r={4} fill="hsl(var(--foreground))" stroke="hsl(var(--card))" strokeWidth={2} />
                ) : (
                  <g key={props.index} />
                )
              }
              activeDot={{ r: 4, fill: "hsl(var(--foreground))", stroke: "hsl(var(--card))", strokeWidth: 2 }}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="sr-only">{summary}</figcaption>
    </figure>
  )
}
