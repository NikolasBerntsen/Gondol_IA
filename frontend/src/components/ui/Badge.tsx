import type { LucideIcon } from 'lucide-react';
import type { HTMLAttributes } from 'react';
import type { ExpiryBucket, ReorderStatus, Severity, StockStatus } from '@/api/types';
import { cn } from '@/lib/cn';

export type BadgeTone = 'neutral' | 'brand' | 'success' | 'warning' | 'orange' | 'danger' | 'info' | 'purple';

const TONE_CLASSES: Record<BadgeTone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-500/15',
  brand: 'bg-brand-50 text-brand-700 ring-brand-600/20',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  warning: 'bg-amber-50 text-amber-800 ring-amber-600/25',
  orange: 'bg-orange-50 text-orange-700 ring-orange-600/20',
  danger: 'bg-red-50 text-red-700 ring-red-600/20',
  info: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  purple: 'bg-violet-50 text-violet-700 ring-violet-600/20',
};

const DOT_CLASSES: Record<BadgeTone, string> = {
  neutral: 'bg-slate-400',
  brand: 'bg-brand-500',
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  orange: 'bg-orange-500',
  danger: 'bg-red-500',
  info: 'bg-sky-500',
  purple: 'bg-violet-500',
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  size?: 'sm' | 'md';
  /** Punto de color a la izquierda. */
  dot?: boolean;
  icon?: LucideIcon;
}

export function Badge({ tone = 'neutral', size = 'md', dot, icon: Icon, className, children, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex max-w-full items-center gap-1.5 whitespace-nowrap rounded-full font-medium ring-1 ring-inset',
        size === 'sm' ? 'px-2 py-0.5 text-[11px]' : 'px-2.5 py-0.5 text-xs',
        TONE_CLASSES[tone],
        className,
      )}
      {...props}
    >
      {dot && <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', DOT_CLASSES[tone])} aria-hidden="true" />}
      {Icon && <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />}
      <span className="truncate">{children}</span>
    </span>
  );
}

/** Tono de badge para una severidad (INFO → celeste, WARNING → ámbar, CRITICAL → rojo). */
export function severityTone(severity: Severity): BadgeTone {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warning';
    default:
      return 'info';
  }
}

/** Tono para buckets de vencimiento: Vencido/Crítico rojo, Por vencer naranja, Próximo ámbar, Vigente verde. */
export function expiryBucketTone(bucket: ExpiryBucket): BadgeTone {
  switch (bucket) {
    case 'EXPIRED':
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'orange';
    case 'UPCOMING':
      return 'warning';
    default:
      return 'success';
  }
}

/** Tono para el estado de stock de un producto: Sin stock rojo, Stock bajo ámbar, Normal verde. */
export function stockStatusTone(status: StockStatus): BadgeTone {
  switch (status) {
    case 'OUT':
      return 'danger';
    case 'LOW':
      return 'warning';
    default:
      return 'success';
  }
}

/** Tono para "Artículos a reponer": Sin stock y Crítico rojo, Bajo ámbar. */
export function reorderStatusTone(status: ReorderStatus): BadgeTone {
  return status === 'BAJO' ? 'warning' : 'danger';
}
