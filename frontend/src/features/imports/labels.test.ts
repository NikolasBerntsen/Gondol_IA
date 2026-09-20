import { describe, expect, it } from 'vitest';
import {
  FILE_FORMAT_LABELS,
  IMPORT_STATUS_LABELS,
  IMPORT_STATUS_TONES,
  ROW_ACTION_LABELS,
  ROW_STATUS_LABELS,
  ROW_STATUS_TONES,
  WIZARD_STEPS,
  rowSeverity,
  summarizeNames,
} from './labels';

describe('etiquetas y tonos', () => {
  it('cada estado de la importación tiene etiqueta y tono', () => {
    for (const status of Object.keys(IMPORT_STATUS_LABELS) as Array<keyof typeof IMPORT_STATUS_LABELS>) {
      expect(IMPORT_STATUS_LABELS[status]).toBeTruthy();
      expect(IMPORT_STATUS_TONES[status]).toBeTruthy();
    }
  });

  it('cada estado de fila tiene etiqueta y tono', () => {
    for (const status of Object.keys(ROW_STATUS_LABELS) as Array<keyof typeof ROW_STATUS_LABELS>) {
      expect(ROW_STATUS_LABELS[status]).toBeTruthy();
      expect(ROW_STATUS_TONES[status]).toBeTruthy();
    }
  });

  it('los errores van en rojo y las advertencias en amarillo', () => {
    expect(IMPORT_STATUS_TONES.FAILED).toBe('crit');
    expect(ROW_STATUS_TONES.ERROR).toBe('crit');
    expect(ROW_STATUS_TONES.WARNING).toBe('warn');
    expect(ROW_STATUS_TONES.IMPORTED).toBe('ok');
  });

  it('las acciones y los formatos están traducidos', () => {
    expect(ROW_ACTION_LABELS.CREATE).toBe('Alta');
    expect(ROW_ACTION_LABELS.SKIP).toBe('Solo stock');
    expect(FILE_FORMAT_LABELS.XLSX).toContain('Excel');
    expect(FILE_FORMAT_LABELS.CSV).toBe('CSV');
  });

  it('el asistente tiene los cinco pasos', () => {
    expect(WIZARD_STEPS).toHaveLength(5);
    expect(WIZARD_STEPS[0]).toBe('Archivo');
    expect(WIZARD_STEPS.at(-1)).toBe('Resultado');
  });
});

describe('rowSeverity', () => {
  it('marca los errores y las advertencias', () => {
    expect(rowSeverity('ERROR')).toBe('crit');
    expect(rowSeverity('FAILED')).toBe('crit');
    expect(rowSeverity('WARNING')).toBe('warn');
    expect(rowSeverity('VALID')).toBe('none');
    expect(rowSeverity('PENDING')).toBe('none');
    expect(rowSeverity('SKIPPED')).toBe('none');
    expect(rowSeverity('IMPORTED')).toBe('none');
  });
});

describe('summarizeNames', () => {
  it('con pocos nombres los muestra todos', () => {
    expect(summarizeNames(['A', 'B'])).toBe('A, B');
    expect(summarizeNames(['A', 'B', 'C'])).toBe('A, B, C');
  });

  it('si sobra uno solo lo muestra igual (no dice "y 1 más")', () => {
    expect(summarizeNames(['A', 'B', 'C', 'D'])).toBe('A, B, C, D');
  });

  it('a partir de dos de más resume', () => {
    expect(summarizeNames(['A', 'B', 'C', 'D', 'E'])).toBe('A, B, C y 2 más');
  });

  it('respeta el máximo que se le pase', () => {
    expect(summarizeNames(['A', 'B', 'C', 'D', 'E'], 1)).toBe('A y 4 más');
  });

  it('una lista vacía es una cadena vacía', () => {
    expect(summarizeNames([])).toBe('');
  });
});
