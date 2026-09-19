import { apiGet, apiPost } from '@/api/client';
import type { BranchScope, PageResponse } from '@/api/types';
import type {
  AnnouncementDetail,
  AnnouncementListItem,
  AnnouncementListParams,
  CreateAnnouncementBody,
  RecallMatch,
  RecallMatchFilter,
  RecallPreview,
  RecallPreviewBody,
  RecallReach,
  ResolveRecallBody,
  TenantAnnouncement,
} from './types';

/** Avisos y recalls de la consola de dueños (SPEC §6.7). */
export const ownerAnnouncementsApi = {
  list: (params: AnnouncementListParams) =>
    apiGet<PageResponse<AnnouncementListItem>>('/platform/announcements', params),
  get: (id: number) => apiGet<AnnouncementDetail>(`/platform/announcements/${id}`),
  create: (body: CreateAnnouncementBody) => apiPost<AnnouncementDetail>('/platform/announcements', body),
  archive: (id: number) => apiPost<AnnouncementDetail>(`/platform/announcements/${id}/archive`),
  preview: (body: RecallPreviewBody) =>
    apiPost<RecallPreview>('/platform/announcements/recall-preview', body),
  /**
   * Comercios **distintos** alcanzados por los recalls activos. Sale de las métricas de la plataforma (SPEC §6.6
   * `recalls.affectedTenantsTotal`): sumar `affectedTenantsCount` de cada recall cuenta dos veces al comercio que
   * coincide con más de uno, y la consola no conoce qué comercios son (§3.4.3) para deduplicarlos.
   */
  recallReach: () =>
    apiGet<{ recalls: RecallReach }>('/platform/metrics').then((metrics) => metrics.recalls),
};

/** Bandeja de avisos del comercio. */
export const noticesApi = {
  list: (params: { page?: number; size?: number }) =>
    apiGet<PageResponse<TenantAnnouncement>>('/tenant/announcements', params),
  unreadCount: () => apiGet<{ count: number }>('/tenant/announcements/unread-count'),
  markRead: (id: number) => apiPost<void>(`/tenant/announcements/${id}/read`),
};

/** Seguridad alimentaria: coincidencias de recall de las sucursales del alcance. */
export const recallsApi = {
  /**
   * Coincidencias del alcance elegido en el topbar o, con `branch`, de ese alcance (`'all'` = todas las sucursales
   * accesibles, SPEC §3.5). La alerta de seguridad y los links a una coincidencia puntual usan `'all'`: una alerta de
   * otra sucursal del usuario no puede depender de qué sucursal tiene elegida.
   */
  list: (status: RecallMatchFilter, branch?: BranchScope) =>
    apiGet<RecallMatch[]>('/tenant/recall-matches', { status }, branch === undefined ? undefined : { branch }),
  acknowledge: (id: number) => apiPost<RecallMatch>(`/tenant/recall-matches/${id}/acknowledge`),
  resolve: (id: number, body: ResolveRecallBody) =>
    apiPost<RecallMatch>(`/tenant/recall-matches/${id}/resolve`, body),
};

/** Query keys del módulo (SPEC §9.6: las de datos por sucursal llevan el segmento de sucursal al final). */
export const announcementKeys = {
  owner: ['owner-announcements'] as const,
  ownerList: (params: AnnouncementListParams) => ['owner-announcements', 'list', params] as const,
  ownerDetail: (id: number) => ['owner-announcements', 'detail', id] as const,
  notices: ['notices'] as const,
  noticesList: (page: number) => ['notices', 'list', page] as const,
  noticesUnread: ['notices', 'unread'] as const,
  recalls: ['recall-matches'] as const,
  /** Coincidencias de todas las sucursales accesibles, sin importar la elegida (sin segmento de sucursal). */
  recallsAllBranches: ['recall-matches', 'all-branches'] as const,
  recallReach: ['owner-announcements', 'recall-reach'] as const,
};
