import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, BellOff, CheckCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { notificationKeys, notificationsApi } from '@/api/notifications';
import type { NotificationDto } from '@/api/types';
import { NotificationItem } from '@/components/notifications/NotificationItem';
import { isInternalLink, useNotificationActions } from '@/components/notifications/useNotificationActions';
import { DropdownPanel, useDropdown } from '@/components/ui/Dropdown';
import { ErrorState } from '@/components/ui/ErrorState';
import { Spinner } from '@/components/ui/Spinner';
import { cn } from '@/lib/cn';
import { useStompSubscription } from '@/realtime/useStompSubscription';

const LATEST_PARAMS = { page: 0, size: 10 } as const;

/** Campana del topbar: contador, últimas 10 y push en vivo con toast (SPEC §9.6). */
export function NotificationBell() {
  const queryClient = useQueryClient();
  const dropdown = useDropdown({ kind: 'dialog' });
  const { openNotification, markAllRead } = useNotificationActions();

  const countQuery = useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: notificationsApi.unreadCount,
    refetchInterval: 120_000,
  });

  const listQuery = useQuery({
    queryKey: notificationKeys.list(LATEST_PARAMS),
    queryFn: () => notificationsApi.list(LATEST_PARAMS),
    enabled: dropdown.open,
  });

  useStompSubscription<NotificationDto>('/user/queue/notifications', (notification) => {
    if (!notification?.id) return;
    queryClient.setQueryData(notificationKeys.unreadCount(), (old: { count: number } | undefined) =>
      old ? { count: old.count + 1 } : old,
    );
    void queryClient.invalidateQueries({ queryKey: notificationKeys.all });

    const action = isInternalLink(notification.link)
      ? { label: 'Ver', onClick: () => openNotification(notification) }
      : undefined;
    const options = { id: `notification-${notification.id}`, description: notification.body ?? undefined, action };
    if (notification.severity === 'CRITICAL') toast.error(notification.title, { ...options, duration: 10_000 });
    else if (notification.severity === 'WARNING') toast.warning(notification.title, options);
    else toast.info(notification.title, options);
  });

  const unread = countQuery.data?.count ?? 0;
  const notifications = listQuery.data?.content ?? [];
  const badge = unread > 99 ? '99+' : String(unread);

  return (
    <div className="relative">
      <button
        {...dropdown.triggerProps}
        aria-label={unread > 0 ? `Notificaciones: ${unread} sin leer` : 'Notificaciones'}
        className={cn(
          'relative flex h-10 w-10 items-center justify-center rounded-xl text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
          dropdown.open && 'bg-slate-100 text-slate-900',
        )}
      >
        <Bell className="h-5 w-5" aria-hidden="true" />
        {unread > 0 && (
          <span
            aria-hidden="true"
            className="absolute right-1 top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold leading-none text-white ring-2 ring-white"
          >
            {badge}
          </span>
        )}
      </button>

      {dropdown.open && (
        <DropdownPanel
          {...dropdown.panelProps}
          aria-label="Notificaciones"
          className="fixed inset-x-3 top-[4.25rem] p-0 sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:w-[24rem]"
        >
          <div className="flex items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
            <div>
              <p className="text-sm font-semibold text-slate-900">Notificaciones</p>
              <p className="text-xs text-slate-500">
                {unread > 0 ? `${unread} sin leer` : 'Estás al día'}
              </p>
            </div>
            {unread > 0 && (
              <button
                type="button"
                onClick={() => markAllRead.mutate()}
                disabled={markAllRead.isPending}
                className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-xs font-medium text-brand-700 transition hover:bg-brand-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 disabled:opacity-50"
              >
                <CheckCheck className="h-4 w-4" aria-hidden="true" />
                Marcar todas como leídas
              </button>
            )}
          </div>

          <div className="max-h-[min(28rem,calc(100dvh-12rem))] overflow-y-auto p-1.5">
            {listQuery.isPending ? (
              <div className="flex justify-center py-10">
                <Spinner label="Cargando notificaciones…" />
              </div>
            ) : listQuery.isError ? (
              <ErrorState error={listQuery.error} onRetry={() => void listQuery.refetch()} size="sm" />
            ) : notifications.length === 0 ? (
              <div className="flex flex-col items-center gap-2 px-6 py-10 text-center">
                <span className="flex h-10 w-10 items-center justify-center rounded-2xl bg-slate-100 text-slate-400">
                  <BellOff className="h-5 w-5" aria-hidden="true" />
                </span>
                <p className="text-sm font-medium text-slate-700">No tenés notificaciones</p>
                <p className="text-xs text-slate-500">Te avisamos acá cuando haya novedades.</p>
              </div>
            ) : (
              <ul className="space-y-0.5">
                {notifications.map((notification) => (
                  <li key={notification.id}>
                    <NotificationItem
                      notification={notification}
                      onClick={(n) => {
                        dropdown.close();
                        openNotification(n);
                      }}
                    />
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="border-t border-slate-100 p-1.5">
            <Link
              to="/notifications"
              onClick={() => dropdown.close()}
              className="block rounded-xl px-3 py-2.5 text-center text-sm font-medium text-brand-700 transition hover:bg-brand-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
            >
              Ver todas las notificaciones
            </Link>
          </div>
        </DropdownPanel>
      )}
    </div>
  );
}
