import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  THEME_META_COLOR,
  THEME_STORAGE_KEY,
  applyTheme,
  getSystemTheme,
  isThemePreference,
  readStoredPreference,
  storePreference,
  subscribeSystemTheme,
  withoutTransitions,
  type ThemePreference,
} from './theme';

/** matchMedia no existe en jsdom: se arma uno que responde lo que pida cada prueba. */
function mockMatchMedia(dark: boolean, options: { legacy?: boolean } = {}) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  const query = {
    matches: dark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: options.legacy ? undefined : (_: string, l: (e: MediaQueryListEvent) => void) => listeners.add(l),
    removeEventListener: options.legacy
      ? undefined
      : (_: string, l: (e: MediaQueryListEvent) => void) => listeners.delete(l),
    addListener: (l: (e: MediaQueryListEvent) => void) => listeners.add(l),
    removeListener: (l: (e: MediaQueryListEvent) => void) => listeners.delete(l),
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => query as unknown as MediaQueryList),
  );
  return {
    listeners,
    emit: (isDark: boolean) => listeners.forEach((l) => l({ matches: isDark } as MediaQueryListEvent)),
  };
}

beforeEach(() => {
  document.documentElement.removeAttribute('data-theme');
  document.head.innerHTML = '';
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('isThemePreference', () => {
  it('acepta las tres opciones y nada más', () => {
    for (const value of ['system', 'light', 'dark'] as ThemePreference[]) {
      expect(isThemePreference(value)).toBe(true);
    }
    expect(isThemePreference('azul')).toBe(false);
    expect(isThemePreference(null)).toBe(false);
    expect(isThemePreference(undefined)).toBe(false);
  });
});

describe('preferencia guardada', () => {
  it('sin nada guardado usa "system"', () => {
    expect(readStoredPreference()).toBe('system');
  });

  it('guarda y recupera la elección', () => {
    storePreference('dark');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(readStoredPreference()).toBe('dark');
  });

  it('un valor inválido en el storage no rompe nada', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'neón');
    expect(readStoredPreference()).toBe('system');
  });

  it('sin storage disponible sigue funcionando', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('SecurityError');
    });
    expect(readStoredPreference()).toBe('system');
    expect(() => storePreference('light')).not.toThrow();
  });
});

describe('tema del sistema', () => {
  it('lee prefers-color-scheme', () => {
    mockMatchMedia(true);
    expect(getSystemTheme()).toBe('dark');
    mockMatchMedia(false);
    expect(getSystemTheme()).toBe('light');
  });

  it('sin matchMedia asume claro', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(getSystemTheme()).toBe('light');
    expect(subscribeSystemTheme(() => undefined)()).toBeUndefined();
  });

  it('avisa cuando el sistema cambia y deja de escuchar al desuscribirse', () => {
    const media = mockMatchMedia(false);
    const onChange = vi.fn();
    const unsubscribe = subscribeSystemTheme(onChange);
    media.emit(true);
    expect(onChange).toHaveBeenCalledWith('dark');
    media.emit(false);
    expect(onChange).toHaveBeenLastCalledWith('light');
    unsubscribe();
    media.emit(true);
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it('usa addListener en los Safari viejos', () => {
    const media = mockMatchMedia(false, { legacy: true });
    const onChange = vi.fn();
    const unsubscribe = subscribeSystemTheme(onChange);
    media.emit(true);
    expect(onChange).toHaveBeenCalledWith('dark');
    unsubscribe();
    expect(media.listeners.size).toBe(0);
  });
});

describe('applyTheme', () => {
  function metas() {
    document.head.innerHTML =
      '<meta name="color-scheme" content="light dark">' +
      '<meta name="theme-color" media="(prefers-color-scheme: light)" content="#fff">' +
      '<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#000">';
  }

  it('"system" saca el atributo y deja que mande el sistema operativo', () => {
    metas();
    document.documentElement.setAttribute('data-theme', 'dark');
    applyTheme('system', 'dark');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    expect(document.querySelector('meta[name="color-scheme"]')?.getAttribute('content')).toBe('light dark');
  });

  it('una elección explícita fija data-theme y los metas', () => {
    metas();
    applyTheme('light', 'light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.querySelector('meta[name="color-scheme"]')?.getAttribute('content')).toBe('light');
    for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
      expect(meta.getAttribute('content')).toBe(THEME_META_COLOR.light);
    }
  });

  it('los dos metas de theme-color toman el color del tema que se ve', () => {
    metas();
    applyTheme('system', 'dark');
    for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
      expect(meta.getAttribute('content')).toBe(THEME_META_COLOR.dark);
    }
  });

  it('sin los metas en el head no falla', () => {
    expect(() => applyTheme('dark', 'dark')).not.toThrow();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});

describe('withoutTransitions', () => {
  it('apaga las transiciones mientras corre la función y las vuelve a prender', () => {
    vi.useFakeTimers();
    let stylesDuringChange = 0;
    withoutTransitions(() => {
      stylesDuringChange = document.head.querySelectorAll('style').length;
    });
    expect(stylesDuringChange).toBe(1);
    vi.advanceTimersByTime(5);
    expect(document.head.querySelectorAll('style').length).toBe(0);
    vi.useRealTimers();
  });

  it('si la función falla, igual saca el estilo', () => {
    vi.useFakeTimers();
    expect(() =>
      withoutTransitions(() => {
        throw new Error('boom');
      }),
    ).toThrow('boom');
    vi.advanceTimersByTime(5);
    expect(document.head.querySelectorAll('style').length).toBe(0);
    vi.useRealTimers();
  });
});
