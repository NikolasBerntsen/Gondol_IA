/**
 * Precio de una línea del carrito tal como la va a cobrar el núcleo (SPEC §4.2, api-h §9 "El total lo calcula el
 * núcleo"): las unidades salen lote por lote en el orden de `priceTiers` (liquidación primero, después FIFO/FEFO),
 * cada una al precio de su lote, y lo que exceda el stock cargado es faltante a precio de lista.
 *
 * Todo se cuenta en centavos enteros para que el total coincida al centavo con la suma de los movimientos.
 */
import type { PosProduct } from './types';

/** Tramo de la línea: `quantity` unidades a `unitPrice`. `discountPct` null = precio de lista. */
export interface LinePricePart {
  quantity: number;
  unitPrice: number;
  discountPct: number | null;
}

export interface LinePricing {
  /** Tramos en el orden en el que salen, agrupando los que tienen el mismo precio. */
  parts: LinePricePart[];
  /** Total que se cobra por la línea. */
  lineTotal: number;
  /** Total a precio de lista (para el subtotal y los descuentos). */
  listTotal: number;
  /** Unidades que salen de un lote en liquidación. */
  discountedUnits: number;
  /** Unidades por encima del stock cargado (faltante, a precio de lista). */
  shortage: number;
}

const toCents = (value: number) => Math.round(value * 100);

export function priceLine(product: Pick<PosProduct, 'listPrice' | 'priceTiers'>, quantity: number): LinePricing {
  const listCents = toCents(product.listPrice);
  const parts: LinePricePart[] = [];
  let totalCents = 0;
  let remaining = Math.max(0, Math.floor(quantity));
  let discountedUnits = 0;

  const push = (units: number, unitPrice: number, discountPct: number | null) => {
    if (units <= 0) return;
    totalCents += toCents(unitPrice) * units;
    const last = parts[parts.length - 1];
    if (last && toCents(last.unitPrice) === toCents(unitPrice) && (last.discountPct ?? 0) === (discountPct ?? 0)) {
      last.quantity += units;
    } else {
      parts.push({ quantity: units, unitPrice, discountPct });
    }
  };

  for (const tier of product.priceTiers ?? []) {
    if (remaining <= 0) break;
    const units = Math.min(remaining, tier.quantity);
    const pct = tier.discountPct && tier.discountPct > 0 ? tier.discountPct : null;
    push(units, tier.unitPrice, pct);
    if (pct) discountedUnits += units;
    remaining -= units;
  }
  const shortage = remaining;
  push(shortage, product.listPrice, null);

  return {
    parts,
    lineTotal: totalCents / 100,
    listTotal: (listCents * Math.max(0, Math.floor(quantity))) / 100,
    discountedUnits,
    shortage,
  };
}
