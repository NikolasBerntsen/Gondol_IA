import type { LucideIcon } from "lucide-react"
import {
  ArrowLeftRight,
  BarChart3,
  Bell,
  Blocks,
  Building2,
  Calculator,
  CalendarClock,
  FileSpreadsheet,
  History,
  Home,
  LifeBuoy,
  Megaphone,
  MonitorSmartphone,
  Package,
  Palette,
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
} from "lucide-react"
import type { Role } from "../data"

export type ScreenId = "inicio" | "pos" | "carga" | "importar" | "modulos" | "ds" | "placeholder"

export interface NavItem {
  key: string
  label: string
  icon: LucideIcon
  screen: ScreenId
  badge?: { text: string; tone: "crit" | "warn" | "info" }
}
export interface NavGroup {
  label?: string
  items: NavItem[]
}

const I = {
  inicio: { key: "inicio", label: "Inicio", icon: Home, screen: "inicio" },
  pos: { key: "pos", label: "Punto de venta", icon: MonitorSmartphone, screen: "pos" },
  inventario: { key: "inventario", label: "Inventario", icon: Package, screen: "placeholder" },
  carga: { key: "carga", label: "Carga de mercadería", icon: ScanBarcode, screen: "carga" },
  importar: { key: "importar", label: "Importar Excel/CSV", icon: FileSpreadsheet, screen: "importar" },
  vencimientos: { key: "vencimientos", label: "Vencimientos", icon: CalendarClock, screen: "placeholder", badge: { text: "18", tone: "warn" } },
  ventas: { key: "ventas", label: "Ventas", icon: ShoppingCart, screen: "placeholder" },
  transferencias: { key: "transferencias", label: "Transferencias", icon: ArrowLeftRight, screen: "placeholder" },
  movimientos: { key: "movimientos", label: "Movimientos", icon: History, screen: "placeholder" },
  estadisticas: { key: "estadisticas", label: "Estadísticas", icon: BarChart3, screen: "placeholder" },
  ia: { key: "ia", label: "Inteligencia IA", icon: Sparkles, screen: "placeholder", badge: { text: "3", tone: "info" } },
  alertas: { key: "alertas", label: "Alertas", icon: Bell, screen: "placeholder" },
  proveedores: { key: "proveedores", label: "Proveedores", icon: Truck, screen: "placeholder" },
  categorias: { key: "categorias", label: "Categorías", icon: Tags, screen: "placeholder" },
  sucursales: { key: "sucursales", label: "Sucursales", icon: Building2, screen: "placeholder" },
  usuarios: { key: "usuarios", label: "Usuarios", icon: UserCog, screen: "placeholder" },
  cajas: { key: "cajas", label: "Cajas y turnos", icon: Calculator, screen: "placeholder" },
  misTurnos: { key: "misTurnos", label: "Mis turnos de caja", icon: Calculator, screen: "placeholder" },
  integracion: { key: "integracion", label: "Integración POS", icon: Plug, screen: "placeholder" },
  configuracion: { key: "configuracion", label: "Configuración", icon: Settings, screen: "placeholder" },
  avisos: { key: "avisos", label: "Avisos", icon: Megaphone, screen: "placeholder" },
  seguridad: { key: "seguridad", label: "Seguridad alimentaria", icon: ShieldAlert, screen: "placeholder", badge: { text: "1", tone: "crit" } },
  soporte: { key: "soporte", label: "Soporte", icon: LifeBuoy, screen: "placeholder" },
  metricas: { key: "metricas", label: "Métricas", icon: BarChart3, screen: "placeholder" },
  clientes: { key: "clientes", label: "Clientes", icon: Store, screen: "placeholder" },
  modulos: { key: "modulos", label: "Módulos por cliente", icon: Blocks, screen: "modulos" },
  avisosRecalls: { key: "avisosRecalls", label: "Avisos y recalls", icon: Megaphone, screen: "placeholder" },
  equipo: { key: "equipo", label: "Equipo GondolIA", icon: Users, screen: "placeholder" },
  ds: { key: "ds", label: "Góndola UI", icon: Palette, screen: "ds" },
} satisfies Record<string, NavItem>

const HELP: NavGroup = { label: "Comunicación", items: [I.avisos, I.seguridad, I.soporte] }
const PROTO: NavGroup = { label: "Prototipo", items: [I.ds] }

export const NAV: Record<Role, NavGroup[]> = {
  TENANT_ADMIN: [
    { items: [I.inicio] },
    {
      label: "Operación",
      items: [I.pos, I.inventario, I.carga, I.importar, I.vencimientos, I.ventas, I.transferencias, I.movimientos, I.proveedores, I.categorias],
    },
    { label: "Análisis", items: [I.estadisticas, I.ia, I.alertas] },
    { label: "Administración", items: [I.sucursales, I.usuarios, I.cajas, I.integracion, I.configuracion] },
    HELP,
    PROTO,
  ],
  TENANT_BOSS: [{ items: [I.inicio, I.estadisticas, I.ia, I.alertas] }, HELP, PROTO],
  TENANT_EMPLOYEE: [{ label: "Operación", items: [I.pos, I.misTurnos, I.carga, I.inventario, I.vencimientos] }, HELP, PROTO],
  TENANT_CASHIER: [{ label: "Caja", items: [I.pos, I.misTurnos] }, HELP, PROTO],
  PLATFORM_OWNER: [{ label: "Plataforma", items: [I.metricas, I.clientes, I.modulos, I.avisosRecalls, I.equipo] }, PROTO],
}

export const ROLE_HOME: Record<Role, string> = {
  TENANT_ADMIN: "inicio",
  TENANT_BOSS: "inicio",
  TENANT_EMPLOYEE: "carga",
  TENANT_CASHIER: "pos",
  PLATFORM_OWNER: "modulos",
}

export const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: "TENANT_ADMIN", label: "Administrador" },
  { value: "TENANT_BOSS", label: "Jefe" },
  { value: "TENANT_EMPLOYEE", label: "Empleado" },
  { value: "TENANT_CASHIER", label: "Cajero" },
  { value: "PLATFORM_OWNER", label: "Dueño GondolIA" },
]

export function findNavItem(role: Role, key: string): NavItem | undefined {
  for (const g of NAV[role]) for (const it of g.items) if (it.key === key) return it
  return undefined
}
