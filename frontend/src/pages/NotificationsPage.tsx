import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Bell, BellOff, Check, CheckCheck } from 'lucide-react';
import { useState } from 'react';
import { notificationKeys, notificationsApi } from '@/api/notifications';
import { NotificationItem } from '@/components/notifications/NotificationItem';
import { useNotificationActions } from '@/components/notifications/useNotificationActions';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import { ErrorState } from '@/components/ui/ErrorState';
import { PageHeader } from '@/components/ui/PageHeader';
import { Pagination, pageInfo } from '@/components/ui/Pagination';
import { Skeleton } from '@/components/ui/Skeleton';
import { Tabs } from '@/components/ui/Tabs';

type Filter = 'all' | 'unread';

const PAGE_SIZE = 20;

export default function NotificationsPage() {
  const [filter, setFilter] = useState<Filter>('all');
  const [page, setPage] = useState(0);
  const { openNotification, markRead, markAllRead } = useNotificationActions();

  const params = { unreadOnly: filter === 'unread', page, size: PAGE_SIZE };

  const listQuery = useQuery({
    queryKey: notificationKeys.list(params),
    queryFn: () => notificationsApi.list(params),
    placeholderData: keepPreviousData,
  });

  const countQuery = useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: notificationsApi.unreadCount,
  });

  const unread = countQuery.data?.count ?? 0;
  const notifications = listQuery.data?.content ?? [];

  const changeFilter = (value: Filter) => {
    setFilter(value);
    setPage(0);
  };

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Notificaciones"
        description="Avisos, alertas, recomendaciones y mensajes de soporte de tu cuenta."
        icon={Bell}
        actions={
          <Button
            variant="outline"
            onClick={() => markAllRead.mutate()}
            loading={markAllRead.isPending}
            disabled={unread === 0}
            leftIcon={<CheckCheck className="h-4 w-4" aria-hidden="true" />}
          >
            Marcar todas como leídas
          </Button>
        }
      />

      <Card padding="none">
        <div className="border-b border-slate-100 px-4 py-3">
          <Tabs<Filter>
            variant="pills"
            ariaLabel="Filtrar notificaciones"
            value={filter}
            onChange={changeFilter}
            className="w-fit"
            tabs={[
              { value: 'all', label: 'Todas' },
              { value: 'unread', label: 'No leídas', count: unread },
            ]}
          />
        </div>

        {listQuery.isPending ? (
          <ul className="space-y-1 p-2" aria-hidden="true">
            {Array.from({ length: 5 }, (_, index) => (
              <li key={index} className="flex gap-3 px-3 py-3">
                <Skeleton className="h-9 w-9 rounded-xl" />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-4 w-2/3" />
                  <Skeleton className="h-3 w-full" />
                  <Skeleton className="h-3 w-20" />
                </div>
              </li>
            ))}
          </ul>
        ) : listQuery.isError ? (
          <ErrorState error={listQuery.error} onRetry={() => void listQuery.refetch()} retrying={listQuery.isFetching} />
        ) : notifications.length === 0 ? (
          <EmptyState
            icon={BellOff}
            title={filter === 'unread' ? 'No tenés notificaciones sin leer' : 'Todavía no tenés notificaciones'}
            description="Cuando haya avisos, alertas o respuestas de soporte, las vas a ver acá."
          />
        ) : (
          <ul className={`divide-y divide-slate-100 p-2 transition-opacity ${listQuery.isPlaceholderData ? 'opacity-60' : ''}`}>
            {notifications.map((notification) => (
              <li key={notification.id} className="flex items-start gap-1 py-0.5">
                <NotificationItem notification={notification} onClick={openNotification} expanded className="flex-1" />
                {!notification.read && (
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    className="mt-3 shrink-0"
                    onClick={() => markRead.mutate(notification.id)}
                    aria-label={`Marcar como leída: ${notification.title}`}
                    title="Marcar como leída"
                  >
                    <Check className="h-4 w-4" aria-hidden="true" />
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}

        <Pagination {...pageInfo(listQuery.data)} onPageChange={setPage} disabled={listQuery.isFetching} />
      </Card>
    </div>
  );
}
