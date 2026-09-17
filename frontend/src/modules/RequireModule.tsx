import type { ReactNode } from 'react';
import { Outlet } from 'react-router-dom';
import type { TenantModule } from '@/api/types';
import ModuleDisabledPage from './ModuleDisabledPage';
import { useModules } from './useModules';

export interface RequireModuleProps {
  module: TenantModule;
  children?: ReactNode;
}

/**
 * Deja pasar solo si el comercio tiene el módulo habilitado; si no, muestra `ModuleDisabledPage`
 * (SPEC §14.1). Se usa junto con `RequireRole`, dentro de `RequireAuth`.
 *
 * ```tsx
 * <Route element={<RequireRole roles={ROLE_GROUPS.TENANT_POS} />}>
 *   <Route element={<RequireModule module="POS_GONDOLIA" />}>
 *     <Route path="pos" element={<PosTerminalPage />} />
 *   </Route>
 * </Route>
 * ```
 */
export function RequireModule({ module, children }: RequireModuleProps) {
  const { hasModule } = useModules();
  if (!hasModule(module)) return <ModuleDisabledPage module={module} />;
  return children ? <>{children}</> : <Outlet />;
}
