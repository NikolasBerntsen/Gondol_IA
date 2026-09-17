import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { ApiError, getErrorMessage, setAuthFailureHandler } from '@/api/client';
import { isPlatformRole, isTenantRole, type MeDto, type Role } from '@/api/types';
import { TOKEN_STORAGE_KEY, tokenStorage } from './tokenStorage';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous' | 'error';

/** Por qué terminó la última sesión; lo usa la pantalla de login para informar al usuario. */
export type SessionNotice =
  | { kind: 'logout' }
  | { kind: 'expired' }
  | { kind: 'message'; message: string };

export interface AuthContextValue {
  status: AuthStatus;
  /** Usuario autenticado (`null` mientras carga o si no hay sesión). */
  me: MeDto | null;
  token: string | null;
  isAuthenticated: boolean;
  isTenantUser: boolean;
  isPlatformUser: boolean;
  /** Mensaje de error cuando no se pudo validar la sesión al iniciar (p. ej. servidor caído). */
  error: string | null;
  notice: SessionNotice | null;
  login: (email: string, password: string) => Promise<MeDto>;
  /** Cierra la sesión. Si se indica `message`, se muestra en la pantalla de login. */
  logout: (message?: string) => void;
  /** Vuelve a pedir `/api/auth/me` y actualiza el usuario. */
  refreshMe: () => Promise<MeDto | null>;
  /** Reemplaza el token sin cerrar la sesión (p. ej. después de cambiar la contraseña). */
  updateToken: (token: string) => void;
  hasRole: (...roles: Role[]) => boolean;
  /** Reintenta validar la sesión cuando `status === 'error'`. */
  retry: () => void;
  clearNotice: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** Tiempo mínimo entre refrescos de `me` provocados por `BRANCH_FORBIDDEN`. */
const BRANCH_REFRESH_INTERVAL_MS = 10_000;

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const initialToken = useRef(tokenStorage.get()).current;

  const [token, setToken] = useState<string | null>(initialToken);
  const [me, setMe] = useState<MeDto | null>(null);
  const [status, setStatus] = useState<AuthStatus>(initialToken ? 'loading' : 'anonymous');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<SessionNotice | null>(null);

  const tokenRef = useRef<string | null>(initialToken);
  const meRef = useRef<MeDto | null>(null);
  meRef.current = me;
  const hadSessionRef = useRef(false);

  const applyToken = useCallback((next: string | null) => {
    tokenRef.current = next;
    setToken(next);
    if (next) tokenStorage.set(next);
    else tokenStorage.clear();
  }, []);

  const endSession = useCallback(
    (reason: SessionNotice) => {
      if (tokenRef.current === null) return;
      applyToken(null);
      setMe(null);
      setError(null);
      setNotice(reason);
      setStatus('anonymous');
    },
    [applyToken],
  );

  const loadMe = useCallback(async (): Promise<MeDto | null> => {
    const requestToken = tokenRef.current;
    if (!requestToken) {
      setStatus('anonymous');
      return null;
    }
    try {
      const user = await authApi.me();
      if (tokenRef.current !== requestToken) return null;
      setMe(user);
      setError(null);
      setStatus('authenticated');
      hadSessionRef.current = true;
      return user;
    } catch (err) {
      if (tokenRef.current !== requestToken) return null;
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        // El interceptor ya cerró la sesión si correspondía; esto cubre cualquier otro rechazo.
        endSession({ kind: 'expired' });
        return null;
      }
      setStatus((current) => (current === 'authenticated' ? current : 'error'));
      setError(getErrorMessage(err));
      throw err;
    }
  }, [endSession]);

  // Validación inicial del token guardado.
  useEffect(() => {
    if (initialToken) loadMe().catch(() => undefined);
  }, [initialToken, loadMe]);

  // Cierre de sesión global ante 401 / tenant deshabilitado (interceptor de axios). Ante `BRANCH_FORBIDDEN`
  // (sucursal desactivada o desasignada con la sesión abierta) se refrescan las sucursales accesibles para que
  // `BranchProvider` descarte la elegida en lugar de seguir mandándola en cada request.
  const lastBranchRefresh = useRef(0);
  useEffect(
    () =>
      setAuthFailureHandler((failure) => {
        if (failure.token !== tokenRef.current) return;
        if (failure.kind === 'branch-forbidden') {
          const now = Date.now();
          if (now - lastBranchRefresh.current < BRANCH_REFRESH_INTERVAL_MS) return;
          lastBranchRefresh.current = now;
          loadMe().catch(() => undefined);
          return;
        }
        endSession(failure.kind === 'blocked' ? { kind: 'message', message: failure.message } : { kind: 'expired' });
      }),
    [endSession, loadMe],
  );

  // Al terminar una sesión se descarta la caché para que el próximo usuario no vea datos ajenos.
  useEffect(() => {
    if (status === 'anonymous' && hadSessionRef.current) {
      hadSessionRef.current = false;
      queryClient.clear();
    }
  }, [status, queryClient]);

  // Sincroniza login/logout entre pestañas.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== TOKEN_STORAGE_KEY) return;
      const next = tokenStorage.get();
      if (next === tokenRef.current) return;
      if (!next) {
        endSession({ kind: 'logout' });
        return;
      }
      const previousUserId = meRef.current?.id ?? null;
      tokenRef.current = next;
      setToken(next);
      if (previousUserId === null) setStatus('loading');
      loadMe()
        .then((user) => {
          // Otro usuario inició sesión en otra pestaña: se descarta todo lo del anterior.
          if (user && user.id !== previousUserId) {
            queryClient.clear();
            navigate('/', { replace: true });
          }
        })
        .catch(() => undefined);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [endSession, loadMe, navigate, queryClient]);

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await authApi.login({ email: email.trim().toLowerCase(), password });
      queryClient.clear();
      applyToken(response.token);
      setMe(response.user);
      setError(null);
      setNotice(null);
      setStatus('authenticated');
      hadSessionRef.current = true;
      return response.user;
    },
    [applyToken, queryClient],
  );

  const logout = useCallback(
    (message?: string) => endSession(message ? { kind: 'message', message } : { kind: 'logout' }),
    [endSession],
  );

  const refreshMe = useCallback(() => loadMe(), [loadMe]);

  const updateToken = useCallback((next: string) => applyToken(next), [applyToken]);

  const retry = useCallback(() => {
    if (!tokenRef.current) return;
    setStatus('loading');
    setError(null);
    loadMe().catch(() => undefined);
  }, [loadMe]);

  const clearNotice = useCallback(() => setNotice(null), []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      me,
      token,
      isAuthenticated: status === 'authenticated' && me !== null,
      isTenantUser: isTenantRole(me?.role),
      isPlatformUser: isPlatformRole(me?.role),
      error,
      notice,
      login,
      logout,
      refreshMe,
      updateToken,
      hasRole: (...roles: Role[]) => !!me && roles.includes(me.role),
      retry,
      clearNotice,
    }),
    [status, me, token, error, notice, login, logout, refreshMe, updateToken, retry, clearNotice],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth debe usarse dentro de <AuthProvider>.');
  return context;
}

/** Usuario autenticado. Solo para componentes renderizados dentro de `<RequireAuth>`. */
export function useCurrentUser(): MeDto {
  const { me } = useAuth();
  if (!me) throw new Error('useCurrentUser requiere una sesión activa (<RequireAuth>).');
  return me;
}
