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
    <div className="relative">
      <button
        {...dropdown.triggerProps}
        aria-label={`Menú de usuario: ${me.fullName}, ${roleLabel}`}
        className={cn(
          'flex items-center gap-2.5 rounded-2xl p-1 pr-1 transition hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 md:pr-2',
          dropdown.open && 'bg-slate-100',
        )}
      >
        <Avatar name={me.fullName} />
        <span className="hidden min-w-0 max-w-[10rem] text-left leading-tight md:block">
          <span className="block truncate text-sm font-semibold text-slate-900">{me.fullName}</span>
          <span className="block truncate text-xs text-slate-500">{roleLabel}</span>
        </span>
        <ChevronDown
          className={cn('hidden h-4 w-4 text-slate-400 transition-transform md:block', dropdown.open && 'rotate-180')}
          aria-hidden="true"
        />
      </button>

      {dropdown.open && (
        <DropdownPanel {...dropdown.panelProps} aria-label="Opciones de usuario" className="w-64">
          <div className="flex items-center gap-3 px-3 pb-3 pt-2">
            <Avatar name={me.fullName} size="lg" />
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-slate-900">{me.fullName}</p>
              <p className="truncate text-xs text-slate-500">{me.email}</p>
              <p className="mt-1 inline-flex rounded-full bg-brand-50 px-2 py-0.5 text-[11px] font-semibold text-brand-700">
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
