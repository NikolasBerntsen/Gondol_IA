import { Monitor, Moon, Sun, type LucideIcon } from 'lucide-react';
import type { ResolvedTheme, ThemePreference } from './theme';

export interface ThemeOption {
  value: ThemePreference;
  label: string;
  /** Bajada corta (menús). */
  hint: string;
  /** Explicación para las tarjetas de "Apariencia" en el perfil. */
  description: string;
  icon: LucideIcon;
}

/** Las tres opciones, en el orden en que se muestran en todos lados (barra superior, menú de usuario, perfil, login). */
export const THEME_OPTIONS: readonly ThemeOption[] = [
  {
    value: 'system',
    label: 'Sistema',
    hint: 'Como tu dispositivo',
    description: 'Cambia solo entre claro y oscuro, igual que tu dispositivo.',
    icon: Monitor,
  },
  {
    value: 'light',
    label: 'Claro',
    hint: 'Fondo claro',
    description: 'Fondo claro y mucho contraste. Ideal con buena luz, en la caja o el salón.',
    icon: Sun,
  },
  {
    value: 'dark',
    label: 'Oscuro',
    hint: 'Menos brillo',
    description: 'Menos brillo en la pantalla. Cómodo de noche o en el depósito.',
    icon: Moon,
  },
];

export const RESOLVED_THEME_LABELS: Record<ResolvedTheme, string> = { light: 'claro', dark: 'oscuro' };

export function themeOption(preference: ThemePreference): ThemeOption {
  return THEME_OPTIONS.find((option) => option.value === preference) ?? THEME_OPTIONS[0];
}
