import { useEffect } from 'react';

/**
 * Entidades que el usuario tiene a la vista ahora (p. ej. la conversación de soporte abierta). La campana no muestra
 * toast ni suma al contador por una notificación de algo que ya está en pantalla: la pantalla lo muestra en vivo y lo
 * marca como leído (SPEC §9.6).
 *
 * Se cuentan registros (no un booleano) porque la misma entidad puede estar abierta en dos lugares a la vez.
 */
const onScreen = new Map<string, number>();

function keyOf(referenceType: string, referenceId: number): string {
  return `${referenceType}:${referenceId}`;
}

/** Registra la entidad como visible mientras el componente esté montado y `referenceId` no sea `null`. */
export function useOnScreenReference(referenceType: string, referenceId: number | null | undefined): void {
  useEffect(() => {
    if (referenceId == null) return undefined;
    const key = keyOf(referenceType, referenceId);
    onScreen.set(key, (onScreen.get(key) ?? 0) + 1);
    return () => {
      const remaining = (onScreen.get(key) ?? 1) - 1;
      if (remaining <= 0) onScreen.delete(key);
      else onScreen.set(key, remaining);
    };
  }, [referenceType, referenceId]);
}

/** `true` si la entidad de la notificación está en pantalla y la pestaña está a la vista. */
export function isReferenceOnScreen(
  referenceType: string | null | undefined,
  referenceId: number | null | undefined,
): boolean {
  if (!referenceType || referenceId == null) return false;
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return false;
  return onScreen.has(keyOf(referenceType, referenceId));
}
