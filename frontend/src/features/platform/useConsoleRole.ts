import { useAuth } from '@/auth/AuthContext';

export interface ConsoleRole {
  /** `true` si mira soporte (Clientes y Módulos por cliente, para resolver tickets). */
  isSupport: boolean;
  /** Rótulo de la consola sobre el título de la pantalla. */
  eyebrow: string;
}

/**
 * Clientes y Módulos por cliente los comparten el dueño y soporte (SPEC §3.3). Esto da solo los textos que cambian
 * según quién mira; qué botones ve cada uno sale de `useAccess()` (`platform.*`).
 */
export function useConsoleRole(): ConsoleRole {
  const { me } = useAuth();
  const isSupport = me?.role === 'SUPPORT_AGENT';
  return { isSupport, eyebrow: isSupport ? 'Consola de soporte' : 'Consola de dueños' };
}
