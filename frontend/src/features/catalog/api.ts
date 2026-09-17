// Llamadas y query keys del módulo A1 — Catálogo y carga de mercadería (docs/api-a1.md).
import { apiDelete, apiGet, apiPost, apiPut, uploadFile } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type {
  BarcodeLookupResponse,
  CategoryDto,
  CategoryRequest,
  DeleteResult,
  LotDto,
  LotRequest,
  LotUpdateRequest,
  OcrBarcodeResponse,
  OcrLabelResponse,
  ProductDetail,
  ProductListItem,
  ProductListParams,
  ProductMovement,
  ProductRequest,
  RecallCheckResponse,
  ReceiveLotResponse,
  SupplierDto,
  SupplierRequest,
} from './types';

/**
 * Keys de React Query. Las que dependen de la sucursal (productos, lotes, movimientos) se arman con
 * `useBranchQueryKey`, que agrega el segmento `{ branch }` al final.
 */
export const catalogKeys = {
  products: ['products'] as const,
  productList: (params: ProductListParams) => ['products', 'list', params] as const,
  productDetail: (id: number) => ['products', 'detail', id] as const,
  productByBarcode: (barcode: string) => ['products', 'barcode', barcode] as const,
  productMovements: (id: number) => ['products', 'movements', id] as const,
  lots: ['lots'] as const,
  lotList: (productId: number, includeEmpty: boolean) => ['lots', 'list', productId, includeEmpty] as const,
  categories: ['categories'] as const,
  suppliers: ['suppliers'] as const,
  recallCheck: (barcode: string, lotNumber: string, expiryDate: string) =>
    ['recalls', 'check', barcode, lotNumber, expiryDate] as const,
};

export const productsApi = {
  list: (params: ProductListParams) => apiGet<PageResponse<ProductListItem>>('/tenant/products', params),
  get: (id: number) => apiGet<ProductDetail>(`/tenant/products/${id}`),
  getByBarcode: (barcode: string) => apiGet<ProductDetail>(`/tenant/products/by-barcode/${encodeURIComponent(barcode)}`),
  movements: (id: number, limit = 12) =>
    apiGet<ProductMovement[]>(`/tenant/products/${id}/movements`, { limit }),
  create: (body: ProductRequest) => apiPost<ProductDetail>('/tenant/products', body),
  update: (id: number, body: ProductRequest) => apiPut<ProductDetail>(`/tenant/products/${id}`, body),
  remove: (id: number) => apiDelete<DeleteResult>(`/tenant/products/${id}`),
};

export const lotsApi = {
  list: (productId: number, includeEmpty = false) =>
    apiGet<LotDto[]>('/tenant/lots', { productId, includeEmpty }),
  receive: (body: LotRequest) => apiPost<ReceiveLotResponse>('/tenant/lots', body),
  update: (id: number, body: LotUpdateRequest) => apiPut<LotDto>(`/tenant/lots/${id}`, body),
};

export const categoriesApi = {
  list: () => apiGet<CategoryDto[]>('/tenant/categories'),
  create: (body: CategoryRequest) => apiPost<CategoryDto>('/tenant/categories', body),
  update: (id: number, body: CategoryRequest) => apiPut<CategoryDto>(`/tenant/categories/${id}`, body),
  remove: (id: number) => apiDelete(`/tenant/categories/${id}`),
};

export const suppliersApi = {
  list: (includeInactive = true) => apiGet<SupplierDto[]>('/tenant/suppliers', { includeInactive }),
  create: (body: SupplierRequest) => apiPost<SupplierDto>('/tenant/suppliers', body),
  update: (id: number, body: SupplierRequest) => apiPut<SupplierDto>(`/tenant/suppliers/${id}`, body),
  remove: (id: number) => apiDelete<DeleteResult>(`/tenant/suppliers/${id}`),
};

export const catalogLookupApi = {
  /** Open Food Facts: nunca falla, devuelve `found: false` si no hay datos. */
  byBarcode: (barcode: string) =>
    apiGet<BarcodeLookupResponse>(`/tenant/catalog/lookup/${encodeURIComponent(barcode)}`),
  /** Chequeo previo de recall antes de cargar un lote. */
  checkRecall: (params: { barcode: string; lotNumber?: string; expiryDate?: string }) =>
    apiGet<RecallCheckResponse>('/tenant/recalls/check', params),
};

export const ocrApi = {
  label: (photo: Blob) => uploadFile<OcrLabelResponse>('/tenant/ocr/label', photo, { fileName: 'etiqueta.jpg' }),
  barcode: (photo: Blob) => uploadFile<OcrBarcodeResponse>('/tenant/ocr/barcode', photo, { fileName: 'codigo.jpg' }),
};
