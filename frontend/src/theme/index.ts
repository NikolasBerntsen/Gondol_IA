// Tema claro / oscuro / sistema (docs/frontend-guide.md §7.1). Importá desde '@/theme'.
export { ThemeProvider, useTheme, type ThemeContextValue } from './ThemeProvider';
export {
  RESOLVED_THEME_LABELS,
  THEME_OPTIONS,
  themeDescription,
  themeHint,
  themeOption,
  type ThemeOption,
} from './themeOptions';
export {
  THEME_META_COLOR,
  THEME_STORAGE_KEY,
  isThemePreference,
  type ResolvedTheme,
  type ThemePreference,
} from './theme';
