import type { IMessage } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';
import { useStomp } from './StompProvider';

export type StompPayloadHandler<T> = (payload: T, message: IMessage) => void;

function parseBody<T>(message: IMessage): T {
  if (!message.body) return undefined as T;
  try {
    return JSON.parse(message.body) as T;
  } catch {
    return message.body as unknown as T;
  }
}

/**
 * Se suscribe a un destino STOMP mientras el componente está montado (y `enabled`).
 * La suscripción sobrevive reconexiones y NO se rehace cuando cambia `handler` (siempre se usa el último).
 *
 * ```ts
 * useStompSubscription<NotificationDto>('/user/queue/notifications', (n) => toast(n.title));
 * useStompSubscription(`/topic/tickets/${id}`, onTicketEvent, !!id);
 * ```
 */
export function useStompSubscription<T = unknown>(
  destination: string | null | undefined,
  handler: StompPayloadHandler<T>,
  enabled = true,
): void {
  const { subscribe } = useStomp();
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    if (!enabled || !destination) return;
    return subscribe(destination, (message) => handlerRef.current(parseBody<T>(message), message));
  }, [destination, enabled, subscribe]);
}
