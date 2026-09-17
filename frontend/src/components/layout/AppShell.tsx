import { lazy, Suspense, useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { PageSpinner } from '@/components/ui/Spinner';
import { RouteErrorBoundary } from './RouteErrorBoundary';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

const SecurityAlertHost = lazy(() => import('@/features/announcements/components/SecurityAlertHost'));
const SupportWidget = lazy(() => import('@/features/support/components/SupportWidget'));

export const MAIN_CONTENT_ID = 'contenido-principal';

/** Estructura de las pantallas autenticadas: sidebar + topbar + página (SPEC §9.2, §9.6). */
export function AppShell() {
  const { isTenantUser } = useAuth();
  const { pathname } = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  useEffect(() => {
    setSidebarOpen(false);
    window.scrollTo({ top: 0 });
  }, [pathname]);

  return (
    <div className="min-h-dvh bg-app">
      <a
        href={`#${MAIN_CONTENT_ID}`}
        className="sr-only left-3 top-3 z-[60] rounded-xl bg-brand-700 px-4 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
      >
        Saltar al contenido
      </a>

      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />

      <div className="flex min-h-dvh min-w-0 flex-col lg:pl-64">
        <Topbar onOpenSidebar={() => setSidebarOpen(true)} />
        <main id={MAIN_CONTENT_ID} tabIndex={-1} className="flex-1 px-4 pb-24 pt-5 outline-none sm:px-6 sm:pt-6 lg:px-8 lg:pb-10 lg:pt-8">
          <div className="mx-auto w-full max-w-7xl">
            <RouteErrorBoundary resetKey={pathname}>
              <Suspense fallback={<PageSpinner label="Cargando sección…" />}>
                <Outlet />
              </Suspense>
            </RouteErrorBoundary>
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
