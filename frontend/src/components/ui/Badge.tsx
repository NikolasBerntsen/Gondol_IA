import type { LucideIcon } from 'lucide-react';
import type { HTMLAttributes } from 'react';
import type { ExpiryBucket, ReorderStatus, Severity, StockStatus } from '@/api/types';
import { cn } from '@/lib/cn';

/**
 * Tonos preferidos: `neutral` · `primary` · `ok` · `warn` · `crit` · `info`.
 * Los nombres del kit anterior (`brand`, `success`, `warning`, `orange`, `danger`, `purple`) siguen
 * funcionando como alias.
 */
export type BadgeTone =
  | 'neutral'
  | 'primary'
  | 'brand'
  | 'ok'
  | 'success'
  | 'warn'
  | 'warning'
  | 'orange'
  | 'crit'
  | 'danger'
  | 'info'
  | 'purple';

const SOFT: Record<BadgeTone, string> = {
  neutral: 'bg-muted text-muted-foreground',
  primary: 'bg-primary/10 text-primary',
  brand: 'bg-primary/10 text-primary',
  ok: 'bg-ok-soft text-ok-ink',
  success: 'bg-ok-soft text-ok-ink',
  warn: 'bg-warn-soft text-warn-ink',
  warning: 'bg-warn-soft text-warn-ink',
  orange: 'bg-warn-soft text-warn-ink',
  crit: 'bg-crit-soft text-crit-ink',
  danger: 'bg-crit-soft text-crit-ink',
  info: 'bg-info-soft text-info-ink',
  purple: 'bg-info-soft text-info-ink',
};

/** `ok` y `warn` sólidos usan la tinta: el blanco sobre el color pleno no llega a AA (§2.6). */
const SOLID: Record<BadgeTone, string> = {
  neutral: 'bg-foreground text-background',
  primary: 'bg-primary text-primary-foreground',
  brand: 'bg-primary text-primary-foreground',
  ok: 'bg-ok-ink text-card',
  success: 'bg-ok-ink text-card',
  warn: 'bg-warn-ink text-card',
  warning: 'bg-warn-ink text-card',
  orange: 'bg-warn-ink text-card',
  crit: 'bg-crit text-crit-foreground',
  danger: 'bg-crit text-crit-foreground',
  info: 'bg-info text-info-foreground',
  purple: 'bg-info text-info-foreground',
};

const DOT: Record<BadgeTone, string> = {
  neutral: 'bg-muted-foreground',
  primary: 'bg-primary',
  brand: 'bg-primary',
  ok: 'bg-ok',
  success: 'bg-ok',
  warn: 'bg-warn',
  warning: 'bg-warn',
  orange: 'bg-warn',
  crit: 'bg-crit',
  danger: 'bg-crit',
  info: 'bg-info',
  purple: 'bg-info',
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  size?: 'sm' | 'md';
  /** Punto de color a la izquierda. */
  dot?: boolean;
  icon?: LucideIcon;
  /** Relleno pleno: solo para estados terminales o que bloquean. */
  solid?: boolean;
  /** Forma de píldora (totalmente redondeada). Reservada para **estados**; ver `StatusPill`. */
  pill?: boolean;
}

/**
 * Etiqueta de dato (radio 4 px): plan, categoría, tipo de movimiento, cantidad…
 * Para el **estado** de una fila usá `StatusPill`/`StockStatusPill` de `@/components/gondola`.
 */
export function Badge({
  tone = 'neutral',
  size = 'md',
  dot,
  icon: Icon,
  solid,
  pill,
  className,
  children,
  ...props
}: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex max-w-full items-center gap-1.5 whitespace-nowrap font-semibold',
        pill ? 'rounded-full' : 'rounded-tag',
        size === 'sm' ? 'h-[20px] px-1.5 text-[11px]' : 'h-[22px] px-2 text-xs',
        solid ? SOLID[tone] : SOFT[tone],
        className,
      )}
      {...props}
    >
      {dot && (
        <span
          className={cn('h-1.5 w-1.5 shrink-0 rounded-full', solid ? 'bg-current opacity-80' : DOT[tone])}
          aria-hidden="true"
        />
      )}
      {Icon && <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />}
      <span className="truncate">{children}</span>
    </span>
  );
}

/** Tono para una severidad (INFO → info, WARNING → warn, CRITICAL → crit). */
export function severityTone(severity: Severity): BadgeTone {
  switch (severity) {
    case 'CRITICAL':
      return 'crit';
    case 'WARNING':
      return 'warn';
    default:
      return 'info';
  }
}

/** Tono por bucket de vencimiento: Vencido/Crítico `crit`, Por vencer `warn`, Próximo `info`, OK `neutral`. */
export function expiryBucketTone(bucket: ExpiryBucket): BadgeTone {
  switch (bucket) {
    case 'EXPIRED':
    case 'CRITICAL':
      return 'crit';
    case 'WARNING':
      return 'warn';
    case 'UPCOMING':
      return 'info';
    default:
      return 'neutral';
  }
}

/** Tono del estado de stock de un producto: Sin stock `crit`, Bajo `warn`, OK `ok`. */
export function stockStatusTone(status: StockStatus): BadgeTone {
  switch (status) {
    case 'OUT':
      return 'crit';
    case 'LOW':
      return 'warn';
    default:
      return 'ok';
  }
}

/** Tono para "Artículos a reponer": Sin stock y Crítico `crit`, Bajo `warn`. */
export function reorderStatusTone(status: ReorderStatus): BadgeTone {
  return status === 'BAJO' ? 'warn' : 'crit';
}
