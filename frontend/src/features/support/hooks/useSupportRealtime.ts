import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import type { PresenceDto } from '@/api/types';
import { useStomp } from '@/realtime/StompProvider';
import { useStompPublish } from '@/realtime/useStompPublish';
import { useStompSubscription } from '@/realtime/useStompSubscription';
import { supportKeys, supportPresenceApi } from '../api';
import type { TicketEvent } from '../types';

/**
 * ¿Hay alguien de soporte conectado? Estado inicial por REST y actualizaciones por
 * `/topic/support/presence` (SPEC §6.2, §7). Lo usan el widget flotante y la pantalla de soporte del comercio.
 */
export function useSupportPresence(enabled = true) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: supportKeys.presence(),
    queryFn: supportPresenceApi.get,
    enabled,
    staleTime: 60_000,
  });

  useStompSubscription<PresenceDto>(
    '/topic/support/presence',
    (presence) => queryClient.setQueryData(supportKeys.presence(), presence),
    enabled,
  );

  return {
    agentsOnline: query.data?.agentsOnline ?? 0,
    isOnline: (query.data?.agentsOnline ?? 0) > 0,
    isPending: query.isPending,
  };
}

/**
 * Escucha varias conversaciones a la vez (`/topic/tickets/{id}`): la lista del widget y la bandeja siguen vivas
 * aunque el chat abierto sea otro. Las suscripciones se rehacen solo cuando cambia el conjunto de ids.
 */
export function useTicketTopics(ticketIds: number[], handler: (event: TicketEvent, ticketId: number) => void): void {
  const { subscribe } = useStomp();
  const handlerRef = useRef(handler);
  handlerRef.current = handler;
  const key = ticketIds.join(',');

  useEffect(() => {
    if (!key) return;
    const ids = key.split(',').map(Number);
    const unsubscribes = ids.map((id) =>
      subscribe(`/topic/tickets/${id}`, (message) => {
        if (!message.body) return;
        try {
          handlerRef.current(JSON.parse(message.body) as TicketEvent, id);
        } catch {
          // Un mensaje ilegible no puede romper el chat.
        }
      }),
    );
    return () => unsubscribes.forEach((off) => off());
  }, [key, subscribe]);
}

const TYPING_THROTTLE_MS = 2_000;
const TYPING_IDLE_MS = 3_500;

/**
 * Avisa "está escribiendo…" al otro lado (`/app/tickets/{id}/typing`, SPEC §7) sin inundar el canal: manda `true`
 * como mucho cada 2 s y `false` a los 3,5 s sin teclear (o al enviar).
 */
export function useTypingNotifier(ticketId: number | null) {
  const publish = useStompPublish();
  const lastSentAt = useRef(0);
  const idleTimer = useRef<number | null>(null);
  const active = useRef(false);

  const send = useCallback(
    (typing: boolean) => {
      if (ticketId == null) return;
      publish(`/app/tickets/${ticketId}/typing`, { typing });
    },
    [publish, ticketId],
  );

  const stop = useCallback(() => {
    if (idleTimer.current !== null) {
      window.clearTimeout(idleTimer.current);
      idleTimer.current = null;
    }
    if (active.current) {
      active.current = false;
      lastSentAt.current = 0;
      send(false);
    }
  }, [send]);

  const notify = useCallback(() => {
    if (ticketId == null) return;
    const now = Date.now();
    if (!active.current || now - lastSentAt.current > TYPING_THROTTLE_MS) {
      active.current = true;
      lastSentAt.current = now;
      send(true);
    }
    if (idleTimer.current !== null) window.clearTimeout(idleTimer.current);
    idleTimer.current = window.setTimeout(stop, TYPING_IDLE_MS);
  }, [send, stop, ticketId]);

  // Al cambiar de conversación o desmontar, dejar de anunciar.
  useEffect(() => stop, [stop, ticketId]);

  return useMemo(() => ({ notify, stop }), [notify, stop]);
}

/**
 * Aviso sonoro corto de mensaje nuevo, generado con la Web Audio API (sin archivos ni CDN). Si el navegador todavía
 * no habilitó el audio (hace falta un gesto del usuario), no pasa nada: el aviso visual alcanza.
 */
export function useMessageChime() {
  const contextRef = useRef<AudioContext | null>(null);

  useEffect(
    () => () => {
      void contextRef.current?.close();
      contextRef.current = null;
    },
    [],
  );

  return useCallback(() => {
    try {
      const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return;
      const context = contextRef.current ?? new Ctor();
      contextRef.current = context;
      if (context.state === 'suspended') void context.resume();
      const now = context.currentTime;
      const gain = context.createGain();
      gain.gain.setValueAtTime(0.0001, now);
      gain.gain.exponentialRampToValueAtTime(0.09, now + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.34);
      gain.connect(context.destination);
      [880, 1320].forEach((frequency, index) => {
        const oscillator = context.createOscillator();
        oscillator.type = 'sine';
        oscillator.frequency.setValueAtTime(frequency, now + index * 0.11);
        oscillator.connect(gain);
        oscillator.start(now + index * 0.11);
        oscillator.stop(now + 0.1 + index * 0.11);
      });
    } catch {
      // El audio es un extra: nunca puede romper la pantalla.
    }
  }, []);
}
