import { Bell, ChevronDown, LogOut, UserRound } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { ROLE_LABELS } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { Avatar } from '@/components/ui/Avatar';
import { DropdownItem, DropdownPanel, DropdownSeparator, useDropdown } from '@/components/ui/Dropdown';
import { cn } from '@/lib/cn';

/** Avatar con nombre y rol; menú con Perfil, Notificaciones y Cerrar sesión. */
export function UserMenu() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();
  const dropdown = useDropdown();

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
