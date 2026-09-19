import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, BellOff, CheckCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { notificationKeys, notificationsApi } from '@/api/notifications';
import type { NotificationDto } from '@/api/types';
import { NotificationItem } from '@/components/notifications/NotificationItem';
import { isReferenceOnScreen } from '@/components/notifications/onScreenReferences';
import { useNotificationActions } from '@/components/notifications/useNotificationActions';
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
  const { openNotification, canOpenNotification, markAllRead } = useNotificationActions();

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
    // Algo que ya está en pantalla (p. ej. el chat de soporte abierto) no avisa: esa pantalla lo muestra en vivo y lo
    // marca como leído, y entonces refresca el contador. Solo se marca la lista como desactualizada.
    if (isReferenceOnScreen(notification.referenceType, notification.referenceId)) {
      void queryClient.invalidateQueries({ queryKey: notificationKeys.all, refetchType: 'none' });
      return;
    }
    queryClient.setQueryData(notificationKeys.unreadCount(), (old: { count: number } | undefined) =>
      old ? { count: old.count + 1 } : old,
    );
    void queryClient.invalidateQueries({ queryKey: notificationKeys.all });

    // "Ver" solo si lleva a una pantalla que este rol puede abrir (SPEC §3.3).
    const action = canOpenNotification(notification)
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
          'relative grid h-9 w-9 shrink-0 place-items-center rounded-control text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          dropdown.open && 'bg-muted',
        )}
      >
        <Bell className="h-[18px] w-[18px]" aria-hidden="true" />
        {unread > 0 && (
          <span
            aria-hidden="true"
            className="absolute right-0.5 top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-crit px-1 font-mono text-[10px] font-bold leading-none text-crit-foreground ring-2 ring-card"
          >
            {badge}
          </span>
        )}
      </button>

      {dropdown.open && (
        <DropdownPanel
          {...dropdown.panelProps}
          aria-label="Notificaciones"
          className="fixed inset-x-3 top-[3.75rem] p-0 sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:w-[24rem]"
        >
          <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
            <div>
              <p className="text-base font-semibold text-foreground">Notificaciones</p>
              <p className="text-xs text-muted-foreground">
                {unread > 0 ? `${unread} sin leer` : 'Estás al día'}
              </p>
            </div>
            {unread > 0 && (
              <button
                type="button"
                onClick={() => markAllRead.mutate()}
                disabled={markAllRead.isPending}
                className="inline-flex items-center gap-1.5 rounded-control px-2 py-1.5 text-xs font-semibold text-primary transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
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
                <span className="grid h-10 w-10 place-items-center rounded-control border border-dashed border-input bg-muted text-muted-foreground">
                  <BellOff className="h-5 w-5" aria-hidden="true" />
                </span>
                <p className="text-base font-semibold text-foreground">No tenés notificaciones</p>
                <p className="text-sm text-muted-foreground">Te avisamos acá cuando haya novedades.</p>
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

          <div className="border-t border-border p-1.5">
            <Link
              to="/notifications"
              onClick={() => dropdown.close()}
              className="block rounded-control px-3 py-2 text-center text-base font-semibold text-primary transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              Ver todas las notificaciones
            </Link>
          </div>
        </DropdownPanel>
      )}
    </div>
  );
}
