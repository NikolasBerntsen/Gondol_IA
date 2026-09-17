import { MutationCache, QueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError, getErrorMessage, SESSION_BLOCKING_CODES } from '@/api/client';

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /** `false` evita el toast de error global de la mutación. */
      errorToast?: boolean;
    };
  }
}

/** Errores que ya maneja el cierre de sesión global: no se muestran como toast. */
function isSessionError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    ((error.status === 401 && error.code !== 'BAD_CREDENTIALS') || SESSION_BLOCKING_CODES.has(error.code))
  );
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
          return failureCount < 2;
        },
      },
      mutations: {
        retry: false,
      },
    },
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        // Si la mutación define su propio onError o pidió silencio, no duplicamos el aviso.
        if (mutation.options.onError || mutation.meta?.errorToast === false || isSessionError(error)) return;
        toast.error(getErrorMessage(error));
      },
    }),
  });
}
