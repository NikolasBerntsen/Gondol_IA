import { apiGet, apiPost, downloadFile, uploadFile } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type {
  AdjustmentRequest,
  BulkDiscardResult,
  DiscardRequest,
  ExpirationRow,
  ExpirationSummary,
  ExpirationsListParams,
  Movement,
  MovementsListParams,
  PosApiKey,
  PosIntegration,
  Sale,
  SaleDetail,
  SaleSummary,
  SalesImportResult,
  SalesListParams,
  SimulateResult,
  Transfer,
  TransferRequest,
  TransferSummary,
  TransferableLot,
} from './types';

// ---------------------------------------------------------------------------
// Query keys (SPEC §9.6: la sucursal va al final, con useBranchQueryKey)
// ---------------------------------------------------------------------------

export const movementKeys = {
  sales: ['sales'] as const,
  salesList: (params: SalesListParams) => ['sales', 'list', params] as const,
  saleDetail: (batchRef: string) => ['sales', 'detail', batchRef] as const,
  movements: ['movements'] as const,
  movementsList: (params: MovementsListParams) => ['movements', 'list', params] as const,
  expirations: ['expirations'] as const,
  expirationsList: (params: ExpirationsListParams) => ['expirations', 'list', params] as const,
  expirationsSummary: ['expirations', 'summary'] as const,
  transfers: ['transfers'] as const,
  transfersList: (params: { from?: string; to?: string; page?: number }) =>
    ['transfers', 'list', params] as const,
  transferDetail: (batchRef: string) => ['transfers', 'detail', batchRef] as const,
  transferLots: (branchId: number | null, q: string) => ['transfers', 'lots', branchId, q] as const,
  integrations: ['pos-integrations'] as const,
};

// ---------------------------------------------------------------------------
// Ventas
// ---------------------------------------------------------------------------

export const salesApi = {
  register: (body: { branchId?: number | null; items: Array<{ productId: number; quantity: number; unitPrice?: number | null }>; occurredAt?: string | null }) =>
    apiPost<Sale>('/tenant/sales', body),
  list: (params: SalesListParams) => apiGet<PageResponse<SaleSummary>>('/tenant/sales', params),
  detail: (batchRef: string) => apiGet<SaleDetail>(`/tenant/sales/${encodeURIComponent(batchRef)}`),
  importCsv: (file: Blob, branchId: number | null, fileName: string) =>
    uploadFile<SalesImportResult>('/tenant/sales/import', file, {
      fileName,
      fields: branchId == null ? undefined : { branchId: String(branchId) },
    }),
  downloadTemplate: () => downloadFile('/tenant/sales/import/template', 'ventas-plantilla.csv'),
};

// ---------------------------------------------------------------------------
// Movimientos
// ---------------------------------------------------------------------------

export const movementsApi = {
  list: (params: MovementsListParams) => apiGet<PageResponse<Movement>>('/tenant/movements', params),
  adjust: (body: AdjustmentRequest) => apiPost<Movement>('/tenant/movements/adjustments', body),
};

// ---------------------------------------------------------------------------
// Vencimientos
// ---------------------------------------------------------------------------

export const expirationsApi = {
  list: (params: ExpirationsListParams) => apiGet<PageResponse<ExpirationRow>>('/tenant/expirations', params),
  summary: (branchId?: number) => apiGet<ExpirationSummary>('/tenant/expirations/summary', { branchId }),
  discard: (lotId: number, body: DiscardRequest) =>
    apiPost<Movement>(`/tenant/expirations/${lotId}/discard`, body),
  discardAllExpired: (body: { branchId?: number | null; reason?: string }) =>
    apiPost<BulkDiscardResult>('/tenant/expirations/discard-expired', body),
};

// ---------------------------------------------------------------------------
// Transferencias
// ---------------------------------------------------------------------------

export const transfersApi = {
  create: (body: TransferRequest) => apiPost<Transfer>('/tenant/transfers', body),
  list: (params: { from?: string; to?: string; branchId?: number; page?: number; size?: number }) =>
    apiGet<PageResponse<TransferSummary>>('/tenant/transfers', params),
  detail: (batchRef: string) => apiGet<Transfer>(`/tenant/transfers/${encodeURIComponent(batchRef)}`),
  availableLots: (params: { branchId: number; q?: string; page?: number; size?: number }) =>
    apiGet<PageResponse<TransferableLot>>('/tenant/transfers/available-lots', params),
};

// ---------------------------------------------------------------------------
// Integración con el POS propio
// ---------------------------------------------------------------------------

export const posIntegrationApi = {
  status: () => apiGet<PosIntegration[]>('/tenant/integrations/pos'),
  generateKey: (branchId: number) => apiPost<PosApiKey>(`/tenant/integrations/pos/${branchId}/key`),
  simulate: (branchId: number, sales: number) =>
    apiPost<SimulateResult>(`/tenant/integrations/pos/${branchId}/simulate`, { sales }),
};

// ---------------------------------------------------------------------------
// Catálogo mínimo para el buscador de la venta manual (endpoint del módulo A1)
// ---------------------------------------------------------------------------

/** Fila del catálogo que necesita el carrito de la venta manual. */
export interface ProductPick {
  id: number;
  barcode: string | null;
  name: string;
  brand: string | null;
  unit: string;
  salePrice: number;
  sellableStock: number;
  stockStatus: 'OK' | 'LOW' | 'OUT';
  stockByBranch?: Array<{ branchId: number; branchName: string; sellableStock: number }>;
}

export const productPickApi = {
  search: (q: string, size = 12) =>
    apiGet<PageResponse<ProductPick>>('/tenant/products', { q, size, active: true, sort: 'name,asc' }),
};

// ---------------------------------------------------------------------------
// Lotes del catálogo (endpoint del módulo A1, SPEC §6.3) — los usa el diálogo de ajustes
// ---------------------------------------------------------------------------

/** Lote tal como lo devuelve `GET /api/tenant/lots` (SPEC §6.3). */
export interface LotPick {
  id: number;
  branchId: number;
  branchName: string;
  productId: number;
  lotNumber: string | null;
  expiryDate: string | null;
  daysToExpiry: number | null;
  initialQuantity: number;
  quantity: number;
  costPrice: number | null;
  receivedAt: string;
  status: 'ACTIVE' | 'DEPLETED' | 'EXPIRED_DISCARDED' | 'RECALLED';
  discountPct: number | null;
  expiryBucket: 'EXPIRED' | 'CRITICAL' | 'WARNING' | 'UPCOMING' | 'OK' | null;
  rotationRank: number | null;
}

export const lotPickApi = {
  byProduct: (productId: number) =>
    apiGet<LotPick[]>('/tenant/lots', { productId, includeEmpty: true }),
};
