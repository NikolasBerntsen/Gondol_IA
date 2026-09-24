/**
 * Tipos del POS GondolIA (módulo H, SPEC §15). Espejo de los DTO de `com.gondolia.pos.dto`.
 * Los enums propios del POS todavía no viven en `@/api/types`, así que se definen acá.
 */
import type { ProductUnit } from '@/api/types';

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

export type PaymentMethod = 'CASH' | 'DEBIT' | 'CREDIT' | 'TRANSFER' | 'QR';
export type CashMovementType = 'CASH_IN' | 'CASH_OUT';
export type PosSessionStatus = 'OPEN' | 'CLOSED';
export type PosSaleStatus = 'COMPLETED' | 'VOIDED';

export const PAYMENT_METHODS: readonly PaymentMethod[] = ['CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'QR'];

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Efectivo',
  DEBIT: 'Débito',
  CREDIT: 'Crédito',
  TRANSFER: 'Transferencia',
  QR: 'QR',
};

export const CASH_MOVEMENT_TYPE_LABELS: Record<CashMovementType, string> = {
  CASH_IN: 'Ingreso',
  CASH_OUT: 'Retiro',
};

export const POS_SESSION_STATUS_LABELS: Record<PosSessionStatus, string> = {
  OPEN: 'Abierto',
  CLOSED: 'Cerrado',
};

export const POS_SALE_STATUS_LABELS: Record<PosSaleStatus, string> = {
  COMPLETED: 'Cobrada',
  VOIDED: 'Anulada',
};

/** Motivos sugeridos de un movimiento de efectivo (el cajero igual puede escribir el suyo). */
export const CASH_OUT_REASONS = [
  'Pago a proveedor',
  'Depósito en banco',
  'Cambio para otra caja',
  'Otro',
] as const;

export const CASH_IN_REASONS = ['Cambio desde otra caja', 'Refuerzo de caja', 'Otro'] as const;

// ---------------------------------------------------------------------------
// Cajas
// ---------------------------------------------------------------------------

export interface PosOpenSessionRef {
  id: number;
  openedById: number;
  openedByName: string | null;
  openedAt: string;
  mine: boolean;
}

export interface PosRegister {
  id: number;
  branchId: number;
  branchName: string | null;
  name: string;
  active: boolean;
  createdAt: string;
  openSession: PosOpenSessionRef | null;
}

export interface PosRegisterRequest {
  branchId?: number | null;
  name: string;
  active?: boolean | null;
}

// ---------------------------------------------------------------------------
// Turnos
// ---------------------------------------------------------------------------

export interface PosCashMovement {
  id: number;
  type: CashMovementType;
  amount: number;
  reason: string;
  userName: string | null;
  createdAt: string;
}

export interface PosTopProduct {
  productId: number | null;
  productName: string;
  units: number;
  total: number;
}

/** Reporte Z de un turno (SPEC §15.2). */
export interface PosSessionReport {
  id: number;
  branchId: number;
  branchName: string | null;
  registerId: number;
  registerName: string | null;
  status: PosSessionStatus;
  openedById: number;
  openedByName: string | null;
  closedByName: string | null;
  openedAt: string;
  closedAt: string | null;
  openingCash: number;
  totalsByMethod: Partial<Record<PaymentMethod, number>>;
  cashIn: number;
  cashOut: number;
  changeGiven: number;
  expectedCash: number;
  countedCash: number | null;
  difference: number | null;
  salesCount: number;
  salesTotal: number;
  units: number;
  voidedCount: number;
  voidedTotal: number;
  topProducts: PosTopProduct[];
  cashMovements: PosCashMovement[];
  mine: boolean;
  closingNote: string | null;
  /** El turno se cerró sin ninguna venta vigente (el cajero confirmó el aviso). `false` mientras sigue abierto. */
  closedWithoutSales: boolean;
}

export interface PosSessionSummary {
  id: number;
  branchId: number;
  branchName: string | null;
  registerId: number;
  registerName: string | null;
  status: PosSessionStatus;
  openedById: number;
  openedByName: string | null;
  closedByName: string | null;
  openedAt: string;
  closedAt: string | null;
  openingCash: number;
  expectedCash: number | null;
  countedCash: number | null;
  difference: number | null;
  salesCount: number;
  salesTotal: number;
  voidedCount: number;
  voidedTotal: number;
  mine: boolean;
  /** El turno se cerró sin ninguna venta vigente. */
  closedWithoutSales: boolean;
}

export interface OpenSessionRequest {
  registerId: number;
  openingCash: number;
}

export interface CashMovementRequest {
  type: CashMovementType;
  amount: number;
  reason: string;
}

export interface CloseSessionRequest {
  countedCash: number;
  note?: string | null;
}

export interface PosSessionListParams {
  status?: PosSessionStatus;
  from?: string;
  to?: string;
  mine?: boolean;
  page?: number;
  size?: number;
}

// ---------------------------------------------------------------------------
// Productos del mostrador
// ---------------------------------------------------------------------------

/** Próximo lote a consumir del producto en la sucursal (orden de rotación, SPEC §4.2). */
export interface PosLotRef {
  lotId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  discountPct: number | null;
  unitPrice: number;
}

/**
 * Tramo de precio: `quantity` unidades a `unitPrice` (con el descuento del lote ya aplicado), en el orden en el
 * que se venden (liquidación primero, después FIFO/FEFO). Lo que pase de la suma de los tramos es faltante y se
 * cobra a precio de lista.
 */
export interface PosPriceTier {
  quantity: number;
  discountPct: number | null;
  unitPrice: number;
}

/**
 * Recall publicado del producto. Los lotes cargados ya pasaron por el chequeo de recall y se venden; lo que no se
 * puede vender son unidades sin lote registrado (faltante), porque podrían ser del lote retirado.
 */
export interface PosRecallRef {
  announcementId: number;
  title: string;
  allLots: boolean;
  lotNumbers: string[];
}

export interface PosProduct {
  productId: number;
  barcode: string | null;
  name: string;
  brand: string | null;
  categoryId: number | null;
  categoryName: string | null;
  unit: ProductUnit;
  listPrice: number;
  sellableStock: number;
  nextLot: PosLotRef | null;
  priceTiers: PosPriceTier[];
  hasRecalledStock: boolean;
  activeRecall: PosRecallRef | null;
  hasExpiredStock: boolean;
  outOfStock: boolean;
  branchId: number;
  branchName: string | null;
}

export interface PosCategory {
  id: number;
  name: string;
  productCount: number;
}

// ---------------------------------------------------------------------------
// Ventas
// ---------------------------------------------------------------------------

export interface PosSaleLot {
  lotId: number | null;
  lotNumber: string | null;
  expiryDate: string | null;
  quantity: number;
  unitPrice: number;
  discountPct: number | null;
}

export interface PosSaleItem {
  id: number;
  productId: number;
  barcode: string | null;
  productName: string;
  quantity: number;
  listUnitPrice: number;
  unitPrice: number;
  discountAmount: number;
  lineTotal: number;
  shortageQuantity: number;
  lots: PosSaleLot[];
}

export interface PosPayment {
  id: number;
  method: PaymentMethod;
  label: string;
  amount: number;
  reference: string | null;
}

export interface PosSale {
  id: number;
  ticketCode: string;
  number: number;
  branchId: number;
  branchName: string | null;
  registerId: number;
  registerName: string | null;
  sessionId: number;
  status: PosSaleStatus;
  subtotal: number;
  discountTotal: number;
  total: number;
  itemsCount: number;
  units: number;
  paidTotal: number;
  changeAmount: number;
  customerName: string | null;
  customerDoc: string | null;
  cashierId: number;
  cashierName: string | null;
  batchRef: string;
  hasShortage: boolean;
  createdAt: string;
  voidedAt: string | null;
  voidedByName: string | null;
  voidReason: string | null;
  items: PosSaleItem[];
  payments: PosPayment[];
}

export interface PosSaleSummary {
  id: number;
  ticketCode: string;
  branchId: number;
  branchName: string | null;
  registerId: number;
  registerName: string | null;
  sessionId: number;
  status: PosSaleStatus;
  total: number;
  itemsCount: number;
  units: number;
  cashierName: string | null;
  createdAt: string;
  hasShortage: boolean;
  voidedAt: string | null;
  voidedByName: string | null;
  voidReason: string | null;
  paymentMethods: PaymentMethod[];
}

export interface PosSaleRequest {
  sessionId: number;
  items: Array<{ productId: number; quantity: number }>;
  payments: Array<{ method: PaymentMethod; amount: number; reference?: string | null }>;
  customerName?: string | null;
  customerDoc?: string | null;
  allowShortage: boolean;
}

export interface PosSaleListParams {
  sessionId?: number;
  status?: PosSaleStatus;
  from?: string;
  to?: string;
  q?: string;
  mine?: boolean;
  page?: number;
  size?: number;
}

/** Detalle por producto del 409 `INSUFFICIENT_STOCK` (SPEC §15.2). */
export interface PosShortageDetail {
  productId: number;
  productName: string;
  requested: number;
  available: number;
}

// ---------------------------------------------------------------------------
// Ticket
// ---------------------------------------------------------------------------

export interface PosTicketItem {
  name: string;
  quantity: number;
  unitPrice: number;
  listPrice: number | null;
  lotNumber: string | null;
  expiryDate: string | null;
  discountPct: number | null;
  lineTotal: number;
}

export interface PosTicketPayment {
  method: PaymentMethod;
  label: string;
  amount: number;
  reference: string | null;
}

export interface PosTicket {
  saleId: number;
  store: string;
  taxId: string | null;
  branch: string | null;
  address: string | null;
  ticketCode: string;
  dateTime: string;
  registerName: string | null;
  cashierName: string | null;
  customerName: string | null;
  customerDoc: string | null;
  items: PosTicketItem[];
  payments: PosTicketPayment[];
  change: number;
  subtotal: number;
  discountTotal: number;
  total: number;
  units: number;
  status: PosSaleStatus;
  voidedAt: string | null;
  voidReason: string | null;
  legend: string;
}

// ---------------------------------------------------------------------------
// Estadísticas
// ---------------------------------------------------------------------------

export interface PosStats {
  days: number;
  from: string;
  to: string;
  salesCount: number;
  salesTotal: number;
  units: number;
  averageTicket: number;
  voidedCount: number;
  voidedTotal: number;
  byMethod: Array<{ method: PaymentMethod; label: string; total: number; sales: number; sharePct: number }>;
  byHour: Array<{ hour: number; sales: number; total: number }>;
  byCashier: Array<{
    userId: number;
    name: string | null;
    sales: number;
    total: number;
    averageTicket: number;
    voided: number;
  }>;
  topProducts: PosTopProduct[];
}
