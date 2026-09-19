import { Menu, Search, X } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { can } from '@/config/access';
import { BranchSelector } from '@/branches/BranchSelector';
import { Button } from '@/components/ui/Button';
import { SearchInput } from '@/components/ui/SearchInput';
import { cn } from '@/lib/cn';
import { formatLongDate } from '@/lib/format';
import { LogoMark } from './Logo';
import { NotificationBell } from './NotificationBell';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';

const INVENTORY_PATH = '/app/inventory';
const SEARCH_PLACEHOLDER = 'Buscar productos o códigos…';

export interface TopbarProps {
  onOpenSidebar: () => void;
}

/**
 * Barra superior al ras (56 px): menú (mobile), buscador, alcance (comercio · sucursal),
 * tema, campana y usuario (docs/design-system.md §7.1). Es la misma en la variante compacta del POS.
 */
export function Topbar({ onOpenSidebar }: TopbarProps) {
  const { me, isTenantUser } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  // El buscador lleva al inventario: lo ve quien puede abrirlo (jefe, administrador y empleado).
  const canSearch = can(me?.role, 'products.view');

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

  return (
    <header className="sticky top-0 z-20 shrink-0 border-b border-border bg-card">
      <div className="flex h-14 items-center gap-2 px-3 sm:gap-3 sm:px-4">
        <Button variant="ghost" size="icon" className="shrink-0 lg:hidden" onClick={onOpenSidebar} aria-label="Abrir menú">
          <Menu className="h-5 w-5" aria-hidden="true" />
        </Button>

        {!isTenantUser && (
          <span className="lg:hidden">
            <LogoMark size={24} />
          </span>
        )}

        {canSearch && (
          <form onSubmit={submitSearch} className="hidden min-w-0 shrink basis-[420px] md:block">
            <SearchInput
              value={query}
              onValueChange={setQuery}
              placeholder={SEARCH_PLACEHOLDER}
              className="border-transparent bg-muted focus-visible:bg-card"
            />
          </form>
        )}

        <div className="flex-1" />

        {isTenantUser ? (
          // En celulares el alcance se recorta ("Todas las sucurs…") antes que aplastar los botones de la derecha.
          <BranchSelector className="shrink sm:shrink-0" />
        ) : (
          <span className="hidden h-9 items-center gap-2 rounded-control border border-dashed border-input px-2.5 text-sm font-semibold text-foreground sm:inline-flex">
            <LogoMark size={18} />
            Consola GondolIA
          </span>
        )}

        <p className="hidden whitespace-nowrap px-1 text-sm font-medium text-muted-foreground 2xl:block">
          {formatLongDate()}
        </p>

        {/* Botones de 36 px: nunca se encogen; en celulares van más juntos para que entren con el alcance. */}
        <div className="flex shrink-0 items-center gap-1 sm:gap-3">
          {canSearch && (
            <Button
              variant="ghost"
              size="icon"
              className="md:hidden"
              onClick={() => setMobileSearchOpen((open) => !open)}
              aria-label={mobileSearchOpen ? 'Cerrar buscador' : 'Buscar productos'}
              aria-expanded={mobileSearchOpen}
            >
              {mobileSearchOpen ? (
                <X className="h-5 w-5" aria-hidden="true" />
              ) : (
                <Search className="h-5 w-5" aria-hidden="true" />
              )}
            </Button>
          )}

          <ThemeToggle />
          <NotificationBell />
          <UserMenu />
        </div>
      </div>

      {canSearch && mobileSearchOpen && (
        <form onSubmit={submitSearch} className={cn('border-t border-border px-3 py-2.5 md:hidden')}>
          <SearchInput value={query} onValueChange={setQuery} placeholder={SEARCH_PLACEHOLDER} autoFocus />
        </form>
      )}
    </header>
  );
}
