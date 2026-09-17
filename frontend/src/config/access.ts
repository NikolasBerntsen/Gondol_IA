import { ROLES, TENANT_ROLES, type Role, type TenantModule } from '@/api/types';

/** Grupos de roles de la matriz de permisos (SPEC §3.3, §9.3). Espejan `com.gondolia.security.Roles`. */
export const ROLE_GROUPS = {
  ALL: ROLES,
  OWNER: ['PLATFORM_OWNER'],
  SUPPORT: ['SUPPORT_AGENT'],
  TENANT_ANY: TENANT_ROLES,
  TENANT_DASHBOARD: ['TENANT_BOSS', 'TENANT_ADMIN'],
  TENANT_INVENTORY: ['TENANT_ADMIN', 'TENANT_EMPLOYEE'],
  TENANT_ADMIN: ['TENANT_ADMIN'],
  /** POS GondolIA: cobra el admin, el empleado y el cajero (SPEC §15). */
  TENANT_POS: ['TENANT_ADMIN', 'TENANT_EMPLOYEE', 'TENANT_CASHIER'],
} as const satisfies Record<string, readonly Role[]>;

const PATH_RULES: ReadonlyArray<readonly [RegExp, readonly Role[]]> = [
  [/^\/(profile|notifications)(\/|$)/, ROLE_GROUPS.ALL],
  [/^\/owner(\/|$)/, ROLE_GROUPS.OWNER],
  [/^\/support(\/|$)/, ROLE_GROUPS.SUPPORT],
  [/^\/app\/pos\/registers(\/|$)/, ROLE_GROUPS.TENANT_ADMIN],
  [/^\/app\/pos(\/|$)/, ROLE_GROUPS.TENANT_POS],
  [/^\/app\/(dashboard|statistics|insights|alerts)(\/|$)/, ROLE_GROUPS.TENANT_DASHBOARD],
  [/^\/app\/(inventory|products|intake|expirations)(\/|$)/, ROLE_GROUPS.TENANT_INVENTORY],
  [
    /^\/app\/(categories|suppliers|sales|movements|transfers|users|branches|settings|integrations|imports)(\/|$)/,
    ROLE_GROUPS.TENANT_ADMIN,
  ],
  [/^\/app\/(notices|recalls|support)(\/|$)/, ROLE_GROUPS.TENANT_ANY],
];

/**
 * Módulo que exige cada ruta (SPEC §9.3, §14). Si el comercio no lo tiene habilitado,
 * `RequireModule` muestra `ModuleDisabledPage` y el ítem no aparece en el menú.
 */
const MODULE_PATH_RULES: ReadonlyArray<readonly [RegExp, TenantModule]> = [
  [/^\/app\/pos(\/|$)/, 'POS_GONDOLIA'],
  [/^\/app\/integrations(\/|$)/, 'POS_INTEGRATION'],
  [/^\/app\/transfers(\/|$)/, 'MULTI_BRANCH'],
];

/** `true` si el rol puede abrir la ruta (se usa para no redirigir a una pantalla prohibida tras el login). */
export function canAccessPath(role: Role, path: string): boolean {
  const pathname = path.split(/[?#]/)[0];
  const rule = PATH_RULES.find(([pattern]) => pattern.test(pathname));
  return rule ? rule[1].includes(role) : false;
}

/** Módulo necesario para abrir la ruta, o `null` si está siempre incluida. */
export function requiredModuleForPath(path: string): TenantModule | null {
  const pathname = path.split(/[?#]/)[0];
  return MODULE_PATH_RULES.find(([pattern]) => pattern.test(pathname))?.[1] ?? null;
}

export function hasRole(role: Role | null | undefined, roles: readonly Role[]): boolean {
  return !!role && roles.includes(role);
}
