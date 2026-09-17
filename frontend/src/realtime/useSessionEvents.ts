import { toast } from 'sonner';
import type { SessionEventMessage } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { useStompSubscription } from './useStompSubscription';

const DEFAULT_FORCE_LOGOUT_MESSAGE = 'Tu sesión se cerró. Volvé a iniciar sesión.';

/**
 * Escucha `/user/queue/session`: ante `FORCE_LOGOUT` (comercio deshabilitado, contraseña reseteada…)
 * cierra la sesión y muestra el motivo (SPEC §3.2, §9.6). Se monta una sola vez en `App`.
 */
export function useSessionEvents(): void {
  const { isAuthenticated, logout } = useAuth();

  useStompSubscription<SessionEventMessage>(
    '/user/queue/session',
    (event) => {
      if (event?.type !== 'FORCE_LOGOUT') return;
      const message = event.message?.trim() || DEFAULT_FORCE_LOGOUT_MESSAGE;
      logout(message);
      toast.error(message, { id: 'force-logout', duration: 10_000 });
    },
    isAuthenticated,
  );
}
