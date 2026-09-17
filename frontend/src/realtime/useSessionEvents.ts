import { useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import type { SessionEventMessage } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { roleHome } from '@/auth/roleHome';
import { requiredModuleForPath } from '@/config/access';
import { useStompSubscription } from './useStompSubscription';

const DEFAULT_FORCE_LOGOUT_MESSAGE = 'Tu sesión se cerró. Volvé a iniciar sesión.';
const MODULES_CHANGED_MESSAGE = 'Se actualizaron las funciones habilitadas';

/**
 * Escucha `/user/queue/session` (SPEC §7, §9.6, §14.1). Se monta una sola vez en `App`:
 *
 * - `FORCE_LOGOUT` (comercio deshabilitado, contraseña reseteada…): cierra la sesión con el motivo.
 * - `MODULES_CHANGED`: vuelve a pedir `me` (menú y guardas se actualizan solos) y, si la pantalla
 *   abierta necesitaba un módulo que acaban de desactivar, vuelve al inicio del rol.
 */
export function useSessionEvents(): void {
  const { isAuthenticated, logout, refreshMe } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const pathnameRef = useRef(location.pathname);
  pathnameRef.current = location.pathname;

  useStompSubscription<SessionEventMessage>(
    '/user/queue/session',
    (event) => {
      if (event?.type === 'FORCE_LOGOUT') {
        const message = event.message?.trim() || DEFAULT_FORCE_LOGOUT_MESSAGE;
        logout(message);
        toast.error(message, { id: 'force-logout', duration: 10_000 });
        return;
      }

      if (event?.type === 'MODULES_CHANGED') {
        void refreshMe()
          .then((user) => {
            toast.info(MODULES_CHANGED_MESSAGE, { id: 'modules-changed' });
            if (!user) return;
            const required = requiredModuleForPath(pathnameRef.current);
            if (required && !(user.tenant?.modules ?? []).includes(required)) {
              navigate(roleHome(user.role), { replace: true });
            }
          })
          .catch(() => undefined);
      }
    },
    isAuthenticated,
  );
}
