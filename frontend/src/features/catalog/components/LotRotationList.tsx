import type { StockRotation } from '@/api/types';
import { ExpiryChip, LotRankChip } from '@/components/gondola';
import { cn } from '@/lib/cn';
import { formatDate, formatNumber } from '@/lib/format';
import type { LotDto } from '../types';

export interface LotRotationListProps {
  lots: readonly LotDto[];
  rotation: StockRotation;
  /** Lote que se está por cargar: se muestra al final, resaltado. */
  pending?: { lotNumber: string | null; expiryDate: string | null; quantity: number; breaksRotation: boolean } | null;
  /** Muestra el nombre de la sucursal en cada fila (vista consolidada). */
  showBranch?: boolean;
  className?: string;
}

/**
 * Lotes de un producto en **orden de salida** (SPEC §4.2): el primero de la lista es el que se vende primero.
 * Es la lista de "Ya tenés en …" de la carga de mercadería.
 */
export function LotRotationList({ lots, rotation, pending, showBranch, className }: LotRotationListProps) {
  return (
    <ul className={cn('divide-y border-t', className)}>
      {lots.map((lot, index) => (
        <li key={lot.id} className="flex items-center gap-2.5 px-4 py-2.5">
          <LotRankChip rank={lot.rotationRank ?? index + 1} rotation={rotation} discounted={!!lot.discountPct} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="font-mono text-sm font-medium">{lot.lotNumber || 'Sin lote'}</span>
              {lot.expiryDate ? (
                <ExpiryChip expiry={lot.expiryDate} bucket={lot.expiryBucket} />
              ) : (
                <span className="text-xs text-muted-foreground">Sin vencimiento</span>
              )}
            </div>
            <div className="text-xs text-muted-foreground">
              {showBranch ? `${lot.branchName} · ` : ''}
              Ingresó el {formatDate(lot.receivedAt)}
            </div>
          </div>
          <span className="shrink-0 font-semibold tabular-nums">{formatNumber(lot.quantity)} u.</span>
        </li>
      ))}

      {pending && (
        <li
          className={cn(
            'flex items-center gap-2.5 bg-primary/[0.04] px-4 py-2.5',
            pending.breaksRotation ? 'gd-stripe-warn' : 'gd-stripe-ok',
          )}
        >
          <LotRankChip rank={lots.length + 1} rotation={rotation} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="font-mono text-sm font-medium">{pending.lotNumber || 'Sin lote'}</span>
              {pending.expiryDate ? <ExpiryChip expiry={pending.expiryDate} /> : null}
            </div>
            <div className="text-xs font-semibold text-primary">Nuevo · ingresa hoy</div>
          </div>
          <span className="shrink-0 font-semibold tabular-nums">{formatNumber(pending.quantity)} u.</span>
        </li>
      )}
    </ul>
  );
}
