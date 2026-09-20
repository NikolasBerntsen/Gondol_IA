import { Ban } from 'lucide-react';
import { StatusPill } from '@/components/gondola';
import { Truncate } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatMoney } from '@/lib/format';
import { recallLotsLabel } from '../recall';
import type { PosProduct } from '../types';

export interface PosProductTileProps {
  product: PosProduct;
  /** Unidades que ya están en el carrito (badge de la esquina). */
  inCart: number;
  onAdd: () => void;
}

/**
 * Mosaico del mostrador: nombre, marca, precio con el descuento del lote y estado.
 * Los productos en cuarentena por recall se ven bloqueados y no se pueden vender (SPEC §15.3). Con un recall
 * vigente y sin stock cargado también: no hay lote que verificar, así que no se ofrece "vender igual".
 */
export function PosProductTile({ product, inCart, onAdd }: PosProductTileProps) {
  const recall = product.activeRecall;
  const blocked = product.hasRecalledStock || (!!recall && product.outOfStock);
  const out = product.outOfStock;
  const pct = product.nextLot?.discountPct ?? 0;
  const unitPrice = product.nextLot?.unitPrice ?? product.listPrice;
  const discounted = pct > 0;

  const stateLabel = blocked
    ? ', bloqueado por recall'
    : out
      ? ', sin stock'
      : recall
        ? `, recall vigente del ${recallLotsLabel(recall)}`
        : '';

  return (
    <button
      type="button"
      onClick={onAdd}
      className={cn(
        'group relative flex min-h-[124px] min-w-0 flex-col rounded-panel border border-border bg-card p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        blocked
          ? 'border-crit/50 bg-crit-soft/50 hover:bg-crit-soft'
          : out
            ? 'bg-muted/40 hover:bg-muted'
            : 'hover:border-primary/50 hover:bg-primary/[0.03]',
        inCart > 0 && !blocked && 'border-primary/60',
      )}
      aria-label={`${product.name}, ${formatMoney(unitPrice, { decimals: 2 })}${stateLabel}`}
    >
      <div className="flex items-start justify-between gap-2">
        <Truncate
          lines={2}
          className={cn(
            'text-base font-semibold leading-5 [text-wrap:balance]',
            blocked || out ? 'text-muted-foreground' : 'text-foreground',
          )}
        >
          {product.name}
        </Truncate>
        {inCart > 0 ? (
          <span
            className="grid h-6 min-w-6 place-items-center rounded-full bg-primary px-1.5 text-xs font-bold tabular-nums text-primary-foreground"
            aria-hidden="true"
          >
            {inCart}
          </span>
        ) : null}
      </div>
      <Truncate className="mt-0.5 text-xs text-muted-foreground">{product.brand ?? ' '}</Truncate>

      <div className="mt-auto flex flex-wrap items-end justify-between gap-x-2 gap-y-1 pt-2">
        {blocked ? (
          <span className="inline-flex items-center gap-1 text-sm font-semibold text-crit-ink">
            <Ban className="h-4 w-4" aria-hidden="true" />
            Recall · bloqueado
          </span>
        ) : out ? (
          <StatusPill tone="crit" solid>
            Sin stock
          </StatusPill>
        ) : (
          <span className="flex flex-col">
            {discounted ? (
              <span className="text-xs tabular-nums text-muted-foreground line-through">
                {formatMoney(product.listPrice)}
              </span>
            ) : null}
            <span className="font-display text-lg font-semibold leading-6 tracking-[-0.01em] tabular-nums text-foreground">
              {formatMoney(unitPrice)}
            </span>
          </span>
        )}
        {!blocked && !out ? (
          discounted ? (
            <span className="rounded-tag bg-crit-soft px-1.5 py-0.5 font-mono text-[11px] font-semibold text-crit-ink">
              -{Math.round(pct)}% VTO
            </span>
          ) : (
            <span className="text-xs tabular-nums text-muted-foreground">{product.sellableStock} u.</span>
          )
        ) : null}
      </div>
    </button>
  );
}
