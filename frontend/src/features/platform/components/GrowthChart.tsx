import {
  Area,
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { formatNumber } from '@/lib/format';
import type { GrowthPoint } from '../types';

const MONTHS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];

/** `"2026-09"` → `"sep 26"`. */
function monthLabel(month: string): string {
  const [year, index] = month.split('-');
  const name = MONTHS[Number(index) - 1] ?? month;
  return `${name} ${year?.slice(2) ?? ''}`.trim();
}

interface TooltipEntry {
  name?: string;
  value?: number | string;
  color?: string;
}

function ChartTooltip({ active, payload, label }: { active?: boolean; payload?: TooltipEntry[]; label?: string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-panel border border-border bg-card px-3 py-2 text-sm shadow-pop">
      <p className="mb-1 font-semibold text-foreground">{label}</p>
      <ul className="space-y-0.5">
        {payload.map((entry) => (
          <li key={entry.name} className="flex items-center gap-2 text-muted-foreground">
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: entry.color }} aria-hidden="true" />
            {entry.name}: <span className="font-semibold tabular-nums text-foreground">{entry.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Crecimiento de la plataforma: clientes activos al cierre de cada mes, altas y bajas (SPEC §6.6). */
export function GrowthChart({ data }: { data: GrowthPoint[] }) {
  const points = data.map((point) => ({
    month: monthLabel(point.month),
    activos: point.activeAtEndOfMonth,
    altas: point.newTenants,
    bajas: point.cancelled,
  }));

  return (
    <>
      <div className="h-[260px] w-full">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: -18 }}>
            <CartesianGrid vertical={false} stroke="hsl(var(--border))" />
            <XAxis
              dataKey="month"
              tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
              tickLine={false}
              axisLine={false}
              interval="preserveStartEnd"
            />
            <YAxis
              allowDecimals={false}
              tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
              tickLine={false}
              axisLine={false}
              width={44}
            />
            <Tooltip content={<ChartTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
            <Legend
              iconType="circle"
              wrapperStyle={{ fontSize: 12, color: 'hsl(var(--muted-foreground))', paddingTop: 8 }}
            />
            <Area
              type="monotone"
              dataKey="activos"
              name="Clientes activos"
              stroke="hsl(var(--primary))"
              fill="hsl(var(--primary))"
              fillOpacity={0.14}
              strokeWidth={2}
            />
            <Bar dataKey="altas" name="Altas" fill="hsl(var(--info))" radius={[3, 3, 0, 0]} barSize={10} />
            <Bar dataKey="bajas" name="Bajas" fill="hsl(var(--crit))" radius={[3, 3, 0, 0]} barSize={10} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <table className="sr-only">
        <caption>Crecimiento de los últimos 12 meses</caption>
        <thead>
          <tr>
            <th scope="col">Mes</th>
            <th scope="col">Clientes activos</th>
            <th scope="col">Altas</th>
            <th scope="col">Bajas</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.month}>
              <th scope="row">{point.month}</th>
              <td>{formatNumber(point.activos)}</td>
              <td>{formatNumber(point.altas)}</td>
              <td>{formatNumber(point.bajas)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
