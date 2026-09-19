import type { PosProduct, PosRecallRef } from './types';

/** "lote L2409A" · "lotes L2409A, L2409B" · "todos los lotes". */
export function recallLotsLabel(recall: PosRecallRef): string {
  if (recall.allLots || recall.lotNumbers.length === 0) return 'todos los lotes';
  return `${recall.lotNumbers.length === 1 ? 'lote' : 'lotes'} ${recall.lotNumbers.join(', ')}`;
}

/**
 * Tope de unidades vendibles de un producto con recall vigente: solo las de lotes cargados (ya pasaron por el
 * chequeo de recall). `null` si el producto no tiene recall y admite venta con faltante.
 */
export function recallCap(product: Pick<PosProduct, 'activeRecall' | 'sellableStock'>): number | null {
  return product.activeRecall ? Math.max(0, product.sellableStock) : null;
}
