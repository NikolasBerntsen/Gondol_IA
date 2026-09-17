import { createContext, useContext } from 'react';

export interface ShellLayout {
  /** `true` en la variante compacta (`/app/pos`): la página maneja su padding y su propio scroll. */
  compact: boolean;
  /** `false` fuera del AppShell (p. ej. la página de impresión del ticket). */
  inShell: boolean;
}

export const ShellLayoutContext = createContext<ShellLayout>({ compact: false, inShell: false });

/**
 * Layout del shell para la página abierta. Útil para las pantallas que pueden aparecer en las dos
 * variantes (p. ej. `ModuleDisabledPage` en `/app/pos`) y necesitan agregar su propio padding.
 * Fuera del shell (`inShell: false`) la página pone todo su layout.
 *
 * ```tsx
 * const { compact, inShell } = useShellLayout();
 * <div className={cn((compact || !inShell) && 'px-4 pt-5 sm:px-6 lg:px-8')}>…</div>
 * ```
 */
export function useShellLayout(): ShellLayout {
  return useContext(ShellLayoutContext);
}
