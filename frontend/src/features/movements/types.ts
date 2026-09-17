import type { BranchScoped, ExpiryBucket, LotStatus, MovementType } from '@/api/types';

// ---------------------------------------------------------------------------
// Enums que el módulo extiende
// ---------------------------------------------------------------------------

/**
 * Origen de un movimiento. El tipo compartido de `@/api/types` todavía no incluye `POS_GONDOLIA`
 * ni `IMPORT` (SPEC §4.1), así que el módulo los agrega acá sin tocar la fundación.
 */
export type MovementSourceExt =
  | 'MANUAL'
  | 'SCAN'
  | 'OCR'
  | 'CSV'
  | 'POS'
  | 'POS_GONDOLIA'
  | 'IMPORT'
  | 'SEED'
  | 'SYSTEM';

export const MOVEMENT_SOURCES: readonly MovementSourceExt[] = [
  'MANUAL',
  'SCAN',
  'OCR',
  'CSV',
  'POS',
  'POS_GONDOLIA',
  'IMPORT',
  'SEED',
  'SYSTEM',
];

/** Espejo de `MovementLabels.source` del backend. */
export const MOVEMENT_SOURCE_LABELS_EXT: Record<MovementSourceExt, string> = {
  MANUAL: 'Manual',
  SCAN: 'Escáner',
  OCR: 'Lectura de etiqueta',
  CSV: 'Importación CSV',
  POS: 'POS externo',
  POS_GONDOLIA: 'POS GondolIA',
  IMPORT: 'Importación masiva',
  SEED: 'Datos demo',
  SYSTEM: 'Sistema',
};

/** Fuentes que se ofrecen como filtro del historial de ventas. */
export const SALE_SOURCE_FILTERS: readonly MovementSourceExt[] = [
  'MANUAL',
  'POS_GONDOLIA',
  'POS',
  'CSV',
  'IMPORT',
];

/** `MovementType` más `SALE_VOID`, que falta en el tipo compartido. */
export type MovementTypeExt = MovementType | 'SALE_VOID';

export const MOVEMENT_TYPES_EXT: readonly MovementTypeExt[] = [
  'ENTRY',
  'SALE',
  'SALE_VOID',
  'ADJUSTMENT_IN',
  'ADJUSTMENT_OUT',
  'WASTE_EXPIRED',
  'WASTE_DAMAGED',
  'RECALL_REMOVAL',
  'TRANSFER_OUT',
  'TRANSFER_IN',
];

/** Espejo de `MovementLabels.type` del backend. */
export const MOVEMENT_TYPE_LABELS_EXT: Record<MovementTypeExt, string> = {
  ENTRY: 'Ingreso',
  SALE: 'Venta',
  SALE_VOID: 'Anulación de venta',
  ADJUSTMENT_IN: 'Ajuste positivo',
  ADJUSTMENT_OUT: 'Ajuste negativo',
  WASTE_EXPIRED: 'Baja por vencimiento',
  WASTE_DAMAGED: 'Baja por daño',
  RECALL_REMOVAL: 'Retiro por recall',
  TRANSFER_OUT: 'Transferencia enviada',
  TRANSFER_IN: 'Transferencia recibida',
};

/** Tipos de ajuste que ofrece el diálogo de ajustes. */
export type AdjustmentType =
  | 'ADJUSTMENT_IN'
  | 'ADJUSTMENT_OUT'
  | 'WASTE_EXPIRED'
  | 'WASTE_DAMAGED'
  | 'RECALL_REMOVAL';

/** Los únicos que puede registrar un empleado (SPEC §3.3). */
export const EMPLOYEE_ADJUSTMENTS: readonly AdjustmentType[] = ['WASTE_EXPIRED', 'WASTE_DAMAGED'];

export const ADJUSTMENT_TYPES: readonly AdjustmentType[] = [
  'ADJUSTMENT_IN',
  'ADJUSTMENT_OUT',
  'WASTE_EXPIRED',
  'WASTE_DAMAGED',
  'RECALL_REMOVAL',
];

export const ADJUSTMENT_HINTS: Record<AdjustmentType, string> = {
  ADJUSTMENT_IN: 'Suma unidades al lote (corrección de conteo, devolución).',
  ADJUSTMENT_OUT: 'Descuenta unidades del lote (faltante, consumo interno).',
  WASTE_EXPIRED: 'Baja por vencimiento: la mercadería se tira.',
  WASTE_DAMAGED: 'Baja por daño o rotura.',
  RECALL_REMOVAL: 'Retiro de un lote alcanzado por un recall.',
};

// ---------------------------------------------------------------------------
// Ventas
// ---------------------------------------------------------------------------

export interface SaleItemRequest {
  productId: number;
  quantity: number;
  unitPrice?: number | null;
}

export interface SaleRequest {
  branchId?: number | null;
  items: SaleItemRequest[];
  occurredAt?: string | null;
}

export interface SaleLot {
  lotId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  quantity: number;
  unitPrice: number | null;
  discountPct: number | null;
}

export interface SaleLine {
  productId: number;
  productName: string;
  barcode: string | null;
  quantity: number;
  unitPrice: number | null;
  discountPct: number | null;
  total: number;
  shortage: number;
  lots: SaleLot[];
}

export interface Sale extends BranchScoped {
  batchRef: string;
  occurredAt: string;
  lines: SaleLine[];
  total: number;
  units: number;
  shortageUnits: number;
}

export interface SaleSummary extends BranchScoped {
  batchRef: string;
  occurredAt: string;
  itemsCount: number;
  units: number;
  total: number;
  source: MovementSourceExt;
  sourceLabel: string;
  userName: string | null;
  voided: boolean;
  voidedUnits: number;
  ticketCode: string | null;
  posSaleId: number | null;
}

export interface SaleDetail {
  sale: SaleSummary;
  lines: SaleLine[];
  grossTotal: number;
  reason: string | null;
}

export interface SalesListParams {
  from?: string;
  to?: string;
  source?: MovementSourceExt;
  q?: string;
  branchId?: number;
  page?: number;
  size?: number;
}

export interface SalesImportError {
  line: number;
  message: string;
}

export interface SalesImportResult {
  imported: number;
  skipped: number;
  units: number;
  total: number;
  batchRefs: string[];
  errors: SalesImportError[];
}

// ---------------------------------------------------------------------------
// Movimientos
// ---------------------------------------------------------------------------

export interface Movement extends BranchScoped {
  id: number;
  productId: number;
  productName: string;
  barcode: string | null;
  lotId: number | null;
  lotNumber: string | null;
  expiryDate: string | null;
  type: MovementTypeExt;
  typeLabel: string;
  quantity: number;
  signedQuantity: number;
  unitPrice: number | null;
  discountPct: number | null;
  totalAmount: number | null;
  source: MovementSourceExt;
  sourceLabel: string;
  batchRef: string | null;
  reason: string | null;
  userName: string | null;
  occurredAt: string;
}

export interface MovementsListParams {
  productId?: number;
  type?: MovementTypeExt;
  source?: MovementSourceExt;
  q?: string;
  from?: string;
  to?: string;
  branchId?: number;
  page?: number;
  size?: number;
}

export interface AdjustmentRequest {
  lotId: number;
  type: AdjustmentType;
  quantity: number;
  reason?: string;
}

// ---------------------------------------------------------------------------
// Vencimientos
// ---------------------------------------------------------------------------

export type ExpirationBucketFilter = 'ALL' | 'EXPIRED' | 'CRITICAL' | 'WARNING' | 'UPCOMING';

/** Buckets reales que devuelve cada fila (sin `ALL`). */
export type ExpirationBucket = Exclude<ExpirationBucketFilter, 'ALL'>;

export const EXPIRATION_BUCKETS: readonly ExpirationBucket[] = ['EXPIRED', 'CRITICAL', 'WARNING', 'UPCOMING'];

export const EXPIRATION_BUCKET_LABELS: Record<ExpirationBucket, string> = {
  EXPIRED: 'Vencido',
  CRITICAL: 'Crítico',
  WARNING: 'Por vencer',
  UPCOMING: 'Próximo',
};

export interface ExpirationRow extends BranchScoped {
  lotId: number;
  productId: number;
  productName: string;
  barcode: string | null;
  categoryName: string | null;
  lotNumber: string | null;
  expiryDate: string;
  daysLeft: number;
  quantity: number;
  receivedAt: string;
  rotationRank: number | null;
  costValue: number;
  saleValue: number;
  bucket: ExpirationBucket;
  discountPct: number | null;
  status: LotStatus;
}

export interface ExpirationTotals {
  lots: number;
  units: number;
  costValue: number;
  saleValue: number;
}

export interface ExpirationSummary {
  expired: ExpirationTotals;
  critical: ExpirationTotals;
  warning: ExpirationTotals;
  upcoming: ExpirationTotals;
  criticalDays: number;
  warningDays: number;
  upcomingDays: number;
  asOf: string;
}

export interface ExpirationsListParams {
  bucket?: ExpirationBucketFilter;
  q?: string;
  branchId?: number;
  page?: number;
  size?: number;
}

export interface DiscardRequest {
  quantity?: number | null;
  reason?: string;
}

export interface BulkDiscardResult {
  lots: number;
  units: number;
  costValue: number;
  failed: number;
}

/** El bucket del backend se traduce al de `ExpiryChip` (`@/api/types`). */
export function toExpiryBucket(bucket: ExpirationBucket): ExpiryBucket {
  return bucket;
}

// ---------------------------------------------------------------------------
// Transferencias
// ---------------------------------------------------------------------------

export interface TransferItemRequest {
  lotId: number;
  quantity: number;
}

export interface TransferRequest {
  fromBranchId: number;
  toBranchId: number;
  items: TransferItemRequest[];
  note?: string;
}

export interface RecallInfo {
  announcementId: number;
  title: string;
  reason: string | null;
  instructions: string | null;
  allLots: boolean;
}

export interface TransferItem {
  lotId: number;
  destinationLotId: number | null;
  productId: number;
  productName: string;
  barcode: string | null;
  lotNumber: string | null;
  expiryDate: string | null;
  quantity: number;
  costPrice: number | null;
  quarantined: boolean;
}

export interface Transfer {
  batchRef: string;
  fromBranchId: number | null;
  fromBranchName: string;
  toBranchId: number | null;
  toBranchName: string;
  occurredAt: string;
  items: TransferItem[];
  units: number;
  costValue: number;
  note: string | null;
  userName: string | null;
  recalls: RecallInfo[];
}

export interface TransferSummary {
  batchRef: string;
  fromBranchId: number | null;
  fromBranchName: string;
  toBranchId: number | null;
  toBranchName: string;
  occurredAt: string;
  itemsCount: number;
  units: number;
  costValue: number;
  userName: string | null;
  note: string | null;
}

export interface TransferableLot extends BranchScoped {
  lotId: number;
  productId: number;
  productName: string;
  barcode: string | null;
  lotNumber: string | null;
  expiryDate: string | null;
  daysLeft: number | null;
  quantity: number;
  receivedAt: string;
  rotationRank: number;
  costPrice: number | null;
  discountPct: number | null;
}

// ---------------------------------------------------------------------------
// Integración con el POS propio
// ---------------------------------------------------------------------------

export interface PosIntegration {
  branchId: number;
  branchName: string;
  branchCode: string | null;
  configured: boolean;
  prefix: string | null;
  createdAt: string | null;
  lastSaleAt: string | null;
  salesLast24h: number;
  unitsLast24h: number;
}

export interface PosApiKey {
  branchId: number;
  branchName: string;
  apiKey: string;
  prefix: string;
  createdAt: string;
}

export interface SimulateResult {
  branchId: number;
  branchName: string;
  sales: number;
  lines: number;
  units: number;
  total: number;
  shortages: number;
  batchRefs: string[];
}
