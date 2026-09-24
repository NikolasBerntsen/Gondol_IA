import { describe, expect, it } from 'vitest';
import {
  addBill,
  canCharge,
  exactCash,
  hasMethod,
  isInvalidAmount,
  lineAmount,
  paymentTotals,
  rebalanceSuggested,
  removeLine,
  setLineAmount,
  toggleMethod,
  toPayments,
  type PaymentLine,
} from './payments';
import type { PaymentMethod } from './types';

const line = (id: number, method: PaymentMethod, amount: string, suggested = false): PaymentLine => ({
  id,
  method,
  amount,
  suggested,
});

describe('paymentTotals', () => {
  it('sin pagos falta todo el total', () => {
    expect(paymentTotals([], 3450)).toEqual({
      paid: 0,
      cash: 0,
      nonCash: 0,
      remaining: 3450,
      change: 0,
      nonCashOver: false,
      invalid: false,
    });
  });

  it('combina medios y el vuelto sale del efectivo', () => {
    const totals = paymentTotals([line(1, 'DEBIT', '2.000'), line(2, 'CASH', '5.000')], 3450);
    expect(totals).toMatchObject({ paid: 7000, cash: 5000, nonCash: 2000, remaining: 0, change: 3550 });
    expect(totals.nonCashOver).toBe(false);
  });

  it('con tarjeta y efectivo, lo que sobra se devuelve del efectivo', () => {
    // Pagó $ 3.000 con tarjeta y $ 1.000 en efectivo para $ 3.450: sobran $ 550.
    expect(paymentTotals([line(1, 'CREDIT', '3.000'), line(2, 'CASH', '1.000')], 3450).change).toBe(550);
  });

  it('si la tarjeta pasa el total no hay vuelto posible', () => {
    const totals = paymentTotals([line(1, 'DEBIT', '4.000'), line(2, 'CASH', '500')], 3450);
    expect(totals.nonCashOver).toBe(true);
    expect(totals.change).toBe(0);
  });

  it('suma en centavos: no arrastra el error de coma flotante', () => {
    expect(paymentTotals([line(1, 'DEBIT', '0,1'), line(2, 'QR', '0,2')], 0.3)).toMatchObject({
      paid: 0.3,
      remaining: 0,
    });
  });

  it('un monto que no se entiende cuenta como 0 y marca la hoja como inválida', () => {
    const totals = paymentTotals([line(1, 'CASH', '12,3,4'), line(2, 'DEBIT', '')], 100);
    expect(totals.paid).toBe(0);
    expect(totals.invalid).toBe(true);
  });
});

describe('canCharge', () => {
  it('pide cubrir el total', () => {
    expect(canCharge(paymentTotals([line(1, 'DEBIT', '3.449')], 3450), 3450)).toBe(false);
    expect(canCharge(paymentTotals([line(1, 'DEBIT', '3.450')], 3450), 3450)).toBe(true);
  });

  it('no deja dar vuelto de tarjeta ni cobrar con montos inválidos', () => {
    expect(canCharge(paymentTotals([line(1, 'DEBIT', '5.000')], 3450), 3450)).toBe(false);
    expect(canCharge(paymentTotals([line(1, 'CASH', '5.000'), line(2, 'QR', 'x')], 3450), 3450)).toBe(false);
    expect(canCharge(paymentTotals([line(1, 'CASH', '5.000')], 3450), 3450)).toBe(true);
  });
});

describe('lineAmount / isInvalidAmount', () => {
  it('vacío es 0 y no es un error', () => {
    expect(lineAmount(line(1, 'CASH', '  '))).toBe(0);
    expect(isInvalidAmount(line(1, 'CASH', '  '))).toBe(false);
  });

  it('entiende el formato del cajero', () => {
    expect(lineAmount(line(1, 'CASH', '$ 1.234,50'))).toBe(1234.5);
    expect(isInvalidAmount(line(1, 'CASH', 'mil'))).toBe(true);
  });
});

describe('toggleMethod', () => {
  it('el primer medio se agrega con todo el total, marcado como sugerido', () => {
    expect(toggleMethod([], 'DEBIT', 1, 3450)).toEqual([line(1, 'DEBIT', '3.450', true)]);
  });

  it('los siguientes se suman al final con lo que falta', () => {
    const lines = [line(1, 'DEBIT', '2.000')];
    expect(toggleMethod(lines, 'CASH', 2, 3450)).toEqual([line(1, 'DEBIT', '2.000'), line(2, 'CASH', '1.450', true)]);
  });

  it('con el total cubierto la línea nueva queda vacía y sugerida: se lleva lo que falte si el cajero reparte', () => {
    const lines = toggleMethod([line(1, 'DEBIT', '3.450', true)], 'CREDIT', 2, 3450);
    expect(lines).toEqual([line(1, 'DEBIT', '3.450', true), line(2, 'CREDIT', '', true)]);
  });

  it('redondea lo que falta a centavos', () => {
    expect(toggleMethod([line(1, 'CASH', '1.000')], 'QR', 2, 1234.567)[1].amount).toBe('234,57');
  });

  it('tocar un medio que ya está quita su línea', () => {
    const lines = [line(1, 'DEBIT', '2.000'), line(2, 'CREDIT', '1.450')];
    expect(toggleMethod(lines, 'DEBIT', 3, 3450)).toEqual([line(2, 'CREDIT', '1.450')]);
  });

  it('si se quita la línea que cubría el total, lo que falta pasa a la sugerida que queda', () => {
    const lines = [line(1, 'DEBIT', '3.450', true), line(2, 'CREDIT', '', true)];
    expect(toggleMethod(lines, 'DEBIT', 3, 3450)).toEqual([line(2, 'CREDIT', '3.450', true)]);
  });
});

describe('rebalanceSuggested', () => {
  it('la primera sugerida se lleva lo que falta después de lo del cajero y las demás quedan vacías', () => {
    const lines = [line(1, 'QR', '', true), line(2, 'DEBIT', '1.000'), line(3, 'CASH', '500', true)];
    expect(rebalanceSuggested(lines, 3450)).toEqual([
      line(1, 'QR', '2.450', true),
      line(2, 'DEBIT', '1.000'),
      line(3, 'CASH', '', true),
    ]);
  });

  it('sigue al total si cambia, sin tocar lo que escribió el cajero', () => {
    const lines = [line(1, 'DEBIT', '1000'), line(2, 'CASH', '2.450', true)];
    expect(rebalanceSuggested(lines, 3300)).toEqual([line(1, 'DEBIT', '1000'), line(2, 'CASH', '2.300', true)]);
    expect(rebalanceSuggested(lines, 4000)[1].amount).toBe('3.000');
  });

  it('si lo del cajero ya cubre el total, las sugeridas quedan vacías', () => {
    const lines = [line(1, 'DEBIT', '3.450', true), line(2, 'CASH', '5.000')];
    expect(rebalanceSuggested(lines, 3450)).toEqual([line(1, 'DEBIT', '', true), line(2, 'CASH', '5.000')]);
  });

  it('si no hay nada que acomodar devuelve el mismo arreglo', () => {
    const lines = [line(1, 'DEBIT', '2.000'), line(2, 'CASH', '1.450', true)];
    expect(rebalanceSuggested(lines, 3450)).toBe(lines);
    expect(rebalanceSuggested([], 3450)).toEqual([]);
  });
});

describe('hasMethod / removeLine / setLineAmount', () => {
  it('quitar la última línea de un medio lo apaga', () => {
    const lines = [line(1, 'DEBIT', '2.000'), line(2, 'CASH', '')];
    expect(hasMethod(lines, 'CASH')).toBe(true);
    const next = removeLine(lines, 2, 3450);
    expect(next).toEqual([line(1, 'DEBIT', '2.000')]);
    expect(hasMethod(next, 'CASH')).toBe(false);
  });

  it('quitar una línea del cajero pasa lo que cubría a la sugerida', () => {
    const lines = [line(1, 'DEBIT', '2.000'), line(2, 'CASH', '1.450', true)];
    expect(removeLine(lines, 1, 3450)).toEqual([line(2, 'CASH', '3.450', true)]);
  });

  it('lo que escribe el cajero deja de ser sugerido', () => {
    const lines = [line(1, 'CASH', '3.450', true), line(2, 'QR', '')];
    expect(setLineAmount(lines, 1, '5000', 3450)).toEqual([line(1, 'CASH', '5000'), line(2, 'QR', '')]);
  });

  it('al repartir entre dos medios, el que no tocó el cajero se lleva lo que falta', () => {
    // Débito + Crédito: Débito arrancó con todo y Crédito vacío.
    const lines = [line(1, 'DEBIT', '3.450', true), line(2, 'CREDIT', '', true)];
    expect(setLineAmount(lines, 2, '1.000', 3450)).toEqual([
      line(1, 'DEBIT', '2.450', true),
      line(2, 'CREDIT', '1.000'),
    ]);
    // Al revés: escribe en Débito y Crédito completa.
    expect(setLineAmount(lines, 1, '1000', 3450)).toEqual([
      line(1, 'DEBIT', '1000'),
      line(2, 'CREDIT', '2.450', true),
    ]);
  });
});

describe('addBill', () => {
  it('el primer billete reemplaza el monto sugerido', () => {
    expect(addBill([line(1, 'CASH', '3.450', true)], 10000, 3450)).toEqual([line(1, 'CASH', '10.000')]);
  });

  it('los siguientes billetes se suman', () => {
    expect(addBill([line(1, 'CASH', '10.000')], 2000, 3450)).toEqual([line(1, 'CASH', '12.000')]);
  });

  it('sobre un monto vacío o ilegible arranca desde cero', () => {
    expect(addBill([line(1, 'CASH', '')], 1000, 3450)[0].amount).toBe('1.000');
    expect(addBill([line(1, 'CASH', 'abc')], 1000, 3450)[0].amount).toBe('1.000');
  });

  it('solo toca la línea de efectivo', () => {
    const debit = line(1, 'DEBIT', '2.000');
    expect(addBill([debit, line(2, 'CASH', '')], 20000, 3450)).toEqual([debit, line(2, 'CASH', '20.000')]);
  });

  it('lo sugerido en otro medio se acomoda a lo que se recibió en efectivo', () => {
    const lines = [line(1, 'DEBIT', '3.450', true), line(2, 'CASH', '', true)];
    expect(addBill(lines, 1000, 3450)).toEqual([line(1, 'DEBIT', '2.450', true), line(2, 'CASH', '1.000')]);
    // Si el efectivo ya cubre todo, el débito sugerido queda vacío y el excedente es vuelto.
    expect(addBill(lines, 10000, 3450)[0]).toEqual(line(1, 'DEBIT', '', true));
  });
});

describe('exactCash', () => {
  it('el efectivo cubre lo que no pagan los otros medios', () => {
    const lines = [line(1, 'DEBIT', '2.000'), line(2, 'CASH', '9.000')];
    expect(exactCash(lines, 3450)).toEqual([line(1, 'DEBIT', '2.000'), line(2, 'CASH', '1.450', true)]);
  });

  it('si los otros medios ya cubren el total no cambia nada', () => {
    const lines = [line(1, 'DEBIT', '3.450'), line(2, 'CASH', '')];
    expect(exactCash(lines, 3450)).toBe(lines);
  });

  it('sin línea de efectivo no cambia nada', () => {
    const lines = [line(1, 'DEBIT', '1.000', true)];
    expect(exactCash(lines, 3450)).toBe(lines);
  });

  it('deja fijos los otros medios como se ven y el efectivo pasa a seguir al total', () => {
    const lines = [line(1, 'DEBIT', '2.450', true), line(2, 'CASH', '1.000')];
    const exact = exactCash(lines, 3450);
    expect(exact).toEqual([line(1, 'DEBIT', '2.450'), line(2, 'CASH', '1.000', true)]);
    // Si después baja el total, se ajusta el efectivo y no el débito.
    expect(rebalanceSuggested(exact, 3300)).toEqual([line(1, 'DEBIT', '2.450'), line(2, 'CASH', '850', true)]);
  });
});

describe('toPayments', () => {
  it('manda las líneas con monto en el orden de la hoja', () => {
    expect(toPayments([line(1, 'CREDIT', '1.000'), line(2, 'DEBIT', ''), line(3, 'CASH', '2.450,50')])).toEqual([
      { method: 'CREDIT', amount: 1000 },
      { method: 'CASH', amount: 2450.5 },
    ]);
  });
});
