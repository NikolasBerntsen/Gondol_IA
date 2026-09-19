/**
 * Piezas compartidas de los gráficos del módulo B (recharts).
 * Todos los colores salen de tokens (`hsl(var(--…))`), así funcionan en tema claro y oscuro
 * (docs/frontend-guide.md §13).
 */
import type { ReactNode } from 'react';
import { formatNumber } from '@/lib/format';
import { cn } from '@/lib/cn';

/** Paleta categórica del módulo, en orden de uso. */
export const SERIES_COLORS = [
  'hsl(var(--primary))',
  'hsl(var(--info))',
  'hsl(var(--warn))',
  'hsl(var(--crit))',
  'hsl(var(--ok))',
  'hsl(var(--foreground))',
] as const;

export const AXIS_TICK = { fill: 'hsl(var(--muted-foreground))', fontSize: 12 } as const;
export const AXIS_TICK_MONO = {
  fill: 'hsl(var(--muted-foreground))',
  fontSize: 12,
  fontFamily: 'var(--font-mono)',
} as const;
export const GRID_STROKE = 'hsl(var(--border))';
export const CURSOR = { stroke: 'hsl(var(--input))', strokeWidth: 1 } as const;

export interface TooltipLine {
  label: string;
  value: ReactNode;
  color?: string;
  /** Cuadrado (área/barra) o línea fina. */
  shape?: 'square' | 'line';
}

/** Caja del tooltip: superficie `card`, radio de control y la única sombra permitida. */
export function ChartTooltipBox({ title, lines }: { title: ReactNode; lines: TooltipLine[] }) {
  return (
    <div className="pointer-events-none rounded-control border border-border bg-card px-3 py-2 text-sm shadow-pop">
      <div className="mb-1 font-mono text-xs text-muted-foreground">{title}</div>
      {lines.map((line) => (
        <div key={line.label} className="flex items-center justify-between gap-6">
          <span className="flex items-center gap-1.5 text-muted-foreground">
            {line.color ? (
              <span
                aria-hidden="true"
                className={cn('shrink-0 rounded-[2px]', line.shape === 'line' ? 'h-0.5 w-3' : 'h-2.5 w-3')}
                style={{ backgroundColor: line.color }}
              />
            ) : null}
            {line.label}
          </span>
          <span className="font-semibold tabular-nums text-foreground">{line.value}</span>
        </div>
      ))}
    </div>
  );
}

/** Referencia de series debajo del título de un panel. */
export function ChartLegend({ items }: { items: { label: string; color: string; shape?: 'square' | 'line' }[] }) {
  return (
    <div className="flex flex-wrap items-center gap-4 text-sm text-muted-foreground" aria-hidden="true">
      {items.map((item) => (
        <span key={item.label} className="flex items-center gap-1.5">
          <span
            className={cn('shrink-0 rounded-[2px]', item.shape === 'line' ? 'h-0.5 w-4' : 'h-3 w-4')}
            style={{ backgroundColor: item.color }}
          />
          {item.label}
        </span>
      ))}
    </div>
  );
}

/** Contenedor con alto fijo y descripción para lectores de pantalla. */
export function ChartFrame({
  children,
  summary,
  className,
}: {
  children: ReactNode;
  summary: string;
  className?: string;
}) {
  return (
    <figure className={cn('m-0', className)}>
      <div className="h-[240px] w-full sm:h-[280px]">{children}</div>
      <figcaption className="sr-only">{summary}</figcaption>
    </figure>
  );
}

export const tickNumber = (value: number) => formatNumber(value);

/** Pasos "redondos" de un eje (× potencia de 10): 1, 2, 2,5, 3, 4, 5. */
const NICE_FACTORS = [1, 2, 2.5, 3, 4, 5, 10] as const;

/** El menor paso redondo ≥ `raw`, entero (son unidades o pesos: nunca "2,5 u."). */
function niceStep(raw: number): number {
  if (raw <= 1) return 1;
  const base = 10 ** Math.floor(Math.log10(raw));
  for (const factor of NICE_FACTORS) {
    const step = factor * base;
    if (step >= raw && Number.isInteger(step)) return step;
  }
  return 10 * base;
}

export interface NiceAxis {
  /** Tope del dominio: `ticks` termina acá. */
  max: number;
  ticks: number[];
}

function axisFor(target: number, intervals: number): NiceAxis {
  const step = niceStep(target / intervals);
  return { max: step * intervals, ticks: Array.from({ length: intervals + 1 }, (_, i) => i * step) };
}

/**
 * Ejes con marcas redondas y parejas (0 · 4.000 · 8.000 · 12.000) para varios valores máximos que comparten el mismo
 * gráfico. Todos los ejes usan la misma cantidad de intervalos, así las marcas de un eje secundario caen sobre las
 * líneas de la grilla del principal. `headroom` deja aire arriba del valor más alto (0,05 = 5 %).
 */
export function niceAxes(maxima: number[], { headroom = 0.05 } = {}): NiceAxis[] {
  const targets = maxima.map((value) => Math.max(1, value * (1 + headroom)));
  let best: NiceAxis[] = [];
  let bestFill = -1;
  for (const intervals of [4, 3, 5]) {
    const axes = targets.map((target) => axisFor(target, intervals));
    // Cuánto del alto aprovecha cada serie: gana la cantidad de intervalos que menos espacio desperdicia.
    const fill = axes.reduce((sum, axis, i) => sum + targets[i]! / axis.max, 0);
    if (fill > bestFill + 1e-9) {
      best = axes;
      bestFill = fill;
    }
  }
  return best;
}
