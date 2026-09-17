import { format as formatWithPattern } from 'date-fns';
import { es } from 'date-fns/locale';

/** Zona horaria del negocio (SPEC §intro). Todos los instantes se muestran en esta zona. */
export const BUSINESS_TIME_ZONE = 'America/Argentina/Buenos_Aires';
export const APP_LOCALE = 'es-AR';

/** Texto que se muestra cuando no hay valor. */
export const EMPTY_VALUE = '—';

export type DateInput = string | number | Date | null | undefined;
export type NumericInput = number | string | null | undefined;

const LOCAL_DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const MINUTE = 60;
const HOUR = 60 * MINUTE;

const numberFormatCache = new Map<string, Intl.NumberFormat>();

function numberFormatter(minDecimals: number, maxDecimals: number): Intl.NumberFormat {
  const key = `${minDecimals}:${maxDecimals}`;
  let formatter = numberFormatCache.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(APP_LOCALE, {
      minimumFractionDigits: minDecimals,
      maximumFractionDigits: maxDecimals,
    });
    numberFormatCache.set(key, formatter);
  }
  return formatter;
}

const zonedFormatter = new Intl.DateTimeFormat(APP_LOCALE, {
  timeZone: BUSINESS_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

interface ZonedParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
}

function toNumber(value: NumericInput): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function zonedParts(date: Date): ZonedParts {
  const parts: Record<string, number> = {};
  for (const part of zonedFormatter.formatToParts(date)) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    hour: parts.hour === 24 ? 0 : parts.hour,
    minute: parts.minute,
  };
}

/** Parsea un `LocalDate` ("2026-09-25") a medianoche local, sin corrimiento por UTC. */
export function parseLocalDate(value: string): Date | null {
  const match = LOCAL_DATE_RE.exec(value);
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Convierte cualquier entrada de fecha en `Date` (o `null` si es inválida). */
export function toDate(value: DateInput): Date | null {
  if (value === null || value === undefined || value === '') return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value === 'string') {
    const local = parseLocalDate(value);
    if (local) return local;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Fecha calendario (año, mes, día) de la entrada: literal para `LocalDate`, en zona del negocio para instantes. */
function calendarParts(value: DateInput): { year: number; month: number; day: number } | null {
  if (typeof value === 'string') {
    const match = LOCAL_DATE_RE.exec(value);
    if (match) return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
  }
  const date = toDate(value);
  if (!date) return null;
  const { year, month, day } = zonedParts(date);
  return { year, month, day };
}

function dayNumber(parts: { year: number; month: number; day: number }): number {
  return Math.round(Date.UTC(parts.year, parts.month - 1, parts.day) / 86_400_000);
}

// ---------------------------------------------------------------------------
// Números y dinero
// ---------------------------------------------------------------------------

export interface FormatNumberOptions {
  /** Cantidad fija de decimales. */
  decimals?: number;
  /** Máximo de decimales cuando no se fija `decimals` (por defecto 2). */
  maxDecimals?: number;
}

/** `1234.5` → `"1.234,5"`. */
export function formatNumber(value: NumericInput, options: FormatNumberOptions = {}): string {
  const n = toNumber(value);
  if (n === null) return EMPTY_VALUE;
  const { decimals, maxDecimals = 2 } = options;
  return decimals !== undefined
    ? numberFormatter(decimals, decimals).format(n)
    : numberFormatter(0, maxDecimals).format(n);
}

export interface FormatMoneyOptions {
  /** `'auto'` (defecto): sin decimales si el monto es entero, 2 si no. */
  decimals?: number | 'auto';
}

/** Pesos argentinos: `8450000` → `"$ 8.450.000"`, `1234.5` → `"$ 1.234,50"`. */
export function formatMoney(value: NumericInput, options: FormatMoneyOptions = {}): string {
  const n = toNumber(value);
  if (n === null) return EMPTY_VALUE;
  const { decimals = 'auto' } = options;
  const rounded = Math.round(n * 100) / 100;
  const digits = decimals === 'auto' ? (Number.isInteger(rounded) ? 0 : 2) : decimals;
  const abs = numberFormatter(digits, digits).format(Math.abs(rounded));
  return `${rounded < 0 ? '-' : ''}$ ${abs}`;
}

/** Recibe un porcentaje ya expresado en 0..100: `37.5` → `"37,5%"`. */
export function formatPercent(value: NumericInput, maxDecimals = 1): string {
  const n = toNumber(value);
  if (n === null) return EMPTY_VALUE;
  return `${numberFormatter(0, maxDecimals).format(n)}%`;
}

/** Recibe una proporción 0..1: `0.78` → `"78%"`. */
export function formatRatio(value: NumericInput, maxDecimals = 0): string {
  const n = toNumber(value);
  if (n === null) return EMPTY_VALUE;
  return formatPercent(n * 100, maxDecimals);
}

/** `1` → `"1 producto"`, `3` → `"3 productos"`. */
export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${formatNumber(count)} ${count === 1 ? singular : plural}`;
}

/** `1_250_000` → `"1,2 MB"`. */
export function formatBytes(bytes: NumericInput): string {
  const n = toNumber(bytes);
  if (n === null) return EMPTY_VALUE;
  if (n < 1024) return `${formatNumber(n, { maxDecimals: 0 })} B`;
  const units = ['KB', 'MB', 'GB'];
  let size = n / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${formatNumber(size, { maxDecimals: 1 })} ${units[unit]}`;
}

// ---------------------------------------------------------------------------
// Fechas
// ---------------------------------------------------------------------------

/** `"2026-09-25"` o un instante → `"25/09/2026"`. */
export function formatDate(value: DateInput): string {
  const parts = calendarParts(value);
  if (!parts) return EMPTY_VALUE;
  return `${pad(parts.day)}/${pad(parts.month)}/${parts.year}`;
}

/** Instante → `"25/09/2026 14:30"` (zona del negocio). */
export function formatDateTime(value: DateInput): string {
  const date = toDate(value);
  if (!date) return EMPTY_VALUE;
  const p = zonedParts(date);
  return `${pad(p.day)}/${pad(p.month)}/${p.year} ${pad(p.hour)}:${pad(p.minute)}`;
}

/** Instante → `"14:30"` (zona del negocio). */
export function formatTime(value: DateInput): string {
  const date = toDate(value);
  if (!date) return EMPTY_VALUE;
  const p = zonedParts(date);
  return `${pad(p.hour)}:${pad(p.minute)}`;
}

/** `"Miércoles, 17 de septiembre de 2026"`. Sin argumento usa la fecha de hoy. */
export function formatLongDate(value: DateInput = new Date()): string {
  const parts = calendarParts(value);
  if (!parts) return EMPTY_VALUE;
  const date = new Date(parts.year, parts.month - 1, parts.day);
  return capitalize(formatWithPattern(date, "EEEE, d 'de' MMMM 'de' yyyy", { locale: es }));
}

/** `"17 sep"` — útil para ejes de gráficos. */
export function formatShortDate(value: DateInput): string {
  const parts = calendarParts(value);
  if (!parts) return EMPTY_VALUE;
  const date = new Date(parts.year, parts.month - 1, parts.day);
  return formatWithPattern(date, 'd MMM', { locale: es }).replace('.', '');
}

/** Tiempo relativo: `"recién"`, `"hace 5 min"`, `"hace 2 h"`, `"ayer"`, `"hace 3 días"` o la fecha. */
export function formatRelative(value: DateInput, now: Date = new Date()): string {
  const date = toDate(value);
  if (!date) return EMPTY_VALUE;
  const seconds = Math.round((now.getTime() - date.getTime()) / 1000);
  const past = seconds >= 0;
  const abs = Math.abs(seconds);

  if (abs < 45) return past ? 'recién' : 'en instantes';
  if (abs < HOUR) {
    const minutes = Math.max(1, Math.round(abs / MINUTE));
    return past ? `hace ${minutes} min` : `en ${minutes} min`;
  }

  const days = dayNumber(calendarParts(now)!) - dayNumber(calendarParts(date)!);
  if (abs < 24 * HOUR && (days === 0 || abs < 6 * HOUR)) {
    const hours = Math.floor(abs / HOUR);
    return past ? `hace ${hours} h` : `en ${hours} h`;
  }
  if (days === 1) return 'ayer';
  if (days === -1) return 'mañana';
  if (days > 1 && days < 7) return `hace ${days} días`;
  if (days < -1 && days > -7) return `en ${-days} días`;
  return formatDate(date);
}

/** Fecha de hoy en la zona del negocio como `LocalDate` (`"2026-09-17"`). */
export function todayLocalDate(): string {
  const p = zonedParts(new Date());
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

/** `Date` (componentes locales) → `LocalDate` (`"2026-09-17"`), p. ej. para `<input type="date">` y la API. */
export function toLocalDateString(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Días calendario desde hoy (zona del negocio) hasta la fecha. Negativo si ya pasó. */
export function daysUntil(value: DateInput): number | null {
  const target = calendarParts(value);
  if (!target) return null;
  return dayNumber(target) - dayNumber(calendarParts(new Date())!);
}

/** `0` → `"Vence hoy"`, `1` → `"Vence mañana"`, `-3` → `"Venció hace 3 días"`. */
export function formatDaysLeft(days: number | null | undefined): string {
  if (days === null || days === undefined) return EMPTY_VALUE;
  if (days < -1) return `Venció hace ${-days} días`;
  if (days === -1) return 'Venció ayer';
  if (days === 0) return 'Vence hoy';
  if (days === 1) return 'Vence mañana';
  return `Vence en ${days} días`;
}

// ---------------------------------------------------------------------------
// Texto
// ---------------------------------------------------------------------------

export function capitalize(text: string): string {
  return text ? text.charAt(0).toLocaleUpperCase(APP_LOCALE) + text.slice(1) : text;
}

/** Iniciales para avatares: `"Laura Gómez"` → `"LG"`. */
export function initials(name: string | null | undefined): string {
  if (!name) return '?';
  const words = name.trim().split(/\s+/).filter(Boolean);
  const letters = words.length > 1 ? [words[0][0], words[words.length - 1][0]] : [words[0]?.[0] ?? '?'];
  return letters.join('').toLocaleUpperCase(APP_LOCALE);
}
