import { apiGet, apiPost } from './client';
import type { CountDto, NotificationDto, PageResponse, PresenceDto } from './types';

export interface NotificationListParams {
  unreadOnly?: boolean;
  page?: number;
  size?: number;
}

/** Query keys de notificaciones (son por usuario, no por sucursal). */
export const notificationKeys = {
  all: ['notifications'] as const,
  unreadCount: () => [...notificationKeys.all, 'unread-count'] as const,
  list: (params: NotificationListParams) => [...notificationKeys.all, 'list', params] as const,
};

export const notificationsApi = {
  list: (params: NotificationListParams = {}) =>
    apiGet<PageResponse<NotificationDto>>('/notifications', {
      unreadOnly: params.unreadOnly ?? false,
      page: params.page ?? 0,
      size: params.size ?? 20,
    }),

  unreadCount: () => apiGet<CountDto>('/notifications/unread-count'),

  markRead: (id: number) => apiPost(`/notifications/${id}/read`),

  markAllRead: () => apiPost('/notifications/read-all'),

  supportPresence: () => apiGet<PresenceDto>('/presence/support'),
};
