import { X } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { getNavigation, isNavItemActive, type NavSection } from '@/config/navigation';
import { cn } from '@/lib/cn';
import { getFocusableElements, trapTabKey } from '@/lib/focus';
import { lockBodyScroll } from '@/lib/scrollLock';
import { Logo } from './Logo';

export interface SidebarProps {
  /** Drawer abierto (solo aplica debajo de `lg`). */
  open: boolean;
  onClose: () => void;
}

/** Menú lateral verde oscuro: fijo desde `lg`, drawer en pantallas chicas (SPEC §9.4, §9.5). */
export function Sidebar({ open, onClose }: SidebarProps) {
  const { me } = useAuth();
  const sections = me ? getNavigation(me.role) : [];

  return (
    <>
      <aside
        className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col bg-brand-900 lg:flex"
        aria-label="Menú principal"
      >
        <SidebarContent sections={sections} />
      </aside>
      {open && <MobileDrawer sections={sections} onClose={onClose} />}
    </>
  );
}

function MobileDrawer({ sections, onClose }: { sections: NavSection[]; onClose: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const unlock = lockBodyScroll();
    getFocusableElements(panelRef.current)[0]?.focus({ preventScroll: true });

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
      else trapTabKey(event, panelRef.current);
    };
    const onResize = () => {
      if (window.matchMedia('(min-width: 1024px)').matches) onCloseRef.current();
    };
    document.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', onResize);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('resize', onResize);
      unlock();
      if (previouslyFocused?.isConnected) previouslyFocused.focus({ preventScroll: true });
    };
  }, []);

  return (
    <div className="fixed inset-0 z-50 lg:hidden" role="presentation">
      <div className="absolute inset-0 animate-fade-in bg-slate-950/50 backdrop-blur-[2px]" aria-hidden="true" onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="Menú principal"
        className="relative flex h-full w-72 max-w-[85vw] animate-slide-in-left flex-col bg-brand-900 shadow-2xl"
      >
        <button
          type="button"
          onClick={onClose}
          className="absolute right-3 top-5 rounded-lg p-2 text-brand-100/80 transition hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-lime-300"
          aria-label="Cerrar menú"
        >
          <X className="h-5 w-5" aria-hidden="true" />
        </button>
        <SidebarContent sections={sections} onNavigate={onClose} />
      </div>
    </div>
  );
}

function SidebarContent({ sections, onNavigate }: { sections: NavSection[]; onNavigate?: () => void }) {
  const { pathname } = useLocation();

  return (
    <>
      <div className="px-5 pb-5 pt-5">
        <Link
          to="/"
          onClick={onNavigate}
          className="inline-flex rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-lime-300"
          aria-label="GondolIA, ir al inicio"
        >
          <Logo variant="light" showTagline />
        </Link>
      </div>

      <nav className="flex-1 overflow-y-auto px-3 pb-4 [scrollbar-color:rgb(255_255_255/0.2)_transparent] [scrollbar-width:thin]">
        {sections.map((section, index) => (
          <div key={section.title ?? index} className={cn(index > 0 && 'mt-3 border-t border-white/10 pt-3')}>
            <ul className="space-y-0.5" aria-label={section.title}>
              {section.items.map((item) => {
                const active = isNavItemActive(item, pathname);
                const Icon = item.icon;
                return (
                  <li key={item.to}>
                    <Link
                      to={item.to}
                      onClick={onNavigate}
                      aria-current={active ? 'page' : undefined}
                      className={cn(
                        'group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors',
                        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-lime-300',
                        active
                          ? 'bg-white/[0.12] text-white shadow-sm'
                          : 'text-brand-100/75 hover:bg-white/[0.06] hover:text-white',
                      )}
                    >
                      {active && (
                        <span className="absolute inset-y-2 left-0 w-1 rounded-r-full bg-lime-300" aria-hidden="true" />
                      )}
                      <Icon
                        className={cn(
                          'h-5 w-5 shrink-0 transition-colors',
                          active ? 'text-lime-300' : 'text-brand-300/80 group-hover:text-brand-100',
                        )}
                        aria-hidden="true"
                      />
                      <span className="truncate">{item.label}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="hidden px-4 pb-5 pt-1 [@media(min-height:720px)]:block">
        <div className="rounded-2xl bg-gradient-to-br from-brand-700/80 to-brand-800 p-4 ring-1 ring-inset ring-white/5">
          <p className="text-sm font-semibold leading-snug text-white">Productos de hoy, clientes de siempre</p>
          <p className="mt-1 text-xs text-brand-200/80">GondolIA cuida tu stock por vos.</p>
        </div>
      </div>
    </>
  );
}
