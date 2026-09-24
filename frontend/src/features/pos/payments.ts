/**
 * Líneas de pago de la hoja de cobro (SPEC §15.3): el cajero toca los medios con los que paga el cliente y cada uno
 * suma una línea, en el orden en que los tocó. Hay una línea por medio: el botón del medio está iluminado mientras su
 * línea exista, y volver a tocarlo la quita.
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
   * El monto lo puso la hoja (lo que faltaba al agregar la línea, o "Monto justo") y no el cajero. En efectivo el
   * primer billete rápido lo reemplaza en vez de sumarle: si faltan $ 3.450 y te dan uno de $ 10.000, recibiste
   * $ 10.000, no $ 13.450.
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
 * Tocar un medio. Si no está, agrega su línea al final con lo que falta (vacía si ya está cubierto); si ya estaba
 * (botón iluminado), la quita. `id` es el de la línea nueva.
 */
export function toggleMethod(lines: PaymentLine[], method: PaymentMethod, id: number, total: number): PaymentLine[] {
  if (hasMethod(lines, method)) return lines.filter((line) => line.method !== method);
  const { remaining } = paymentTotals(lines, total);
  return [...lines, { id, method, amount: remaining ? formatArsInput(remaining) : '', suggested: remaining > 0 }];
}

export function removeLine(lines: PaymentLine[], id: number): PaymentLine[] {
  return lines.filter((line) => line.id !== id);
}

/** El cajero escribió en el input: desde acá el monto es suyo. */
export function setLineAmount(lines: PaymentLine[], id: number, amount: string): PaymentLine[] {
  return lines.map((line) => (line.id === id ? { ...line, amount, suggested: false } : line));
}

/** Billete rápido sobre la línea de efectivo: reemplaza el monto sugerido o suma al que cargó el cajero. */
export function addBill(lines: PaymentLine[], bill: number): PaymentLine[] {
  return lines.map((line) => {
    if (line.method !== 'CASH') return line;
    const amount = line.suggested ? bill : sumMoney([lineAmount(line), bill]);
    return { ...line, amount: formatArsInput(amount), suggested: false };
  });
}

/** "Monto justo": el efectivo cubre exactamente lo que no pagan los otros medios. Sin nada que cubrir, no cambia. */
export function exactCash(lines: PaymentLine[], total: number): PaymentLine[] {
  const need = subtractMoney(total, paymentTotals(lines, total).nonCash);
  if (need <= 0) return lines;
  return lines.map((line) =>
    line.method === 'CASH' ? { ...line, amount: formatArsInput(need), suggested: true } : line,
  );
}

/** Pagos que viajan al núcleo (`PosSaleRequest.payments`): las líneas con monto, en el orden de la hoja. */
export function toPayments(lines: PaymentLine[]): Array<{ method: PaymentMethod; amount: number }> {
  return lines
    .map((line) => ({ method: line.method, amount: lineAmount(line) }))
    .filter((payment) => payment.amount > 0);
}
