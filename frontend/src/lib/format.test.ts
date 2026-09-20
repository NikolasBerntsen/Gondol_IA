import { describe, expect, it } from 'vitest';
import {
  EMPTY_VALUE,
  capitalize,
  daysUntil,
  formatBytes,
  formatDate,
  formatDateCompact,
  formatDateTime,
  formatDaysLeft,
  formatLongDate,
  formatMoney,
  formatMoneyCompact,
  formatNumber,
  formatPercent,
  formatRatio,
  formatRelative,
  formatShortDate,
  formatTime,
  initials,
  parseLocalDate,
  pluralize,
  splitMoney,
  toDate,
  toLocalDateString,
  todayLocalDate,
} from './format';

/** Los espacios de Intl en es-AR son NBSP/NNBSP: se normalizan para comparar. */
const plain = (value: string) => value.replace(/[  ]/g, ' ');

describe('formatNumber', () => {
  it('usa el formato argentino (punto de miles, coma decimal)', () => {
    expect(plain(formatNumber(1234.5))).toBe('1.234,5');
    expect(plain(formatNumber(1_000_000))).toBe('1.000.000');
  });

  it('respeta los decimales fijos y el máximo', () => {
    expect(plain(formatNumber(2, { decimals: 2 }))).toBe('2,00');
    expect(plain(formatNumber(2.129, { maxDecimals: 1 }))).toBe('2,1');
  });

  it('acepta números en texto y descarta lo que no es número', () => {
    expect(plain(formatNumber('1500'))).toBe('1.500');
    expect(formatNumber('')).toBe(EMPTY_VALUE);
    expect(formatNumber(null)).toBe(EMPTY_VALUE);
    expect(formatNumber(undefined)).toBe(EMPTY_VALUE);
    expect(formatNumber('no es un número')).toBe(EMPTY_VALUE);
    expect(formatNumber(Number.POSITIVE_INFINITY)).toBe(EMPTY_VALUE);
  });
});

describe('formatMoney', () => {
  it('no muestra decimales cuando el monto es entero', () => {
    expect(plain(formatMoney(8_450_000))).toBe('$ 8.450.000');
  });

  it('muestra dos decimales cuando los hay', () => {
    expect(plain(formatMoney(1234.5))).toBe('$ 1.234,50');
  });

  it('pone el signo antes del símbolo', () => {
    expect(plain(formatMoney(-1234.5))).toBe('-$ 1.234,50');
  });

  it('permite fijar los decimales', () => {
    expect(plain(formatMoney(1234.5, { decimals: 0 }))).toBe('$ 1.235');
    expect(plain(formatMoney(10, { decimals: 2 }))).toBe('$ 10,00');
  });

  it('sin valor devuelve el guion', () => {
    expect(formatMoney(null)).toBe(EMPTY_VALUE);
  });
});

describe('formatMoneyCompact', () => {
  it('deja el importe completo por debajo de 10.000', () => {
    expect(plain(formatMoneyCompact(8450))).toBe('$ 8.450');
  });

  it('abrevia miles y millones', () => {
    expect(plain(formatMoneyCompact(850_000))).toBe('$ 850 mil');
    expect(plain(formatMoneyCompact(25_400))).toBe('$ 25,4 mil');
    expect(plain(formatMoneyCompact(8_974_748.39))).toBe('$ 8,97 M');
    // A partir de 10 M queda 1 decimal, así que 11,97 M se muestra redondeado.
    expect(plain(formatMoneyCompact(11_974_748.39))).toBe('$ 12 M');
    expect(plain(formatMoneyCompact(11_400_000))).toBe('$ 11,4 M');
    expect(plain(formatMoneyCompact(45_000_000))).toBe('$ 45 M');
    expect(plain(formatMoneyCompact(120_000_000))).toBe('$ 120 M');
  });

  it('conserva el signo de los negativos', () => {
    expect(plain(formatMoneyCompact(-2_500_000))).toBe('-$ 2,5 M');
  });

  it('sin valor devuelve el guion', () => {
    expect(formatMoneyCompact(undefined)).toBe(EMPTY_VALUE);
  });
});

describe('splitMoney', () => {
  it('separa la parte entera de los centavos', () => {
    expect(splitMoney(16_270)).toEqual({ int: '16.270', cents: '00', negative: false });
    expect(splitMoney(1234.56)).toEqual({ int: '1.234', cents: '56', negative: false });
  });

  it('marca los negativos y trata la ausencia de valor como cero', () => {
    expect(splitMoney(-5.2).negative).toBe(true);
    expect(splitMoney(null)).toEqual({ int: '0', cents: '00', negative: false });
  });

  it('redondea a dos decimales sin arrastrar el 99,999', () => {
    expect(splitMoney(99.999)).toEqual({ int: '100', cents: '00', negative: false });
  });
});

describe('porcentajes', () => {
  it('formatPercent recibe 0..100', () => {
    expect(plain(formatPercent(37.5))).toBe('37,5%');
    expect(plain(formatPercent(37.55, 0))).toBe('38%');
    expect(formatPercent(null)).toBe(EMPTY_VALUE);
  });

  it('formatRatio recibe 0..1', () => {
    expect(plain(formatRatio(0.78))).toBe('78%');
    expect(plain(formatRatio(0.7812, 2))).toBe('78,12%');
    expect(formatRatio(undefined)).toBe(EMPTY_VALUE);
  });
});

describe('pluralize', () => {
  it('usa el singular solo con 1', () => {
    expect(pluralize(1, 'producto')).toBe('1 producto');
    expect(pluralize(3, 'producto')).toBe('3 productos');
    expect(pluralize(0, 'producto')).toBe('0 productos');
  });

  it('acepta un plural irregular', () => {
    expect(pluralize(2, 'lote', 'lotes')).toBe('2 lotes');
  });
});

describe('formatBytes', () => {
  it('escala de bytes a GB', () => {
    expect(plain(formatBytes(512))).toBe('512 B');
    expect(plain(formatBytes(2048))).toBe('2 KB');
    expect(plain(formatBytes(1_250_000))).toBe('1,2 MB');
    expect(plain(formatBytes(3 * 1024 ** 3))).toBe('3 GB');
  });

  it('sin valor devuelve el guion', () => {
    expect(formatBytes(null)).toBe(EMPTY_VALUE);
  });
});

describe('parseLocalDate y toDate', () => {
  it('parsea un LocalDate a medianoche local (sin corrimiento por UTC)', () => {
    const date = parseLocalDate('2026-09-25');
    expect(date?.getFullYear()).toBe(2026);
    expect(date?.getMonth()).toBe(8);
    expect(date?.getDate()).toBe(25);
  });

  it('rechaza lo que no tiene forma de LocalDate', () => {
    expect(parseLocalDate('25/09/2026')).toBeNull();
    expect(parseLocalDate('2026-09-25T10:00:00Z')).toBeNull();
  });

  it('toDate acepta Date, texto, epoch y descarta lo inválido', () => {
    const date = new Date('2026-09-25T12:00:00Z');
    expect(toDate(date)).toBe(date);
    expect(toDate('2026-09-25T12:00:00Z')?.toISOString()).toBe('2026-09-25T12:00:00.000Z');
    expect(toDate(0)?.getTime()).toBe(0);
    expect(toDate(null)).toBeNull();
    expect(toDate('')).toBeNull();
    expect(toDate('cualquier cosa')).toBeNull();
    expect(toDate(new Date('inválida'))).toBeNull();
  });
});

describe('fechas', () => {
  it('formatDate muestra dd/mm/aaaa', () => {
    expect(formatDate('2026-09-25')).toBe('25/09/2026');
    expect(formatDate('2026-01-05T23:30:00Z')).toBe('05/01/2026');
    expect(formatDate(null)).toBe(EMPTY_VALUE);
  });

  it('formatDate usa la zona del negocio, no UTC', () => {
    // 02:00 UTC del 26 son las 23:00 del 25 en Buenos Aires (UTC-3).
    expect(formatDate('2026-09-26T02:00:00Z')).toBe('25/09/2026');
  });

  it('formatDateCompact acorta el año', () => {
    expect(formatDateCompact('2026-09-25')).toBe('25/09/26');
    expect(formatDateCompact(undefined)).toBe(EMPTY_VALUE);
  });

  it('formatDateTime y formatTime muestran la hora del negocio en 24 h', () => {
    expect(formatDateTime('2026-09-25T17:30:00Z')).toBe('25/09/2026 14:30');
    expect(formatTime('2026-09-25T17:30:00Z')).toBe('14:30');
    // Medianoche: el formateador devuelve 24 y hay que mostrar 00.
    expect(formatTime('2026-09-25T03:00:00Z')).toBe('00:00');
    expect(formatDateTime(null)).toBe(EMPTY_VALUE);
    expect(formatTime(null)).toBe(EMPTY_VALUE);
  });

  it('formatLongDate escribe el día y el mes en español', () => {
    expect(formatLongDate('2026-09-17')).toBe('Jueves, 17 de septiembre de 2026');
    expect(formatLongDate(null)).toBe(EMPTY_VALUE);
  });

  it('formatShortDate sirve para los ejes de los gráficos', () => {
    expect(formatShortDate('2026-09-17')).toBe('17 sep');
    expect(formatShortDate(null)).toBe(EMPTY_VALUE);
  });

  it('toLocalDateString usa los componentes locales', () => {
    expect(toLocalDateString(new Date(2026, 8, 7))).toBe('2026-09-07');
  });

  it('todayLocalDate devuelve un LocalDate', () => {
    expect(todayLocalDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});

describe('formatRelative', () => {
  const now = new Date('2026-09-25T12:00:00Z');

  it('los instantes cercanos son "recién"', () => {
    expect(formatRelative('2026-09-25T11:59:30Z', now)).toBe('recién');
    expect(formatRelative('2026-09-25T12:00:30Z', now)).toBe('en instantes');
  });

  it('cuenta minutos y horas', () => {
    expect(formatRelative('2026-09-25T11:55:00Z', now)).toBe('hace 5 min');
    expect(formatRelative('2026-09-25T12:05:00Z', now)).toBe('en 5 min');
    expect(formatRelative('2026-09-25T10:00:00Z', now)).toBe('hace 2 h');
  });

  it('usa días de calendario para ayer y mañana', () => {
    expect(formatRelative('2026-09-24T12:00:00Z', now)).toBe('ayer');
    expect(formatRelative('2026-09-26T12:00:00Z', now)).toBe('mañana');
    expect(formatRelative('2026-09-22T12:00:00Z', now)).toBe('hace 3 días');
    expect(formatRelative('2026-09-28T12:00:00Z', now)).toBe('en 3 días');
  });

  it('más allá de una semana muestra la fecha', () => {
    expect(formatRelative('2026-09-01T12:00:00Z', now)).toBe('01/09/2026');
  });

  it('sin valor devuelve el guion', () => {
    expect(formatRelative(null, now)).toBe(EMPTY_VALUE);
  });
});

describe('daysUntil y formatDaysLeft', () => {
  it('daysUntil cuenta días de calendario', () => {
    expect(daysUntil(todayLocalDate())).toBe(0);
    expect(daysUntil(null)).toBeNull();
  });

  it('formatDaysLeft explica el vencimiento', () => {
    expect(formatDaysLeft(-3)).toBe('Venció hace 3 días');
    expect(formatDaysLeft(-1)).toBe('Venció ayer');
    expect(formatDaysLeft(0)).toBe('Vence hoy');
    expect(formatDaysLeft(1)).toBe('Vence mañana');
    expect(formatDaysLeft(5)).toBe('Vence en 5 días');
    expect(formatDaysLeft(null)).toBe(EMPTY_VALUE);
  });
});

describe('texto', () => {
  it('capitalize solo toca la primera letra', () => {
    expect(capitalize('miércoles')).toBe('Miércoles');
    expect(capitalize('')).toBe('');
  });

  it('initials toma la primera y la última palabra', () => {
    expect(initials('Laura Gómez')).toBe('LG');
    expect(initials('Laura del Valle Gómez')).toBe('LG');
    expect(initials('Laura')).toBe('L');
    expect(initials('  ')).toBe('?');
    expect(initials(null)).toBe('?');
  });
});
