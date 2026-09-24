// Helpers del módulo A1 — Catálogo y carga de mercadería.
import { MOVEMENT_TYPE_LABELS, type ProductUnit } from '@/api/types';
import type { CategoryDto, ProductListItem, ProductMovement, ProductStockFilter } from './types';

/** Etiquetas de los tipos que el núcleo todavía no lista (A2 los agrega al historial completo). */
const EXTRA_TYPE_LABELS: Record<string, string> = {
  SALE_VOID: 'Anulación de venta',
};

export function movementTypeLabel(type: ProductMovement['type']): string {
  return (MOVEMENT_TYPE_LABELS as Record<string, string>)[type] ?? EXTRA_TYPE_LABELS[type] ?? type;
}

/** Mismas etiquetas que Ventas y Movimientos: "POS externo" vs. "POS GondolIA" (SPEC §4.1). */
export { movementSourceLabel } from '@/api/types';

/** Los ingresos suman y las bajas restan: sirve para el signo y el color de la fila. */
export function movementSign(type: ProductMovement['type']): 1 | -1 {
  switch (type) {
    case 'ENTRY':
    case 'ADJUSTMENT_IN':
    case 'TRANSFER_IN':
    case 'SALE_VOID':
      return 1;
    default:
      return -1;
  }
}

/** Abreviatura de la unidad para las cantidades ("24 u.", "3,5 kg"). */
const UNIT_SHORT: Record<ProductUnit, string> = {
  UNIDAD: 'u.',
  KG: 'kg',
  LITRO: 'l',
  PAQUETE: 'paq.',
  CAJA: 'caj.',
};

export function unitShort(unit: ProductUnit | null | undefined): string {
  return unit ? UNIT_SHORT[unit] : 'u.';
}

/** Cantidad con su unidad: `24 u.`. */
export function formatQuantity(quantity: number, unit?: ProductUnit | null): string {
  return `${new Intl.NumberFormat('es-AR').format(quantity)} ${unitShort(unit)}`;
}

/**
 * `true` cuando ninguna sucursal del alcance trabaja el producto: nunca tuvo lotes ahí, así que no hay stock que
 * mostrar ni faltante que avisar (es la misma regla del Inicio, SPEC §4.2). El backend lo informa con
 * `stockByBranch` vacío y un estado distinto de `Sin stock`; si el comercio no lo tiene en ninguna sucursal, el
 * estado sí es `OUT` y la fila va en rojo como cualquier faltante.
 */
export function notHandledInScope(product: Pick<ProductListItem, 'stockByBranch' | 'stockStatus'>): boolean {
  return product.stockByBranch.length === 0 && product.stockStatus !== 'OUT';
}

/** Opciones del filtro de estado de stock del inventario (SPEC §6.3). */
export const STOCK_FILTERS: ReadonlyArray<{ value: ProductStockFilter; label: string }> = [
  { value: 'ALL', label: 'Todos' },
  { value: 'OK', label: 'Con stock' },
  { value: 'LOW', label: 'Stock bajo' },
  { value: 'OUT', label: 'Sin stock' },
  { value: 'EXPIRING', label: 'Por vencer' },
];

/**
 * Link de WhatsApp del proveedor. Toma los dígitos del teléfono y antepone el código de país argentino
 * cuando el número está escrito en formato local (0341 555-1234 → 543415551234). Misma regla que el
 * backend (`RecommendationService.whatsappPhone`), que arma el link del pedido al proveedor.
 *
 * Ojo con los celulares escritos a la argentina ("0341 15 555-1234"): el "15" queda en el número y
 * wa.me espera el formato internacional ("+54 9 341 555-1234"). Cargalos con el "+54 9" adelante.
 */
export function whatsappLink(phone: string | null | undefined): string | null {
  if (!phone) return null;
  let digits = phone.replace(/\D/g, '');
  if (!digits) return null;
  if (phone.trim().startsWith('+')) {
    return `https://wa.me/${digits}`;
  }
  if (digits.startsWith('00')) digits = digits.slice(2);
  if (!digits.startsWith('54')) {
    digits = digits.replace(/^0/, '');
    digits = `54${digits}`;
  }
  return digits.length >= 10 ? `https://wa.me/${digits}` : null;
}

/** Código de barras tal como lo normaliza el backend (`Barcodes.normalize`). */
export function normalizeBarcode(raw: string): string {
  return raw.replace(/\s+/g, '').trim();
}

/** Origen del autocompletado por código de barras: el catálogo de productos argentinos que trae el sistema. */
export const REFERENCE_CATALOG_SOURCE = 'REFERENCE_CATALOG';

/**
 * De dónde salieron los datos del autocompletado (`BarcodeLookupResponse.source`), para mostrarlo al cargar. Los del
 * catálogo de referencia son de Open Food Facts: la licencia ODbL pide decirlo donde se muestran.
 */
export function lookupSourceLabel(source: string | null | undefined): string | null {
  switch (source) {
    case REFERENCE_CATALOG_SOURCE:
      return 'catálogo de productos argentinos (datos de Open Food Facts, licencia ODbL)';
    case 'OPEN_FOOD_FACTS':
      return 'Open Food Facts';
    default:
      return null;
  }
}

export type CategorySuggestion = { categoryId: number } | { newCategoryName: string } | null;

/**
 * Categoría para un producto autocompletado por su código: la del comercio que se llama igual (sin importar
 * mayúsculas ni tildes) o, si el dato viene del catálogo de referencia, una nueva con ese nombre (son las categorías
 * del mundo demo). Open Food Facts trae categorías en crudo ("Mieles de flores"): de ahí solo se toma una existente.
 */
export function suggestCategory(
  categories: ReadonlyArray<Pick<CategoryDto, 'id' | 'name'>>,
  hint: string | null | undefined,
  source: string | null | undefined,
): CategorySuggestion {
  const name = hint?.trim();
  if (!name) return null;
  const existing = categories.find((category) => categoryKey(category.name) === categoryKey(name));
  if (existing) return { categoryId: existing.id };
  return source === REFERENCE_CATALOG_SOURCE ? { newCategoryName: name } : null;
}

function categoryKey(name: string): string {
  return name.normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim().toLowerCase();
}

/** Número de lote tal como lo normaliza el backend (`LotNumbers.normalize`). */
export function normalizeLotNumber(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

/** `dd/mm/aaaa` o `aaaa-mm-dd` → `aaaa-mm-dd` (o `null` si la fecha no existe). */
export function parseDateInput(raw: string): string | null {
  const value = raw.trim();
  if (!value) return null;
  const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const dmy = /^(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})$/.exec(value);
  let year: number;
  let month: number;
  let day: number;
  if (iso) {
    year = Number(iso[1]);
    month = Number(iso[2]);
    day = Number(iso[3]);
  } else if (dmy) {
    day = Number(dmy[1]);
    month = Number(dmy[2]);
    year = Number(dmy[3]);
    if (year < 100) year += 2000;
  } else {
    return null;
  }
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** Texto a número aceptando coma decimal y puntos de miles ("1.380,50" → 1380.5). */
export function parseDecimal(raw: string): number | null {
  const value = raw.trim();
  if (!value) return null;
  const normalized = value.includes(',')
    ? value.replace(/\./g, '').replace(',', '.')
    : value.replace(/\.(?=\d{3}\b)/g, '');
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}
