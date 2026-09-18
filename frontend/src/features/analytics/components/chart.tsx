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
