import { ROLES, TENANT_ROLES, type Role } from '@/api/types';

/** Grupos de roles de la matriz de permisos (SPEC §3.3, §9.3). Espejan `com.gondolia.security.Roles`. */
export const ROLE_GROUPS = {
  ALL: ROLES,
  OWNER: ['PLATFORM_OWNER'],
  SUPPORT: ['SUPPORT_AGENT'],
  TENANT_ANY: TENANT_ROLES,
  TENANT_DASHBOARD: ['TENANT_BOSS', 'TENANT_ADMIN'],
  TENANT_INVENTORY: ['TENANT_ADMIN', 'TENANT_EMPLOYEE'],
  TENANT_ADMIN: ['TENANT_ADMIN'],
} as const satisfies Record<string, readonly Role[]>;

const PATH_RULES: ReadonlyArray<readonly [RegExp, readonly Role[]]> = [
  [/^\/(profile|notifications)(\/|$)/, ROLE_GROUPS.ALL],
  [/^\/owner(\/|$)/, ROLE_GROUPS.OWNER],
  [/^\/support(\/|$)/, ROLE_GROUPS.SUPPORT],
  [/^\/app\/(dashboard|statistics|insights|alerts)(\/|$)/, ROLE_GROUPS.TENANT_DASHBOARD],
  [/^\/app\/(inventory|products|intake|expirations)(\/|$)/, ROLE_GROUPS.TENANT_INVENTORY],
  [/^\/app\/(categories|suppliers|sales|movements|transfers|users|branches|settings)(\/|$)/, ROLE_GROUPS.TENANT_ADMIN],
  [/^\/app\/(notices|recalls|support)(\/|$)/, ROLE_GROUPS.TENANT_ANY],
];

/** `true` si el rol puede abrir la ruta (se usa para no redirigir a una pantalla prohibida tras el login). */
export function canAccessPath(role: Role, path: string): boolean {
  const pathname = path.split(/[?#]/)[0];
  const rule = PATH_RULES.find(([pattern]) => pattern.test(pathname));
  return rule ? rule[1].includes(role) : false;
}

export function hasRole(role: Role | null | undefined, roles: readonly Role[]): boolean {
  return !!role && roles.includes(role);
}
