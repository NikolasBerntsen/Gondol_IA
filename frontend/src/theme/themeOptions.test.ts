import { describe, expect, it } from 'vitest';
import { RESOLVED_THEME_LABELS, THEME_OPTIONS, themeDescription, themeHint, themeOption } from './themeOptions';

describe('THEME_OPTIONS', () => {
  it('son las tres, en orden', () => {
    expect(THEME_OPTIONS.map((o) => o.value)).toEqual(['system', 'light', 'dark']);
  });

  it('cada una trae etiqueta, bajada, descripción e ícono', () => {
    for (const option of THEME_OPTIONS) {
      expect(option.label).toBeTruthy();
      expect(option.hint).toBeTruthy();
      expect(option.description).toBeTruthy();
      // Los íconos de lucide son componentes con forwardRef (objeto, no función).
      expect(option.icon).toBeTruthy();
    }
  });
});

describe('themeOption', () => {
  it('encuentra la opción por su valor', () => {
    expect(themeOption('dark').label).toBe('Oscuro');
    expect(themeOption('light').label).toBe('Claro');
  });

  it('cae en "Sistema" ante un valor raro', () => {
    expect(themeOption('turquesa' as never).value).toBe('system');
  });
});

describe('themeHint y themeDescription', () => {
  it('"Sistema" cuenta cómo está el dispositivo, no lo que se ve ahora', () => {
    expect(themeHint(themeOption('system'), 'dark')).toBe(`Como tu dispositivo: ${RESOLVED_THEME_LABELS.dark}`);
    expect(themeHint(themeOption('system'), 'light')).toBe(`Como tu dispositivo: ${RESOLVED_THEME_LABELS.light}`);
    expect(themeDescription(themeOption('system'), 'dark')).toContain('Tu dispositivo está en oscuro');
  });

  it('las opciones explícitas no dependen del dispositivo', () => {
    const light = themeOption('light');
    expect(themeHint(light, 'dark')).toBe(light.hint);
    expect(themeDescription(light, 'dark')).toBe(light.description);
  });
});
