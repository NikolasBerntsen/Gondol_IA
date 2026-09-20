import { cn } from '@/lib/cn';

/**
 * Aire para que la burbuja de soporte no tape el final de la pantalla (docs/design-system.md §4).
 *
 * Va **arriba** de una barra de acción pegada abajo (la marcada con `data-bottom-action-bar`: carga de
 * mercadería, asistente de importación, cobro del POS en mobile). En esas pantallas la burbuja se corre
 * justo arriba de la barra, así que el aire tiene que quedar entre el contenido y la barra: si se pusiera
 * como `padding-bottom` del contenedor de scroll, la barra se despegaría del borde inferior al llegar al
 * final (el `position: sticky` se frena en la caja de contenido de su contenedor).
 *
 * En las pantallas **sin** barra no hace falta: el `<main>` del shell ya suma el aire (`--gd-scroll-space`).
 */
export function BubbleSpacer({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('h-[var(--support-bubble-space,0px)] shrink-0', className)} />;
}
