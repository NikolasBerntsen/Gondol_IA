import { useMemo } from 'react';
import type { TenantModule } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';

export interface ModulesState {
  /** Módulos habilitados para el comercio del usuario (vacío para roles de plataforma). */
  modules: TenantModule[];
  /** `true` si el comercio tiene ese módulo habilitado. */
  hasModule: (module: TenantModule) => boolean;
  /** Máximo **efectivo** de sucursales: sin `MULTI_BRANCH` es 1 (SPEC §14.1). */
  maxBranches: number;
  /** `true` si hay un comercio (no es un usuario de plataforma). */
  isTenant: boolean;
}

const NO_MODULES: TenantModule[] = [];

/**
 * Módulos habilitados por los dueños de GondolIA para este comercio (SPEC §14).
 *
 * ```tsx
 * const { hasModule } = useModules();
 * {hasModule('POS_GONDOLIA') && <ButtonLink to="/app/pos">Ir al punto de venta</ButtonLink>}
 * ```
 *
 * Los cambios llegan por WebSocket (`MODULES_CHANGED`): la fundación refresca `me` y esto se
 * actualiza solo. Ocultá las acciones, pero no te confíes: el backend igual responde
 * 403 `MODULE_DISABLED`.
 */
export function useModules(): ModulesState {
  const { me } = useAuth();
  const modules = me?.tenant?.modules ?? NO_MODULES;
  const maxBranches = me?.tenant?.maxBranches ?? 1;
  const isTenant = !!me?.tenant;

  return useMemo(
    () => ({
      modules,
      hasModule: (module: TenantModule) => modules.includes(module),
      maxBranches,
      isTenant,
    }),
    [modules, maxBranches, isTenant],
  );
}
