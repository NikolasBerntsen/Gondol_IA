import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { notificationKeys, notificationsApi } from '@/api/notifications';
import type { NotificationDto, PageResponse } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { linkTargetFor } from '@/config/access';

/** Solo se navega a rutas internas de la app. */
export function isInternalLink(link: string | null | undefined): link is string {
  return !!link && link.startsWith('/') && !link.startsWith('//');
}

/** Acciones sobre notificaciones compartidas por la campana y la página de notificaciones. */
export function useNotificationActions() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { me } = useAuth();
  const role = me?.role;

  const markLocallyRead = useCallback(
    (id: number | 'all') => {
      queryClient.setQueriesData<PageResponse<NotificationDto>>({ queryKey: [...notificationKeys.all, 'list'] }, (page) =>
        page
          ? {
              ...page,
              content: page.content.map((n) => (id === 'all' || n.id === id ? { ...n, read: true } : n)),
            }
          : page,
      );
    },
    [queryClient],
  );

  const invalidate = useCallback(
    () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
    [queryClient],
  );

  const markRead = useMutation({
    mutationFn: (id: number) => notificationsApi.markRead(id),
    onMutate: (id) => markLocallyRead(id),
    onSettled: invalidate,
    meta: { errorToast: false },
  });

  const markAllRead = useMutation({
    mutationFn: () => notificationsApi.markAllRead(),
    onMutate: () => markLocallyRead('all'),
    onSuccess: () => toast.success('Marcaste todas las notificaciones como leídas.'),
    onError: (error) => toast.error(getErrorMessage(error)),
    onSettled: invalidate,
  });

  const markReadMutate = markRead.mutate;

  /**
   * Marca como leída (si hace falta) y navega al `link` de la notificación, o a la pantalla equivalente que el rol
   * puede abrir (nunca a "Acceso denegado"; SPEC §3.3).
   */
  const openNotification = useCallback(
    (notification: NotificationDto) => {
      if (!notification.read) markReadMutate(notification.id);
      if (!isInternalLink(notification.link)) return;
      const target = linkTargetFor(role, notification.link);
      if (target) navigate(target);
    },
    [markReadMutate, navigate, role],
  );

  /** `true` si la notificación lleva a una pantalla que el rol puede abrir (para mostrar o no el botón "Ver"). */
  const canOpenNotification = useCallback(
    (notification: NotificationDto) =>
      isInternalLink(notification.link) && linkTargetFor(role, notification.link) != null,
    [role],
  );

  return { openNotification, canOpenNotification, markRead, markAllRead };
}
