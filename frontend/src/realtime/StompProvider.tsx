import { Client, ReconnectionTimeMode, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useAuth } from '@/auth/AuthContext';
import { tokenStorage } from '@/auth/tokenStorage';

export type StompMessageHandler = (message: IMessage) => void;

export interface StompContextValue {
  /** `true` mientras hay una sesión STOMP establecida. */
  connected: boolean;
  /**
   * Registra una suscripción que se restablece sola en cada reconexión.
   * Devuelve la función para cancelarla. Preferí el hook `useStompSubscription`.
   */
  subscribe: (destination: string, handler: StompMessageHandler) => () => void;
  /** Envía un mensaje (`body` se serializa a JSON). Devuelve `false` si no hay conexión. */
  publish: (destination: string, body?: unknown) => boolean;
}

const StompContext = createContext<StompContextValue | null>(null);

interface Registration {
  destination: string;
  handler: StompMessageHandler;
  subscription: StompSubscription | null;
}

/** Tiempo mínimo entre verificaciones de sesión cuando el servidor rechaza el CONNECT. */
const SESSION_CHECK_INTERVAL_MS = 30_000;

/** `ws(s)://<host actual>/ws` (en dev lo proxea Vite; en producción nginx). */
export function buildBrokerUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${window.location.host}/ws`;
}

/**
 * Conexión STOMP única de la app (SPEC §7, §9.6). Conecta al autenticarse, se reconecta con backoff
 * exponencial y vuelve a suscribir todos los destinos registrados.
 */
export function StompProvider({ children }: { children: ReactNode }) {
  const { token, isAuthenticated, refreshMe } = useAuth();
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const registry = useRef(new Map<number, Registration>());
  const nextId = useRef(1);
  const lastSessionCheck = useRef(0);
  const refreshMeRef = useRef(refreshMe);
  refreshMeRef.current = refreshMe;

  useEffect(() => {
    if (!isAuthenticated || !token) return;

    const resetSubscriptions = () => {
      for (const registration of registry.current.values()) registration.subscription = null;
    };

    const client = new Client({
      brokerURL: buildBrokerUrl(),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2_000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 30_000,
      heartbeatIncoming: 15_000,
      heartbeatOutgoing: 15_000,
      connectionTimeout: 10_000,
      debug: () => undefined,
      beforeConnect: (self) => {
        self.connectHeaders = { Authorization: `Bearer ${tokenStorage.get() ?? token}` };
      },
      onConnect: () => {
        if (clientRef.current !== client) return;
        for (const registration of registry.current.values()) {
          registration.subscription = client.subscribe(registration.destination, registration.handler);
        }
        setConnected(true);
      },
      onWebSocketClose: () => {
        if (clientRef.current !== client) return;
        resetSubscriptions();
        setConnected(false);
      },
      onStompError: () => {
        if (clientRef.current !== client) return;
        setConnected(false);
        // Un CONNECT rechazado suele ser token vencido o comercio bloqueado: se valida la sesión por HTTP
        // (si corresponde, el interceptor cierra la sesión con el mensaje del backend).
        const now = Date.now();
        if (now - lastSessionCheck.current > SESSION_CHECK_INTERVAL_MS) {
          lastSessionCheck.current = now;
          refreshMeRef.current().catch(() => undefined);
        }
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      clientRef.current = null;
      resetSubscriptions();
      setConnected(false);
      void client.deactivate();
    };
  }, [isAuthenticated, token]);

  const subscribe = useCallback((destination: string, handler: StompMessageHandler) => {
    const id = nextId.current++;
    const registration: Registration = { destination, handler, subscription: null };
    registry.current.set(id, registration);
    const client = clientRef.current;
    if (client?.connected) {
      registration.subscription = client.subscribe(destination, handler);
    }
    return () => {
      registry.current.delete(id);
      const subscription = registration.subscription;
      registration.subscription = null;
      if (subscription && clientRef.current?.connected) {
        try {
          subscription.unsubscribe();
        } catch {
          // La conexión se cerró entre medio: no hay nada que cancelar.
        }
      }
    };
  }, []);

  const publish = useCallback((destination: string, body?: unknown) => {
    const client = clientRef.current;
    if (!client?.connected) return false;
    client.publish({
      destination,
      body: body === undefined ? '' : JSON.stringify(body),
      headers: { 'content-type': 'application/json' },
    });
    return true;
  }, []);

  const value = useMemo<StompContextValue>(() => ({ connected, subscribe, publish }), [connected, subscribe, publish]);

  return <StompContext.Provider value={value}>{children}</StompContext.Provider>;
}

export function useStomp(): StompContextValue {
  const context = useContext(StompContext);
  if (!context) throw new Error('useStomp debe usarse dentro de <StompProvider>.');
  return context;
}

/** `true` si la conexión en tiempo real está activa (para indicadores "en vivo"). */
export function useStompConnected(): boolean {
  return useStomp().connected;
}
