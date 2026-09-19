/**
 * Tema de Góndola UI (docs/frontend-guide.md §7.1, docs/design-system.md §2.1).
 *
 * El CSS (`src/index.css`) ya resuelve los tres estados:
 * - sin atributo en `<html>` → sigue al sistema operativo (`prefers-color-scheme`);
 * - `data-theme="light"` / `data-theme="dark"` → elección explícita, gana siempre.
 *
 * Acá solo se guarda la preferencia y se aplica el atributo. El script inline de `index.html` repite la clave y los
 * colores de la barra del navegador para aplicar el tema antes del primer pintado: si cambiás algo acá, cambialo allá.
 */

export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'gondolia.theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * Color de la barra del navegador en celulares (`<meta name="theme-color">`): el `--background` de cada tema
 * (`src/index.css`). Va literal porque el meta no entiende variables CSS: si cambia el token, cambialo acá.
 */
export const THEME_META_COLOR: Record<ResolvedTheme, string> = {
  light: '#F3F5F1',
  dark: '#0D1410',
};

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'system' || value === 'light' || value === 'dark';
}

/** Preferencia guardada en este navegador; `'system'` si no hay (o si el storage no está disponible). */
export function readStoredPreference(): ThemePreference {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY);
    return isThemePreference(value) ? value : 'system';
  } catch {
    return 'system';
  }
}

export function storePreference(preference: ThemePreference): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, preference);
  } catch {
    // Navegación privada o storage bloqueado: el tema funciona igual, solo no se recuerda.
  }
}

function darkQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null;
}

/** Tema del sistema operativo en este momento. */
export function getSystemTheme(): ResolvedTheme {
  return darkQuery()?.matches ? 'dark' : 'light';
}

/** Escucha los cambios del tema del sistema. Devuelve la función para dejar de escuchar. */
export function subscribeSystemTheme(onChange: (theme: ResolvedTheme) => void): () => void {
  const query = darkQuery();
  if (!query) return () => undefined;
  const listener = (event: MediaQueryListEvent) => onChange(event.matches ? 'dark' : 'light');
  // Safari < 14 solo tiene addListener: sin este respaldo el provider (en la raíz) tiraría y dejaría la app en blanco.
  if (typeof query.addEventListener === 'function') {
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
  }
  query.addListener(listener);
  return () => query.removeListener(listener);
}

/**
 * Aplica el tema al documento: atributo `data-theme` (o ninguno para "Sistema"), `<meta name="color-scheme">`
 * (controles nativos y lienzo antes del CSS) y `<meta name="theme-color">` (barra del navegador).
 */
export function applyTheme(preference: ThemePreference, resolved: ResolvedTheme): void {
  const root = document.documentElement;
  if (preference === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', preference);

  document
    .querySelector('meta[name="color-scheme"]')
    ?.setAttribute('content', preference === 'system' ? 'light dark' : preference);

  // Los dos metas (uno por `media`) quedan con el color del tema que se ve: con "Sistema" coincide con el del
  // sistema y cambia con él (el provider vuelve a llamar a esta función).
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
    meta.setAttribute('content', THEME_META_COLOR[resolved]);
  });
}

/**
 * Ejecuta `fn` sin transiciones CSS, para que el cambio de tema sea instantáneo y parejo (si no, cada
 * `transition-colors` anima a destiempo). Si una CSP bloqueara el `<style>`, el tema cambia igual.
 */
export function withoutTransitions(fn: () => void): void {
  const style = document.createElement('style');
  style.appendChild(document.createTextNode('*,*::before,*::after{transition:none!important}'));
  document.head.appendChild(style);
  try {
    fn();
  } finally {
    // Fuerza el recálculo de estilos con las transiciones apagadas antes de volver a prenderlas.
    void window.getComputedStyle(document.body).opacity;
    window.setTimeout(() => style.remove(), 1);
  }
}
