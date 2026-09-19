import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/Sheet';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/Tooltip';
import { getNavigation, isNavItemActive, type NavItem, type NavSection } from '@/config/navigation';
import { cn } from '@/lib/cn';
import { useModules } from '@/modules/useModules';
import { Logo, LogoMark } from './Logo';

export interface SidebarProps {
  /** Drawer abierto (solo debajo de `lg`). */
  open: boolean;
  onClose: () => void;
  /** Riel angosto con tooltips (variante compacta del POS o preferencia del usuario). */
  collapsed?: boolean;
  onToggleCollapse?: () => void;
}

/**
 * Riel de navegación verde profundo: fijo desde `lg` (256 px, 68 px colapsado) y drawer en
 * pantallas chicas (SPEC §9.4, docs/design-system.md §7.1).
 */
export function Sidebar({ open, onClose, collapsed = false, onToggleCollapse }: SidebarProps) {
  const { me } = useAuth();
  const { modules, isTenant } = useModules();
  const sections = me ? getNavigation(me.role, isTenant ? modules : undefined) : [];

  return (
    <>
      <aside
        className={cn(
          // El borde derecho separa el riel del lienzo: en tema oscuro el verde profundo (#09100C)
          // queda a un paso del fondo (#0D1410) y sin él la columna se pierde.
          'fixed inset-y-0 left-0 z-30 hidden border-r border-border transition-[width] duration-200 lg:block',
          collapsed ? 'w-[68px]' : 'w-64',
        )}
      >
        <Rail sections={sections} collapsed={collapsed} onToggleCollapse={onToggleCollapse} />
      </aside>

      <Sheet open={open} onOpenChange={(next) => !next && onClose()}>
        <SheetContent
          side="left"
          className="w-[284px] max-w-[86%] border-r-0 bg-rail p-0 text-rail-foreground shadow-pop sm:max-w-[284px] [&>button]:text-rail-foreground"
          aria-describedby={undefined}
        >
          <SheetTitle className="sr-only">Menú principal</SheetTitle>
          <Rail sections={sections} collapsed={false} onNavigate={onClose} inDrawer />
        </SheetContent>
      </Sheet>
    </>
  );
}

function Rail({
  sections,
  collapsed,
  onNavigate,
  onToggleCollapse,
  inDrawer,
}: {
  sections: NavSection[];
  collapsed: boolean;
  onNavigate?: () => void;
  onToggleCollapse?: () => void;
  inDrawer?: boolean;
}) {
  const { pathname } = useLocation();

  return (
    <TooltipProvider delayDuration={200}>
      <nav aria-label="Menú principal" className="gd-rail flex h-full min-h-0 flex-col bg-rail text-rail-foreground">
        <div
          className={cn(
            'flex h-14 shrink-0 items-center border-b border-rail-strong/[0.07]',
            collapsed ? 'justify-center px-2' : 'px-4',
          )}
        >
          <Link
            to="/"
            onClick={onNavigate}
            // Sin `outline-none`: toma el anillo de foco del riel (amarillo, `.gd-rail :focus-visible`).
            className="inline-flex rounded-control"
            aria-label="GondolIA, ir al inicio"
          >
            {collapsed ? <LogoMark /> : <Logo />}
          </Link>
        </div>

        <div
          className="gd-scroll min-h-0 flex-1 overflow-y-auto px-2.5 pb-3 pt-2"
          style={{ scrollbarColor: 'hsl(var(--rail-hover)) transparent' }}
        >
          {sections.map((section, index) => (
            <div key={section.title ?? index} className={cn(index > 0 && 'mt-3')}>
              {section.title &&
                (collapsed ? (
                  <div aria-hidden="true" className="mx-3 mb-2 mt-1 h-px bg-rail-strong/10" />
                ) : (
                  <div className="mb-1 px-3 pt-1 text-[11px] font-semibold uppercase tracking-[0.1em] text-rail-muted">
                    {section.title}
                  </div>
                ))}
              <ul className="space-y-0.5" aria-label={section.title}>
                {section.items.map((navItem) => (
                  <li key={navItem.to}>
                    <RailItem
                      item={navItem}
                      active={isNavItemActive(navItem, pathname)}
                      collapsed={collapsed}
                      onNavigate={onNavigate}
                    />
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>

        <div className={cn('shrink-0 border-t border-rail-strong/[0.07]', collapsed ? 'px-2 py-2' : 'px-4 py-3')}>
          {!collapsed && <p className="mb-2 text-xs text-rail-muted">Productos de hoy, clientes de siempre.</p>}
          {!inDrawer && onToggleCollapse && (
            <button
              type="button"
              onClick={onToggleCollapse}
              className={cn(
                'flex h-8 items-center gap-2 rounded-control text-sm font-medium text-rail-muted transition-colors hover:bg-rail-hover hover:text-rail-foreground',
                collapsed ? 'w-full justify-center' : '-mx-2 px-2',
              )}
              aria-label={collapsed ? 'Expandir menú' : 'Colapsar menú'}
            >
              {collapsed ? <PanelLeftOpen className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
              {!collapsed && 'Colapsar menú'}
            </button>
          )}
        </div>
      </nav>
    </TooltipProvider>
  );
}

function RailItem({
  item,
  active,
  collapsed,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const Icon = item.icon;
  const link = (
    <Link
      to={item.to}
      onClick={onNavigate}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'group relative flex h-9 w-full items-center gap-3 rounded-control text-left text-base font-medium transition-colors',
        collapsed ? 'justify-center px-0' : 'px-3',
        active ? 'bg-rail-active text-rail-strong' : 'text-rail-foreground hover:bg-rail-hover hover:text-rail-strong',
      )}
    >
      <Icon
        className={cn(
          'h-[18px] w-[18px] shrink-0',
          active ? 'text-rail-strong' : 'text-rail-muted group-hover:text-rail-foreground',
        )}
        aria-hidden="true"
      />
      {collapsed ? <span className="sr-only">{item.label}</span> : <span className="min-w-0 flex-1 truncate">{item.label}</span>}
    </Link>
  );

  if (!collapsed) return link;
  return (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right">{item.label}</TooltipContent>
    </Tooltip>
  );
}
