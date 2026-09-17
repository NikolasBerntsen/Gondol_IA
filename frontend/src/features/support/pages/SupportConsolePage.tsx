import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  BellOff,
  BellRing,
  CircleUserRound,
  Headset,
  Inbox,
  PanelRightOpen,
  Radio,
  Timer,
  UserCheck,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import type { TicketCategory, TicketPriority, TicketStatus } from '@/api/types';
import { useCurrentUser } from '@/auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  SearchInput,
  Segmented,
  Sheet,
  SheetContent,
  Skeleton,
  StatCard,
  pageInfo,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { useDebounce } from '@/lib/useDebounce';
import { useStompSubscription } from '@/realtime/useStompSubscription';
import { supportAgentApi, supportKeys } from '../api';
import { ChatThread } from '../components/ChatThread';
import { MessageComposer } from '../components/MessageComposer';
import { TicketInfoPanel } from '../components/TicketInfoPanel';
import { TicketListItem } from '../components/TicketListItem';
import { TicketPriorityBadge, TicketStatusBadge, UnreadBadge } from '../components/TicketBadges';
import { useMessageChime, useTypingNotifier } from '../hooks/useSupportRealtime';
import { invalidateSupportLists, useTicketConversation } from '../hooks/useTicketConversation';
import { formatMinutes, isTicketWritable } from '../labels';
import type { AssignedFilter, QueueEvent, TicketStatusFilter } from '../types';

const PAGE_SIZE = 20;
const MUTE_KEY = 'gondolia.support.mute';

const ASSIGNED_OPTIONS = [
  { value: 'all', label: 'Todos' },
  { value: 'me', label: 'Míos' },
  { value: 'unassigned', label: 'Sin asignar' },
] as const;

const STATUS_FILTERS = [
  { value: 'ACTIVE', label: 'Activos' },
  { value: 'OPEN', label: 'Abiertos' },
  { value: 'IN_PROGRESS', label: 'En curso' },
  { value: 'WAITING_CUSTOMER', label: 'Esperando' },
  { value: 'RESOLVED', label: 'Resueltos' },
  { value: 'ALL', label: 'Todos' },
] as const;

function readMuted(): boolean {
  try {
    return window.localStorage.getItem(MUTE_KEY) === 'true';
  } catch {
    return false;
  }
}

/**
 * Consola del equipo de soporte (SPEC §6.8): bandeja con filtros y no leídos, conversación en vivo con imágenes y
 * panel con los datos del comercio y los controles de gestión. Comparte página con `/support/tickets/:id`.
 */
export default function SupportConsolePage() {
  const me = useCurrentUser();
  const params = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const chime = useMessageChime();

  const selectedId = params.id ? Number(params.id) : null;
  const [status, setStatus] = useState<TicketStatusFilter>('ACTIVE');
  const [assigned, setAssigned] = useState<AssignedFilter>('all');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [infoOpen, setInfoOpen] = useState(false);
  const [muted, setMuted] = useState(readMuted);
  const q = useDebounce(search, 300);
  const unreadSnapshot = useRef(new Map<number, number>());

  useEffect(() => setPage(0), [status, assigned, q]);

  const queueParams = useMemo(
    () => ({ status, assigned, q: q || undefined, page, size: PAGE_SIZE }),
    [assigned, page, q, status],
  );

  const queue = useQuery({
    queryKey: supportKeys.agentQueue(queueParams),
    queryFn: () => supportAgentApi.list(queueParams),
    placeholderData: keepPreviousData,
  });

  const stats = useQuery({ queryKey: supportKeys.stats(), queryFn: supportAgentApi.stats });
  const agents = useQuery({ queryKey: supportKeys.agents(), queryFn: supportAgentApi.agents, staleTime: 60_000 });

  // Novedades de la bandeja: refresco y aviso sonoro cuando entra algo nuevo del comercio.
  useStompSubscription<QueueEvent>('/topic/support/queue', (event) => {
    const previous = unreadSnapshot.current.get(event.ticket.id);
    const isNews = event.event === 'TICKET_CREATED' || event.ticket.unreadCount > (previous ?? 0);
    unreadSnapshot.current.set(event.ticket.id, event.ticket.unreadCount);
    queryClient.invalidateQueries({ queryKey: ['support', 'agent', 'list'] });
    queryClient.invalidateQueries({ queryKey: supportKeys.stats() });
    if (isNews && event.ticket.id !== selectedId && !muted) chime();
  });

  useStompSubscription<{ agentsOnline: number }>('/topic/support/presence', () => {
    queryClient.invalidateQueries({ queryKey: supportKeys.agents() });
  });

  useEffect(() => {
    queue.data?.content.forEach((ticket) => unreadSnapshot.current.set(ticket.id, ticket.unreadCount));
  }, [queue.data]);

  const onIncoming = useCallback(() => {
    if (!muted) chime();
  }, [chime, muted]);

  const conversation = useTicketConversation({ ticketId: selectedId, side: 'agent', onIncoming });
  const typing = useTypingNotifier(selectedId);
  const ticket = conversation.ticket;

  const afterUpdate = (message: string) => {
    toast.success(message);
    if (selectedId != null) queryClient.invalidateQueries({ queryKey: supportKeys.ticket('agent', selectedId) });
    invalidateSupportLists(queryClient);
  };

  const changeStatus = useMutation({
    mutationFn: (next: TicketStatus) => supportAgentApi.changeStatus(selectedId as number, next),
    onSuccess: () => afterUpdate('Actualizaste el estado.'),
  });
  const assign = useMutation({
    mutationFn: (agentId: number) => supportAgentApi.assign(selectedId as number, agentId),
    onSuccess: () => afterUpdate('Asignaste el ticket.'),
  });
  const patch = useMutation({
    mutationFn: (body: { priority?: TicketPriority; category?: TicketCategory }) =>
      supportAgentApi.patch(selectedId as number, body),
    onSuccess: () => afterUpdate('Reclasificaste el ticket.'),
  });
  const busy = changeStatus.isPending || assign.isPending || patch.isPending;

  const toggleMute = () => {
    setMuted((previous) => {
      const next = !previous;
      try {
        window.localStorage.setItem(MUTE_KEY, String(next));
      } catch {
        // Sin almacenamiento local el aviso sigue funcionando en esta sesión.
      }
      return next;
    });
  };

  const onlineAgents = agents.data?.filter((agent) => agent.online).length ?? 0;

  return (
    <>
      <PageHeader
        title="Bandeja de soporte"
        icon={Headset}
        description="Atendé los tickets y el chat en vivo de todos los comercios."
        actions={
          <div className="flex items-center gap-2">
            <Badge tone={onlineAgents > 0 ? 'ok' : 'neutral'} dot>
              {onlineAgents === 1 ? '1 agente en línea' : `${onlineAgents} agentes en línea`}
            </Badge>
            <Button
              variant="outline"
              size="sm"
              leftIcon={muted ? <BellOff className="h-4 w-4" /> : <BellRing className="h-4 w-4" />}
              onClick={toggleMute}
            >
              {muted ? 'Sonido apagado' : 'Sonido activado'}
            </Button>
          </div>
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <StatCard label="Sin asignar" value={stats.data?.unassigned ?? 0} tone="warn" icon={Inbox} loading={stats.isPending} />
        <StatCard label="Míos activos" value={stats.data?.mine ?? 0} tone="primary" icon={UserCheck} loading={stats.isPending} />
        <StatCard label="En curso" value={stats.data?.inProgress ?? 0} tone="info" icon={Radio} loading={stats.isPending} />
        <StatCard label="Resueltos hoy" value={stats.data?.resolvedToday ?? 0} tone="ok" icon={CircleUserRound} loading={stats.isPending} />
        <StatCard
          label="1.ª respuesta (30 días)"
          value={formatMinutes(stats.data?.avgFirstResponseMinutes)}
          tone="neutral"
          icon={Timer}
          loading={stats.isPending}
        />
      </div>

      <div className="grid gap-4 lg:h-[calc(100dvh-24rem)] lg:min-h-[34rem] lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)] xl:grid-cols-[minmax(0,22rem)_minmax(0,1fr)_minmax(0,20rem)]">
        {/* 1. Cola */}
        <Card
          padding="none"
          className={cn('flex min-h-0 flex-col overflow-hidden', selectedId != null && 'hidden lg:flex')}
        >
          <div className="space-y-2 border-b border-border p-3">
            <SearchInput
              value={search}
              onValueChange={setSearch}
              placeholder="Buscar por asunto, comercio o persona"
              inputSize="sm"
            />
            <Segmented
              size="sm"
              label="Asignación"
              value={assigned}
              onChange={setAssigned}
              options={ASSIGNED_OPTIONS.map((option) => ({ ...option }))}
            />
            <Segmented
              size="sm"
              label="Estado"
              value={status}
              onChange={setStatus}
              options={STATUS_FILTERS.map((option) => ({ ...option }))}
            />
          </div>

          <div className="gd-scroll min-h-0 flex-1 overflow-y-auto">
            {queue.isPending ? (
              <div className="space-y-3 p-3">
                {[0, 1, 2, 3].map((row) => (
                  <Skeleton key={row} className="h-16 w-full" />
                ))}
              </div>
            ) : queue.isError ? (
              <ErrorState error={queue.error} onRetry={queue.refetch} size="sm" />
            ) : queue.data && queue.data.content.length > 0 ? (
              <ul>
                {queue.data.content.map((row) => (
                  <TicketListItem
                    key={row.id}
                    ticket={row}
                    showTenant
                    dense
                    active={row.id === selectedId}
                    onSelect={(selected) => navigate(`/support/tickets/${selected.id}`)}
                  />
                ))}
              </ul>
            ) : (
              <EmptyState
                icon={Inbox}
                size="sm"
                title="No hay tickets con estos filtros"
                description="Probá con otro estado o limpiá la búsqueda."
              />
            )}
          </div>

          {queue.data && queue.data.totalPages > 1 && (
            <div className="border-t border-border p-2">
              <Pagination {...pageInfo(queue.data)} onPageChange={setPage} />
            </div>
          )}
        </Card>

        {/* 2. Conversación */}
        <Card padding="none" className={cn('flex min-h-[70dvh] flex-col overflow-hidden lg:min-h-0')}>
          {selectedId == null ? (
            <EmptyState
              icon={Headset}
              title="Elegí una conversación"
              description="Las consultas de los comercios aparecen a la izquierda, las más nuevas primero."
              className="m-auto"
            />
          ) : conversation.isPending ? (
            <div className="space-y-3 p-4">
              <Skeleton className="h-6 w-2/3" />
              <Skeleton className="h-20 w-3/4" />
              <Skeleton className="ml-auto h-16 w-2/3" />
            </div>
          ) : conversation.isError || !ticket ? (
            <ErrorState error={conversation.error} onRetry={conversation.refetch} />
          ) : (
            <>
              <header className="flex items-start gap-2 border-b border-border p-3">
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="lg:hidden"
                  aria-label="Volver a la bandeja"
                  onClick={() => navigate('/support')}
                >
                  <ArrowLeft className="h-4 w-4" />
                </Button>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-lg font-semibold leading-tight">{ticket.subject}</p>
                  <p className="truncate text-sm text-muted-foreground">
                    {ticket.tenantName} · {ticket.createdByName ?? 'Usuario dado de baja'}
                  </p>
                </div>
                <div className="hidden shrink-0 items-center gap-1.5 sm:flex">
                  <TicketStatusBadge status={ticket.status} size="sm" />
                  <TicketPriorityBadge priority={ticket.priority} size="sm" />
                  <UnreadBadge count={ticket.unreadCount} />
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  className="xl:hidden"
                  leftIcon={<PanelRightOpen className="h-4 w-4" />}
                  onClick={() => setInfoOpen(true)}
                >
                  Datos
                </Button>
              </header>

              {ticket.assignedToId == null && (
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-warn-soft px-3 py-2 text-sm text-warn-ink">
                  <span>Este ticket no lo tomó nadie todavía.</span>
                  <Button size="sm" loading={assign.isPending} onClick={() => assign.mutate(me.id)}>
                    Tomarlo
                  </Button>
                </div>
              )}

              <ChatThread
                messages={conversation.messages}
                pending={conversation.pending}
                typingPeer={conversation.typingPeer}
                peerReadAt={conversation.peerReadAt}
                onRetry={conversation.retry}
                onDiscard={conversation.discard}
              />

              <MessageComposer
                onSend={(payload) => {
                  typing.stop();
                  conversation.send(payload);
                }}
                onTyping={typing.notify}
                onStopTyping={typing.stop}
                disabled={!isTicketWritable(ticket.status)}
                disabledReason={
                  isTicketWritable(ticket.status)
                    ? undefined
                    : 'El comercio cerró esta conversación: ya no se pueden enviar mensajes.'
                }
                placeholder="Respondé al comercio…"
              />
            </>
          )}
        </Card>

        {/* 3. Datos del comercio y gestión */}
        {ticket && (
          <Card padding="none" className="hidden min-h-0 overflow-hidden xl:flex xl:flex-col">
            <TicketInfoPanel
              ticket={ticket}
              agents={agents.data ?? []}
              busy={busy}
              onStatus={(next) => changeStatus.mutate(next)}
              onPriority={(priority) => patch.mutate({ priority })}
              onCategory={(category) => patch.mutate({ category })}
              onAssign={(agentId) => assign.mutate(agentId)}
            />
          </Card>
        )}
      </div>

      <Sheet open={infoOpen} onOpenChange={setInfoOpen}>
        <SheetContent side="right" className="w-full max-w-sm p-0">
          {ticket && (
            <TicketInfoPanel
              ticket={ticket}
              agents={agents.data ?? []}
              busy={busy}
              onStatus={(next) => changeStatus.mutate(next)}
              onPriority={(priority) => patch.mutate({ priority })}
              onCategory={(category) => patch.mutate({ category })}
              onAssign={(agentId) => assign.mutate(agentId)}
            />
          )}
        </SheetContent>
      </Sheet>
    </>
  );
}
