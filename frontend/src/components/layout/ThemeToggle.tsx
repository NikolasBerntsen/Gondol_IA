import { Check } from 'lucide-react';
import { DropdownItem, DropdownLabel, DropdownPanel, useDropdown } from '@/components/ui/Dropdown';
import { cn } from '@/lib/cn';
import { RESOLVED_THEME_LABELS, THEME_OPTIONS, themeOption, useTheme } from '@/theme';

export interface ThemeToggleProps {
  /** Clases del contenedor (posición). */
  className?: string;
  /** Clases del botón (p. ej. colores del riel en el encabezado móvil del login). */
  triggerClassName?: string;
  align?: 'start' | 'end';
}

/**
 * Botón "Cambiar tema" de la barra superior (y del login): muestra el ícono de la preferencia actual
 * (Monitor · Sol · Luna) y abre un menú con Sistema / Claro / Oscuro. Flechas, Enter y Esc incluidos.
 */
export function ThemeToggle({ className, triggerClassName, align = 'end' }: ThemeToggleProps) {
  const { preference, resolved, setPreference } = useTheme();
  const dropdown = useDropdown();
  const current = themeOption(preference);
  const CurrentIcon = current.icon;

  return (
    <div className={cn('relative shrink-0', className)}>
      <button
        {...dropdown.triggerProps}
        aria-label="Cambiar tema"
        title={`Tema: ${current.label}`}
        className={cn(
          'grid h-9 w-9 place-items-center rounded-control text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-expanded:bg-muted',
          triggerClassName,
        )}
      >
        <CurrentIcon className="h-[18px] w-[18px]" aria-hidden="true" />
      </button>

      {dropdown.open && (
        <DropdownPanel {...dropdown.panelProps} align={align} aria-label="Tema" className="w-64">
          <DropdownLabel aria-hidden="true">Tema</DropdownLabel>
          {THEME_OPTIONS.map((option) => {
            const checked = option.value === preference;
            const Icon = option.icon;
            return (
              <DropdownItem
                key={option.value}
                checked={checked}
                icon={<Icon />}
                description={
                  option.value === 'system' ? `${option.hint}: ${RESOLVED_THEME_LABELS[resolved]}` : option.hint
                }
                trailing={checked ? <Check className="h-4 w-4 shrink-0" aria-hidden="true" /> : null}
                onClick={() => {
                  setPreference(option.value);
                  dropdown.close(true);
                }}
              >
                {option.label}
              </DropdownItem>
            );
          })}
        </DropdownPanel>
      )}
    </div>
  );
}
