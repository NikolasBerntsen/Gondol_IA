import type { ReorderStatus, StockStatus } from '@/api/types';
import { StatusPill, type Tone } from './StatusPill';

/** Estado de stock por sucursal (SPEC §4.2) o fila de "Artículos a reponer". */
export type StockPillStatus = StockStatus | ReorderStatus;

/** Glosario fijo del sistema (docs/design-system.md §9): Sin stock / Crítico / Bajo / OK. */
export const STOCK_PILL_LABELS: Record<StockPillStatus, string> = {
  OUT: 'Sin stock',
  SIN_STOCK: 'Sin stock',
  CRITICO: 'Crítico',
  LOW: 'Bajo',
  BAJO: 'Bajo',
  OK: 'OK',
};

const TONE: Record<StockPillStatus, Tone> = {
  OUT: 'crit',
  SIN_STOCK: 'crit',
  CRITICO: 'crit',
  LOW: 'warn',
  BAJO: 'warn',
  OK: 'ok',
};

export interface StockStatusPillProps {
  status: StockPillStatus;
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * Estado de stock: Sin stock (sólido, bloquea) · Crítico · Bajo · OK.
 * En vista consolidada mostrá el **peor** estado entre las sucursales.
 */
export function StockStatusPill({ status, size, className }: StockStatusPillProps) {
  const solid = status === 'OUT' || status === 'SIN_STOCK';
  return (
    <StatusPill tone={TONE[status]} solid={solid} size={size} className={className}>
      {STOCK_PILL_LABELS[status]}
    </StatusPill>
  );
}

/** Severidad de la fila para la franja de `Table`/`SeverityRow`. */
export function stockSeverity(status: StockPillStatus): 'crit' | 'warn' | 'ok' {
  if (status === 'OUT' || status === 'SIN_STOCK' || status === 'CRITICO') return 'crit';
  if (status === 'LOW' || status === 'BAJO') return 'warn';
  return 'ok';
}
