import type { ReactNode } from 'react';
import { Outlet } from 'react-router-dom';
import type { Role } from '@/api/types';
import ForbiddenPage from '@/pages/ForbiddenPage';
import { useAuth } from './AuthContext';

export interface RequireRoleProps {
  roles: readonly Role[];
  children?: ReactNode;
}

/** Renderiza la ruta solo para los roles indicados; al resto le muestra "Acceso denegado". Usar dentro de `RequireAuth`. */
export function RequireRole({ roles, children }: RequireRoleProps) {
  const { me } = useAuth();
  if (!me || !roles.includes(me.role)) return <ForbiddenPage />;
  return children ? <>{children}</> : <Outlet />;
}
