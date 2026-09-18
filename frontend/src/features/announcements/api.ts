import { apiGet, apiPost } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type {
  AnnouncementDetail,
  AnnouncementListItem,
  AnnouncementListParams,
  CreateAnnouncementBody,
  RecallMatch,
  RecallMatchFilter,
  RecallPreview,
  RecallPreviewBody,
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
  list: (status: RecallMatchFilter) => apiGet<RecallMatch[]>('/tenant/recall-matches', { status }),
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
};
