import { useEffect, useState, type RefObject } from 'react';

/**
 * Atributo que marca una barra de acción pegada abajo de la pantalla (la barra "Cobrar" del POS en mobile, el
 * "Registrar ingreso" de la carga de mercadería). El botón flotante de soporte se corre arriba de cualquier elemento
 * con esta marca para no tapar la acción principal (SPEC §9.5, §15.3).
 */
export const BOTTOM_ACTION_BAR_ATTR = 'data-bottom-action-bar';

const SELECTOR = `[${BOTTOM_ACTION_BAR_ATTR}]`;
/** Separación entre la barra y el botón flotante. */
const GAP_PX = 12;

/**
 * Distancia (en px) que hay que dejar entre el borde inferior de la ventana y el botón flotante para que no tape una
 * barra marcada con {@link BOTTOM_ACTION_BAR_ATTR}. Devuelve `0` si no hay ninguna barra visible debajo del botón.
 *
 * Solo cuenta las barras visibles (en `lg` el POS oculta la suya), que están en la mitad de abajo de la pantalla y
 * que comparten columna con el botón (`anchorRef`). Se recalcula al desplazarse (en la ventana o en un contenedor,
 * como el `<main>` del POS), al cambiar el tamaño de la ventana o de la barra y cuando la barra aparece o desaparece.
 */
export function useBottomBarClearance(anchorRef: RefObject<HTMLElement>, enabled = true): number {
  const [clearance, setClearance] = useState(0);

  useEffect(() => {
    if (!enabled || typeof window === 'undefined') {
      setClearance(0);
      return undefined;
    }

    let frame = 0;
    const observedBars = new Set<Element>();
    const resizeObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => schedule());

    function measure() {
      frame = 0;
      const bars = Array.from(document.querySelectorAll<HTMLElement>(SELECTOR));

      if (resizeObserver) {
        observedBars.forEach((bar) => {
          if (!bars.includes(bar as HTMLElement)) {
            resizeObserver.unobserve(bar);
            observedBars.delete(bar);
          }
        });
        bars.forEach((bar) => {
          if (!observedBars.has(bar)) {
            resizeObserver.observe(bar);
            observedBars.add(bar);
          }
        });
      }

      const anchor = anchorRef.current;
      let next = 0;
      if (anchor && bars.length > 0) {
        const viewportHeight = window.innerHeight;
        const anchorRect = anchor.getBoundingClientRect();
        for (const bar of bars) {
          const rect = bar.getBoundingClientRect();
          const visible = rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.top < viewportHeight;
          const sameColumn = rect.right > anchorRect.left && rect.left < anchorRect.right;
          const atTheBottom = rect.bottom > viewportHeight / 2;
          if (visible && sameColumn && atTheBottom) {
            next = Math.max(next, Math.ceil(viewportHeight - rect.top) + GAP_PX);
          }
        }
      }
      setClearance((previous) => (previous === next ? previous : next));
    }

    function schedule() {
      if (frame === 0) frame = window.requestAnimationFrame(measure);
    }

    // Las barras aparecen y desaparecen con la pantalla (p. ej. al elegir un producto en la carga).
    const mutationObserver = new MutationObserver(schedule);
    mutationObserver.observe(document.body, { childList: true, subtree: true });
    // `capture`: los eventos de scroll de un contenedor no burbujean hasta la ventana.
    window.addEventListener('scroll', schedule, { capture: true, passive: true });
    window.addEventListener('resize', schedule);
    schedule();

    return () => {
      if (frame !== 0) window.cancelAnimationFrame(frame);
      mutationObserver.disconnect();
      resizeObserver?.disconnect();
      window.removeEventListener('scroll', schedule, { capture: true });
      window.removeEventListener('resize', schedule);
    };
  }, [anchorRef, enabled]);

  return clearance;
}
