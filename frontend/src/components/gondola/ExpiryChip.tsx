import { EXPIRY_BUCKET_LABELS, type ExpiryBucket } from '@/api/types';
import { cn } from '@/lib/cn';
import { daysUntil, formatDate, formatDateCompact } from '@/lib/format';

/** Umbrales de la demo (`tenant_settings`): crítico 2 días, por vencer 7, próximo 30 (SPEC §4.2). */
export const DEFAULT_EXPIRY_THRESHOLDS = { criticalDays: 2, warningDays: 7, upcomingDays: 30 } as const;

export interface ExpiryThresholds {
  criticalDays?: number;
  warningDays?: number;
  upcomingDays?: number;
}

/**
 * Bucket de una fecha de vencimiento. Usalo solo si el backend no manda `bucket`:
 * la fuente de verdad son los umbrales del comercio (`tenant_settings`).
 */
export function expiryBucketOf(expiry: string | null | undefined, thresholds: ExpiryThresholds = {}): ExpiryBucket {
  const { criticalDays, warningDays, upcomingDays } = { ...DEFAULT_EXPIRY_THRESHOLDS, ...thresholds };
  const days = daysUntil(expiry);
  if (days === null) return 'OK';
  if (days < 0) return 'EXPIRED';
  if (days <= criticalDays) return 'CRITICAL';
  if (days <= warningDays) return 'WARNING';
  if (days <= upcomingDays) return 'UPCOMING';
  return 'OK';
}

const STYLE: Record<ExpiryBucket, string> = {
  EXPIRED: 'border-crit bg-crit text-crit-foreground',
  CRITICAL: 'border-crit/35 bg-crit-soft text-crit-ink',
  WARNING: 'border-warn/35 bg-warn-soft text-warn-ink',
  UPCOMING: 'border-info/30 bg-info-soft text-info-ink',
  OK: 'border-border bg-card text-muted-foreground',
};

export interface ExpiryChipProps {
  /** Fecha ISO `aaaa-mm-dd` (o un instante). */
  expiry: string;
  /** Número de lote normalizado ("L2410C"); se muestra antes del vencimiento. */
  lot?: string | null;
  /** Bucket calculado por el backend. Si no se pasa se calcula con los umbrales por defecto. */
  bucket?: ExpiryBucket;
  thresholds?: ExpiryThresholds;
  /** Año con 4 dígitos (fichas y detalles); por defecto `dd/mm/aa`. */
  longYear?: boolean;
  /** Agrega "(en 8 d)" / "(hace 3 d)". */
  showDays?: boolean;
  className?: string;
}

/**
 * Chip de vencimiento: dato en mono (`L2410C · VTO 25/10/26`) con color por bucket.
 * Radio de etiqueta (4 px): es un dato rotulado, no una píldora de estado.
 * Nunca es el único indicador de una fila: acompañalo con `StatusPill` o una franja.
 */
export function ExpiryChip({ expiry, lot, bucket, thresholds, longYear, showDays, className }: ExpiryChipProps) {
  const resolved = bucket ?? expiryBucketOf(expiry, thresholds);
  const days = daysUntil(expiry);
  const daysText = days === null ? '' : days < 0 ? `hace ${-days} d` : days === 0 ? 'hoy' : `en ${days} d`;
  return (
    <span
      className={cn(
        'inline-flex h-[22px] shrink-0 items-center gap-1.5 whitespace-nowrap rounded-tag border px-1.5 font-mono text-xs font-medium tabular-nums',
        STYLE[resolved],
        className,
      )}
      title={`${EXPIRY_BUCKET_LABELS[resolved]} · vence el ${formatDate(expiry)}`}
    >
      {lot && (
        <>
          <span>{lot}</span>
          <span aria-hidden="true" className="opacity-50">
            ·
          </span>
        </>
      )}
      <span>
        <span className="opacity-70">VTO</span> {longYear ? formatDate(expiry) : formatDateCompact(expiry)}
      </span>
      {showDays && daysText && <span className="opacity-75">({daysText})</span>}
      <span className="sr-only">, {EXPIRY_BUCKET_LABELS[resolved]}</span>
    </span>
  );
}
