import type { Tone } from '@/components/gondola';
import type { ImportFileFormat, ImportRowAction, ImportRowStatus, ImportStatus } from './types';

/** Etiquetas y tonos en español de la importación masiva (SPEC §16). */

export const IMPORT_STATUS_LABELS: Record<ImportStatus, string> = {
  UPLOADED: 'Archivo subido',
  VALIDATED: 'Listo para importar',
  APPLYING: 'Importando…',
  APPLIED: 'Importada',
  FAILED: 'Falló',
  CANCELLED: 'Cancelada',
};

export const IMPORT_STATUS_TONES: Record<ImportStatus, Tone> = {
  UPLOADED: 'neutral',
  VALIDATED: 'info',
  APPLYING: 'info',
  APPLIED: 'ok',
  FAILED: 'crit',
  CANCELLED: 'neutral',
};

export const ROW_STATUS_LABELS: Record<ImportRowStatus, string> = {
  PENDING: 'Sin validar',
  VALID: 'Válida',
  WARNING: 'Advertencia',
  ERROR: 'Error',
  SKIPPED: 'Omitida',
  IMPORTED: 'Importada',
  FAILED: 'No se aplicó',
};

export const ROW_STATUS_TONES: Record<ImportRowStatus, Tone> = {
  PENDING: 'neutral',
  VALID: 'ok',
  WARNING: 'warn',
  ERROR: 'crit',
  SKIPPED: 'neutral',
  IMPORTED: 'ok',
  FAILED: 'crit',
};

export const ROW_ACTION_LABELS: Record<ImportRowAction, string> = {
  CREATE: 'Alta',
  UPDATE: 'Actualiza',
  SKIP: 'Solo stock',
};

export const FILE_FORMAT_LABELS: Record<ImportFileFormat, string> = {
  XLSX: 'Excel (.xlsx)',
  XLS: 'Excel (.xls)',
  CSV: 'CSV',
};

/** Pasos del asistente (SPEC §16.4). */
export const WIZARD_STEPS = ['Archivo', 'Columnas', 'Revisión', 'Confirmar', 'Resultado'];

/** Franja de severidad de una fila en la grilla de revisión. */
export function rowSeverity(status: ImportRowStatus): 'crit' | 'warn' | 'none' {
  if (status === 'ERROR' || status === 'FAILED') return 'crit';
  if (status === 'WARNING') return 'warn';
  return 'none';
}

/**
 * Lista corta de nombres para un resumen: "A, B, C y 2 más". Si queda afuera uno solo se muestran todos (no tiene
 * sentido ocupar el lugar de un nombre con «y 1 más»).
 */
export function summarizeNames(names: readonly string[], max = 3): string {
  if (names.length <= max + 1) return names.join(', ');
  return `${names.slice(0, max).join(', ')} y ${names.length - max} más`;
}
