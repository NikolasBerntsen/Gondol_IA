import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, getErrorMessage } from '@/api/client';
import { notificationKeys } from '@/api/notifications';
import type { MessageSenderType } from '@/api/types';
import { useCurrentUser } from '@/auth/AuthContext';
import { useOnScreenReference } from '@/components/notifications/onScreenReferences';
import { useStompConnected } from '@/realtime/StompProvider';
import { useStompSubscription } from '@/realtime/useStompSubscription';
import { conversationApi, supportKeys, type SupportSide } from '../api';
import type {
  PendingMessage,
  SendMessagePayload,
  SupportMessage,
  TicketDetail,
  TicketEvent,
} from '../types';

export interface TypingPeer {
  userId: number;
  name: string;
  senderType: MessageSenderType;
}

/** Refresca listas, bandeja y tablero después de tocar una conversación (el detalle se actualiza en el momento). */
export function invalidateSupportLists(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: ['support', 'tenant', 'list'] });
  queryClient.invalidateQueries({ queryKey: ['support', 'agent', 'list'] });
  queryClient.invalidateQueries({ queryKey: supportKeys.stats() });
}

/** Vista previa del último mensaje, con la misma regla que el backend. */
export function messagePreview(message: SupportMessage): string {
  const body = message.body?.replace(/\s+/g, ' ').trim();
  if (body) return body.length <= 140 ? body : `${body.slice(0, 139)}…`;
  return 'Imagen adjunta';
}

function sortMessages(messages: SupportMessage[]): SupportMessage[] {
  return [...messages].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id - b.id);
}

function withMessage(ticket: TicketDetail | undefined, message: SupportMessage): TicketDetail | undefined {
  if (!ticket) return ticket;
  if (ticket.messages.some((existing) => existing.id === message.id)) return ticket;
  return {
    ...ticket,
    messages: sortMessages([...ticket.messages, message]),
    lastMessageAt: message.createdAt,
    lastMessagePreview: messagePreview(message),
  };
}

/** Mismo `referenceType` que usa el backend en las notificaciones de soporte (`SupportNotifier`). */
const TICKET_REFERENCE = 'SUPPORT_TICKET';

type TicketUpdatedEvent = Extract<TicketEvent, { event: 'TICKET_UPDATED' }>;

/**
 * Los eventos del tópico son compartidos por las dos partes: el contador de no leídos propio no se pisa. Además del
 * resumen, `TICKET_UPDATED` trae el comentario de la calificación y la primera respuesta (no están en el resumen).
 */
function withTicketFields(ticket: TicketDetail | undefined, event: TicketUpdatedEvent): TicketDetail | undefined {
  if (!ticket) return ticket;
  const { unreadCount: _ignored, ...fields } = event.ticket;
  const next: TicketDetail = { ...ticket, ...fields };
  if (event.ratingComment !== undefined) next.ratingComment = event.ratingComment;
  if (event.firstResponseAt !== undefined) next.firstResponseAt = event.firstResponseAt;
  return next;
}

/**
 * ¿Vale la pena reintentar el envío? Sí ante errores de red, demoras o del servidor; no si el servidor rechazó el
 * mensaje por su contenido (imagen inválida, conversación cerrada…): el mismo pedido va a fallar igual.
 */
function isRetryable(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true;
  return error.status === 0 || error.status === 408 || error.status === 429 || error.status >= 500;
}

const TYPING_TIMEOUT_MS = 6_000;
const READ_DEBOUNCE_MS = 400;

export interface ConversationOptions {
  ticketId: number | null;
  side: SupportSide;
  /** Marca la conversación como leída al abrirla y cuando llega un mensaje del otro lado. */
  autoRead?: boolean;
  /** Se llama cuando entra un mensaje del otro lado (para el sonido de la consola). */
  onIncoming?: (message: SupportMessage) => void;
}

/**
 * Una conversación de soporte, viva: detalle por REST, mensajes y estado por `/topic/tickets/{id}` (SPEC §7),
 * **envío optimista** (el mensaje aparece al instante y después se reconcilia con el del servidor), indicador de
 * escritura y acuse de lectura.
 *
 * Sirve para los tres lugares donde se chatea: la consola del agente, la pantalla del comercio y el widget flotante.
 */
export function useTicketConversation({ ticketId, side, autoRead = true, onIncoming }: ConversationOptions) {
  const me = useCurrentUser();
  const queryClient = useQueryClient();
  const api = useMemo(() => conversationApi(side), [side]);
  const queryKey = ticketId == null ? supportKeys.ticket(side, -1) : supportKeys.ticket(side, ticketId);

  const [pending, setPending] = useState<PendingMessage[]>([]);
  const [typingPeer, setTypingPeer] = useState<TypingPeer | null>(null);
  const [peerReadAt, setPeerReadAt] = useState<string | null>(null);
  const payloads = useRef(new Map<string, SendMessagePayload>());
  const typingTimer = useRef<number | null>(null);
  const readTimer = useRef<number | null>(null);
  const incomingRef = useRef(onIncoming);
  incomingRef.current = onIncoming;

  const detail = useQuery({
    queryKey,
    queryFn: () => api.get(ticketId as number),
    enabled: ticketId != null,
  });

  // Conversación a la vista: la campana no avisa con toast lo que ya se ve acá (SPEC §9.6).
  useOnScreenReference(TICKET_REFERENCE, ticketId);

  const markReadMutation = useMutation({
    mutationFn: (id: number) => api.markRead(id),
    onSuccess: () => {
      queryClient.setQueryData<TicketDetail>(queryKey, (prev) => (prev ? { ...prev, unreadCount: 0 } : prev));
      invalidateSupportLists(queryClient);
      // Leer la conversación también deja leídas sus notificaciones (en el backend): refrescar la campana.
      queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
    meta: { errorToast: false },
  });

  const markRead = useCallback(() => {
    if (ticketId == null) return;
    markReadMutation.mutate(ticketId);
  }, [markReadMutation, ticketId]);
  const markReadRef = useRef(markRead);
  markReadRef.current = markRead;

  const scheduleRead = useCallback(() => {
    if (!autoRead || (typeof document !== 'undefined' && document.visibilityState === 'hidden')) return;
    if (readTimer.current !== null) window.clearTimeout(readTimer.current);
    readTimer.current = window.setTimeout(() => markReadRef.current(), READ_DEBOUNCE_MS);
  }, [autoRead]);

  // Al abrir una conversación con mensajes sin leer, acusar recibo.
  useEffect(() => {
    if (!autoRead || ticketId == null) return;
    if ((detail.data?.unreadCount ?? 0) > 0) scheduleRead();
  }, [autoRead, detail.data?.unreadCount, scheduleRead, ticketId]);

  // Limpieza de temporizadores al cambiar de conversación.
  useEffect(() => {
    setTypingPeer(null);
    setPeerReadAt(null);
    setPending([]);
    payloads.current.clear();
    return () => {
      if (typingTimer.current !== null) window.clearTimeout(typingTimer.current);
      if (readTimer.current !== null) window.clearTimeout(readTimer.current);
    };
  }, [ticketId]);

  useStompSubscription<TicketEvent>(
    ticketId == null ? null : `/topic/tickets/${ticketId}`,
    (event) => {
      switch (event.event) {
        case 'MESSAGE': {
          queryClient.setQueryData<TicketDetail>(queryKey, (prev) => withMessage(prev, event.message));
          invalidateSupportLists(queryClient);
          if (event.message.senderId !== me.id) {
            if (event.message.senderType !== 'SYSTEM') incomingRef.current?.(event.message);
            scheduleRead();
          }
          break;
        }
        case 'TICKET_UPDATED':
          queryClient.setQueryData<TicketDetail>(queryKey, (prev) => withTicketFields(prev, event));
          invalidateSupportLists(queryClient);
          break;
        case 'TYPING': {
          if (event.userId === me.id) break;
          if (typingTimer.current !== null) window.clearTimeout(typingTimer.current);
          if (!event.typing) {
            setTypingPeer(null);
            break;
          }
          setTypingPeer({ userId: event.userId, name: event.name, senderType: event.senderType });
          typingTimer.current = window.setTimeout(() => setTypingPeer(null), TYPING_TIMEOUT_MS);
          break;
        }
        case 'READ': {
          const mine: MessageSenderType = side === 'agent' ? 'AGENT' : 'CUSTOMER';
          if (event.senderType !== mine) setPeerReadAt(event.readAt);
          break;
        }
      }
    },
  );

  const releasePending = useCallback((tempId: string) => {
    setPending((prev) => {
      const item = prev.find((entry) => entry.tempId === tempId);
      if (item?.previewUrl) URL.revokeObjectURL(item.previewUrl);
      return prev.filter((entry) => entry.tempId !== tempId);
    });
    payloads.current.delete(tempId);
  }, []);

  const isLive = useStompConnected();

  const sendMutation = useMutation({
    mutationFn: ({ tempId, payload }: { tempId: string; payload: SendMessagePayload }) =>
      api.sendMessage(ticketId as number, payload).then((message) => ({ tempId, message })),
    onSuccess: ({ tempId, message }) => {
      queryClient.setQueryData<TicketDetail>(queryKey, (prev) => withMessage(prev, message));
      invalidateSupportLists(queryClient);
      // Escribir deja leídas las notificaciones propias de esta conversación.
      queryClient.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
      releasePending(tempId);
    },
    onError: (error, { tempId }) => {
      setPending((prev) =>
        prev.map((item) =>
          item.tempId === tempId
            ? { ...item, failed: true, error: getErrorMessage(error), retryable: isRetryable(error) }
            : item,
        ),
      );
    },
  });

  const send = useCallback(
    (payload: SendMessagePayload) => {
      if (ticketId == null) return;
      const body = payload.body?.trim() || null;
      if (!body && !payload.file) return;
      const tempId = `tmp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      payloads.current.set(tempId, { ...payload, body });
      setPending((prev) => [
        ...prev,
        {
          tempId,
          body,
          previewUrl: payload.file ? URL.createObjectURL(payload.file) : null,
          fileName: payload.fileName ?? (payload.file instanceof File ? payload.file.name : null),
          createdAt: new Date().toISOString(),
          failed: false,
        },
      ]);
      sendMutation.mutate({ tempId, payload: { ...payload, body } });
    },
    [sendMutation, ticketId],
  );

  const retry = useCallback(
    (tempId: string) => {
      const payload = payloads.current.get(tempId);
      if (!payload) return;
      setPending((prev) =>
        prev.map((item) =>
          item.tempId === tempId ? { ...item, failed: false, error: undefined, retryable: undefined } : item,
        ),
      );
      sendMutation.mutate({ tempId, payload });
    },
    [sendMutation],
  );

  // Liberar los object URL de las vistas previas al desmontar.
  useEffect(
    () => () => {
      setPending((prev) => {
        prev.forEach((item) => item.previewUrl && URL.revokeObjectURL(item.previewUrl));
        return [];
      });
    },
    [],
  );

  return {
    ticket: detail.data,
    messages: detail.data?.messages ?? [],
    pending,
    isPending: detail.isPending && ticketId != null,
    isError: detail.isError,
    error: detail.error,
    refetch: detail.refetch,
    send,
    retry,
    discard: releasePending,
    sending: sendMutation.isPending,
    typingPeer,
    peerReadAt,
    markRead,
    isLive,
  };
}
