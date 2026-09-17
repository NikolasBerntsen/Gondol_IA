import { ArrowDownRight, ArrowUpRight, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

export type StatTone = 'brand' | 'orange' | 'red' | 'amber' | 'sky' | 'violet' | 'slate';

const TONE_CLASSES: Record<StatTone, { icon: string; accent: string }> = {
  brand: { icon: 'bg-brand-100 text-brand-700', accent: 'text-brand-700' },
  orange: { icon: 'bg-orange-100 text-orange-600', accent: 'text-orange-600' },
  red: { icon: 'bg-red-100 text-red-600', accent: 'text-red-600' },
  amber: { icon: 'bg-amber-100 text-amber-700', accent: 'text-amber-700' },
  sky: { icon: 'bg-sky-100 text-sky-700', accent: 'text-sky-700' },
  violet: { icon: 'bg-violet-100 text-violet-700', accent: 'text-violet-700' },
  slate: { icon: 'bg-slate-100 text-slate-600', accent: 'text-slate-700' },
};

export interface StatTrend {
  /** Variación en porcentaje (p. ej. `12.5` o `-3`). */
  value: number;
  label?: string;
  /** Si subir es malo (p. ej. mermas), se invierten los colores. */
  invert?: boolean;
}

export interface StatCardProps {
  label: ReactNode;
  value: ReactNode;
  icon?: LucideIcon;
  tone?: StatTone;
  /** Texto chico debajo del valor (p. ej. "registrados"). */
  hint?: ReactNode;
  trend?: StatTrend;
  loading?: boolean;
  /** Convierte la tarjeta en link. */
  to?: string;
  className?: string;
}

/** Tarjeta de indicador (Productos, Por vencer, Stock bajo, Valor inventario…). */
export function StatCard({ label, value, icon: Icon, tone = 'brand', hint, trend, loading = false, to, className }: StatCardProps) {
  const style = TONE_CLASSES[tone];
  const positive = trend ? (trend.invert ? trend.value <= 0 : trend.value >= 0) : true;
  const TrendIcon = trend && trend.value < 0 ? ArrowDownRight : ArrowUpRight;

  const body = (
    <>
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm font-medium text-slate-500">{label}</p>
        {Icon && (
          <span className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded-xl', style.icon)}>
            <Icon className="h-5 w-5" aria-hidden="true" />
          </span>
        )}
      </div>
      <div className="mt-1">
        {loading ? (
          <div className="h-8 w-24 animate-pulse rounded-lg bg-slate-100" aria-hidden="true" />
        ) : (
          <p className={cn('truncate text-2xl font-bold tracking-tight sm:text-[1.75rem]', style.accent)}>{value}</p>
        )}
        {(hint || trend) && (
          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
            {trend && !loading && (
              <span
                className={cn(
                  'inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 font-semibold',
                  positive ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700',
                )}
              >
                <TrendIcon className="h-3 w-3" aria-hidden="true" />
                {`${trend.value > 0 ? '+' : ''}${trend.value.toLocaleString('es-AR', { maximumFractionDigits: 1 })}%`}
              </span>
            )}
            {trend?.label && <span>{trend.label}</span>}
            {hint && <span>{hint}</span>}
          </div>
        )}
      </div>
    </>
  );

  const classes = cn(
    'block rounded-2xl border border-slate-200/70 bg-white p-4 shadow-sm sm:p-5',
    to && 'transition hover:border-brand-200 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
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
