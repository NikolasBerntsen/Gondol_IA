import {
  ArrowLeftRight,
  BarChart3,
  Bell,
  Blocks,
  Building2,
  Calculator,
  CalendarClock,
  FileSpreadsheet,
  Headset,
  History,
  Home,
  LifeBuoy,
  Megaphone,
  MonitorSmartphone,
  Package,
  Plug,
  ScanBarcode,
  Settings,
  ShieldAlert,
  ShoppingCart,
  Sparkles,
  Store,
  Tags,
  Truck,
  UserCog,
  Users,
  type LucideIcon,
} from 'lucide-react';
import type { Role, TenantModule } from '@/api/types';

export interface NavItem {
  label: string;
  to: string;
  icon: LucideIcon;
  /** Activo solo con coincidencia exacta de ruta. */
  end?: boolean;
  /** Otras rutas que también marcan el ítem como activo (p. ej. detalle de producto → Inventario). */
  matchPrefixes?: string[];
  /** Si el comercio no tiene este módulo habilitado, el ítem no se muestra (SPEC §9.4, §14). */
  module?: TenantModule;
}

/** Grupo de ítems; los grupos se separan visualmente con una línea (SPEC §9.4 "‖"). */
export interface NavSection {
  /** Rótulo del grupo en el riel. */
  title?: string;
  items: NavItem[];
}

const item = {
  // Consola de dueños
  metrics: { label: 'Métricas', to: '/owner', icon: BarChart3, end: true },
  tenants: { label: 'Clientes', to: '/owner/tenants', icon: Store },
  ownerModules: { label: 'Módulos por cliente', to: '/owner/modules', icon: Blocks },
  ownerAnnouncements: { label: 'Avisos y recalls', to: '/owner/announcements', icon: Megaphone },
  platformTeam: { label: 'Equipo GondolIA', to: '/owner/team', icon: Users },
  // Soporte
  supportInbox: { label: 'Bandeja de soporte', to: '/support', icon: Headset },
  // Comercio
  dashboard: { label: 'Inicio', to: '/app/dashboard', icon: Home },
  pos: { label: 'Punto de venta', to: '/app/pos', icon: MonitorSmartphone, end: true, module: 'POS_GONDOLIA' },
  posSessions: { label: 'Mis turnos de caja', to: '/app/pos/sessions', icon: Calculator, module: 'POS_GONDOLIA' },
  posRegisters: {
    label: 'Cajas y turnos',
    to: '/app/pos/registers',
    icon: Calculator,
    matchPrefixes: ['/app/pos/sessions'],
    module: 'POS_GONDOLIA',
  },
  inventory: { label: 'Inventario', to: '/app/inventory', icon: Package, matchPrefixes: ['/app/products'] },
  intake: { label: 'Carga de mercadería', to: '/app/intake', icon: ScanBarcode },
  imports: { label: 'Importar Excel/CSV', to: '/app/imports', icon: FileSpreadsheet },
  expirations: { label: 'Vencimientos', to: '/app/expirations', icon: CalendarClock },
  sales: { label: 'Ventas', to: '/app/sales', icon: ShoppingCart },
  transfers: { label: 'Transferencias', to: '/app/transfers', icon: ArrowLeftRight, module: 'MULTI_BRANCH' },
  movements: { label: 'Movimientos', to: '/app/movements', icon: History },
  statistics: { label: 'Estadísticas', to: '/app/statistics', icon: BarChart3 },
  insights: { label: 'Inteligencia IA', to: '/app/insights', icon: Sparkles },
  alerts: { label: 'Alertas', to: '/app/alerts', icon: Bell },
  suppliers: { label: 'Proveedores', to: '/app/suppliers', icon: Truck },
  categories: { label: 'Categorías', to: '/app/categories', icon: Tags },
  branches: { label: 'Sucursales', to: '/app/branches', icon: Building2 },
  users: { label: 'Usuarios', to: '/app/users', icon: UserCog },
  integrations: { label: 'Integración POS', to: '/app/integrations', icon: Plug, module: 'POS_INTEGRATION' },
  settings: { label: 'Configuración', to: '/app/settings', icon: Settings },
  notices: { label: 'Avisos', to: '/app/notices', icon: Megaphone },
  recalls: { label: 'Seguridad alimentaria', to: '/app/recalls', icon: ShieldAlert },
  tenantSupport: { label: 'Soporte', to: '/app/support', icon: LifeBuoy },
} satisfies Record<string, NavItem>;

const communication: NavSection = { title: 'Comunicación', items: [item.notices, item.recalls, item.tenantSupport] };

/** Menú lateral por rol, en el orden exacto de SPEC §9.4. */
export const NAVIGATION: Record<Role, NavSection[]> = {
  PLATFORM_OWNER: [
    {
      title: 'Consola GondolIA',
      items: [item.metrics, item.tenants, item.ownerModules, item.ownerAnnouncements, item.platformTeam],
    },
  ],
  SUPPORT_AGENT: [{ title: 'Soporte', items: [item.supportInbox] }],
  TENANT_BOSS: [
    { title: 'Mi negocio', items: [item.dashboard, item.statistics, item.insights, item.alerts] },
    communication,
  ],
  TENANT_ADMIN: [
    {
      title: 'Mi negocio',
      items: [
        item.dashboard,
        item.pos,
        item.inventory,
        item.intake,
        item.imports,
        item.expirations,
        item.sales,
        item.transfers,
        item.movements,
        item.statistics,
        item.insights,
        item.alerts,
        item.suppliers,
        item.categories,
      ],
    },
    {
      title: 'Administración',
      items: [item.branches, item.users, item.posRegisters, item.integrations, item.settings],
    },
    communication,
  ],
  TENANT_EMPLOYEE: [
    {
      title: 'Operación',
      items: [item.pos, item.posSessions, item.intake, item.inventory, item.expirations],
    },
    communication,
  ],
  TENANT_CASHIER: [{ title: 'Caja', items: [item.pos, item.posSessions] }, communication],
};

/**
 * Menú del rol, filtrando los ítems cuyo módulo no está habilitado.
 * `modules` sale de `me.tenant.modules` (usá `useModules()`); los roles de plataforma no tienen módulos.
 */
export function getNavigation(role: Role, modules?: readonly TenantModule[]): NavSection[] {
  const sections = NAVIGATION[role] ?? [];
  if (!modules) return sections;
  return sections
    .map((section) => ({ ...section, items: section.items.filter((i) => !i.module || modules.includes(i.module)) }))
    .filter((section) => section.items.length > 0);
}

export function isNavItemActive(navItem: NavItem, pathname: string): boolean {
  if (navItem.end) return pathname === navItem.to || pathname === `${navItem.to}/`;
  return [navItem.to, ...(navItem.matchPrefixes ?? [])].some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}
