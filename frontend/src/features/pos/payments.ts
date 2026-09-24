/**
 * Líneas de pago de la hoja de cobro (SPEC §15.3): el cajero toca los medios con los que paga el cliente y cada uno
 * suma una línea, en el orden en que los tocó. Hay una línea por medio: el botón del medio está iluminado mientras su
 * línea exista, y volver a tocarlo la quita.
 *
 * Las líneas que el cajero no tocó (`suggested`) se completan solas: lo que falta después de las que sí escribió lo
 * toma la primera de ellas, y las otras quedan vacías. Así, con un total de $ 3.450 y Débito + Crédito, escribir
 * $ 1.000 en Crédito deja Débito en $ 2.450 en vez de pasarse del total. Cada cambio de líneas (y del total) pasa por
 * `rebalanceSuggested`.
 *
 * Lógica pura (sin React) para probarla aparte de la pantalla. Las reglas son las del núcleo
 * (`PosSaleService.validatePayments`): los pagos cubren el total y el excedente sale del efectivo.
 */
import { formatArsInput, parseArs, subtractMoney, sumMoney } from './money';
import type { PaymentMethod } from './types';

export interface PaymentLine {
  id: number;
  method: PaymentMethod;
  /** Lo que se ve en el input, como lo escribe el cajero ("15.000", "1.234,50"). */
  amount: string;
  /**
   * El monto lo pone la hoja (lo que falta, o "Monto justo") y no el cajero: sigue a los cambios de las otras líneas y
   * del total. En efectivo el primer billete rápido lo reemplaza en vez de sumarle: si faltan $ 3.450 y te dan uno de
   * $ 10.000, recibiste $ 10.000, no $ 13.450.
   */
  suggested: boolean;
}

export interface PaymentTotals {
  /** Suma de todas las líneas. */
  paid: number;
  /** Parte del pago en efectivo. */
  cash: number;
  /** Tarjetas, transferencia y QR. */
  nonCash: number;
  /** Lo que falta para cubrir el total (0 si ya está cubierto). */
  remaining: number;
  /** Vuelto: el excedente, que solo puede salir del efectivo. */
  change: number;
  /** Lo que no es efectivo pasa el total: habría vuelto de tarjeta, transferencia o QR (`CHANGE_NOT_ALLOWED`). */
  nonCashOver: boolean;
  /** Hay un monto escrito que no se entiende. */
  invalid: boolean;
}

/** Monto de la línea; lo que no se entiende (o está vacío) cuenta como 0. */
export function lineAmount(line: PaymentLine): number {
  return parseArs(line.amount) ?? 0;
}

/** El cajero escribió algo que no es un monto. */
export function isInvalidAmount(line: PaymentLine): boolean {
  return line.amount.trim() !== '' && parseArs(line.amount) === null;
}

export function paymentTotals(lines: PaymentLine[], total: number): PaymentTotals {
  const paid = sumMoney(lines.map(lineAmount));
  const cash = sumMoney(lines.filter((line) => line.method === 'CASH').map(lineAmount));
  const nonCash = subtractMoney(paid, cash);
  const remaining = Math.max(0, subtractMoney(total, paid));
  const over = Math.max(0, subtractMoney(paid, total));
  const nonCashOver = nonCash > total;
  const change = nonCashOver ? 0 : Math.min(over, cash);
  return { paid, cash, nonCash, remaining, change, nonCashOver, invalid: lines.some(isInvalidAmount) };
}

/** Se puede confirmar: cubre el total, el vuelto sale del efectivo y todos los montos se entienden. */
export function canCharge(totals: PaymentTotals, total: number): boolean {
  return totals.paid >= total && !totals.nonCashOver && !totals.invalid;
}

export function hasMethod(lines: PaymentLine[], method: PaymentMethod): boolean {
  return lines.some((line) => line.method === method);
}

/**
 * Completa las líneas sugeridas: la primera se lleva lo que falta después de las que escribió el cajero (redondeado a
 * centavos, vacía si no falta nada) y las demás sugeridas quedan vacías. Si no cambia nada devuelve el mismo arreglo.
 */
export function rebalanceSuggested(lines: PaymentLine[], total: number): PaymentLine[] {
  const typed = sumMoney(lines.filter((line) => !line.suggested).map(lineAmount));
  let left = Math.max(0, subtractMoney(total, typed));
  let changed = false;
  const next = lines.map((line) => {
    if (!line.suggested) return line;
    const amount = left > 0 ? formatArsInput(left) : '';
    left = 0;
    if (line.amount === amount) return line;
    changed = true;
    return { ...line, amount };
  });
  return changed ? next : lines;
}

/**
 * Tocar un medio. Si no está, agrega su línea al final como sugerida: se lleva lo que falta, o queda vacía si otra
 * línea sugerida ya lo cubre. Si ya estaba (botón iluminado), la quita. `id` es el de la línea nueva.
 */
export function toggleMethod(lines: PaymentLine[], method: PaymentMethod, id: number, total: number): PaymentLine[] {
  if (hasMethod(lines, method)) return rebalanceSuggested(lines.filter((line) => line.method !== method), total);
  return rebalanceSuggested([...lines, { id, method, amount: '', suggested: true }], total);
}

/** Quitar una línea: lo que cubría pasa a la primera sugerida que quede. */
export function removeLine(lines: PaymentLine[], id: number, total: number): PaymentLine[] {
  return rebalanceSuggested(lines.filter((line) => line.id !== id), total);
}

/** El cajero escribió en el input: desde acá el monto es suyo y las sugeridas se acomodan a lo que falte. */
export function setLineAmount(lines: PaymentLine[], id: number, amount: string, total: number): PaymentLine[] {
  return rebalanceSuggested(
    lines.map((line) => (line.id === id ? { ...line, amount, suggested: false } : line)),
    total,
  );
}

/** Billete rápido sobre la línea de efectivo: reemplaza el monto sugerido o suma al que cargó el cajero. */
export function addBill(lines: PaymentLine[], bill: number, total: number): PaymentLine[] {
  return rebalanceSuggested(
    lines.map((line) => {
      if (line.method !== 'CASH') return line;
      const amount = line.suggested ? bill : sumMoney([lineAmount(line), bill]);
      return { ...line, amount: formatArsInput(amount), suggested: false };
    }),
    total,
  );
}

/**
 * "Monto justo": el efectivo cubre exactamente lo que no pagan los otros medios, tal como se ven. Esos montos quedan
 * fijos (dejan de ser sugeridos) y el efectivo pasa a ser el que sigue al total. Sin nada que cubrir, no cambia.
 */
export function exactCash(lines: PaymentLine[], total: number): PaymentLine[] {
  const need = subtractMoney(total, paymentTotals(lines, total).nonCash);
  if (need <= 0 || !hasMethod(lines, 'CASH')) return lines;
  return rebalanceSuggested(
    lines.map((line) => {
      if (line.method === 'CASH') return { ...line, amount: formatArsInput(need), suggested: true };
      return line.suggested ? { ...line, suggested: false } : line;
    }),
    total,
  );
}

/** Pagos que viajan al núcleo (`PosSaleRequest.payments`): las líneas con monto, en el orden de la hoja. */
export function toPayments(lines: PaymentLine[]): Array<{ method: PaymentMethod; amount: number }> {
  return lines
    .map((line) => ({ method: line.method, amount: lineAmount(line) }))
    .filter((payment) => payment.amount > 0);
}
