import {
  ArrowLeftRight,
  BarChart3,
  Bell,
  Building2,
  CalendarClock,
  Headset,
  History,
  Home,
  LifeBuoy,
  Megaphone,
  Package,
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
import type { Role } from '@/api/types';

export interface NavItem {
  label: string;
  to: string;
  icon: LucideIcon;
  /** Activo solo con coincidencia exacta de ruta. */
  end?: boolean;
  /** Otras rutas que también marcan el ítem como activo (p. ej. detalle de producto → Inventario). */
  matchPrefixes?: string[];
}

/** Grupo de ítems; los grupos se separan visualmente con una línea (SPEC §9.4 "‖"). */
export interface NavSection {
  /** Título opcional del grupo (solo visual). */
  title?: string;
  items: NavItem[];
}

const item = {
  metrics: { label: 'Métricas', to: '/owner', icon: BarChart3, end: true },
  tenants: { label: 'Clientes', to: '/owner/tenants', icon: Store },
  ownerAnnouncements: { label: 'Avisos y recalls', to: '/owner/announcements', icon: Megaphone },
  platformTeam: { label: 'Equipo GondolIA', to: '/owner/team', icon: Users },
  supportInbox: { label: 'Bandeja de soporte', to: '/support', icon: Headset },
  dashboard: { label: 'Inicio', to: '/app/dashboard', icon: Home },
  statistics: { label: 'Estadísticas', to: '/app/statistics', icon: BarChart3 },
  insights: { label: 'Inteligencia IA', to: '/app/insights', icon: Sparkles },
  alerts: { label: 'Alertas', to: '/app/alerts', icon: Bell },
  inventory: { label: 'Inventario', to: '/app/inventory', icon: Package, matchPrefixes: ['/app/products'] },
  intake: { label: 'Carga de mercadería', to: '/app/intake', icon: ScanBarcode },
  expirations: { label: 'Vencimientos', to: '/app/expirations', icon: CalendarClock },
  sales: { label: 'Ventas', to: '/app/sales', icon: ShoppingCart },
  transfers: { label: 'Transferencias', to: '/app/transfers', icon: ArrowLeftRight },
  movements: { label: 'Movimientos', to: '/app/movements', icon: History },
  suppliers: { label: 'Proveedores', to: '/app/suppliers', icon: Truck },
  categories: { label: 'Categorías', to: '/app/categories', icon: Tags },
  branches: { label: 'Sucursales', to: '/app/branches', icon: Building2 },
  users: { label: 'Usuarios', to: '/app/users', icon: UserCog },
  settings: { label: 'Configuración', to: '/app/settings', icon: Settings },
  notices: { label: 'Avisos', to: '/app/notices', icon: Megaphone },
  recalls: { label: 'Seguridad alimentaria', to: '/app/recalls', icon: ShieldAlert },
  tenantSupport: { label: 'Soporte', to: '/app/support', icon: LifeBuoy },
} satisfies Record<string, NavItem>;

const communication: NavSection = { title: 'Comunicación', items: [item.notices, item.recalls, item.tenantSupport] };

/** Menú lateral por rol (SPEC §9.4). */
export const NAVIGATION: Record<Role, NavSection[]> = {
  PLATFORM_OWNER: [
    { title: 'Consola GondolIA', items: [item.metrics, item.tenants, item.ownerAnnouncements, item.platformTeam] },
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
        item.inventory,
        item.intake,
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
    { title: 'Administración', items: [item.branches, item.users, item.settings] },
    communication,
  ],
  TENANT_EMPLOYEE: [
    { title: 'Inventario', items: [item.intake, item.inventory, item.expirations] },
    communication,
  ],
};

export function getNavigation(role: Role): NavSection[] {
  return NAVIGATION[role] ?? [];
}

export function isNavItemActive(navItem: NavItem, pathname: string): boolean {
  if (navItem.end) return pathname === navItem.to || pathname === `${navItem.to}/`;
  return [navItem.to, ...(navItem.matchPrefixes ?? [])].some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}
