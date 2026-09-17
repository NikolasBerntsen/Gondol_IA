import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { isTenantRole, type BranchRef, type BranchScope, type MeDto } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { branchScope, readStoredBranch, writeStoredBranch } from './branchScope';

export interface BranchContextValue {
  /** Sucursales accesibles por el usuario (`me.branches`, ordenadas por nombre). */
  branches: BranchRef[];
  /** Sucursal elegida o `'all'` (vista consolidada). */
  selectedBranchId: BranchScope;
  /** Cambia la sucursal; invalida todas las queries de React Query. */
  setBranch: (scope: BranchScope) => void;
  /** Sucursal elegida (`null` si `isAll`). */
  currentBranch: BranchRef | null;
  /** `true` si se ve el consolidado de todas las sucursales accesibles. */
  isAll: boolean;
  /** `true` si se puede elegir "Todas las sucursales" (hay más de una accesible). */
  canSelectAll: boolean;
  /** `true` si el usuario es de un comercio (los roles de plataforma no tienen sucursales). */
  enabled: boolean;
  /** Etiqueta legible del alcance actual ("Todas las sucursales" o el nombre). */
  scopeLabel: string;
  /** Nombre de una sucursal por id (o `null` si no es accesible). */
  branchName: (branchId: number | null | undefined) => string | null;
}

const BranchContext = createContext<BranchContextValue | null>(null);

/** Raíces de query keys que no dependen de la sucursal y no se refrescan al cambiarla (imágenes cacheadas). */
const BRANCH_INDEPENDENT_KEYS: ReadonlySet<unknown> = new Set(['auth-image']);

/** `true` si la key termina con el segmento `{ branch }` de `useBranchQueryKey`. */
function hasBranchSegment(queryKey: readonly unknown[]): boolean {
  const last = queryKey[queryKey.length - 1];
  return typeof last === 'object' && last !== null && !Array.isArray(last) && 'branch' in last;
}

export const ALL_BRANCHES_LABEL = 'Todas las sucursales';
export const ALL_MY_BRANCHES_LABEL = 'Todas mis sucursales';

function sortBranches(branches: BranchRef[] | undefined): BranchRef[] {
  return [...(branches ?? [])].sort((a, b) => a.name.localeCompare(b.name, 'es'));
}

/** Default (SPEC §9.6): ADMIN/BOSS con más de una sucursal → consolidado; si no, la primera. */
function defaultScope(me: MeDto, branches: BranchRef[]): BranchScope {
  if (branches.length > 1 && me.role !== 'TENANT_EMPLOYEE') return 'all';
  return branches[0]?.id ?? 'all';
}

function resolveScope(me: MeDto, branches: BranchRef[], candidate: BranchScope | null): BranchScope {
  if (candidate === 'all' && branches.length > 1) return 'all';
  if (typeof candidate === 'number' && branches.some((b) => b.id === candidate)) return candidate;
  return defaultScope(me, branches);
}

export function BranchProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const queryClient = useQueryClient();
  const enabled = !!me && isTenantRole(me.role);
  const userId = enabled ? me.id : null;

  const branches = useMemo(() => (enabled ? sortBranches(me.branches) : []), [enabled, me]);

  const [choice, setChoice] = useState<{ userId: number | null; scope: BranchScope | null }>(() => ({
    userId,
    scope: userId !== null ? readStoredBranch(userId) : null,
  }));

  // Si cambia el usuario, se toma la elección guardada del nuevo usuario.
  const storedChoice =
    choice.userId === userId ? choice.scope : userId !== null ? readStoredBranch(userId) : null;

  const selectedBranchId: BranchScope = enabled ? resolveScope(me, branches, storedChoice) : 'all';
  const headerScope: BranchScope | null = enabled ? selectedBranchId : null;

  // Antes que los efectos de los hijos (que disparan las queries) para que el header ya sea el correcto.
  useLayoutEffect(() => {
    branchScope.set(headerScope);
  }, [headerScope]);

  useEffect(() => () => branchScope.set(null), []);

  // Tras un cambio de sucursal (ya renderizado con las nuevas query keys) se refresca todo lo activo (SPEC §9.6).
  // Las queries con el segmento `{ branch }` que ya están pidiendo datos de la sucursal nueva no se cancelan
  // (evita requests duplicadas); las que no lo tienen sí, porque pudieron salir con el header anterior.
  const previousScope = useRef<{ userId: number | null; scope: BranchScope | null }>({ userId, scope: headerScope });
  useEffect(() => {
    const previous = previousScope.current;
    previousScope.current = { userId, scope: headerScope };
    if (previous.userId !== userId || previous.scope === headerScope || userId === null) return;
    const refreshable = (queryKey: readonly unknown[]) => !BRANCH_INDEPENDENT_KEYS.has(queryKey[0]);
    void queryClient.invalidateQueries(
      { predicate: ({ queryKey }) => refreshable(queryKey) && hasBranchSegment(queryKey) },
      { cancelRefetch: false },
    );
    void queryClient.invalidateQueries({
      predicate: ({ queryKey }) => refreshable(queryKey) && !hasBranchSegment(queryKey),
    });
  }, [userId, headerScope, queryClient]);

  const setBranch = useCallback(
    (scope: BranchScope) => {
      if (userId === null) return;
      setChoice({ userId, scope });
      writeStoredBranch(userId, scope);
    },
    [userId],
  );

  const value = useMemo<BranchContextValue>(() => {
    const currentBranch =
      selectedBranchId === 'all' ? null : (branches.find((b) => b.id === selectedBranchId) ?? null);
    const isEmployee = me?.role === 'TENANT_EMPLOYEE';
    const allLabel = isEmployee ? ALL_MY_BRANCHES_LABEL : ALL_BRANCHES_LABEL;
    return {
      branches,
      selectedBranchId,
      setBranch,
      currentBranch,
      isAll: enabled && selectedBranchId === 'all',
      canSelectAll: branches.length > 1,
      enabled,
      scopeLabel: currentBranch?.name ?? (enabled ? allLabel : ''),
      branchName: (branchId) => (branchId == null ? null : (branches.find((b) => b.id === branchId)?.name ?? null)),
    };
  }, [branches, selectedBranchId, setBranch, enabled, me?.role]);

  return <BranchContext.Provider value={value}>{children}</BranchContext.Provider>;
}

export function useBranch(): BranchContextValue {
  const context = useContext(BranchContext);
  if (!context) throw new Error('useBranch debe usarse dentro de <BranchProvider>.');
  return context;
}

/** Segmento que se agrega al final de las query keys de datos por sucursal. */
export interface BranchKeyPart {
  branch: BranchScope;
}

/**
 * Query key que incluye la sucursal elegida (SPEC §9.6). Convención: la sucursal va **al final**,
 * así `invalidateQueries({ queryKey: ['products'] })` sigue invalidando todas las sucursales.
 *
 * ```ts
 * const queryKey = useBranchQueryKey('products', 'list', params); // ['products','list',params,{branch:3}]
 * ```
 */
export function useBranchQueryKey<const T extends readonly unknown[]>(...parts: T): [...T, BranchKeyPart] {
  const { selectedBranchId } = useBranch();
  return [...parts, { branch: selectedBranchId }];
}

export interface WriteBranchState {
  /** `true` si hay que mostrar `BranchPicker` (vista consolidada con más de una sucursal). */
  needsPicker: boolean;
  /** Sucursal destino de la escritura: la elegida en el topbar, la del picker o la única accesible. */
  branchId: number | null;
  setBranchId: (branchId: number | null) => void;
  /** `true` cuando ya hay una sucursal determinada para enviar la operación. */
  isReady: boolean;
}

/**
 * Sucursal para formularios de escritura (carga de lotes, ventas, ajustes).
 * Con una sucursal elegida en el topbar la usa; con "Todas" y varias sucursales pide elegir con `BranchPicker`.
 * Enviá `branchId` en el body (el backend lo prioriza sobre el header).
 */
export function useWriteBranch(): WriteBranchState {
  const { branches, currentBranch, isAll } = useBranch();
  const [picked, setPicked] = useState<number | null>(null);
  const needsPicker = isAll && branches.length > 1;
  const pickedValid = picked !== null && branches.some((b) => b.id === picked) ? picked : null;
  const branchId = currentBranch?.id ?? (needsPicker ? pickedValid : (branches[0]?.id ?? null));
  return { needsPicker, branchId, setBranchId: setPicked, isReady: branchId !== null };
}
