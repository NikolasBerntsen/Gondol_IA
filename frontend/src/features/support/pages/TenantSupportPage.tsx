import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, LifeBuoy, MessageSquarePlus, Plus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  EmptyState,
  ErrorState,
  Modal,
  PageHeader,
  Segmented,
  Skeleton,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { supportKeys, tenantSupportApi } from '../api';
import { ChatThread } from '../components/ChatThread';
import { MessageComposer } from '../components/MessageComposer';
import { NewTicketForm } from '../components/NewTicketForm';
import { RatingPrompt } from '../components/RatingPrompt';
import { TicketListItem } from '../components/TicketListItem';
import { TicketPriorityBadge, TicketStatusBadge } from '../components/TicketBadges';
import { useSupportPresence, useTypingNotifier } from '../hooks/useSupportRealtime';
import { invalidateSupportLists, useTicketConversation } from '../hooks/useTicketConversation';
import { isTicketWritable } from '../labels';
import type { TicketStatusFilter } from '../types';

const FILTERS = [
  { value: 'ACTIVE', label: 'Abiertas' },
  { value: 'ALL', label: 'Todas' },
  { value: 'CLOSED', label: 'Cerradas' },
] as const;

/**
 * Soporte del comercio (SPEC §6.8): mis conversaciones a la izquierda y el chat a la derecha, con adjuntos,
 * indicador de escritura y calificación al final. Comparte página con `/app/support/:id`.
 */
export default function TenantSupportPage() {
  const params = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const selectedId = params.id ? Number(params.id) : null;

  const [filter, setFilter] = useState<TicketStatusFilter>('ACTIVE');
  const [creating, setCreating] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const presence = useSupportPresence();

  const list = useQuery({
    queryKey: supportKeys.tenantList(filter),
    queryFn: () => tenantSupportApi.list(filter),
  });

  const conversation = useTicketConversation({ ticketId: selectedId, side: 'customer' });
  const typing = useTypingNotifier(selectedId);
  const ticket = conversation.ticket;

  const close = useMutation({
    mutationFn: () => tenantSupportApi.close(selectedId as number),
    onSuccess: (updated) => {
      toast.success('Cerraste la conversación.');
      queryClient.setQueryData(supportKeys.ticket('customer', updated.id), updated);
      invalidateSupportLists(queryClient);
      setConfirmClose(false);
    },
  });

  return (
    <>
      <PageHeader
        title="Soporte"
        icon={LifeBuoy}
        description="Escribinos: te respondemos por acá y te avisamos cuando haya novedades."
        actions={
          <div className="flex items-center gap-2">
            <Badge tone={presence.isOnline ? 'ok' : 'neutral'} dot>
              {presence.isOnline ? 'Soporte en línea' : 'Soporte fuera de línea'}
            </Badge>
            <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setCreating(true)}>
              Nueva consulta
            </Button>
          </div>
        }
      />

      <div className="grid gap-4 lg:h-[calc(100dvh-18rem)] lg:min-h-[32rem] lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        <Card padding="none" className={cn('flex min-h-0 flex-col overflow-hidden', selectedId != null && 'hidden lg:flex')}>
          <div className="border-b border-border p-3">
            <Segmented
              size="sm"
              label="Estado de las consultas"
              value={filter}
              onChange={setFilter}
              options={FILTERS.map((option) => ({ ...option }))}
            />
          </div>
          <div className="gd-scroll min-h-0 flex-1 overflow-y-auto">
            {list.isPending ? (
              <div className="space-y-3 p-3">
                {[0, 1, 2].map((row) => (
                  <Skeleton key={row} className="h-16 w-full" />
                ))}
              </div>
            ) : list.isError ? (
              <ErrorState error={list.error} onRetry={list.refetch} size="sm" />
            ) : list.data && list.data.length > 0 ? (
              <ul>
                {list.data.map((row) => (
                  <TicketListItem
                    key={row.id}
                    ticket={row}
                    dense
                    active={row.id === selectedId}
                    onSelect={(selected) => navigate(`/app/support/${selected.id}`)}
                  />
                ))}
              </ul>
            ) : (
              <EmptyState
                icon={MessageSquarePlus}
                size="sm"
                title="Todavía no nos escribiste"
                description="Abrí una consulta y te contestamos apenas la veamos."
                action={<Button size="sm" onClick={() => setCreating(true)}>Nueva consulta</Button>}
              />
            )}
          </div>
        </Card>

        <Card
          padding="none"
          className={cn(
            'flex min-h-[70dvh] flex-col overflow-hidden lg:min-h-0',
            // En mobile la lista ES la pantalla: el panel vacío solo tiene sentido en escritorio.
            selectedId == null && 'hidden lg:flex',
          )}
        >
          {selectedId == null ? (
            <EmptyState
              icon={LifeBuoy}
              title="Elegí una conversación"
              description="O abrí una nueva consulta y contanos qué necesitás."
              action={<Button onClick={() => setCreating(true)}>Nueva consulta</Button>}
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
                  aria-label="Volver a mis consultas"
                  onClick={() => navigate('/app/support')}
                >
                  <ArrowLeft className="h-4 w-4" />
                </Button>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-display text-lg font-semibold leading-tight">{ticket.subject}</p>
                  <p className="truncate text-sm text-muted-foreground">
                    {ticket.assignedToName ? `Te atiende ${ticket.assignedToName}` : 'Esperando que un agente la tome'}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <TicketStatusBadge status={ticket.status} size="sm" />
                  <span className="hidden sm:inline-flex">
                    <TicketPriorityBadge priority={ticket.priority} size="sm" />
                  </span>
                  {isTicketWritable(ticket.status) && (
                    <Button
                      variant="outline"
                      size="sm"
                      leftIcon={<CheckCircle2 className="h-4 w-4" />}
                      onClick={() => setConfirmClose(true)}
                    >
                      Cerrar
                    </Button>
                  )}
                </div>
              </header>

              <ChatThread
                messages={conversation.messages}
                pending={conversation.pending}
                typingPeer={conversation.typingPeer}
                peerReadAt={conversation.peerReadAt}
                onRetry={conversation.retry}
                onDiscard={conversation.discard}
              />

              {(ticket.status === 'RESOLVED' || ticket.status === 'CLOSED') && <RatingPrompt ticket={ticket} />}

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
                    : 'Cerraste esta conversación. Abrí una consulta nueva si necesitás algo más.'
                }
              />
            </>
          )}
        </Card>
      </div>

      <Modal open={creating} onClose={() => setCreating(false)} title="Nueva consulta a soporte" size="md">
        <NewTicketForm
          channel="TICKET"
          onCancel={() => setCreating(false)}
          onCreated={(created) => {
            setCreating(false);
            setFilter('ACTIVE');
            navigate(`/app/support/${created.id}`);
          }}
        />
      </Modal>

      <ConfirmDialog
        open={confirmClose}
        onClose={() => setConfirmClose(false)}
        onConfirm={() => close.mutateAsync()}
        title="¿Cerrar la conversación?"
        description="No vas a poder seguir escribiendo en esta consulta, pero podés abrir una nueva cuando quieras."
        confirmLabel="Cerrar conversación"
      />
    </>
  );
}
