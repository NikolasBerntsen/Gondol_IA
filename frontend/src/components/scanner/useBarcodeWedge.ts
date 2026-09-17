import { useEffect, useRef } from 'react';

export interface BarcodeWedgeOptions {
  /** Desactiva el lector sin desmontar el hook. */
  enabled?: boolean;
  /** Largo mínimo del código para considerarlo válido (defecto 6). */
  minLength?: number;
  /** Máximo de milisegundos entre teclas de la misma ráfaga (defecto 50). Un humano tipea mucho más lento. */
  maxKeyIntervalMs?: number;
  /**
   * `true` para capturar también mientras se escribe en un input o textarea.
   * Por defecto se ignora: el lector ya escribe en el campo enfocado.
   */
  captureWhileTyping?: boolean;
  /** Se llama con el código completo cuando el lector manda Enter. */
  onScan: (code: string) => void;
}

const TYPING_TAGS = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

function isTyping(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element) return false;
  if (element.isContentEditable) return true;
  return TYPING_TAGS.has(element.tagName);
}

/**
 * Lectores USB "keyboard wedge" (los de caja): escriben el código muy rápido y terminan con Enter.
 * El hook escucha en toda la página, arma la ráfaga y devuelve el código completo.
 *
 * Ráfaga = teclas separadas por menos de `maxKeyIntervalMs`. Si alguien tipea a mano, el intervalo
 * es mayor y no se dispara. Mientras el foco está en un campo de texto no captura (salvo
 * `captureWhileTyping`), así el POS puede tener su propio input siempre enfocado.
 *
 * ```ts
 * useBarcodeWedge({ onScan: (code) => addToCart(code) });
 * ```
 */
export function useBarcodeWedge({
  enabled = true,
  minLength = 6,
  maxKeyIntervalMs = 50,
  captureWhileTyping = false,
  onScan,
}: BarcodeWedgeOptions): void {
  const bufferRef = useRef('');
  const lastKeyRef = useRef(0);
  const onScanRef = useRef(onScan);
  onScanRef.current = onScan;

  useEffect(() => {
    if (!enabled) return;

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.altKey || event.metaKey) return;
      if (!captureWhileTyping && isTyping(event.target)) return;

      const now = Date.now();
      if (now - lastKeyRef.current > maxKeyIntervalMs) bufferRef.current = '';
      lastKeyRef.current = now;

      if (event.key === 'Enter') {
        const code = bufferRef.current.trim();
        bufferRef.current = '';
        if (code.length >= minLength) {
          event.preventDefault();
          onScanRef.current(code);
        }
        return;
      }

      // Solo caracteres imprimibles: los códigos de barras son alfanuméricos.
      if (event.key.length === 1) bufferRef.current += event.key;
      else bufferRef.current = '';
    };

    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [enabled, minLength, maxKeyIntervalMs, captureWhileTyping]);
}
