import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { PageSpinner } from '@/components/ui/Spinner';
import { cn } from '@/lib/cn';
import { RouteErrorBoundary } from './RouteErrorBoundary';
import { ShellLayoutContext, type ShellLayout } from './shellLayout';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

const SecurityAlertHost = lazy(() => import('@/features/announcements/components/SecurityAlertHost'));
const SupportWidget = lazy(() => import('@/features/support/components/SupportWidget'));

export const MAIN_CONTENT_ID = 'contenido-principal';

/** Rutas con variante compacta: pantalla completa, riel colapsado y sin padding (SPEC §9.3, §15.3). */
const COMPACT_PATHS = ['/app/pos'];

function isCompactPath(pathname: string): boolean {
  const clean = pathname.endsWith('/') && pathname.length > 1 ? pathname.slice(0, -1) : pathname;
  return COMPACT_PATHS.includes(clean);
}

// El contexto vive en `shellLayout.ts` (sin dependencias del shell) para que cualquier pantalla
// pueda leerlo sin arrastrar el riel y la barra superior. Se re-exporta acá por comodidad.
export { useShellLayout, type ShellLayout } from './shellLayout';

/**
 * Estructura de las pantallas autenticadas: riel + barra superior + página (SPEC §9.2, §9.6).
 *
 * En `/app/pos` usa la **variante compacta**: el riel arranca colapsado y el contenido ocupa toda
 * la pantalla sin padding ni ancho máximo (la página del POS maneja su propio layout y scroll).
 */
export function AppShell() {
  const { isTenantUser } = useAuth();
  const { pathname } = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [collapsedPref, setCollapsedPref] = useState<boolean | null>(null);

  const compact = isCompactPath(pathname);
  const collapsed = collapsedPref ?? compact;
  const layout = useMemo<ShellLayout>(() => ({ compact, inShell: true }), [compact]);

  useEffect(() => {
    setSidebarOpen(false);
    window.scrollTo({ top: 0 });
  }, [pathname]);

  // Al entrar o salir del POS vuelve a mandar la variante (el usuario puede volver a abrir el riel).
  useEffect(() => {
    setCollapsedPref(null);
  }, [compact]);

  return (
    <div className={cn('bg-background', compact ? 'flex h-dvh overflow-hidden' : 'min-h-dvh')}>
      <a
        href={`#${MAIN_CONTENT_ID}`}
        className="sr-only z-[60] rounded-control bg-primary px-3 py-2 text-base font-semibold text-primary-foreground focus:not-sr-only focus:fixed focus:left-3 focus:top-3"
      >
        Saltar al contenido
      </a>

      <Sidebar
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        collapsed={collapsed}
        onToggleCollapse={() => setCollapsedPref(!collapsed)}
      />

      <div
        className={cn(
          'flex min-w-0 flex-1 flex-col',
          compact ? 'h-dvh' : 'min-h-dvh',
          collapsed ? 'lg:pl-[68px]' : 'lg:pl-64',
        )}
      >
        <Topbar onOpenSidebar={() => setSidebarOpen(true)} />
        <main
          id={MAIN_CONTENT_ID}
          tabIndex={-1}
          className={cn(
            'gd-scroll min-w-0 flex-1 outline-none',
            // `--gd-scroll-space` es el aire de la burbuja de soporte (index.css): la última tarjeta,
            // fila o botón de cualquier pantalla se puede desplazar por encima de la burbuja.
            compact
              ? 'min-h-0 overflow-y-auto'
              : 'px-4 pb-[calc(var(--gd-scroll-pad,1.5rem)+var(--gd-scroll-space,0px))] pt-5 sm:px-6 lg:px-8 lg:pb-[calc(var(--gd-scroll-pad,2.5rem)+var(--gd-scroll-space,0px))] lg:pt-7',
          )}
        >
          <div className={cn(compact ? 'flex min-h-full flex-col' : 'mx-auto w-full max-w-7xl')}>
            <ShellLayoutContext.Provider value={layout}>
              <RouteErrorBoundary resetKey={pathname}>
                <Suspense fallback={<PageSpinner label="Cargando sección…" />}>
                  <Outlet />
                </Suspense>
              </RouteErrorBoundary>
            </ShellLayoutContext.Provider>
          </div>
        </main>
      </div>

      {isTenantUser && (
        <Suspense fallback={null}>
          <SecurityAlertHost />
          <SupportWidget />
        </Suspense>
      )}
    </div>
  );
}
