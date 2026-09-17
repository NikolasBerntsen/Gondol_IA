import { Menu, Search, X } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { BranchSelector } from '@/branches/BranchSelector';
import { SearchInput } from '@/components/ui/SearchInput';
import { cn } from '@/lib/cn';
import { formatLongDate } from '@/lib/format';
import { Logo } from './Logo';
import { NotificationBell } from './NotificationBell';
import { UserMenu } from './UserMenu';

const INVENTORY_PATH = '/app/inventory';
const SEARCH_PLACEHOLDER = 'Buscar productos, categorías o códigos…';

export interface TopbarProps {
  onOpenSidebar: () => void;
}

/** Barra superior: menú (mobile), buscador, sucursal, fecha, notificaciones y usuario (SPEC §1.1). */
export function Topbar({ onOpenSidebar }: TopbarProps) {
  const { me, isTenantUser } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const canSearch = me?.role === 'TENANT_ADMIN' || me?.role === 'TENANT_EMPLOYEE';

  const [query, setQuery] = useState('');
  const [mobileSearchOpen, setMobileSearchOpen] = useState(false);

  // Refleja el `?q=` del inventario y limpia el buscador al salir de esa pantalla.
  useEffect(() => {
    if (location.pathname === INVENTORY_PATH) {
      setQuery(new URLSearchParams(location.search).get('q') ?? '');
    } else {
      setQuery('');
    }
    setMobileSearchOpen(false);
  }, [location.pathname, location.search]);

  const submitSearch = (event?: FormEvent) => {
    event?.preventDefault();
    const q = query.trim();
    navigate(q ? `${INVENTORY_PATH}?q=${encodeURIComponent(q)}` : INVENTORY_PATH);
  };

  const today = formatLongDate();

  return (
    <header className="sticky top-0 z-20 border-b border-slate-200/70 bg-white/90 backdrop-blur supports-[backdrop-filter]:bg-white/75">
      <div className="flex h-16 items-center gap-2 px-3 sm:gap-3 sm:px-6 lg:px-8">
        <button
          type="button"
          onClick={onOpenSidebar}
          className="order-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 lg:hidden"
          aria-label="Abrir menú"
        >
          <Menu className="h-5 w-5" aria-hidden="true" />
        </button>

        {!isTenantUser && (
          <div className="order-2 min-w-0 lg:hidden">
            <Logo variant="dark" size="sm" />
          </div>
        )}

        {canSearch && (
          <form onSubmit={submitSearch} className="order-2 hidden min-w-0 max-w-md flex-1 md:block">
            <SearchInput value={query} onValueChange={setQuery} placeholder={SEARCH_PLACEHOLDER} />
          </form>
        )}

        {isTenantUser && (
          <div className={cn('order-2 flex min-w-0 flex-1 md:order-3 md:flex-none', !canSearch && 'md:flex-1')}>
            <BranchSelector />
          </div>
        )}

        <div className="order-4 ml-auto flex shrink-0 items-center gap-1 sm:gap-2">
          <p
            className={cn(
              'hidden whitespace-nowrap pr-2 text-sm font-medium text-slate-500',
              isTenantUser ? 'xl:block' : 'md:block',
            )}
          >
            {today}
          </p>
          {canSearch && (
            <button
              type="button"
              onClick={() => setMobileSearchOpen((open) => !open)}
              className="flex h-10 w-10 items-center justify-center rounded-xl text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 md:hidden"
              aria-label={mobileSearchOpen ? 'Cerrar buscador' : 'Buscar productos'}
              aria-expanded={mobileSearchOpen}
            >
              {mobileSearchOpen ? <X className="h-5 w-5" aria-hidden="true" /> : <Search className="h-5 w-5" aria-hidden="true" />}
            </button>
          )}
          <NotificationBell />
          <UserMenu />
        </div>
      </div>

      {canSearch && mobileSearchOpen && (
        <form onSubmit={submitSearch} className="border-t border-slate-100 px-3 py-2.5 md:hidden">
          <SearchInput value={query} onValueChange={setQuery} placeholder={SEARCH_PLACEHOLDER} autoFocus />
        </form>
      )}
    </header>
  );
}
