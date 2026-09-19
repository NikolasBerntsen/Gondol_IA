import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import {
  applyTheme,
  getSystemTheme,
  readStoredPreference,
  storePreference,
  subscribeSystemTheme,
  THEME_STORAGE_KEY,
  withoutTransitions,
  type ResolvedTheme,
  type ThemePreference,
} from './theme';

export interface ThemeContextValue {
  /** Lo que eligió el usuario en este navegador: `system` (sigue al sistema operativo), `light` o `dark`. */
  preference: ThemePreference;
  /** El tema que se ve ahora. Dependé de este valor si leés colores desde JS (canvas, `getComputedStyle`). */
  resolved: ResolvedTheme;
  setPreference: (preference: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/**
 * Tema claro / oscuro / sistema (docs/frontend-guide.md §7.1). Va en la raíz de la app (`App.tsx`), así funciona
 * también en el login, el ticket de impresión y cualquier pantalla fuera del AppShell.
 *
 * La preferencia vive en `localStorage["gondolia.theme"]` (por navegador, no por cuenta). El script inline de
 * `index.html` la aplica antes de que cargue React; este provider la mantiene sincronizada después: cambios del
 * usuario, del sistema operativo y de otras pestañas.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(readStoredPreference);
  const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(getSystemTheme);
  const resolved: ResolvedTheme = preference === 'system' ? systemTheme : preference;
  const applied = useRef(false);

  // Tema del sistema operativo (se sigue escuchando aunque el usuario haya elegido uno fijo: si vuelve a
  // "Sistema" ya está al día).
  useEffect(() => subscribeSystemTheme(setSystemTheme), []);

  // Otra pestaña cambió el tema: se aplica acá también.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === THEME_STORAGE_KEY || event.key === null) setPreferenceState(readStoredPreference());
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  // Antes del pintado: la primera vez solo confirma lo que ya hizo `index.html`; después cambia sin transiciones.
  useLayoutEffect(() => {
    if (!applied.current) {
      applied.current = true;
      applyTheme(preference, resolved);
      return;
    }
    withoutTransitions(() => applyTheme(preference, resolved));
  }, [preference, resolved]);

  const setPreference = useCallback((next: ThemePreference) => {
    setPreferenceState(next);
    storePreference(next);
  }, []);

  const value = useMemo<ThemeContextValue>(
    () => ({ preference, resolved, setPreference }),
    [preference, resolved, setPreference],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

/** `{ preference, resolved, setPreference }` del tema. Requiere `ThemeProvider` arriba (ya está en `App.tsx`). */
export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useTheme() tiene que usarse dentro de <ThemeProvider>.');
  return context;
}
