import { describe, expect, it } from 'vitest';
import type { ProductListItem, ProductMovement } from './types';
import {
  REFERENCE_CATALOG_SOURCE,
  STOCK_FILTERS,
  formatQuantity,
  lookupSourceLabel,
  movementSign,
  movementTypeLabel,
  normalizeBarcode,
  normalizeLotNumber,
  notHandledInScope,
  parseDateInput,
  parseDecimal,
  suggestCategory,
  unitShort,
  whatsappLink,
} from './lib';

const plain = (value: string) => value.replace(/[  ]/g, ' ');

describe('movimientos', () => {
  it('traduce los tipos conocidos', () => {
    expect(movementTypeLabel('ENTRY')).not.toBe('ENTRY');
    expect(movementTypeLabel('SALE_VOID' as ProductMovement['type'])).toBe('Anulación de venta');
  });

  it('un tipo desconocido se muestra tal cual', () => {
    expect(movementTypeLabel('LO_QUE_SEA' as ProductMovement['type'])).toBe('LO_QUE_SEA');
  });

  it('los ingresos suman y las bajas restan', () => {
    expect(movementSign('ENTRY')).toBe(1);
    expect(movementSign('ADJUSTMENT_IN')).toBe(1);
    expect(movementSign('TRANSFER_IN')).toBe(1);
    expect(movementSign('SALE_VOID' as ProductMovement['type'])).toBe(1);
    expect(movementSign('SALE')).toBe(-1);
    expect(movementSign('TRANSFER_OUT')).toBe(-1);
  });
});

describe('unidades', () => {
  it('abrevia cada unidad', () => {
    expect(unitShort('UNIDAD')).toBe('u.');
    expect(unitShort('KG')).toBe('kg');
    expect(unitShort('LITRO')).toBe('l');
    expect(unitShort('PAQUETE')).toBe('paq.');
    expect(unitShort('CAJA')).toBe('caj.');
  });

  it('sin unidad asume unidades', () => {
    expect(unitShort(null)).toBe('u.');
    expect(unitShort(undefined)).toBe('u.');
  });

  it('formatQuantity junta cantidad y unidad', () => {
    expect(plain(formatQuantity(24, 'UNIDAD'))).toBe('24 u.');
    expect(plain(formatQuantity(3.5, 'KG'))).toBe('3,5 kg');
    expect(plain(formatQuantity(1200))).toBe('1.200 u.');
  });
});

describe('notHandledInScope', () => {
  const product = (
    stockByBranch: ProductListItem['stockByBranch'],
    stockStatus: ProductListItem['stockStatus'],
  ): Pick<ProductListItem, 'stockByBranch' | 'stockStatus'> => ({ stockByBranch, stockStatus });

  it('sin lotes en el alcance y sin estado OUT: la sucursal no lo trabaja', () => {
    expect(notHandledInScope(product([], 'OK'))).toBe(true);
  });

  it('si el estado es OUT sí es un faltante', () => {
    expect(notHandledInScope(product([], 'OUT'))).toBe(false);
  });

  it('con stock por sucursal siempre se muestra', () => {
    const stock: ProductListItem['stockByBranch'] = [
      { branchId: 1, branchName: 'Centro', sellableStock: 4, stockStatus: 'OK' },
    ];
    expect(notHandledInScope(product(stock, 'OK'))).toBe(false);
  });
});

describe('STOCK_FILTERS', () => {
  it('cubre los estados del inventario sin repetirse', () => {
    const values = STOCK_FILTERS.map((f) => f.value);
    expect(values).toEqual(['ALL', 'OK', 'LOW', 'OUT', 'EXPIRING']);
    expect(new Set(values).size).toBe(values.length);
  });
});

describe('whatsappLink', () => {
  it('un número internacional se usa tal cual', () => {
    expect(whatsappLink('+54 9 341 555-1234')).toBe('https://wa.me/5493415551234');
  });

  it('un número local argentino recibe el código de país', () => {
    expect(whatsappLink('0341 555-1234')).toBe('https://wa.me/543415551234');
    expect(whatsappLink('341 555 1234')).toBe('https://wa.me/543415551234');
  });

  it('el "15" de los celulares queda en el número (misma regla que el backend)', () => {
    // wa.me necesita el formato internacional "+54 9 341 555-1234": si el proveedor se carga
    // con el "15", el link sale con un número que WhatsApp no resuelve.
    expect(whatsappLink('0341 15 555-1234')).toBe('https://wa.me/54341155551234');
  });

  it('el prefijo internacional 00 se descarta', () => {
    expect(whatsappLink('0054 341 555 1234')).toBe('https://wa.me/543415551234');
  });

  it('descarta lo que no alcanza a ser un teléfono', () => {
    expect(whatsappLink('123')).toBeNull();
    expect(whatsappLink('sin números')).toBeNull();
    expect(whatsappLink('')).toBeNull();
    expect(whatsappLink(null)).toBeNull();
  });
});

describe('normalización (espejo del backend)', () => {
  it('normalizeBarcode saca todos los espacios', () => {
    expect(normalizeBarcode('  779 012 345 6789 ')).toBe('7790123456789');
  });

  it('normalizeLotNumber deja mayúsculas y alfanuméricos', () => {
    expect(normalizeLotNumber('l-2026/09 a')).toBe('L202609A');
    expect(normalizeLotNumber('--')).toBe('');
  });
});

describe('autocompletado por código de barras', () => {
  const categories = [
    { id: 1, name: 'Almacén' },
    { id: 2, name: 'Lácteos' },
  ];

  it('lookupSourceLabel nombra el origen de los datos', () => {
    // Datos de Open Food Facts: la ODbL pide atribuirlos donde se muestran.
    expect(lookupSourceLabel(REFERENCE_CATALOG_SOURCE)).toBe(
      'catálogo de productos argentinos (datos de Open Food Facts, licencia ODbL)',
    );
    expect(lookupSourceLabel('OPEN_FOOD_FACTS')).toBe('Open Food Facts');
    expect(lookupSourceLabel(null)).toBeNull();
    expect(lookupSourceLabel('OTRA')).toBeNull();
  });

  it('suggestCategory elige la categoría existente sin importar mayúsculas ni tildes', () => {
    expect(suggestCategory(categories, 'Almacén', REFERENCE_CATALOG_SOURCE)).toEqual({ categoryId: 1 });
    expect(suggestCategory(categories, ' lacteos ', 'OPEN_FOOD_FACTS')).toEqual({ categoryId: 2 });
  });

  it('suggestCategory propone crear la del catálogo de referencia, no la de Open Food Facts', () => {
    expect(suggestCategory(categories, 'Golosinas', REFERENCE_CATALOG_SOURCE)).toEqual({
      newCategoryName: 'Golosinas',
    });
    expect(suggestCategory(categories, 'Mieles de flores', 'OPEN_FOOD_FACTS')).toBeNull();
  });

  it('suggestCategory sin sugerencia no propone nada', () => {
    expect(suggestCategory(categories, null, REFERENCE_CATALOG_SOURCE)).toBeNull();
    expect(suggestCategory(categories, '  ', REFERENCE_CATALOG_SOURCE)).toBeNull();
    expect(suggestCategory([], 'Almacén', REFERENCE_CATALOG_SOURCE)).toEqual({ newCategoryName: 'Almacén' });
  });
});

describe('parseDateInput', () => {
  it('acepta dd/mm/aaaa y aaaa-mm-dd', () => {
    expect(parseDateInput('25/09/2026')).toBe('2026-09-25');
    expect(parseDateInput('5-9-2026')).toBe('2026-09-05');
    expect(parseDateInput('2026-09-25')).toBe('2026-09-25');
  });

  it('completa el siglo de un año de dos dígitos', () => {
    expect(parseDateInput('25/09/26')).toBe('2026-09-25');
  });

  it('rechaza fechas que no existen', () => {
    expect(parseDateInput('31/02/2026')).toBeNull();
    expect(parseDateInput('25/13/2026')).toBeNull();
    expect(parseDateInput('00/01/2026')).toBeNull();
  });

  it('rechaza lo que no es una fecha', () => {
    expect(parseDateInput('')).toBeNull();
    expect(parseDateInput('   ')).toBeNull();
    expect(parseDateInput('mañana')).toBeNull();
  });
});

describe('parseDecimal', () => {
  it('entiende la coma decimal y el punto de miles', () => {
    expect(parseDecimal('1.380,50')).toBe(1380.5);
    expect(parseDecimal('1380,5')).toBe(1380.5);
    expect(parseDecimal('1.380')).toBe(1380);
    expect(parseDecimal('1380')).toBe(1380);
  });

  it('un punto decimal (teclado numérico) sigue funcionando', () => {
    expect(parseDecimal('1380.5')).toBe(1380.5);
  });

  it('devuelve null cuando no hay número', () => {
    expect(parseDecimal('')).toBeNull();
    expect(parseDecimal('  ')).toBeNull();
    expect(parseDecimal('abc')).toBeNull();
  });
});
