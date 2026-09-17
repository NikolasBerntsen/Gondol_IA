// Tipos del módulo A1 — Catálogo y carga de mercadería (SPEC §6.3, docs/api-a1.md).
import type {
  ExpiryBucket,
  LotStatus,
  MovementSource,
  MovementType,
  PageParams,
  ProductUnit,
  StockStatus,
} from '@/api/types';

// ---------------------------------------------------------------------------
// Categorías y proveedores
// ---------------------------------------------------------------------------

export interface CategoryDto {
  id: number;
  name: string;
  productCount: number;
}

export interface CategoryRequest {
  name: string;
}

export interface SupplierDto {
  id: number;
  name: string;
  contactName: string | null;
  phone: string | null;
  email: string | null;
  leadTimeDays: number | null;
  notes: string | null;
  active: boolean;
  productCount: number;
}

export interface SupplierRequest {
  name: string;
  contactName?: string | null;
  phone?: string | null;
  email?: string | null;
  leadTimeDays?: number | null;
  notes?: string | null;
  active?: boolean;
}

// ---------------------------------------------------------------------------
// Productos
// ---------------------------------------------------------------------------

/** Stock vendible del producto en una sucursal del alcance. */
export interface BranchStock {
  branchId: number;
  branchName: string;
  sellableStock: number;
  stockStatus: StockStatus;
}

export interface ProductListItem {
  id: number;
  barcode: string | null;
  name: string;
  brand: string | null;
  categoryId: number | null;
  categoryName: string | null;
  unit: ProductUnit;
  costPrice: number;
  salePrice: number;
  minStock: number;
  perishable: boolean;
  active: boolean;
  /** Suma del alcance de sucursales. */
  sellableStock: number;
  expiredStock: number;
  quarantinedStock: number;
  nextExpiryDate: string | null;
  lotsCount: number;
  /** Peor estado entre las sucursales del alcance. */
  stockStatus: StockStatus;
  stockByBranch: BranchStock[];
}

export interface ProductDetail extends Omit<ProductListItem, 'stockByBranch'> {
  description: string | null;
  supplierId: number | null;
  supplierName: string | null;
  stockByBranch: BranchStock[];
  lots: LotDto[];
  createdAt: string;
  updatedAt: string;
}

export interface ProductRequest {
  barcode?: string | null;
  name: string;
  brand?: string | null;
  description?: string | null;
  categoryId?: number | null;
  /** Crea la categoría si no existe (solo se usa cuando no viene `categoryId`). */
  categoryName?: string | null;
  supplierId?: number | null;
  unit?: ProductUnit;
  costPrice?: number | null;
  salePrice?: number | null;
  minStock?: number | null;
  perishable?: boolean;
  active?: boolean;
}

/** Filtro por estado de stock del inventario. */
export type ProductStockFilter = 'ALL' | StockStatus | 'EXPIRING';

export interface ProductListParams extends PageParams {
  q?: string;
  categoryId?: number;
  stockStatus?: ProductStockFilter;
  active?: boolean;
}

// ---------------------------------------------------------------------------
// Lotes
// ---------------------------------------------------------------------------

export interface LotDto {
  id: number;
  branchId: number;
  branchName: string;
  productId: number;
  productName: string | null;
  lotNumber: string | null;
  expiryDate: string | null;
  daysToExpiry: number | null;
  initialQuantity: number;
  quantity: number;
  costPrice: number | null;
  receivedAt: string;
  status: LotStatus;
  source: MovementSource;
  discountPct: number | null;
  supplierId: number | null;
  supplierName: string | null;
  expiryBucket: ExpiryBucket;
  originLotId: number | null;
  /** 1 = se vende primero en su sucursal; `null` si el lote ya no es vendible. */
  rotationRank: number | null;
}

/** Origen declarado por la pantalla de carga (SPEC §6.3). */
export type IntakeSource = 'MANUAL' | 'SCAN' | 'OCR';

export interface LotRequest {
  branchId?: number | null;
  productId: number;
  lotNumber?: string | null;
  expiryDate?: string | null;
  quantity: number;
  costPrice?: number | null;
  supplierId?: number | null;
  source?: IntakeSource;
}

export interface LotUpdateRequest {
  lotNumber?: string | null;
  expiryDate?: string | null;
}

/** Recall que alcanza a un código/lote (`RecallMatchingService.RecallInfo`). */
export interface RecallInfo {
  announcementId: number;
  title: string;
  reason: string | null;
  instructions: string | null;
  allLots: boolean;
}

export interface ReceiveLotResponse {
  lot: LotDto;
  /** El lote quedó en cuarentena por un recall: no se puede vender. */
  quarantined: boolean;
  recalls: RecallInfo[];
  /** Aviso de FIFO cuando el lote nuevo vence antes que mercadería más vieja. */
  rotationWarning: string | null;
  existingLots: LotDto[];
}

export interface RecallCheckResponse {
  recalled: boolean;
  recalls: RecallInfo[];
}

// ---------------------------------------------------------------------------
// Movimientos recientes (ficha del producto)
// ---------------------------------------------------------------------------

export interface ProductMovement {
  id: number;
  branchId: number;
  branchName: string;
  lotId: number | null;
  lotNumber: string | null;
  type: MovementType | 'SALE_VOID';
  quantity: number;
  unitPrice: number | null;
  discountPct: number | null;
  totalAmount: number | null;
  source: MovementSource | 'POS_GONDOLIA' | 'IMPORT';
  batchRef: string | null;
  reason: string | null;
  userName: string | null;
  occurredAt: string;
}

// ---------------------------------------------------------------------------
// Autocompletado y lectura con cámara
// ---------------------------------------------------------------------------

export interface BarcodeLookupResponse {
  found: boolean;
  source: string | null;
  barcode: string;
  name: string | null;
  brand: string | null;
  quantity: string | null;
  categoryHint: string | null;
  imageUrl: string | null;
}

export interface OcrDateCandidate {
  value: string;
  raw: string;
  confidence: number;
}

export interface OcrLotCandidate {
  value: string;
  raw: string;
  confidence: number;
}

export interface OcrBarcode {
  value: string;
  format: string | null;
}

export interface OcrLabelResponse {
  text: string | null;
  lines: string[];
  expiryDates: OcrDateCandidate[];
  lotNumbers: OcrLotCandidate[];
  manufactureDates: OcrDateCandidate[];
  barcodes: OcrBarcode[];
  productNameCandidates: string[];
  processingMs: number | null;
  matchedProduct: ProductListItem | null;
}

export interface OcrBarcodeResponse {
  barcodes: OcrBarcode[];
}

/** Respuesta de las bajas del catálogo: `true` cuando quedó desactivado en vez de eliminado. */
export interface DeleteResult {
  deactivated: boolean;
}
