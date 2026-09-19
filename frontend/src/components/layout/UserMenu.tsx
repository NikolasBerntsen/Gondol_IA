import { Bell, ChevronDown, LogOut, UserRound } from 'lucide-react';
import { useId, useRef, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ROLE_LABELS } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { Avatar } from '@/components/ui/Avatar';
import { DropdownItem, DropdownLabel, DropdownPanel, DropdownSeparator, useDropdown } from '@/components/ui/Dropdown';
import { cn } from '@/lib/cn';
import { THEME_OPTIONS, useTheme } from '@/theme';

/** Avatar con nombre y rol; menú con Perfil, Notificaciones, Tema y Cerrar sesión. */
export function UserMenu() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();
  // El foco arranca en "Mi perfil" aunque el tema tenga una opción marcada.
  const dropdown = useDropdown({ initialFocus: 'first' });

  if (!me) return null;
  const roleLabel = ROLE_LABELS[me.role];

  const go = (path: string) => {
    dropdown.close();
    navigate(path);
  };

  return (
    <div className="relative shrink-0">
      <button
        {...dropdown.triggerProps}
        aria-label={`Menú de usuario: ${me.fullName}, ${roleLabel}`}
        className={cn(
          'flex items-center gap-2.5 rounded-control p-1 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring lg:pr-2',
          dropdown.open && 'bg-muted',
        )}
      >
        <Avatar name={me.fullName} />
        <span className="hidden min-w-0 max-w-[10rem] flex-col text-left leading-tight lg:flex">
          <span className="truncate text-sm font-semibold text-foreground">{me.fullName}</span>
          <span className="truncate text-xs text-muted-foreground">{roleLabel}</span>
        </span>
        <ChevronDown
          className={cn('hidden h-4 w-4 text-muted-foreground transition-transform lg:block', dropdown.open && 'rotate-180')}
          aria-hidden="true"
        />
      </button>

      {dropdown.open && (
        <DropdownPanel {...dropdown.panelProps} aria-label="Opciones de usuario" className="w-64">
          <div className="flex items-center gap-3 px-2.5 pb-3 pt-2">
            <Avatar name={me.fullName} size="lg" />
            <div className="min-w-0">
              <p className="truncate text-base font-semibold text-foreground">{me.fullName}</p>
              <p className="truncate text-xs text-muted-foreground">{me.email}</p>
              <p className="mt-1 inline-flex rounded-tag bg-primary/10 px-1.5 py-0.5 text-[11px] font-semibold text-primary">
                {roleLabel}
                {me.tenant ? ` · ${me.tenant.name}` : ''}
              </p>
            </div>
          </div>
          <DropdownSeparator />
          <DropdownItem icon={<UserRound />} onClick={() => go('/profile')}>
            Mi perfil
          </DropdownItem>
          <DropdownItem icon={<Bell />} onClick={() => go('/notifications')}>
            Notificaciones
          </DropdownItem>
          <DropdownSeparator />
          <ThemeSection />
          <DropdownSeparator />
          <DropdownItem
            icon={<LogOut />}
            tone="danger"
            onClick={() => {
              dropdown.close();
              logout();
            }}
          >
            Cerrar sesión
          </DropdownItem>
        </DropdownPanel>
      )}
    </div>
  );
}

/**
 * "Tema" dentro del menú: segmentado Sistema / Claro / Oscuro (`menuitemradio`). Cambia en el momento y deja el
 * menú abierto para comparar. Las flechas ↑/↓ recorren el menú entero; ←/→ se mueven dentro del segmentado.
 */
function ThemeSection() {
  const { preference, setPreference } = useTheme();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const labelId = useId();

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    const index = refs.current.findIndex((element) => element === document.activeElement);
    if (index < 0) return;
    event.preventDefault();
    const next = (index + (event.key === 'ArrowRight' ? 1 : -1) + THEME_OPTIONS.length) % THEME_OPTIONS.length;
    refs.current[next]?.focus();
  };

  return (
    <>
      <DropdownLabel id={labelId}>Tema</DropdownLabel>
      <div
        role="group"
        aria-labelledby={labelId}
        onKeyDown={onKeyDown}
        className="mx-1 mb-1 grid grid-cols-3 gap-0.5 rounded-control border border-border bg-muted p-0.5"
      >
        {THEME_OPTIONS.map((option, index) => {
          const checked = option.value === preference;
          const Icon = option.icon;
          return (
            <button
              key={option.value}
              ref={(element) => {
                refs.current[index] = element;
              }}
              type="button"
              role="menuitemradio"
              aria-checked={checked}
              tabIndex={-1}
              onClick={() => setPreference(option.value)}
              className={cn(
                'inline-flex h-8 min-w-0 items-center justify-center gap-1 rounded-[6px] px-1 text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                checked
                  ? 'bg-card text-foreground shadow-[0_0_0_1px_hsl(var(--border))]'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span className="truncate">{option.label}</span>
            </button>
          );
        })}
      </div>
    </>
  );
}
