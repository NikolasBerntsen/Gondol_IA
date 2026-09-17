import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { SplashError, SplashScreen } from '@/components/layout/SplashScreen';
import { useAuth } from './AuthContext';

export const PROFILE_PATH = '/profile';

export interface RequireAuthProps {
  children?: ReactNode;
}

/**
 * Protege rutas que requieren sesión. Mientras valida el token muestra una pantalla de carga;
 * sin sesión redirige a `/login` (con `?motivo=sesion` si expiró); con `mustChangePassword`
 * obliga a pasar por `/profile`.
 */
export function RequireAuth({ children }: RequireAuthProps) {
  const { status, me, notice, error, retry, logout } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return <SplashScreen label="Verificando tu sesión…" />;
  }

  if (status === 'error') {
    return (
      <SplashError
        message={error ?? 'Revisá tu conexión e intentá de nuevo.'}
        onRetry={retry}
        onLogout={() => logout()}
      />
    );
  }

  if (status === 'anonymous' || !me) {
    const expired = notice?.kind === 'expired';
    // Se vuelve a la pantalla original solo si se llegó sin sesión o si expiró; tras "Cerrar sesión" o un bloqueo
    // quien ingrese después (quizás otra cuenta) arranca en su inicio.
    const state = !notice || expired ? { from: location } : undefined;
    return <Navigate to={expired ? '/login?motivo=sesion' : '/login'} replace state={state} />;
  }

  if (me.mustChangePassword && location.pathname !== PROFILE_PATH) {
    return <Navigate to={PROFILE_PATH} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
}
