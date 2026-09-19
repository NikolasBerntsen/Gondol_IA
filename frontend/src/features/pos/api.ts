/** Llamadas y query keys del POS GondolIA (módulo H, SPEC §15.2). */
import { apiGet, apiPost, apiPut } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type {
  CashMovementRequest,
  CloseSessionRequest,
  OpenSessionRequest,
  PosCategory,
  PosProduct,
  PosRegister,
  PosRegisterRequest,
  PosSale,
  PosSaleListParams,
  PosSaleRequest,
  PosSaleSummary,
  PosSessionListParams,
  PosSessionReport,
  PosSessionSummary,
  PosStats,
  PosTicket,
} from './types';

const BASE = '/tenant/pos';

export const posApi = {
  // cajas
  registers: (includeInactive = false) =>
    apiGet<PosRegister[]>(`${BASE}/registers`, { includeInactive }),
  createRegister: (body: PosRegisterRequest) => apiPost<PosRegister>(`${BASE}/registers`, body),
  updateRegister: (id: number, body: PosRegisterRequest) =>
    apiPut<PosRegister>(`${BASE}/registers/${id}`, body),

  // turnos
  currentSession: () => apiGet<PosSessionReport | null>(`${BASE}/sessions/current`),
  openSession: (body: OpenSessionRequest) => apiPost<PosSessionReport>(`${BASE}/sessions/open`, body),
  cashMovement: (sessionId: number, body: CashMovementRequest) =>
    apiPost<PosSessionReport>(`${BASE}/sessions/${sessionId}/cash-movements`, body),
  closeSession: (sessionId: number, body: CloseSessionRequest) =>
    apiPost<PosSessionReport>(`${BASE}/sessions/${sessionId}/close`, body),
  sessions: (params: PosSessionListParams) =>
    apiGet<PageResponse<PosSessionSummary>>(`${BASE}/sessions`, params),
  session: (id: number) => apiGet<PosSessionReport>(`${BASE}/sessions/${id}`),

  // productos del mostrador
  lookup: (code: string, branchId?: number | null) =>
    apiGet<PosProduct>(`${BASE}/products/lookup`, { code, branchId }),
  search: (params: { q?: string; categoryId?: number | null; branchId?: number | null; limit?: number }) =>
    apiGet<PosProduct[]>(`${BASE}/products/search`, params),
  /** Productos del carrito al día (stock, tramos de precio y recalls) antes de cobrar. */
  byIds: (ids: number[], branchId?: number | null) =>
    apiGet<PosProduct[]>(`${BASE}/products`, { ids: ids.join(','), branchId }),
  categories: (branchId?: number | null) =>
    apiGet<PosCategory[]>(`${BASE}/products/categories`, { branchId }),

  // ventas
  createSale: (body: PosSaleRequest) => apiPost<PosSale>(`${BASE}/sales`, body),
  sales: (params: PosSaleListParams) => apiGet<PageResponse<PosSaleSummary>>(`${BASE}/sales`, params),
  sale: (id: number) => apiGet<PosSale>(`${BASE}/sales/${id}`),
  ticket: (id: number) => apiGet<PosTicket>(`${BASE}/sales/${id}/ticket`),
  voidSale: (id: number, reason: string) => apiPost<PosSale>(`${BASE}/sales/${id}/void`, { reason }),

  // estadísticas
  stats: (days: number) => apiGet<PosStats>(`${BASE}/stats`, { days }),
};

/**
 * Query keys del POS. Todo lo que depende de la sucursal se arma con `useBranchQueryKey`
 * (la fundación agrega el segmento `{ branch }` al final).
 */
export const posKeys = {
  registers: (includeInactive: boolean) => ['pos', 'registers', { includeInactive }] as const,
  currentSession: () => ['pos', 'sessions', 'current'] as const,
  sessions: (params: PosSessionListParams) => ['pos', 'sessions', 'list', params] as const,
  session: (id: number) => ['pos', 'sessions', 'detail', id] as const,
  search: (params: { q: string; categoryId: number | null }) => ['pos', 'products', 'search', params] as const,
  categories: () => ['pos', 'products', 'categories'] as const,
  sales: (params: PosSaleListParams) => ['pos', 'sales', 'list', params] as const,
  sale: (id: number) => ['pos', 'sales', 'detail', id] as const,
  ticket: (id: number) => ['pos', 'sales', 'ticket', id] as const,
  stats: (days: number) => ['pos', 'stats', { days }] as const,
};
