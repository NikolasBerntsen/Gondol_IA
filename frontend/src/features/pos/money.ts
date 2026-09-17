/**
 * Importes escritos a mano en la caja (efectivo recibido, arqueo, retiros).
 * El cajero escribe como habla: "15.000", "15000", "1.234,50", "$ 2000".
 */

/** Convierte lo que el cajero escribió a un número. `null` si no se entiende. */
export function parseArs(raw: string): number | null {
  const text = raw.replace(/[$\s ]/g, '').trim();
  if (!text) return null;
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,2})?$|^\d+([.,]\d{1,2})?$/.test(text)) return null;

  let normalized: string;
  if (text.includes(',')) {
    // Coma decimal: los puntos son separadores de miles.
    normalized = text.replace(/\./g, '').replace(',', '.');
  } else if (/^\d{1,3}(\.\d{3})+$/.test(text)) {
    // Solo puntos y todos agrupan de a tres: son miles ("15.000").
    normalized = text.replace(/\./g, '');
  } else {
    normalized = text;
  }

  const value = Number(normalized);
  if (!Number.isFinite(value) || value < 0) return null;
  return Math.round(value * 100) / 100;
}

/** Formato para volver a escribir el valor en el input ("15.000" / "1.234,50"). */
export function formatArsInput(value: number): string {
  return value.toLocaleString('es-AR', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
}

/** Suma de importes evitando el error de coma flotante de los centavos. */
export function sumMoney(values: number[]): number {
  return Math.round(values.reduce((acc, value) => acc + value * 100, 0)) / 100;
}

/** Resta con la misma precisión que `sumMoney`. */
export function subtractMoney(a: number, b: number): number {
  return Math.round(a * 100 - b * 100) / 100;
}

/** Billetes de uso corriente en la caja (SPEC §15.3, "billetes rápidos"). */
export const QUICK_BILLS = [1000, 2000, 10000, 20000] as const;
