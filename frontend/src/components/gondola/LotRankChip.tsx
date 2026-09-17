import { Tag } from 'lucide-react';
import type { StockRotation } from '@/api/types';
import { cn } from '@/lib/cn';

export interface LotRankChipProps {
  /** Posición en la cola de salida (1 = el próximo que se vende). */
  rank: number;
  /** Rotación del comercio (`me.tenant.stockRotation`). */
  rotation?: StockRotation;
  /** Lote con descuento aceptado: en liquidación **sale primero** (SPEC §4.2). */
  discounted?: boolean;
  className?: string;
}

const ROTATION_HINT: Record<StockRotation, string> = {
  FIFO: 'primero sale lo que entró antes',
  FEFO: 'primero sale lo que vence antes',
};

/**
 * Orden de salida del lote según la rotación del comercio.
 * El 1º se destaca en verde sólido ("1º sale"); el resto queda en contorno.
 * Un lote en liquidación que además sale primero muestra "En liquidación · sale primero"
 * (mismo verde sólido: el amarillo queda reservado para precios y totales).
 *
 * Solo tiene sentido dentro de un mismo producto y sucursal: nunca en listas de productos distintos.
 */
export function LotRankChip({ rank, rotation = 'FIFO', discounted, className }: LotRankChipProps) {
  const first = rank === 1;
  const base =
    'inline-flex h-[22px] shrink-0 items-center gap-1 whitespace-nowrap rounded-tag border px-1.5 text-xs font-bold tabular-nums';

  if (discounted && first) {
    return (
      <span
        className={cn(base, 'border-primary bg-primary text-primary-foreground', className)}
        title={`En liquidación: se vende primero (${ROTATION_HINT[rotation]})`}
      >
        <Tag className="h-3 w-3" aria-hidden="true" />
        En liquidación <span className="font-semibold">· sale primero</span>
      </span>
    );
  }

  return (
    <span
      className={cn(
        base,
        first ? 'border-primary bg-primary text-primary-foreground' : 'border-input bg-card text-foreground',
        className,
      )}
      title={`${rank}º en salir (${ROTATION_HINT[rotation]})`}
    >
      {rank}º{first && <span className="font-semibold">sale</span>}
    </span>
  );
}
