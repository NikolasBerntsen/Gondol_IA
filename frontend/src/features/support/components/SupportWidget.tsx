import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ExternalLink, Headset, MessageCircle, MessageSquarePlus, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Button, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { supportKeys, tenantSupportApi } from '../api';
import { ChatThread } from './ChatThread';
import { MessageComposer } from './MessageComposer';
import { NewTicketForm } from './NewTicketForm';
import { PresenceDot } from './TicketBadges';
import { TicketListItem } from './TicketListItem';
import { useSupportPresence, useTicketTopics, useTypingNotifier } from '../hooks/useSupportRealtime';
import { invalidateSupportLists, useTicketConversation } from '../hooks/useTicketConversation';
import { isTicketWritable } from '../labels';

type View = 'list' | 'chat' | 'new';

/**
 * Botón flotante de soporte, presente en todas las pantallas del comercio (SPEC §9.6).
 *
 * Muestra si hay alguien de soporte en línea y cuántos mensajes tenés sin leer, y abre un panel con tus
 * conversaciones abiertas, el formulario de consulta nueva y el chat en vivo (con imágenes por selector, pegado,
 * arrastre, cámara y "Capturar pantalla"). Se marca con `data-support-widget` para que la captura de pantalla no
 * se fotografíe a sí misma.
 */
export default function SupportWidget() {
  const location = useLocation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<View>('list');
  const [ticketId, setTicketId] = useState<number | null>(null);

  const presence = useSupportPresence();

  const list = useQuery({
    queryKey: supportKeys.tenantList('ACTIVE'),
    queryFn: () => tenantSupportApi.list('ACTIVE'),
    staleTime: 30_000,
  });

  const openIds = useMemo(() => (list.data ?? []).map((ticket) => ticket.id).slice(0, 12), [list.data]);
  const unread = (list.data ?? []).reduce((total, ticket) => total + ticket.unreadCount, 0);

  // La lista sigue viva aunque el panel esté cerrado: la burbuja avisa los mensajes nuevos.
  useTicketTopics(openIds, () => invalidateSupportLists(queryClient));

  const conversation = useTicketConversation({
    ticketId: view === 'chat' && open ? ticketId : null,
    side: 'customer',
  });
  const typing = useTypingNotifier(view === 'chat' && open ? ticketId : null);
  const ticket = conversation.ticket;

  const openChat = useCallback((id: number) => {
    setTicketId(id);
    setView('chat');
  }, []);

  // En la pantalla de soporte el widget sobra: ahí ya está la conversación completa.
  const onSupportScreen = location.pathname.startsWith('/app/support');
  useEffect(() => {
    if (onSupportScreen) setOpen(false);
  }, [onSupportScreen]);
  if (onSupportScreen) return null;

  return (
    <div data-support-widget="true" className="fixed bottom-4 right-4 z-50 flex flex-col items-end gap-2 print:hidden">
      {open && (
        <section
          role="dialog"
          aria-label="Chat con soporte"
          className={cn(
            'flex w-[min(24rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-panel border border-border bg-card shadow-pop',
            'h-[min(34rem,calc(100dvh-7rem))]',
          )}
        >
          <header className="flex items-center gap-2 border-b border-border bg-rail px-3 py-2.5 text-rail-foreground">
            {view !== 'list' && (
              <button
                type="button"
                aria-label="Volver a mis conversaciones"
                onClick={() => setView('list')}
                className="rounded-control p-1 hover:bg-rail-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rail-strong"
              >
                <ArrowLeft className="h-4 w-4" />
              </button>
            )}
            <div className="min-w-0 flex-1">
              <p className="truncate font-display text-base font-semibold text-rail-strong">
                {view === 'chat' && ticket ? ticket.subject : 'Soporte GondolIA'}
              </p>
              <p className="flex items-center gap-1.5 text-xs text-rail-muted">
                <PresenceDot online={presence.isOnline} />
                {presence.isOnline ? 'Estamos en línea' : 'Fuera de línea: te respondemos apenas volvamos'}
              </p>
            </div>
            <Link
              to={view === 'chat' && ticketId ? `/app/support/${ticketId}` : '/app/support'}
              onClick={() => setOpen(false)}
              aria-label="Abrir soporte en pantalla completa"
              className="rounded-control p-1 hover:bg-rail-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rail-strong"
            >
              <ExternalLink className="h-4 w-4" />
            </Link>
            <button
              type="button"
              aria-label="Cerrar el chat de soporte"
              onClick={() => setOpen(false)}
              className="rounded-control p-1 hover:bg-rail-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rail-strong"
            >
              <X className="h-4 w-4" />
            </button>
          </header>

          {view === 'list' && (
            <>
              <div className="gd-scroll min-h-0 flex-1 overflow-y-auto">
                {list.isPending ? (
                  <div className="space-y-3 p-3">
                    {[0, 1].map((row) => (
                      <Skeleton key={row} className="h-16 w-full" />
                    ))}
                  </div>
                ) : list.isError ? (
                  <ErrorState error={list.error} onRetry={list.refetch} size="sm" />
                ) : list.data && list.data.length > 0 ? (
                  <ul>
                    {list.data.map((row) => (
                      <TicketListItem key={row.id} ticket={row} dense onSelect={(selected) => openChat(selected.id)} />
                    ))}
                  </ul>
                ) : (
                  <EmptyState
                    icon={Headset}
                    size="sm"
                    title="¿Necesitás una mano?"
                    description="Abrí una consulta y te contestamos por acá."
                  />
                )}
              </div>
              <div className="border-t border-border p-3">
                <Button
                  fullWidth
                  leftIcon={<MessageSquarePlus className="h-4 w-4" />}
                  onClick={() => setView('new')}
                >
                  Nueva consulta
                </Button>
              </div>
            </>
          )}

          {view === 'new' && (
            <div className="gd-scroll min-h-0 flex-1 overflow-y-auto">
              <NewTicketForm
                channel="CHAT"
                dense
                onCancel={() => setView('list')}
                onCreated={(created) => openChat(created.id)}
              />
            </div>
          )}

          {view === 'chat' && (
            <>
              {conversation.isPending ? (
                <div className="flex-1 space-y-3 p-3">
                  <Skeleton className="h-16 w-3/4" />
                  <Skeleton className="ml-auto h-12 w-2/3" />
                </div>
              ) : conversation.isError || !ticket ? (
                <ErrorState error={conversation.error} onRetry={conversation.refetch} size="sm" />
              ) : (
                <>
                  <ChatThread
                    dense
                    messages={conversation.messages}
                    pending={conversation.pending}
                    typingPeer={conversation.typingPeer}
                    peerReadAt={conversation.peerReadAt}
                    onRetry={conversation.retry}
                    onDiscard={conversation.discard}
                  />
                  <MessageComposer
                    dense
                    allowScreenshot
                    autoFocus
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
                        : 'Esta conversación está cerrada. Abrí una consulta nueva si necesitás algo más.'
                    }
                  />
                </>
              )}
            </>
          )}
        </section>
      )}

      <button
        type="button"
        onClick={() => setOpen((previous) => !previous)}
        aria-expanded={open}
        aria-label={open ? 'Cerrar el chat de soporte' : 'Abrir el chat de soporte'}
        className={cn(
          'relative grid h-14 w-14 place-items-center rounded-full bg-primary text-primary-foreground shadow-pop',
          'transition-transform hover:scale-105 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        )}
      >
        {open ? <X className="h-6 w-6" /> : <MessageCircle className="h-6 w-6" />}
        {!open && unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 grid min-w-[1.4rem] place-items-center rounded-full bg-crit px-1 text-xs font-semibold tabular-nums text-crit-foreground">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
        {!open && (
          <span
            className={cn(
              'absolute -bottom-0.5 -right-0.5 h-3.5 w-3.5 rounded-full border-2 border-background',
              presence.isOnline ? 'bg-ok' : 'bg-muted-foreground',
            )}
            aria-hidden="true"
          />
        )}
      </button>
    </div>
  );
}
