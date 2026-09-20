import { useEffect, useState } from 'react';

/** Variable de CSS que publica el aire que necesita la burbuja (la lee `index.css`). */
export const SUPPORT_BUBBLE_SPACE_VAR = '--support-bubble-space';

/**
 * Aire que la burbuja de soporte reserva al final de cada pantalla:
 * botón (56) + separación con el borde (16) + margen contra la última fila (16).
 */
export const SUPPORT_BUBBLE_SPACE_PX = 88;

/**
 * Publica `--support-bubble-space` en `<html>` mientras la burbuja está montada, para que los
 * contenedores de scroll (el `<main>` del shell y las pantallas con barra de acción abajo) dejen
 * ese aire y la última fila/tarjeta/botón se pueda desplazar por encima de la burbuja.
 *
 * Con `enabled = false` (pantalla de soporte, donde la burbuja no se muestra) la variable vuelve a 0.
 */
export function useSupportBubbleSpace(enabled: boolean): void {
  useEffect(() => {
    if (!enabled || typeof document === 'undefined') return undefined;
    const root = document.documentElement;
    root.style.setProperty(SUPPORT_BUBBLE_SPACE_VAR, `${SUPPORT_BUBBLE_SPACE_PX}px`);
    return () => {
      root.style.removeProperty(SUPPORT_BUBBLE_SPACE_VAR);
    };
  }, [enabled]);
}

/** Diálogos y hojas de Radix abiertos (Modal, ConfirmDialog, hoja de cobro, alerta de recall…). */
const MODAL_SELECTOR = '[role="dialog"][data-state="open"], [role="alertdialog"][data-state="open"]';

/**
 * `true` mientras hay un diálogo o una hoja abierta. La burbuja se esconde: no tiene que verse
 * a través del velo ni tapar el pie de un diálogo anclado abajo (SPEC §9.6).
 */
export function useModalOpen(): boolean {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (typeof document === 'undefined') return undefined;
    let frame = 0;
    const check = () => {
      frame = 0;
      setOpen(document.querySelector(MODAL_SELECTOR) !== null);
    };
    const schedule = () => {
      if (frame === 0) frame = window.requestAnimationFrame(check);
    };
    const observer = new MutationObserver(schedule);
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-state'],
    });
    check();
    return () => {
      if (frame !== 0) window.cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, []);

  return open;
}
