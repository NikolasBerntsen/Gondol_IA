import { QueryClientProvider } from '@tanstack/react-query';
import { lazy, Suspense, useState, type CSSProperties } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Toaster } from 'sonner';
import { AuthProvider, useAuth } from '@/auth/AuthContext';
import { RequireAuth } from '@/auth/RequireAuth';
import { RequireRole } from '@/auth/RequireRole';
import { roleHome } from '@/auth/roleHome';
import { BranchProvider } from '@/branches/BranchContext';
import { AppShell } from '@/components/layout/AppShell';
import { RouteDocumentTitle } from '@/components/layout/RouteDocumentTitle';
import { SplashScreen } from '@/components/layout/SplashScreen';
import { ROLE_GROUPS, rolesWith } from '@/config/access';
import { createQueryClient } from '@/lib/queryClient';
import { RequireModule } from '@/modules/RequireModule';
import { StompProvider } from '@/realtime/StompProvider';
import { useSessionEvents } from '@/realtime/useSessionEvents';
import { ThemeProvider, useTheme } from '@/theme';

// Páginas de la fundación
const LoginPage = lazy(() => import('@/pages/LoginPage'));
const ProfilePage = lazy(() => import('@/pages/ProfilePage'));
const NotificationsPage = lazy(() => import('@/pages/NotificationsPage'));
const NotFoundPage = lazy(() => import('@/pages/NotFoundPage'));

// A1 · Catálogo y carga
const InventoryPage = lazy(() => import('@/features/catalog/pages/InventoryPage'));
const ProductFormPage = lazy(() => import('@/features/catalog/pages/ProductFormPage'));
const ProductDetailPage = lazy(() => import('@/features/catalog/pages/ProductDetailPage'));
const IntakePage = lazy(() => import('@/features/catalog/pages/IntakePage'));
const CategoriesPage = lazy(() => import('@/features/catalog/pages/CategoriesPage'));
const SuppliersPage = lazy(() => import('@/features/catalog/pages/SuppliersPage'));

// A2 · Movimientos
const SalesPage = lazy(() => import('@/features/movements/pages/SalesPage'));
const MovementsPage = lazy(() => import('@/features/movements/pages/MovementsPage'));
const ExpirationsPage = lazy(() => import('@/features/movements/pages/ExpirationsPage'));
const TransfersPage = lazy(() => import('@/features/movements/pages/TransfersPage'));
const IntegrationsPage = lazy(() => import('@/features/movements/pages/IntegrationsPage'));

// H · POS GondolIA
const PosTerminalPage = lazy(() => import('@/features/pos/pages/PosTerminalPage'));
const PosSessionsPage = lazy(() => import('@/features/pos/pages/PosSessionsPage'));
const PosRegistersPage = lazy(() => import('@/features/pos/pages/PosRegistersPage'));
const PosTicketPage = lazy(() => import('@/features/pos/pages/PosTicketPage'));

// I · Importación masiva
const ImportsPage = lazy(() => import('@/features/imports/pages/ImportsPage'));
const ImportWizardPage = lazy(() => import('@/features/imports/pages/ImportWizardPage'));

// B · Analítica e IA
const DashboardPage = lazy(() => import('@/features/analytics/pages/DashboardPage'));
const StatisticsPage = lazy(() => import('@/features/analytics/pages/StatisticsPage'));
const InsightsPage = lazy(() => import('@/features/analytics/pages/InsightsPage'));
const AlertsPage = lazy(() => import('@/features/analytics/pages/AlertsPage'));

// C · Consola de dueños
const OwnerMetricsPage = lazy(() => import('@/features/platform/pages/OwnerMetricsPage'));
const TenantsPage = lazy(() => import('@/features/platform/pages/TenantsPage'));
const TenantFormPage = lazy(() => import('@/features/platform/pages/TenantFormPage'));
const TenantDetailPage = lazy(() => import('@/features/platform/pages/TenantDetailPage'));
const PlatformTeamPage = lazy(() => import('@/features/platform/pages/PlatformTeamPage'));
const ModulesMatrixPage = lazy(() => import('@/features/platform/pages/ModulesMatrixPage'));

// D · Avisos y recalls
const OwnerAnnouncementsPage = lazy(() => import('@/features/announcements/pages/OwnerAnnouncementsPage'));
const AnnouncementFormPage = lazy(() => import('@/features/announcements/pages/AnnouncementFormPage'));
const NoticesPage = lazy(() => import('@/features/announcements/pages/NoticesPage'));
const RecallsPage = lazy(() => import('@/features/announcements/pages/RecallsPage'));

// E · Soporte
const SupportConsolePage = lazy(() => import('@/features/support/pages/SupportConsolePage'));
const TenantSupportPage = lazy(() => import('@/features/support/pages/TenantSupportPage'));

// F · Administración del comercio
const UsersPage = lazy(() => import('@/features/tenantadmin/pages/UsersPage'));
const SettingsPage = lazy(() => import('@/features/tenantadmin/pages/SettingsPage'));
const BranchesPage = lazy(() => import('@/features/tenantadmin/pages/BranchesPage'));

/** `/` → pantalla inicial del rol (SPEC §9.3). */
function HomeRedirect() {
  const { me } = useAuth();
  return <Navigate to={me ? roleHome(me.role) : '/login'} replace />;
}

/** Cierre de sesión forzado por WebSocket (`FORCE_LOGOUT`). */
function SessionEventsListener() {
  useSessionEvents();
  return null;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      {/* Ticket para imprimir: sin riel ni barra superior (SPEC §9.3). */}
      <Route
        path="/app/pos/sales/:id/ticket"
        element={
          <RequireAuth>
            <RequireRole roles={rolesWith('pos.use')}>
              <RequireModule module="POS_GONDOLIA">
                <PosTicketPage />
              </RequireModule>
            </RequireRole>
          </RequireAuth>
        }
      />

      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<HomeRedirect />} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="notifications" element={<NotificationsPage />} />

        <Route path="owner" element={<RequireRole roles={ROLE_GROUPS.OWNER} />}>
          <Route index element={<OwnerMetricsPage />} />
          <Route path="tenants" element={<TenantsPage />} />
          <Route path="tenants/new" element={<TenantFormPage />} />
          <Route path="tenants/:id" element={<TenantDetailPage />} />
          <Route path="tenants/:id/edit" element={<TenantFormPage />} />
          <Route path="modules" element={<ModulesMatrixPage />} />
          <Route path="announcements" element={<OwnerAnnouncementsPage />} />
          <Route path="announcements/new" element={<AnnouncementFormPage />} />
          <Route path="team" element={<PlatformTeamPage />} />
        </Route>

        <Route path="support" element={<RequireRole roles={ROLE_GROUPS.SUPPORT} />}>
          <Route index element={<SupportConsolePage />} />
          <Route path="tickets/:id" element={<SupportConsolePage />} />
        </Route>

        <Route path="app">
          <Route index element={<HomeRedirect />} />

          {/* Cada grupo usa los roles de un permiso de config/access.ts (SPEC §3.3, §9.3). */}
          <Route element={<RequireRole roles={rolesWith('pos.use')} />}>
            <Route element={<RequireModule module="POS_GONDOLIA" />}>
              <Route path="pos" element={<PosTerminalPage />} />
              <Route path="pos/sessions" element={<PosSessionsPage />} />
            </Route>
          </Route>

          <Route element={<RequireRole roles={rolesWith('pos.admin')} />}>
            <Route element={<RequireModule module="POS_GONDOLIA" />}>
              <Route path="pos/registers" element={<PosRegistersPage />} />
            </Route>
          </Route>
          <Route element={<RequireRole roles={rolesWith('integrations.manage')} />}>
            <Route element={<RequireModule module="POS_INTEGRATION" />}>
              <Route path="integrations" element={<IntegrationsPage />} />
            </Route>
          </Route>

          <Route element={<RequireRole roles={rolesWith('dashboard.view')} />}>
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="statistics" element={<StatisticsPage />} />
            <Route path="insights" element={<InsightsPage />} />
            <Route path="alerts" element={<AlertsPage />} />
          </Route>

          {/* Ver inventario: también el jefe. Crear y editar productos, y cargar mercadería: admin y empleado. */}
          <Route element={<RequireRole roles={rolesWith('products.view')} />}>
            <Route path="inventory" element={<InventoryPage />} />
            <Route path="products/:id" element={<ProductDetailPage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('products.write')} />}>
            <Route path="products/new" element={<ProductFormPage />} />
            <Route path="products/:id/edit" element={<ProductFormPage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('intake.use')} />}>
            <Route path="intake" element={<IntakePage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('expirations.view')} />}>
            <Route path="expirations" element={<ExpirationsPage />} />
          </Route>

          {/* Historiales: jefe (solo lectura) y administrador. */}
          <Route element={<RequireRole roles={rolesWith('sales.view')} />}>
            <Route path="sales" element={<SalesPage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('movements.view')} />}>
            <Route path="movements" element={<MovementsPage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('transfers.view')} />}>
            <Route element={<RequireModule module="MULTI_BRANCH" />}>
              <Route path="transfers" element={<TransfersPage />} />
            </Route>
          </Route>

          <Route element={<RequireRole roles={rolesWith('imports.use')} />}>
            <Route path="imports" element={<ImportsPage />} />
            <Route path="imports/:id" element={<ImportWizardPage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('catalog.manage')} />}>
            <Route path="categories" element={<CategoriesPage />} />
            <Route path="suppliers" element={<SuppliersPage />} />
          </Route>
          <Route element={<RequireRole roles={rolesWith('tenant.admin')} />}>
            <Route path="users" element={<UsersPage />} />
            <Route path="branches" element={<BranchesPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>

          <Route element={<RequireRole roles={rolesWith('communication.view')} />}>
            <Route path="notices" element={<NoticesPage />} />
            <Route path="recalls" element={<RecallsPage />} />
            <Route path="support" element={<TenantSupportPage />} />
            <Route path="support/:id" element={<TenantSupportPage />} />
          </Route>
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

/**
 * Toaster con los tokens de Góndola UI: superficie `card`, radio de panel y sombra de flotante.
 * `theme` sigue al tema de la app (sonner trae sus propios colores por tema) y las variables `--normal-*` los
 * reemplazan por tokens: sus reglas `[data-styled]` le ganan a las clases de Tailwind.
 */
const TOASTER_STYLE = {
  '--normal-bg': 'hsl(var(--card))',
  '--normal-bg-hover': 'hsl(var(--muted))',
  '--normal-border': 'hsl(var(--border))',
  '--normal-border-hover': 'hsl(var(--input))',
  '--normal-text': 'hsl(var(--foreground))',
} as CSSProperties;

function ThemedToaster() {
  const { resolved } = useTheme();
  return (
    <Toaster
      theme={resolved}
      closeButton
      position="top-right"
      style={TOASTER_STYLE}
      toastOptions={{
        classNames: {
          toast:
            'font-sans rounded-panel border border-border bg-card text-foreground shadow-pop text-base items-start',
          title: 'font-semibold',
          description: 'text-muted-foreground text-sm',
          actionButton: 'rounded-control bg-primary text-primary-foreground font-semibold',
          cancelButton: 'rounded-control bg-muted text-foreground font-semibold',
          closeButton: 'bg-card border-border text-muted-foreground hover:text-foreground',
          error: 'text-crit-ink [&_[data-icon]]:text-crit',
          success: 'text-ok-ink [&_[data-icon]]:text-ok',
          warning: 'text-warn-ink [&_[data-icon]]:text-warn',
          info: 'text-info-ink [&_[data-icon]]:text-info',
        },
      }}
    />
  );
}

export default function App() {
  const [queryClient] = useState(createQueryClient);

  return (
    // El tema va afuera de todo: lo usan el login, el splash, el ticket de impresión y el Toaster.
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <AuthProvider>
            <BranchProvider>
              <StompProvider>
                <SessionEventsListener />
                <RouteDocumentTitle />
                <Suspense fallback={<SplashScreen />}>
                  <AppRoutes />
                </Suspense>
              </StompProvider>
            </BranchProvider>
          </AuthProvider>
        </BrowserRouter>
        <ThemedToaster />
      </QueryClientProvider>
    </ThemeProvider>
  );
}
