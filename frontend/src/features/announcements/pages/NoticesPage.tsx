import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Megaphone, ShieldAlert, Info, TriangleAlert } from 'lucide-react';
import { ANNOUNCEMENT_KIND_LABELS, SEVERITY_LABELS, type Severity } from '@/api/types';
import { StatusPill } from '@/components/gondola';
import {
  Badge,
  ButtonLink,
  Card,
  EmptyState,
  ErrorState,
  Pagination,
  PageHeader,
  Segmented,
  Skeleton,
  pageInfo,
} from '@/components/ui';
import { formatDateTime, formatRelative } from '@/lib/format';
import { cn } from '@/lib/cn';
import { announcementKeys, noticesApi } from '../api';
import { RecallSummary } from '../components/RecallSummary';
import type { TenantAnnouncement } from '../types';

type Filter = 'ALL' | 'UNREAD' | 'AFFECTS_ME';

const PAGE_SIZE = 20;

/**
 * Avisos del comercio (SPEC §6.7). Lista con severidad, no leídos y detalle; los recalls muestran la ficha del
 * producto retirado y la marca "Te afecta" cuando alcanzaron un lote de tus sucursales.
 */
export default function NoticesPage() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [openId, setOpenId] = useState<number | null>(null);

  const query = useQuery({
    queryKey: announcementKeys.noticesList(page),
    queryFn: () => noticesApi.list({ page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  const markRead = useMutation({
    mutationFn: (id: number) => noticesApi.markRead(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: announcementKeys.notices });
    },
  });

  const all = useMemo(() => query.data?.content ?? [], [query.data]);
  const unreadCount = all.filter((notice) => !notice.read).length;
  const affectsCount = all.filter((notice) => notice.affectsMe).length;
  const notices = useMemo(() => {
    if (filter === 'UNREAD') return all.filter((notice) => !notice.read);
    if (filter === 'AFFECTS_ME') return all.filter((notice) => notice.affectsMe);
    return all;
  }, [all, filter]);

  // Al abrir un aviso queda leído (no hace falta un botón aparte).
  useEffect(() => {
    if (openId === null) return;
    const notice = all.find((item) => item.id === openId);
    if (notice && !notice.read && !markRead.isPending) markRead.mutate(notice.id);
    // markRead se recrea en cada render: solo nos interesa el aviso abierto.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openId, all]);

  return (
    <>
      <PageHeader
        title="Avisos"
        icon={Megaphone}
        description="Novedades y retiros de mercadería que publica GondolIA."
      >
        <Segmented<Filter>
          label="Filtrar avisos"
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'ALL', label: 'Todos', count: all.length },
            { value: 'UNREAD', label: 'Sin leer', count: unreadCount },
            { value: 'AFFECTS_ME', label: 'Te afectan', count: affectsCount, tone: 'crit' },
          ]}
        />
      </PageHeader>

      {query.isPending ? (
        <div className="space-y-3" aria-busy="true">
          {[0, 1, 2].map((row) => (
            <Card key={row} padding="md">
              <Skeleton className="h-4 w-24" />
              <Skeleton className="mt-3 h-5 w-2/3" />
              <Skeleton className="mt-2 h-4 w-full" />
            </Card>
          ))}
        </div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : notices.length === 0 ? (
        <EmptyState
          icon={Megaphone}
          title={filter === 'ALL' ? 'Todavía no hay avisos' : 'No hay avisos con ese filtro'}
          description={
            filter === 'ALL'
              ? 'Acá vas a ver las novedades de GondolIA y los retiros de mercadería que te afecten.'
              : 'Probá con "Todos" para ver el resto de los avisos.'
          }
          bordered
        />
      ) : (
        <div className="space-y-3">
          {notices.map((notice) => (
            <NoticeCard
              key={notice.id}
              notice={notice}
              open={openId === notice.id}
              onToggle={() => setOpenId(openId === notice.id ? null : notice.id)}
            />
          ))}
        </div>
      )}

      {query.data && query.data.totalPages > 1 ? (
        <div className="mt-4">
          <Pagination {...pageInfo(query.data)} onPageChange={setPage} />
        </div>
      ) : null}
    </>
  );
}

function NoticeCard({
  notice,
  open,
  onToggle,
}: {
  notice: TenantAnnouncement;
  open: boolean;
  onToggle: () => void;
}) {
  const isRecall = notice.kind === 'RECALL';
  const Icon = isRecall ? ShieldAlert : notice.severity === 'WARNING' ? TriangleAlert : Info;
  return (
    <Card
      padding="none"
      className={cn('overflow-hidden', stripeClassFor(notice.severity), !notice.read && 'border-foreground/20')}
    >
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full flex-col gap-2 px-4 py-3.5 text-left transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring sm:px-5"
      >
        <div className="flex flex-wrap items-center gap-2">
          <Icon
            className={cn('h-4 w-4 shrink-0', isRecall ? 'text-crit' : 'text-muted-foreground')}
            aria-hidden="true"
          />
          <Badge tone={isRecall ? 'crit' : 'neutral'} size="sm">
            {ANNOUNCEMENT_KIND_LABELS[notice.kind]}
          </Badge>
          {notice.affectsMe ? (
            <StatusPill tone="crit" solid dot size="sm">
              Te afecta
            </StatusPill>
          ) : null}
          {!notice.read ? (
            <StatusPill tone="info" dot size="sm">
              Sin leer
            </StatusPill>
          ) : null}
          <span className="ml-auto text-xs text-muted-foreground">
            {notice.publishedAt ? formatRelative(notice.publishedAt) : '—'}
          </span>
        </div>

        <div>
          <h2
            className={cn(
              'font-display text-md leading-6 text-foreground',
              notice.read ? 'font-medium' : 'font-semibold',
            )}
          >
            {notice.title}
          </h2>
          {!open ? <p className="mt-0.5 line-clamp-2 text-base text-muted-foreground">{notice.body}</p> : null}
        </div>

        {notice.affectsMe && notice.myOpenMatchesCount > 0 ? (
          <p className="text-sm font-medium text-crit-ink">
            {notice.myOpenMatchesCount === 1
              ? 'Tenés 1 lote pendiente de retirar.'
              : `Tenés ${notice.myOpenMatchesCount} lotes pendientes de retirar.`}
          </p>
        ) : null}
      </button>

      {open ? (
        <div className="space-y-4 border-t border-border px-4 pb-4 pt-4 sm:px-5">
          <p className="whitespace-pre-line text-read text-foreground">{notice.body}</p>
          {notice.recall ? <RecallSummary recall={notice.recall} compact /> : null}
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
            <span className="text-xs text-muted-foreground">
              Publicado el {notice.publishedAt ? formatDateTime(notice.publishedAt) : '—'} ·{' '}
              {SEVERITY_LABELS[notice.severity]}
            </span>
            {notice.affectsMe ? (
              <ButtonLink to="/app/recalls" variant="destructive" size="sm">
                Ver seguridad alimentaria
              </ButtonLink>
            ) : null}
          </div>
        </div>
      ) : null}
    </Card>
  );
}

function stripeClassFor(severity: Severity): string {
  if (severity === 'CRITICAL') return 'gd-stripe-crit';
  if (severity === 'WARNING') return 'gd-stripe-warn';
  return 'gd-stripe-info';
}
