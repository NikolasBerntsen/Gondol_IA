import { describe, expect, it } from 'vitest';
import type { PosProduct } from './types';
import { priceLine } from './pricing';

type PricedProduct = Pick<PosProduct, 'listPrice' | 'priceTiers'>;

const product = (listPrice: number, priceTiers: PosProduct['priceTiers']): PricedProduct => ({
  listPrice,
  priceTiers,
});

describe('priceLine', () => {
  it('sin lotes cargados cobra todo a precio de lista y lo marca como faltante', () => {
    const result = priceLine(product(100, []), 3);
    expect(result.lineTotal).toBe(300);
    expect(result.listTotal).toBe(300);
    expect(result.shortage).toBe(3);
    expect(result.discountedUnits).toBe(0);
    expect(result.parts).toEqual([{ quantity: 3, unitPrice: 100, discountPct: null }]);
  });

  it('saca las unidades lote por lote, en el orden de priceTiers', () => {
    const result = priceLine(
      product(100, [
        { quantity: 2, unitPrice: 70, discountPct: 30 },
        { quantity: 5, unitPrice: 100, discountPct: null },
      ]),
      4,
    );
    expect(result.parts).toEqual([
      { quantity: 2, unitPrice: 70, discountPct: 30 },
      { quantity: 2, unitPrice: 100, discountPct: null },
    ]);
    expect(result.lineTotal).toBe(340);
    expect(result.listTotal).toBe(400);
    expect(result.discountedUnits).toBe(2);
    expect(result.shortage).toBe(0);
  });

  it('agrupa tramos consecutivos con el mismo precio', () => {
    const result = priceLine(
      product(100, [
        { quantity: 2, unitPrice: 100, discountPct: null },
        { quantity: 3, unitPrice: 100, discountPct: null },
      ]),
      5,
    );
    expect(result.parts).toEqual([{ quantity: 5, unitPrice: 100, discountPct: null }]);
  });

  it('lo que excede el stock cargado va a precio de lista como faltante', () => {
    const result = priceLine(product(100, [{ quantity: 2, unitPrice: 80, discountPct: 20 }]), 5);
    expect(result.shortage).toBe(3);
    expect(result.lineTotal).toBe(2 * 80 + 3 * 100);
    expect(result.parts).toEqual([
      { quantity: 2, unitPrice: 80, discountPct: 20 },
      { quantity: 3, unitPrice: 100, discountPct: null },
    ]);
  });

  it('cuenta en centavos enteros: no arrastra el error de coma flotante', () => {
    const result = priceLine(product(0.1, [{ quantity: 3, unitPrice: 0.1, discountPct: null }]), 3);
    expect(result.lineTotal).toBe(0.3);
    expect(result.listTotal).toBe(0.3);
  });

  it('un descuento de 0 % no cuenta como liquidación', () => {
    const result = priceLine(product(100, [{ quantity: 2, unitPrice: 100, discountPct: 0 }]), 2);
    expect(result.discountedUnits).toBe(0);
    expect(result.parts[0].discountPct).toBeNull();
  });

  it('cantidad cero o negativa no cobra nada', () => {
    expect(priceLine(product(100, [{ quantity: 5, unitPrice: 90, discountPct: 10 }]), 0)).toMatchObject({
      parts: [],
      lineTotal: 0,
      listTotal: 0,
      shortage: 0,
    });
    expect(priceLine(product(100, []), -2).lineTotal).toBe(0);
  });

  it('las cantidades fraccionarias se truncan a unidades enteras', () => {
    expect(priceLine(product(100, []), 2.9).lineTotal).toBe(200);
  });

  it('tolera un producto sin priceTiers', () => {
    const result = priceLine({ listPrice: 50, priceTiers: undefined as unknown as PosProduct['priceTiers'] }, 2);
    expect(result.lineTotal).toBe(100);
    expect(result.shortage).toBe(2);
  });
});
