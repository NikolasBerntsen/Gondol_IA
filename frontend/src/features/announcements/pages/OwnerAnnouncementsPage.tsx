import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Archive, Eye, Megaphone, Plus, ShieldAlert, Store, Users } from 'lucide-react';
import {
  ANNOUNCEMENT_KIND_LABELS,
  ANNOUNCEMENT_STATUS_LABELS,
  BUSINESS_TYPE_LABELS,
  SEVERITY_LABELS,
  type AnnouncementKind,
} from '@/api/types';
import { StatusPill } from '@/components/gondola';
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  Card,
  ConfirmDialog,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  Segmented,
  Skeleton,
  StatCard,
  pageInfo,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatDateTime, formatNumber, formatRelative } from '@/lib/format';
import { announcementKeys, ownerAnnouncementsApi } from '../api';
import { RecallSummary } from '../components/RecallSummary';
import type { AnnouncementListItem } from '../types';

type KindFilter = 'ALL' | AnnouncementKind;

const PAGE_SIZE = 20;

/**
 * Consola de dueños: avisos generales y recalls publicados, con estadísticas agregadas.
 * Nunca se muestra **qué** comercios coinciden con un recall, solo cuántos (SPEC §3.4.3).
 */
export default function OwnerAnnouncementsPage() {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<KindFilter>('ALL');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [archiving, setArchiving] = useState<AnnouncementListItem | null>(null);

  const params = { kind: kind === 'ALL' ? undefined : kind, page, size: PAGE_SIZE };
  const query = useQuery({
    queryKey: announcementKeys.ownerList(params),
    queryFn: () => ownerAnnouncementsApi.list(params),
    placeholderData: keepPreviousData,
  });

  const archive = useMutation({
    mutationFn: (id: number) => ownerAnnouncementsApi.archive(id),
    onSuccess: () => {
      toast.success('Aviso archivado.');
      setArchiving(null);
      void queryClient.invalidateQueries({ queryKey: announcementKeys.owner });
    },
  });

  const items = query.data?.content ?? [];
  const recalls = items.filter((item) => item.kind === 'RECALL');
  const affected = recalls.reduce((total, item) => total + item.affectedTenantsCount, 0);
  const recipients = items.reduce((total, item) => total + item.recipientsCount, 0);

  return (
    <>
      <PageHeader
        title="Avisos y recalls"
        icon={Megaphone}
        description="Novedades del producto y retiros de mercadería para todos los clientes activos."
        actions={
          <ButtonLink to="/owner/announcements/new" leftIcon={<Plus aria-hidden="true" />}>
            Publicar aviso
          </ButtonLink>
        }
      >
        <Segmented<KindFilter>
          label="Filtrar por tipo"
          value={kind}
          onChange={(value) => {
            setKind(value);
            setPage(0);
          }}
          options={[
            { value: 'ALL', label: 'Todos' },
            { value: 'GENERAL', label: 'Avisos generales' },
            { value: 'RECALL', label: 'Recalls', tone: 'crit' },
          ]}
        />
      </PageHeader>

      <Alert tone="info" className="mb-4">
        Solo ves datos administrativos y agregados: nunca el stock, las ventas ni qué cliente coincide con un recall.
      </Alert>

      <div className="mb-4 grid gap-4 sm:grid-cols-3">
        <StatCard label="Avisos en esta página" value={items.length} icon={Megaphone} tone="primary" />
        <StatCard
          label="Clientes alcanzados por recalls"
          value={formatNumber(affected)}
          icon={Store}
          tone={affected ? 'crit' : 'neutral'}
          hint={`${recalls.length} ${recalls.length === 1 ? 'recall' : 'recalls'}`}
        />
        <StatCard label="Destinatarios notificados" value={formatNumber(recipients)} icon={Users} tone="info" />
      </div>

      {query.isPending ? (
        <div className="space-y-3" aria-busy="true">
          {[0, 1, 2].map((row) => (
            <Card key={row} padding="md">
              <Skeleton className="h-4 w-24" />
              <Skeleton className="mt-3 h-5 w-2/3" />
              <Skeleton className="mt-2 h-4 w-1/2" />
            </Card>
          ))}
        </div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState
          icon={Megaphone}
          title="Todavía no publicaste avisos"
          description="Contales una novedad a tus clientes o publicá un retiro de mercadería para bloquear los lotes alcanzados."
          action={
            <ButtonLink to="/owner/announcements/new" leftIcon={<Plus aria-hidden="true" />}>
              Publicar aviso
            </ButtonLink>
          }
          bordered
        />
      ) : (
        <div className="space-y-3">
          {items.map((item) => (
            <AnnouncementCard
              key={item.id}
              item={item}
              open={openId === item.id}
              onToggle={() => setOpenId(openId === item.id ? null : item.id)}
              onArchive={() => setArchiving(item)}
            />
          ))}
        </div>
      )}

      {query.data && query.data.totalPages > 1 ? (
        <div className="mt-4">
          <Pagination {...pageInfo(query.data)} onPageChange={setPage} />
        </div>
      ) : null}

      <ConfirmDialog
        open={archiving !== null}
        onClose={() => setArchiving(null)}
        onConfirm={() => {
          if (archiving) archive.mutate(archiving.id);
        }}
        title="Archivar el aviso"
        description={
          archiving?.kind === 'RECALL'
            ? `"${archiving.title}" deja de verse en los comercios. Los lotes que ya quedaron en cuarentena siguen bloqueados y las alertas pendientes no se cierran.`
            : `"${archiving?.title ?? ''}" deja de verse en la bandeja de avisos de los comercios.`
        }
        confirmLabel="Archivar aviso"
        tone="danger"
        loading={archive.isPending}
      />
    </>
  );
}

function AnnouncementCard({
  item,
  open,
  onToggle,
  onArchive,
}: {
  item: AnnouncementListItem;
  open: boolean;
  onToggle: () => void;
  onArchive: () => void;
}) {
  const isRecall = item.kind === 'RECALL';
  const archived = item.status === 'ARCHIVED';
  const detail = useQuery({
    queryKey: announcementKeys.ownerDetail(item.id),
    queryFn: () => ownerAnnouncementsApi.get(item.id),
    enabled: open,
  });

  return (
    <Card
      padding="none"
      className={cn(
        'overflow-hidden',
        isRecall ? 'gd-stripe-crit' : item.severity === 'WARNING' ? 'gd-stripe-warn' : 'gd-stripe-info',
        archived && 'opacity-75',
      )}
    >
      <div className="flex flex-col gap-3 px-4 py-4 sm:px-5">
        <div className="flex flex-wrap items-center gap-2">
          {isRecall ? <ShieldAlert className="h-4 w-4 shrink-0 text-crit" aria-hidden="true" /> : null}
          <Badge tone={isRecall ? 'crit' : 'neutral'} size="sm">
            {ANNOUNCEMENT_KIND_LABELS[item.kind]}
          </Badge>
          <StatusPill tone={archived ? 'neutral' : 'ok'} size="sm">
            {ANNOUNCEMENT_STATUS_LABELS[item.status]}
          </StatusPill>
          <Badge tone={item.severity === 'CRITICAL' ? 'crit' : item.severity === 'WARNING' ? 'warn' : 'info'} size="sm">
            {SEVERITY_LABELS[item.severity]}
          </Badge>
          {item.targetBusinessTypes.length > 0 ? (
            <Badge tone="neutral" size="sm">
              {item.targetBusinessTypes.map((type) => BUSINESS_TYPE_LABELS[type]).join(' · ')}
            </Badge>
          ) : null}
          <span className="ml-auto text-xs text-muted-foreground">
            {item.publishedAt ? formatRelative(item.publishedAt) : '—'}
          </span>
        </div>

        <div>
          <h2 className="font-display text-md font-semibold leading-6 text-foreground">{item.title}</h2>
          <p className={cn('mt-0.5 text-base text-muted-foreground', !open && 'line-clamp-2')}>{item.body}</p>
        </div>

        <dl className="grid grid-cols-2 gap-3 border-t border-border pt-3 sm:grid-cols-4">
          <Stat label="Destinatarios" value={formatNumber(item.recipientsCount)} />
          <Stat label="Lo leyeron" value={open && detail.data ? formatNumber(detail.data.readCount) : '—'} />
          <Stat
            label="Clientes alcanzados"
            value={isRecall ? formatNumber(item.affectedTenantsCount) : '—'}
            tone={isRecall && item.affectedTenantsCount > 0 ? 'crit' : undefined}
          />
          <Stat
            label="Lotes sin retirar"
            value={
              !isRecall
                ? '—'
                : open && detail.data
                  ? formatNumber(detail.data.matchesOpen + detail.data.matchesAcknowledged)
                  : '—'
            }
          />
        </dl>

        {open ? (
          <div className="space-y-4 border-t border-border pt-4">
            {detail.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : detail.isError ? (
              <ErrorState error={detail.error} onRetry={() => void detail.refetch()} size="sm" />
            ) : detail.data ? (
              <>
                {detail.data.recall ? <RecallSummary recall={detail.data.recall} /> : null}
                {detail.data.kind === 'RECALL' ? (
                  <div className="grid grid-cols-3 gap-3">
                    <Stat label="Pendientes" value={formatNumber(detail.data.matchesOpen)} tone="crit" />
                    <Stat label="Confirmados" value={formatNumber(detail.data.matchesAcknowledged)} tone="warn" />
                    <Stat label="Resueltos" value={formatNumber(detail.data.matchesResolved)} tone="ok" />
                  </div>
                ) : null}
                <p className="text-xs text-muted-foreground">
                  Publicado por {detail.data.createdByName ?? 'GondolIA'} el{' '}
                  {detail.data.publishedAt ? formatDateTime(detail.data.publishedAt) : '—'}
                </p>
              </>
            ) : null}
          </div>
        ) : null}

        <div className="flex flex-col-reverse gap-2 border-t border-border pt-3 sm:flex-row sm:justify-end">
          <Button variant="ghost" size="sm" leftIcon={<Eye aria-hidden="true" />} onClick={onToggle} aria-expanded={open}>
            {open ? 'Ocultar detalle' : 'Ver detalle'}
          </Button>
          {!archived ? (
            <Button variant="outline" size="sm" leftIcon={<Archive aria-hidden="true" />} onClick={onArchive}>
              Archivar
            </Button>
          ) : null}
        </div>
      </div>
    </Card>
  );
}

function Stat({ label, value, tone }: { label: string; value: string | number; tone?: 'crit' | 'warn' | 'ok' }) {
  return (
    <div>
      <dt className="gd-eyebrow">{label}</dt>
      <dd
        className={cn(
          'font-display text-md font-semibold tabular-nums',
          tone === 'crit' ? 'text-crit-ink' : tone === 'warn' ? 'text-warn-ink' : tone === 'ok' ? 'text-ok-ink' : 'text-foreground',
        )}
      >
        {value}
      </dd>
    </div>
  );
}
