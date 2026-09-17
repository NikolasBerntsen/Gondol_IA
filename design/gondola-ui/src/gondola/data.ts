// Góndola UI · datos de ejemplo (ficticios) para el prototipo.
// Comercio demo: Minimercado El Sol (Rosario) · sucursales Centro, Fisherton y Echesortu.
import { daysBetween, eanCheckDigit } from "./format"

export const TODAY = "2026-09-17"
export const NOW_TIME = "10:42"

export type Severity = "crit" | "warn" | "info" | "ok"
export type ExpiryBucket = "EXPIRED" | "CRITICAL" | "WARNING" | "UPCOMING" | "OK"
export type StockStatus = "OUT" | "CRITICAL" | "LOW" | "OK"
export type Role = "TENANT_ADMIN" | "TENANT_BOSS" | "TENANT_EMPLOYEE" | "TENANT_CASHIER" | "PLATFORM_OWNER"
export type BranchId = "centro" | "fisherton" | "echesortu"

/** Umbrales del comercio (tenant_settings) */
export const SETTINGS = { criticalDays: 2, warningDays: 7, upcomingDays: 30, rotation: "FIFO" as const }

export function expiryBucket(expiryIso: string, today = TODAY): ExpiryBucket {
  const d = daysBetween(today, expiryIso)
  if (d < 0) return "EXPIRED"
  if (d <= SETTINGS.criticalDays) return "CRITICAL"
  if (d <= SETTINGS.warningDays) return "WARNING"
  if (d <= SETTINGS.upcomingDays) return "UPCOMING"
  return "OK"
}

export function stockStatus(stock: number, min: number): StockStatus {
  if (stock <= 0) return "OUT"
  if (stock <= min * 0.5) return "CRITICAL"
  if (stock <= min) return "LOW"
  return "OK"
}

const ean = (first12: string) => first12 + eanCheckDigit(first12)

export const TENANT = {
  name: "Minimercado El Sol",
  city: "Rosario, Santa Fe",
  cuit: "30-71654321-9",
  plan: "PROFESIONAL",
}

export const BRANCHES: { id: BranchId; name: string; full: string; address: string }[] = [
  { id: "centro", name: "Centro", full: "Sucursal Centro", address: "Córdoba 1450" },
  { id: "fisherton", name: "Fisherton", full: "Sucursal Fisherton", address: "Av. Eva Perón 8200" },
  { id: "echesortu", name: "Echesortu", full: "Sucursal Echesortu", address: "Mendoza 3980" },
]
export const branchName = (id: BranchId) => BRANCHES.find((b) => b.id === id)!.name

export const USERS: Record<Role, { name: string; short: string; initials: string; roleLabel: string }> = {
  TENANT_ADMIN: { name: "Laura Benítez", short: "Laura", initials: "LB", roleLabel: "Administradora" },
  TENANT_BOSS: { name: "Ricardo Ferreyra", short: "Ricardo", initials: "RF", roleLabel: "Jefe" },
  TENANT_EMPLOYEE: { name: "Sofía Acosta", short: "Sofía", initials: "SA", roleLabel: "Empleada" },
  TENANT_CASHIER: { name: "Martín Ríos", short: "Martín", initials: "MR", roleLabel: "Cajero" },
  PLATFORM_OWNER: { name: "Nicolás Carp", short: "Nicolás", initials: "NC", roleLabel: "Dueño GondolIA" },
}

// ---------------------------------------------------------------------------
// Catálogo
// ---------------------------------------------------------------------------
export interface Product {
  id: string
  ean: string
  name: string
  brand: string
  category: string
  price: number
  cost: number
  unitLabel: string // para la etiqueta: "x litro", "x kg"
  unitFactor: number // precio por unidad de medida = price / unitFactor
}

export const PRODUCTS: Product[] = [
  { id: "yogur", ean: "7790080000123", name: "Yogur bebible frutilla 1 L", brand: "Lácteos del Valle", category: "Lácteos", price: 2150, cost: 1380, unitLabel: "x litro", unitFactor: 1 },
  { id: "leche", ean: ean("779008000021"), name: "Leche entera larga vida 1 L", brand: "Lácteos del Valle", category: "Lácteos", price: 1580, cost: 1040, unitLabel: "x litro", unitFactor: 1 },
  { id: "queso", ean: ean("779008000045"), name: "Queso cremoso 500 g", brand: "Campo Serrano", category: "Fiambrería", price: 6900, cost: 4700, unitLabel: "x kg", unitFactor: 0.5 },
  { id: "pan", ean: ean("779215500017"), name: "Pan lactal blanco 550 g", brand: "Panificados San Roque", category: "Panadería", price: 3200, cost: 2050, unitLabel: "x kg", unitFactor: 0.55 },
  { id: "yerba", ean: ean("779044100011"), name: "Yerba mate suave 1 kg", brand: "Don Julián", category: "Almacén", price: 4890, cost: 3300, unitLabel: "x kg", unitFactor: 1 },
  { id: "fideos", ean: ean("779031200034"), name: "Fideos tirabuzón 500 g", brand: "Molinos Paraná", category: "Almacén", price: 1340, cost: 820, unitLabel: "x kg", unitFactor: 0.5 },
  { id: "agua", ean: ean("779067700020"), name: "Agua mineral sin gas 2 L", brand: "Aguas del Litoral", category: "Bebidas", price: 1290, cost: 760, unitLabel: "x litro", unitFactor: 2 },
  { id: "galletitas", ean: ean("779052800016"), name: "Galletitas de agua 3 x 100 g", brand: "Dulce Norte", category: "Almacén", price: 1650, cost: 1010, unitLabel: "x kg", unitFactor: 0.3 },
  { id: "dulce", ean: ean("779052800047"), name: "Dulce de leche clásico 400 g", brand: "Dulce Norte", category: "Almacén", price: 2780, cost: 1790, unitLabel: "x kg", unitFactor: 0.4 },
  // EAN del escenario de recall: corregido en el addendum (el de SPEC §11 tenía dígito verificador inválido)
  { id: "sopa", ean: "7791234500017", name: "Sopa de tomate La Huerta 340 g", brand: "La Huerta", category: "Almacén", price: 1960, cost: 1250, unitLabel: "x kg", unitFactor: 0.34 },
  { id: "detergente", ean: ean("779088100052"), name: "Detergente limón 750 ml", brand: "Frescor", category: "Limpieza", price: 2450, cost: 1560, unitLabel: "x litro", unitFactor: 0.75 },
  { id: "huevos", ean: ean("779099900018"), name: "Huevos blancos x 12", brand: "Granja Santa Fe", category: "Frescos", price: 4300, cost: 3050, unitLabel: "x unidad", unitFactor: 12 },
  { id: "cafe", ean: ean("779071100029"), name: "Café molido 250 g", brand: "Café Rosarino", category: "Almacén", price: 5600, cost: 3900, unitLabel: "x kg", unitFactor: 0.25 },
  { id: "aceite", ean: ean("779040500061"), name: "Aceite de girasol 1,5 L", brand: "Girasoles del Sur", category: "Almacén", price: 3950, cost: 2700, unitLabel: "x litro", unitFactor: 1.5 },
  { id: "jamon", ean: ean("779008000076"), name: "Jamón cocido feteado 200 g", brand: "Campo Serrano", category: "Fiambrería", price: 4200, cost: 2900, unitLabel: "x kg", unitFactor: 0.2 },
  { id: "crema", ean: ean("779008000090"), name: "Crema de leche 200 ml", brand: "Lácteos del Valle", category: "Lácteos", price: 1890, cost: 1200, unitLabel: "x litro", unitFactor: 0.2 },
  { id: "harina", ean: ean("779031200058"), name: "Harina 000 1 kg", brand: "Molinos Paraná", category: "Almacén", price: 1180, cost: 720, unitLabel: "x kg", unitFactor: 1 },
]
export const product = (id: string) => PRODUCTS.find((p) => p.id === id)!

// ---------------------------------------------------------------------------
// Inicio (dashboard)
// ---------------------------------------------------------------------------
export interface BranchSummary {
  id: BranchId
  salesToday: number
  tickets: number
  expiring: number
  lowStock: number
  outOfStock: number
  inventoryValue: number
  recall?: boolean
}

export const BRANCH_SUMMARY: BranchSummary[] = [
  { id: "centro", salesToday: 1284600, tickets: 212, expiring: 7, lowStock: 5, outOfStock: 2, inventoryValue: 3720000 },
  { id: "fisherton", salesToday: 948300, tickets: 161, expiring: 6, lowStock: 4, outOfStock: 1, inventoryValue: 2610000, recall: true },
  { id: "echesortu", salesToday: 702150, tickets: 128, expiring: 5, lowStock: 3, outOfStock: 1, inventoryValue: 2120000 },
]

function mulberry32(seed: number) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export interface TrendPoint {
  iso: string
  label: string
  ventas: number
  stock: number
}

/** 30 días (19/08 → 17/09/2026): ventas en unidades por día y stock total al cierre. */
export const TREND: TrendPoint[] = (() => {
  const rand = mulberry32(2609)
  const out: TrendPoint[] = []
  let stock = 15420
  const start = Date.UTC(2026, 7, 19)
  for (let i = 0; i < 30; i++) {
    const dt = new Date(start + i * 86400000)
    const dow = dt.getUTCDay() // 0 domingo
    const dowEffect = [-70, -35, -10, 0, 20, 90, 150][dow]
    const ventas = Math.round(455 + i * 2.2 + dowEffect + (rand() - 0.5) * 70)
    if (dow === 2 || dow === 5) stock += Math.round(1480 + rand() * 420)
    stock -= ventas
    const d = String(dt.getUTCDate()).padStart(2, "0")
    const m = String(dt.getUTCMonth() + 1).padStart(2, "0")
    out.push({ iso: `2026-${m}-${d}`, label: `${d}/${m}`, ventas, stock })
  }
  return out
})()

export const KPI_SPARKS = {
  products: [1188, 1192, 1196, 1201, 1203, 1210, 1214, 1219, 1224, 1229, 1236, 1240, 1244, 1248],
  expiring: [11, 13, 12, 14, 15, 13, 16, 17, 15, 16, 19, 17, 18, 18],
  lowStock: [7, 8, 8, 9, 10, 9, 11, 10, 12, 11, 13, 12, 11, 12],
  value: [8.02, 8.11, 8.05, 8.18, 8.22, 8.19, 8.28, 8.31, 8.26, 8.35, 8.39, 8.41, 8.43, 8.45],
}

export interface ExpiringLot {
  id: string
  productId: string
  branch: BranchId
  lot: string
  expiry: string
  qty: number
}

export const EXPIRING: ExpiringLot[] = [
  { id: "e1", productId: "yogur", branch: "centro", lot: "L2409B", expiry: "2026-09-18", qty: 26 },
  { id: "e2", productId: "leche", branch: "fisherton", lot: "L2409C", expiry: "2026-09-19", qty: 18 },
  { id: "e3", productId: "queso", branch: "echesortu", lot: "QC0912", expiry: "2026-09-21", qty: 9 },
  { id: "e4", productId: "pan", branch: "centro", lot: "PL1709", expiry: "2026-09-22", qty: 14 },
  { id: "e5", productId: "jamon", branch: "fisherton", lot: "JC2231", expiry: "2026-09-22", qty: 11 },
  { id: "e6", productId: "huevos", branch: "centro", lot: "H0907", expiry: "2026-09-22", qty: 20 },
  { id: "e7", productId: "dulce", branch: "echesortu", lot: "DL2408", expiry: "2026-10-02", qty: 32 },
  { id: "e8", productId: "crema", branch: "echesortu", lot: "L2410A", expiry: "2026-10-09", qty: 24 },
]

export interface RestockRow {
  id: string
  productId: string
  branch: BranchId
  stock: number
  min: number
  suggest: number
}

export const RESTOCK: RestockRow[] = [
  { id: "r1", productId: "agua", branch: "centro", stock: 0, min: 24, suggest: 48 },
  { id: "r2", productId: "cafe", branch: "centro", stock: 0, min: 8, suggest: 12 },
  { id: "r3", productId: "yerba", branch: "fisherton", stock: 0, min: 15, suggest: 30 },
  { id: "r4", productId: "harina", branch: "echesortu", stock: 0, min: 20, suggest: 40 },
  { id: "r5", productId: "fideos", branch: "echesortu", stock: 4, min: 20, suggest: 40 },
  { id: "r6", productId: "aceite", branch: "centro", stock: 6, min: 18, suggest: 24 },
  { id: "r7", productId: "galletitas", branch: "fisherton", stock: 9, min: 12, suggest: 20 },
  { id: "r8", productId: "detergente", branch: "echesortu", stock: 7, min: 10, suggest: 20 },
]

export type RecommendationType = "REORDER" | "DISCOUNT" | "REVIEW_ANOMALY"
export interface Recommendation {
  id: string
  type: RecommendationType
  branch: BranchId
  title: string
  explanation: string
  confidence: number
  impact: string
  action: string
}

export const RECOMMENDATIONS: Recommendation[] = [
  {
    id: "ia1",
    type: "REORDER",
    branch: "centro",
    title: "Reponé Agua mineral sin gas 2 L",
    explanation:
      "Se venden 11 u. por día y los sábados sube a 19. Está sin stock desde ayer a las 18:40: estimamos 22 ventas perdidas hasta el lunes.",
    confidence: 91,
    impact: "Evitás perder ~$ 38.500 en ventas",
    action: "Comprar 48 u.",
  },
  {
    id: "ia2",
    type: "DISCOUNT",
    branch: "centro",
    title: "Aplicá -20% al Yogur bebible frutilla 1 L",
    explanation:
      "Quedan 26 u. del lote L2409B que vencen el 18/09/2026. Al ritmo actual se venden 9 antes de vencer; con el descuento esperamos vender 22.",
    confidence: 78,
    impact: "Recuperás ~$ 41.800 que irían a merma",
    action: "Descuento 20% en el lote",
  },
  {
    id: "ia3",
    type: "REVIEW_ANOMALY",
    branch: "echesortu",
    title: "Revisá el stock de Queso cremoso 500 g",
    explanation:
      "El martes el stock bajó 14 u. sin ventas registradas. Puede ser un ajuste sin cargar o mercadería dañada que no se descartó.",
    confidence: 66,
    impact: "Diferencia de $ 65.800 a costo",
    action: "Contar y ajustar",
  },
]

export const NOTIFICATIONS = [
  { id: "n1", sev: "crit" as Severity, title: "Alerta de recall en Fisherton", body: "Sopa de tomate en lata La Huerta 340 g · lote L2409A", time: "09:14" },
  { id: "n2", sev: "warn" as Severity, title: "Yogur bebible frutilla vence mañana", body: "Centro · lote L2409B · 26 u.", time: "08:00" },
  { id: "n3", sev: "info" as Severity, title: "La IA generó 3 recomendaciones", body: "Reposición, descuento y una anomalía de stock", time: "07:30" },
  { id: "n4", sev: "ok" as Severity, title: "Importación aplicada", body: "planilla_stock_elsol.xlsx · 1.239 filas", time: "ayer" },
]

// ---------------------------------------------------------------------------
// Recall
// ---------------------------------------------------------------------------
export const RECALL = {
  productId: "sopa",
  productName: "Sopa de tomate en lata La Huerta 340 g",
  lot: "L2409A",
  expiry: "2027-09-30",
  branch: "fisherton" as BranchId,
  units: 24,
  publishedAt: "hoy 09:14",
  reference: "Comunicado sanitario N.º 4127/2026",
  reason:
    "Posible presencia de fragmentos metálicos por una falla en el cierre de las latas de este lote. No se reportaron personas afectadas.",
  steps: [
    "Retirá de la góndola y del depósito todas las latas del lote L2409A.",
    "Separalas en una caja rotulada «NO VENDER · RECALL» hasta que el proveedor las retire.",
    "Si un cliente compró este lote, pedile que no lo consuma y ofrecé el reembolso.",
    "Marcá el retiro en GondolIA para dejar registro de las unidades.",
  ],
}

// ---------------------------------------------------------------------------
// Punto de venta (Sucursal Centro, Caja 1)
// ---------------------------------------------------------------------------
export interface PosLot {
  lot: string
  expiry: string
  discountPct?: number
}
export interface PosItem {
  productId: string
  stock: number
  nextLot?: PosLot
  blocked?: "RECALL"
}

export const POS_ITEMS: PosItem[] = [
  { productId: "yogur", stock: 62, nextLot: { lot: "L2409B", expiry: "2026-09-18", discountPct: 20 } },
  { productId: "leche", stock: 84, nextLot: { lot: "L2410C", expiry: "2026-10-25" } },
  { productId: "yerba", stock: 37, nextLot: { lot: "YM2503", expiry: "2027-03-12" } },
  { productId: "pan", stock: 14, nextLot: { lot: "PL1709", expiry: "2026-09-22" } },
  { productId: "queso", stock: 12, nextLot: { lot: "QC0915", expiry: "2026-09-20", discountPct: 15 } },
  { productId: "sopa", stock: 8, nextLot: { lot: "L2409A", expiry: "2027-09-30" }, blocked: "RECALL" },
  { productId: "huevos", stock: 20, nextLot: { lot: "H0907", expiry: "2026-09-22" } },
  { productId: "dulce", stock: 41, nextLot: { lot: "DL2409", expiry: "2027-01-15" } },
  { productId: "fideos", stock: 58, nextLot: { lot: "FT2507", expiry: "2027-07-02" } },
  { productId: "galletitas", stock: 33, nextLot: { lot: "GA2511", expiry: "2026-12-11" } },
  { productId: "detergente", stock: 25 },
  { productId: "aceite", stock: 6, nextLot: { lot: "AG2602", expiry: "2027-02-20" } },
  { productId: "agua", stock: 0 },
  { productId: "cafe", stock: 0 },
]

export const POS_SESSION = {
  branch: "centro" as BranchId,
  register: "Caja 1",
  openedAt: "08:02",
  cashier: "Martín R.",
  nextTicket: "0001-00000418",
  openingCash: 20000,
}

export const POS_INITIAL_CART: { productId: string; qty: number }[] = [
  { productId: "yogur", qty: 2 },
  { productId: "leche", qty: 3 },
  { productId: "yerba", qty: 1 },
  { productId: "pan", qty: 1 },
]

// ---------------------------------------------------------------------------
// Carga de mercadería (Sofía · Sucursal Centro)
// ---------------------------------------------------------------------------
export const INTAKE = {
  productId: "yogur",
  ocr: {
    expiry: [
      { value: "25/10/2026", iso: "2026-10-25", confidence: 92 },
      { value: "26/10/2026", iso: "2026-10-26", confidence: 41 },
    ],
    lot: [
      { value: "L2410C", confidence: 85 },
      { value: "L241OC", confidence: 38 },
    ],
  },
  existingLots: [
    { lot: "L2409B", receivedAt: "01/09/2026", expiry: "2026-09-18", qty: 26 },
    { lot: "L2409F", receivedAt: "12/09/2026", expiry: "2026-11-02", qty: 36 },
  ],
  suppliers: ["Lácteos del Valle S.A.", "Distribuidora Litoral Frío", "Mayorista Rosario Norte"],
}

// ---------------------------------------------------------------------------
// Importación Excel/CSV (revisión)
// ---------------------------------------------------------------------------
export interface ImportRow {
  id: string
  row: number
  code: string
  name: string
  category: string
  price: string
  stock: string
  lot: string
  expiry: string
  branch: string
  cost: number
  skipped?: boolean
}

export const IMPORT_FILE = { name: "planilla_stock_elsol.xlsx", sheet: "Stock", rows: 1248 }
/** Filas fuera de la muestra editable (ya validadas en el servidor) */
export const IMPORT_BASE = { valid: 1176, warning: 50, error: 0 }
export const KNOWN_CATEGORIES = ["Almacén", "Bebidas", "Lácteos", "Fiambrería", "Panadería", "Limpieza", "Frescos", "Congelados"]
export const EXISTING_CATEGORIES = ["Almacén", "Bebidas", "Lácteos", "Fiambrería", "Panadería", "Limpieza", "Frescos"]

export const IMPORT_ROWS: ImportRow[] = [
  { id: "i2", row: 2, code: ean("779044100011"), name: "Yerba mate suave 1 kg", category: "Almacén", price: "4.890", stock: "37", lot: "YM2503", expiry: "12/03/2027", branch: "Centro", cost: 3300 },
  { id: "i14", row: 14, code: ean("779008000021"), name: "Leche entera larga vida 1 L", category: "Lácteos", price: "1.580", stock: "84", lot: "L2410C", expiry: "31/02/2026", branch: "Centro", cost: 1040 },
  { id: "i33", row: 33, code: "7790312000345", name: "Fideos tirabuzón 500 g", category: "Almacén", price: "1.340", stock: "58", lot: "FT2507", expiry: "02/07/2027", branch: "Centro", cost: 820 },
  { id: "i61", row: 61, code: ean("779052800047"), name: "Dulce de leche clásico 400 g", category: "Almacén", price: "2.780", stock: "32", lot: "DL2408", expiry: "02/10/2026", branch: "Echesortu", cost: 1790 },
  { id: "i87", row: 87, code: ean("779008000045"), name: "Queso cremoso 500 g", category: "Fiambrería", price: "6.900", stock: "9", lot: "QC0912", expiry: "21/09/2026", branch: "Rosario Norte", cost: 4700 },
  { id: "i141", row: 141, code: ean("779040500061"), name: "Aceite de girasol 1,5 L", category: "Almacén", price: "2.450", stock: "6", lot: "AG2602", expiry: "20/02/2027", branch: "Centro", cost: 2700 },
  { id: "i212", row: 212, code: ean("779215500017"), name: "", category: "Panadería", price: "3.200", stock: "14", lot: "PL1709", expiry: "22/09/2026", branch: "Centro", cost: 2050 },
  { id: "i274", row: 274, code: ean("779099900018"), name: "Huevos blancos x 12", category: "Frescos", price: "4.300", stock: "20", lot: "H0907", expiry: "22/09/2026", branch: "Centro", cost: 3050 },
  { id: "i318", row: 318, code: ean("779088100052"), name: "Detergente limón 750 ml", category: "Limpieza", price: "2.45O", stock: "25", lot: "", expiry: "", branch: "Echesortu", cost: 1560 },
  { id: "i402", row: 402, code: ean("779067700020"), name: "Agua mineral sin gas 2 L", category: "Bebidas", price: "1.290", stock: "4,5", lot: "AL2608", expiry: "15/08/2027", branch: "Fisherton", cost: 760 },
  { id: "i506", row: 506, code: ean("779008000076"), name: "Jamón cocido feteado 200 g", category: "Fiambrería", price: "4.200", stock: "3", lot: "JC2201", expiry: "10/09/2026", branch: "Fisherton", cost: 2900 },
  { id: "i540", row: 540, code: ean("779052800016"), name: "Galletitas de agua 3 x 100 g", category: "Almacén", price: "1.650", stock: "33", lot: "GA2511", expiry: "11/12/2026", branch: "Fisherton", cost: 1010 },
  { id: "i655", row: 655, code: ean("779008000090"), name: "Crema de leche 200 ml", category: "Lácteos", price: "1.890", stock: "24", lot: "L2410A", expiry: "15/13/2026", branch: "Echesortu", cost: 1200 },
  { id: "i733", row: 733, code: "7790711000292", name: "Café molido 250 g", category: "Almacén", price: "5.600", stock: "12", lot: "CR2604", expiry: "30/04/2027", branch: "Centro", cost: 3900 },
  { id: "i790", row: 790, code: ean("779031200058"), name: "Harina 000 1 kg", category: "Almacén", price: "1.180", stock: "40", lot: "HA2605", expiry: "05/05/2027", branch: "Centr", cost: 720 },
  { id: "i812", row: 812, code: "7791234500017", name: "Sopa de tomate La Huerta 340 g", category: "Almacén", price: "1.960", stock: "16", lot: "L2409A", expiry: "30/09/2027", branch: "Fisherton", cost: 1250 },
  { id: "i958", row: 958, code: ean("779008000038"), name: "Leche descremada 1 L", category: "Lácteos", price: "990", stock: "30", lot: "L2410D", expiry: "28/10/2026", branch: "Fisherton", cost: 1060 },
  { id: "i1013", row: 1013, code: ean("779215500024"), name: "Pan de salvado 400 g", category: "Panadería", price: "3.450", stock: "-6", lot: "PS1709", expiry: "23/09/2026", branch: "Echesortu", cost: 2200 },
  { id: "i1102", row: 1102, code: ean("779099900032"), name: "Espinaca congelada 500 g", category: "Congelados", price: "2.900", stock: "18", lot: "EC2611", expiry: "10/11/2027", branch: "Centro", cost: 1900 },
  { id: "i1155", row: 1155, code: ean("779040500078"), name: "Vinagre de alcohol 500 ml", category: "Almacén", price: "980", stock: "22", lot: "", expiry: "", branch: "Echesortu", cost: 610 },
  { id: "i1187", row: 1187, code: ean("779071100036"), name: "Té negro x 25 saquitos", category: "Almacén", price: "-1.200", stock: "15", lot: "TN2701", expiry: "01/07/2027", branch: "Centro", cost: 890 },
  { id: "i1231", row: 1231, code: ean("779088100069"), name: "Lavandina 1 L", category: "Limpieza", price: "1.150", stock: "28", lot: "", expiry: "", branch: "Fisherton", cost: 700 },
]

// ---------------------------------------------------------------------------
// Consola de dueños · módulos por cliente
// ---------------------------------------------------------------------------
export type TenantModule = "POS_GONDOLIA" | "POS_INTEGRATION" | "MULTI_BRANCH"
export type TenantPlan = "FREEMIUM" | "BASICO" | "PROFESIONAL"
export type TenantStatus = "ACTIVE" | "DISABLED" | "CANCELLED"

export const PLAN_PRICE: Record<TenantPlan, number> = { FREEMIUM: 0, BASICO: 25000, PROFESIONAL: 55000 }
export const PLAN_LABEL: Record<TenantPlan, string> = { FREEMIUM: "Freemium", BASICO: "Básico", PROFESIONAL: "Profesional" }

export const MODULES: { id: TenantModule; name: string; short: string; description: string; price: number; enabledTenants: number }[] = [
  {
    id: "POS_GONDOLIA",
    name: "Punto de venta GondolIA",
    short: "POS GondolIA",
    description: "Cajas por sucursal, cobro con escáner, medios de pago, tickets y cierre de caja.",
    price: 12000,
    enabledTenants: 23,
  },
  {
    id: "POS_INTEGRATION",
    name: "Integración con POS propio",
    short: "Integración POS",
    description: "Clave de API por sucursal para recibir ventas del sistema de caja del cliente.",
    price: 8000,
    enabledTenants: 15,
  },
  {
    id: "MULTI_BRANCH",
    name: "Multi-sucursal",
    short: "Multi-sucursal",
    description: "Más de una sucursal, transferencias de stock y vista consolidada.",
    price: 0,
    enabledTenants: 13,
  },
]
export const PLATFORM_ACTIVE_TENANTS = 36

export interface TenantRow {
  id: string
  name: string
  city: string
  type: string
  plan: TenantPlan
  status: TenantStatus
  branches: number
  modules: Record<TenantModule, boolean>
  lastActivity: string
}

export const TENANTS: TenantRow[] = [
  { id: "t1", name: "Minimercado El Sol", city: "Rosario", type: "Minimercado", plan: "PROFESIONAL", status: "ACTIVE", branches: 3, modules: { POS_GONDOLIA: true, POS_INTEGRATION: true, MULTI_BRANCH: true }, lastActivity: "hace 2 min" },
  { id: "t2", name: "Almacén Don Pepe", city: "Córdoba", type: "Almacén", plan: "BASICO", status: "ACTIVE", branches: 1, modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: false }, lastActivity: "hace 14 min" },
  { id: "t3", name: "Dietética Vida Sana", city: "Mendoza", type: "Dietética", plan: "BASICO", status: "ACTIVE", branches: 2, modules: { POS_GONDOLIA: false, POS_INTEGRATION: true, MULTI_BRANCH: true }, lastActivity: "hace 1 h" },
  { id: "t4", name: "Kiosco La Esquina", city: "La Plata", type: "Kiosco", plan: "FREEMIUM", status: "ACTIVE", branches: 1, modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: false }, lastActivity: "hace 3 h" },
  { id: "t5", name: "Farmacia Belgrano", city: "CABA", type: "Farmacia", plan: "PROFESIONAL", status: "ACTIVE", branches: 4, modules: { POS_GONDOLIA: true, POS_INTEGRATION: true, MULTI_BRANCH: true }, lastActivity: "hace 6 min" },
  { id: "t6", name: "Autoservicio Los Pinos", city: "Neuquén", type: "Minimercado", plan: "BASICO", status: "DISABLED", branches: 2, modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: true }, lastActivity: "hace 12 días" },
  { id: "t7", name: "Dietética Tierra Viva", city: "Salta", type: "Dietética", plan: "FREEMIUM", status: "ACTIVE", branches: 1, modules: { POS_GONDOLIA: false, POS_INTEGRATION: false, MULTI_BRANCH: false }, lastActivity: "ayer" },
  { id: "t8", name: "Minimercado Doña Rosa", city: "Santa Fe", type: "Minimercado", plan: "BASICO", status: "ACTIVE", branches: 3, modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: true }, lastActivity: "hace 25 min" },
  { id: "t9", name: "Kiosco 24 h Parque", city: "Rosario", type: "Kiosco", plan: "BASICO", status: "ACTIVE", branches: 1, modules: { POS_GONDOLIA: true, POS_INTEGRATION: true, MULTI_BRANCH: false }, lastActivity: "hace 2 h" },
  { id: "t10", name: "Dietética El Granero", city: "Tucumán", type: "Dietética", plan: "PROFESIONAL", status: "ACTIVE", branches: 2, modules: { POS_GONDOLIA: false, POS_INTEGRATION: true, MULTI_BRANCH: true }, lastActivity: "hace 40 min" },
  { id: "t11", name: "Almacén San Martín", city: "Paraná", type: "Almacén", plan: "BASICO", status: "CANCELLED", branches: 1, modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: false }, lastActivity: "hace 2 meses" },
]

export function tenantMrr(t: Pick<TenantRow, "plan" | "status" | "branches" | "modules">): number {
  if (t.status !== "ACTIVE") return 0
  const addOns = MODULES.reduce((acc, m) => acc + (t.modules[m.id] ? m.price : 0), 0)
  return t.branches * (PLAN_PRICE[t.plan] + addOns)
}
