import { useCallback, useEffect, useRef, type ElementType, type ReactNode, type RefObject } from 'react';
import { cn } from '@/lib/cn';

/**
 * Texto recortado con puntos suspensivos (docs/design-system.md §3: "`truncate` con `title` para nombres largos").
 *
 * Nombres de productos, asuntos de tickets, notas, correos y demás datos del comercio no entran en una tarjeta de
 * 390 px ni en una columna angosta. El corte está bien; perder el valor, no: estos helpers ponen el texto completo
 * en el `title` **solo cuando de verdad quedó cortado**, así el hover lo muestra sin llenar la pantalla de globos
 * en los casos en que entra entero.
 */

/** Tolerancia en px: el redondeo del layout hace que un texto justo mida ~1 px de más. */
const OVERFLOW_SLACK = 1;

const LINE_CLAMP_CLASSES = {
  1: 'truncate',
  2: 'line-clamp-2',
  3: 'line-clamp-3',
} as const;

export type TruncateLines = keyof typeof LINE_CLAMP_CLASSES;

/** Normaliza el texto que va al `title` (los saltos de línea del JSX no aportan nada en un globo). */
function tidy(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

/**
 * Un solo `ResizeObserver` para toda la app: hay un `Truncate` por nombre, correo, asunto y nota de cada fila,
 * y una tabla larga tendría cientos de observadores propios.
 */
const RESIZE_CALLBACKS = new WeakMap<Element, () => void>();
let resizeObserver: ResizeObserver | null = null;

function observeWidth(element: Element, callback: () => void): () => void {
  if (typeof ResizeObserver === 'undefined') return () => undefined;
  if (!resizeObserver) {
    resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) RESIZE_CALLBACKS.get(entry.target)?.();
    });
  }
  RESIZE_CALLBACKS.set(element, callback);
  resizeObserver.observe(element);
  return () => {
    RESIZE_CALLBACKS.delete(element);
    resizeObserver?.unobserve(element);
  };
}

/** Texto plano de un `ReactNode` simple. `undefined` si hay elementos adentro (ahí se usa el `textContent`). */
export function plainText(node: ReactNode): string | undefined {
  if (typeof node === 'string') return node;
  if (typeof node === 'number') return String(node);
  if (Array.isArray(node)) {
    const parts = node.map(plainText);
    return parts.some((part) => part === undefined) ? undefined : parts.join('');
  }
  return undefined;
}

/**
 * Pone (y saca) el `title` de un elemento recortado. Devuelve el `ref` que hay que colgarle.
 *
 * - `text`: el valor completo. Si no lo pasás se usa el `textContent` del elemento.
 * - `enabled`: `false` cuando el elemento ya tiene un `title` propio; no se toca nada.
 *
 * Se vuelve a medir cuando cambia el ancho (ventana, riel plegado, columna), cuando terminan de cargar las
 * tipografías y después de cada render (la fila pudo cambiar de dato sin desmontarse).
 */
export function useTruncationTitle<T extends HTMLElement = HTMLElement>(
  text?: string,
  enabled = true,
): RefObject<T> {
  const ref = useRef<T>(null);
  const textRef = useRef(text);
  textRef.current = text;
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  const sync = useCallback(() => {
    const element = ref.current;
    if (!element) return;
    if (!enabledRef.current) return;
    const cut =
      element.scrollWidth > element.clientWidth + OVERFLOW_SLACK ||
      element.scrollHeight > element.clientHeight + OVERFLOW_SLACK;
    const full = tidy(textRef.current ?? element.textContent ?? '');
    if (cut && full) element.setAttribute('title', full);
    else element.removeAttribute('title');
  }, []);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    // Escribir un atributo no invalida el layout, así que el callback no se vuelve a disparar a sí mismo.
    const unobserve = observeWidth(element, sync);
    let active = true;
    void document.fonts?.ready.then(() => {
      if (active) sync();
    });
    return () => {
      active = false;
      unobserve();
    };
  }, [sync]);

  // Después de cada render: el mismo nodo puede mostrar otro dato (otra página de la tabla, otro filtro).
  useEffect(sync);

  return ref;
}

export interface TruncateProps {
  /** Etiqueta o componente a renderizar (`span` por defecto; sirve `p`, `div`, `dd`, `Link`…). */
  as?: ElementType;
  /** 1 (defecto) = una línea con `truncate`; 2 o 3 = `line-clamp`. */
  lines?: TruncateLines;
  /** Texto completo para el `title` cuando los hijos no son texto plano (ícono + texto, `<strong>`…). */
  fullText?: string;
  /** Un `title` propio gana: se muestra siempre, entre o no entre. */
  title?: string;
  className?: string;
  children?: ReactNode;
  [prop: string]: unknown;
}

/**
 * Texto de una (o `lines`) línea(s) que, si queda cortado, muestra el valor completo en el `title`.
 *
 * ```tsx
 * <Truncate className="font-semibold">{row.productName}</Truncate>
 * <Truncate as={Link} to={`/app/products/${row.id}`}>{row.productName}</Truncate>
 * ```
 */
export function Truncate({
  as: Tag = 'span',
  lines = 1,
  fullText,
  title,
  className,
  children,
  ...rest
}: TruncateProps) {
  const ref = useTruncationTitle<HTMLElement>(fullText ?? plainText(children), title === undefined);
  const Component = Tag as ElementType;
  return (
    <Component ref={ref} title={title} className={cn(LINE_CLAMP_CLASSES[lines], className)} {...rest}>
      {children}
    </Component>
  );
}
