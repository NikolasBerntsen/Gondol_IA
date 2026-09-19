import { ArrowDownRight, ArrowUpRight, type LucideIcon } from 'lucide-react';
import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

/** Tonos preferidos: `primary` · `ok` · `warn` · `crit` · `info` · `neutral` (el resto son alias). */
export type StatTone =
  | 'primary'
  | 'brand'
  | 'ok'
  | 'warn'
  | 'orange'
  | 'amber'
  | 'crit'
  | 'red'
  | 'info'
  | 'sky'
  | 'violet'
  | 'neutral'
  | 'slate';

const TONE_CLASSES: Record<StatTone, string> = {
  primary: 'bg-primary/10 text-primary',
  brand: 'bg-primary/10 text-primary',
  ok: 'bg-ok-soft text-ok-ink',
  warn: 'bg-warn-soft text-warn-ink',
  orange: 'bg-warn-soft text-warn-ink',
  amber: 'bg-warn-soft text-warn-ink',
  crit: 'bg-crit-soft text-crit-ink',
  red: 'bg-crit-soft text-crit-ink',
  info: 'bg-info-soft text-info-ink',
  sky: 'bg-info-soft text-info-ink',
  violet: 'bg-info-soft text-info-ink',
  neutral: 'bg-muted text-muted-foreground',
  slate: 'bg-muted text-muted-foreground',
};

export interface StatTrend {
  /** Variación en porcentaje (p. ej. `12.5` o `-3`). */
  value: number;
  label?: string;
  /** Si subir es malo (p. ej. mermas), se invierten los colores. */
  invert?: boolean;
}

/** Tamaño mínimo al que se achica un número de KPI largo antes de quedar cortado (px). */
const MIN_VALUE_FONT_PX = 16;

/**
 * Tamaño de letra con el que un texto de `neededWidth` px (medido a `baseFontPx`) entra en `availableWidth` px.
 * `null` si ya entra con el tamaño del sistema de diseño.
 */
export function fittedFontSize(baseFontPx: number, neededWidth: number, availableWidth: number): number | null {
  if (availableWidth <= 0 || neededWidth <= availableWidth) return null;
  return Math.max(MIN_VALUE_FONT_PX, Math.floor(((baseFontPx * availableWidth) / neededWidth) * 10) / 10);
}

/**
 * Número del KPI en una sola línea (docs/design-system.md §7.2). Los montos largos ("$ 11.974.748,39") no entran en
 * las tarjetas angostas de la grilla de 4 columnas a 1280–1440 px: en vez de desbordar la tarjeta, se achica la letra
 * lo justo para que entren. Se vuelve a medir cuando cambia el ancho de la tarjeta o terminan de cargar las fuentes.
 */
function StatValue({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLParagraphElement>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    let frame = 0;
    let lastWidth = -1;
    const fit = () => {
      element.style.fontSize = '';
      const size = fittedFontSize(parseFloat(getComputedStyle(element).fontSize), element.scrollWidth, element.clientWidth);
      if (size !== null) element.style.fontSize = `${size}px`;
    };
    fit();
    // En un frame aparte: cambiar la letra dentro del callback dispararía el aviso de "ResizeObserver loop".
    const observer =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(([entry]) => {
            const width = entry.contentRect.width;
            if (width === lastWidth) return;
            lastWidth = width;
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(fit);
          });
    observer?.observe(element);
    let active = true;
    document.fonts?.ready.then(() => active && fit());
    return () => {
      active = false;
      observer?.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [children]);

  return (
    <p
      ref={ref}
      className="mt-2 whitespace-nowrap font-display text-xl font-bold leading-none tracking-[-0.02em] tabular-nums text-foreground sm:text-2xl"
    >
      {children}
    </p>
  );
}

export interface StatCardProps {
  label: ReactNode;
  value: ReactNode;
  icon?: LucideIcon;
  tone?: StatTone;
  /** Texto chico debajo del valor (p. ej. "registrados"). */
  hint?: ReactNode;
  trend?: StatTrend;
  /** Mini gráfico a la derecha del rótulo (`Sparkline`). */
  sparkline?: ReactNode;
  loading?: boolean;
  /** Convierte la tarjeta en link. */
  to?: string;
  className?: string;
}

/**
 * KPI: fila de ícono tonal + rótulo (con sparkline a la derecha), número en Bricolage en una sola
 * línea y delta debajo. Sin barras de acento ni sombras.
 */
export function StatCard({
  label,
  value,
  icon: Icon,
  tone = 'primary',
  hint,
  trend,
  sparkline,
  loading = false,
  to,
  className,
}: StatCardProps) {
  const positive = trend ? (trend.invert ? trend.value <= 0 : trend.value >= 0) : true;
  const TrendIcon = trend && trend.value < 0 ? ArrowDownRight : ArrowUpRight;

  const body = (
    <>
      <div className="flex items-center gap-2.5">
        {Icon && (
          <span className={cn('grid h-8 w-8 shrink-0 place-items-center rounded-control', TONE_CLASSES[tone])}>
            <Icon className="h-4 w-4" aria-hidden="true" />
          </span>
        )}
        <p className="min-w-0 flex-1 truncate text-base font-medium text-muted-foreground">{label}</p>
        {sparkline && <span className="shrink-0">{sparkline}</span>}
      </div>
      {loading ? (
        <div className="gd-skeleton mt-3 h-8 w-28 rounded-[6px] bg-muted" aria-hidden="true" />
      ) : (
        <StatValue>{value}</StatValue>
      )}
      {(hint || trend) && (
        <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
          {trend && !loading && (
            <span
              className={cn(
                'inline-flex items-center gap-0.5 rounded-tag px-1.5 font-semibold tabular-nums',
                positive ? 'bg-ok-soft text-ok-ink' : 'bg-crit-soft text-crit-ink',
              )}
            >
              <TrendIcon className="h-3.5 w-3.5" aria-hidden="true" />
              {`${trend.value > 0 ? '+' : ''}${trend.value.toLocaleString('es-AR', { maximumFractionDigits: 1 })}%`}
            </span>
          )}
          {trend?.label && <span>{trend.label}</span>}
          {hint && <span>{hint}</span>}
        </div>
      )}
    </>
  );

  const classes = cn(
    'block min-w-0 rounded-panel border border-border bg-card p-4',
    to && 'transition-colors hover:border-primary/40 hover:bg-muted/40',
    className,
  );

  if (to) {
    return (
      <Link to={to} className={classes}>
        {body}
      </Link>
    );
  }
  return (
    <div className={classes} aria-busy={loading || undefined}>
      {body}
    </div>
  );
}
