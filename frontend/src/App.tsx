import { QueryClientProvider } from '@tanstack/react-query';
import { lazy, Suspense, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Toaster } from 'sonner';
import { AuthProvider, useAuth } from '@/auth/AuthContext';
import { RequireAuth } from '@/auth/RequireAuth';
import { RequireRole } from '@/auth/RequireRole';
import { roleHome } from '@/auth/roleHome';
import { BranchProvider } from '@/branches/BranchContext';
import { AppShell } from '@/components/layout/AppShell';
import { SplashScreen } from '@/components/layout/SplashScreen';
import { ROLE_GROUPS } from '@/config/access';
import { createQueryClient } from '@/lib/queryClient';
import { StompProvider } from '@/realtime/StompProvider';
import { useSessionEvents } from '@/realtime/useSessionEvents';

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

          <Route element={<RequireRole roles={ROLE_GROUPS.TENANT_DASHBOARD} />}>
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="statistics" element={<StatisticsPage />} />
            <Route path="insights" element={<InsightsPage />} />
            <Route path="alerts" element={<AlertsPage />} />
          </Route>

          <Route element={<RequireRole roles={ROLE_GROUPS.TENANT_INVENTORY} />}>
            <Route path="inventory" element={<InventoryPage />} />
            <Route path="products/new" element={<ProductFormPage />} />
            <Route path="products/:id" element={<ProductDetailPage />} />
            <Route path="products/:id/edit" element={<ProductFormPage />} />
            <Route path="intake" element={<IntakePage />} />
            <Route path="expirations" element={<ExpirationsPage />} />
          </Route>

          <Route element={<RequireRole roles={ROLE_GROUPS.TENANT_ADMIN} />}>
            <Route path="categories" element={<CategoriesPage />} />
            <Route path="suppliers" element={<SuppliersPage />} />
            <Route path="sales" element={<SalesPage />} />
            <Route path="movements" element={<MovementsPage />} />
            <Route path="transfers" element={<TransfersPage />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="branches" element={<BranchesPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>

          <Route element={<RequireRole roles={ROLE_GROUPS.TENANT_ANY} />}>
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

export default function App() {
  const [queryClient] = useState(createQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <AuthProvider>
          <BranchProvider>
            <StompProvider>
              <SessionEventsListener />
              <Suspense fallback={<SplashScreen />}>
                <AppRoutes />
              </Suspense>
            </StompProvider>
          </BranchProvider>
        </AuthProvider>
      </BrowserRouter>
      <Toaster richColors closeButton position="top-right" toastOptions={{ className: 'font-sans' }} />
    </QueryClientProvider>
  );
}
