import { ROLES, TENANT_ROLES, type Role, type TenantModule } from '@/api/types';

/**
 * Permisos del frontend (SPEC §3.3, §9.3). Una sola fuente para las rutas, el menú y los botones.
 *
 * Regla: **todo botón o enlace visible para un rol funciona para ese rol; lo que un rol no puede hacer no se le
 * muestra.** Antes de dibujar un `<Link>`, un `navigate()` o un botón que llama a la API, preguntá con
 * `can(role, permiso)` (o `useAccess()` en componentes) o, para un enlace, con `canAccessPath(role, ruta)`.
 */

/** Grupos de roles de la matriz de permisos. Espejan `com.gondolia.security.Roles`. */
export const ROLE_GROUPS = {
  ALL: ROLES,
  OWNER: ['PLATFORM_OWNER'],
  SUPPORT: ['SUPPORT_AGENT'],
  /**
   * Equipo de GondolIA: dueño y soporte. Comparten Clientes y Módulos por cliente; soporte los usa para resolver
   * tickets y lo comercial o destructivo sigue siendo del dueño.
   */
  PLATFORM_ANY: ['PLATFORM_OWNER', 'SUPPORT_AGENT'],
  TENANT_ANY: TENANT_ROLES,
  /** Jefe y administrador: tableros, decisiones sobre la IA y las alertas, historiales. */
  TENANT_DASHBOARD: ['TENANT_BOSS', 'TENANT_ADMIN'],
  /** Escritura de inventario: administrador y empleado. */
  TENANT_INVENTORY: ['TENANT_ADMIN', 'TENANT_EMPLOYEE'],
  /** Lectura de inventario (productos, lotes, vencimientos): también el jefe. */
  TENANT_INVENTORY_READ: ['TENANT_BOSS', 'TENANT_ADMIN', 'TENANT_EMPLOYEE'],
  TENANT_ADMIN: ['TENANT_ADMIN'],
  /** POS GondolIA: cobra el admin, el empleado y el cajero (SPEC §15). */
  TENANT_POS: ['TENANT_ADMIN', 'TENANT_EMPLOYEE', 'TENANT_CASHIER'],
} as const satisfies Record<string, readonly Role[]>;

/**
 * Qué puede hacer cada rol (SPEC §3.3). Los permisos `platform.*` son de la consola de GondolIA (dueño y soporte);
 * el resto, de los roles de un comercio.
 */
export const PERMISSIONS = {
  // Consola de GondolIA: clientes y módulos. Soporte ve y edita los datos de un cliente, sus módulos y la contraseña
  // de su administrador para resolver tickets; el alta, el cambio de plan, el bloqueo, la baja y la eliminación son
  // decisiones comerciales del dueño, igual que las métricas.
  'platform.tenants.view': ROLE_GROUPS.PLATFORM_ANY,
  'platform.tenants.edit': ROLE_GROUPS.PLATFORM_ANY,
  'platform.tenants.resetAdminPassword': ROLE_GROUPS.PLATFORM_ANY,
  'platform.modules.manage': ROLE_GROUPS.PLATFORM_ANY,
  'platform.tenants.create': ROLE_GROUPS.OWNER,
  'platform.tenants.changePlan': ROLE_GROUPS.OWNER,
  'platform.tenants.changeStatus': ROLE_GROUPS.OWNER,
  'platform.metrics.view': ROLE_GROUPS.OWNER,
  // Tableros (ver) y decisiones
  'dashboard.view': ROLE_GROUPS.TENANT_DASHBOARD,
  'recommendations.decide': ROLE_GROUPS.TENANT_DASHBOARD,
  'alerts.manage': ROLE_GROUPS.TENANT_DASHBOARD,
  'insights.run': ROLE_GROUPS.TENANT_DASHBOARD,
  // Inventario
  'products.view': ROLE_GROUPS.TENANT_INVENTORY_READ,
  'products.write': ROLE_GROUPS.TENANT_INVENTORY,
  'products.delete': ROLE_GROUPS.TENANT_ADMIN,
  'intake.use': ROLE_GROUPS.TENANT_INVENTORY,
  'imports.use': ROLE_GROUPS.TENANT_ADMIN,
  'catalog.manage': ROLE_GROUPS.TENANT_ADMIN,
  'expirations.view': ROLE_GROUPS.TENANT_INVENTORY_READ,
  'expirations.discard': ROLE_GROUPS.TENANT_INVENTORY,
  // Ventas, movimientos y transferencias
  'sales.view': ROLE_GROUPS.TENANT_DASHBOARD,
  'sales.write': ROLE_GROUPS.TENANT_ADMIN,
  'movements.view': ROLE_GROUPS.TENANT_DASHBOARD,
  'movements.adjust': ROLE_GROUPS.TENANT_INVENTORY,
  'transfers.view': ROLE_GROUPS.TENANT_DASHBOARD,
  'transfers.write': ROLE_GROUPS.TENANT_ADMIN,
  // POS
  'pos.use': ROLE_GROUPS.TENANT_POS,
  'pos.admin': ROLE_GROUPS.TENANT_ADMIN,
  'integrations.manage': ROLE_GROUPS.TENANT_ADMIN,
  // Administración del comercio
  'tenant.admin': ROLE_GROUPS.TENANT_ADMIN,
  // Comunicación
  'communication.view': ROLE_GROUPS.TENANT_ANY,
  'recalls.resolve': ROLE_GROUPS.TENANT_INVENTORY,
} as const satisfies Record<string, readonly Role[]>;

export type Permission = keyof typeof PERMISSIONS;

/** `true` si el rol tiene el permiso. */
export function can(role: Role | null | undefined, permission: Permission): boolean {
  return !!role && (PERMISSIONS[permission] as readonly Role[]).includes(role);
}

/** Roles con el permiso (para `<RequireRole roles={…}>`). */
export function rolesWith(permission: Permission): readonly Role[] {
  return PERMISSIONS[permission];
}

/** Rutas y roles que las abren (el primer patrón que coincide gana: van de lo más específico a lo general). */
const PATH_RULES: ReadonlyArray<readonly [RegExp, readonly Role[]]> = [
  [/^\/(profile|notifications)(\/|$)/, ROLE_GROUPS.ALL],
  [/^\/owner\/tenants\/new(\/|$)/, PERMISSIONS['platform.tenants.create']],
  [/^\/owner\/tenants\/[^/]+\/edit(\/|$)/, PERMISSIONS['platform.tenants.edit']],
  [/^\/owner\/tenants(\/|$)/, PERMISSIONS['platform.tenants.view']],
  [/^\/owner\/modules(\/|$)/, PERMISSIONS['platform.modules.manage']],
  [/^\/owner(\/|$)/, ROLE_GROUPS.OWNER],
  [/^\/support(\/|$)/, ROLE_GROUPS.SUPPORT],
  [/^\/app\/pos\/registers(\/|$)/, PERMISSIONS['pos.admin']],
  [/^\/app\/pos(\/|$)/, PERMISSIONS['pos.use']],
  [/^\/app\/(dashboard|statistics|insights|alerts)(\/|$)/, PERMISSIONS['dashboard.view']],
  [/^\/app\/products\/(new|[^/]+\/edit)(\/|$)/, PERMISSIONS['products.write']],
  [/^\/app\/intake(\/|$)/, PERMISSIONS['intake.use']],
  [/^\/app\/(inventory|products)(\/|$)/, PERMISSIONS['products.view']],
  [/^\/app\/expirations(\/|$)/, PERMISSIONS['expirations.view']],
  [/^\/app\/sales(\/|$)/, PERMISSIONS['sales.view']],
  [/^\/app\/movements(\/|$)/, PERMISSIONS['movements.view']],
  [/^\/app\/transfers(\/|$)/, PERMISSIONS['transfers.view']],
  [/^\/app\/imports(\/|$)/, PERMISSIONS['imports.use']],
  [/^\/app\/integrations(\/|$)/, PERMISSIONS['integrations.manage']],
  [/^\/app\/(categories|suppliers)(\/|$)/, PERMISSIONS['catalog.manage']],
  [/^\/app\/(users|branches|settings)(\/|$)/, PERMISSIONS['tenant.admin']],
  [/^\/app\/(notices|recalls|support)(\/|$)/, PERMISSIONS['communication.view']],
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

function pathnameOf(path: string): string {
  return path.split(/[?#]/)[0];
}

/** Roles que pueden abrir la ruta (vacío si la ruta no existe). */
export function rolesForPath(path: string): readonly Role[] {
  const pathname = pathnameOf(path);
  return PATH_RULES.find(([pattern]) => pattern.test(pathname))?.[1] ?? [];
}

/** `true` si el rol puede abrir la ruta (links, redirección tras el login, notificaciones). */
export function canAccessPath(role: Role | null | undefined, path: string): boolean {
  return !!role && rolesForPath(path).includes(role);
}

/** Módulo necesario para abrir la ruta, o `null` si está siempre incluida. */
export function requiredModuleForPath(path: string): TenantModule | null {
  const pathname = pathnameOf(path);
  return MODULE_PATH_RULES.find(([pattern]) => pattern.test(pathname))?.[1] ?? null;
}

/**
 * Destino de un enlace que no armó el frontend (p. ej. el `link` de una notificación): la ruta si el rol la puede
 * abrir; si no, la pantalla equivalente que sí puede ver (una alerta de vencimiento lleva al empleado a
 * Vencimientos), o `null` para no navegar a una pantalla de "Acceso denegado".
 */
export function linkTargetFor(role: Role | null | undefined, link: string): string | null {
  if (canAccessPath(role, link)) return link;
  const pathname = pathnameOf(link);
  // Las alertas de vencimiento también le llegan al empleado, que no ve la bandeja de alertas.
  if (/^\/app\/alerts(\/|$)/.test(pathname) && can(role, 'expirations.view')) return '/app/expirations';
  return null;
}

export function hasRole(role: Role | null | undefined, roles: readonly Role[]): boolean {
  return !!role && roles.includes(role);
}
