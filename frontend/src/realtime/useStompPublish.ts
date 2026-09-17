import { useStomp } from './StompProvider';

/**
 * Devuelve `publish(destination, body)` para enviar mensajes al servidor (se serializa a JSON).
 * Devuelve `false` si no hay conexión en ese momento (no se encola).
 *
 * ```ts
 * const publish = useStompPublish();
 * publish(`/app/tickets/${ticketId}/typing`, { typing: true });
 * ```
 */
export function useStompPublish(): (destination: string, body?: unknown) => boolean {
  return useStomp().publish;
}
