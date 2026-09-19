import { useMemo } from 'react';
import { can as roleCan, canAccessPath, requiredModuleForPath, type Permission } from '@/config/access';
import { useAuth } from './AuthContext';

export interface AccessState {
  /** `true` si el usuario tiene el permiso (SPEC §3.3). */
  can: (permission: Permission) => boolean;
  /**
   * `true` si el usuario puede abrir la ruta: su rol la tiene y, si la ruta exige un módulo, el comercio lo tiene
   * habilitado. Usalo antes de dibujar un enlace armado a mano.
   */
  canOpen: (path: string) => boolean;
}

/**
 * Permisos del usuario actual para ocultar lo que no puede usar (regla de SPEC §3.3: todo botón o enlace visible
 * funciona).
 *
 * ```tsx
 * const { can, canOpen } = useAccess();
 * {can('products.write') && <ButtonLink to="/app/products/new">Nuevo producto</ButtonLink>}
 * {canOpen(`/app/pos/sales/${id}/ticket`) ? <Link …>Ticket</Link> : <Badge>…</Badge>}
 * ```
 */
export function useAccess(): AccessState {
  const { me } = useAuth();
  const role = me?.role ?? null;
  const modules = me?.tenant?.modules;

  return useMemo(
    () => ({
      can: (permission: Permission) => roleCan(role, permission),
      canOpen: (path: string) => {
        if (!canAccessPath(role, path)) return false;
        const module = requiredModuleForPath(path);
        return !module || !!modules?.includes(module);
      },
    }),
    [role, modules],
  );
}
