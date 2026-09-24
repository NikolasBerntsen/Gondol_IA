import {
  forwardRef,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'react-router-dom';
import { cn } from '@/lib/cn';
import { useOnClickOutside } from '@/lib/useOnClickOutside';
import { Truncate } from './Truncate';

const ITEM_SELECTOR = '[role="menuitem"]:not([aria-disabled="true"]),[role="menuitemradio"]:not([aria-disabled="true"])';

export interface UseDropdownOptions {
  /** Rol ARIA del panel: `menu` (acciones) o `dialog` (contenido libre, p. ej. notificaciones). */
  kind?: 'menu' | 'dialog';
  /**
   * Qué ítem recibe el foco al abrir un menú: el marcado (`aria-checked="true"`, p. ej. la sucursal elegida) o
   * siempre el primero (menús de acciones que además incluyen una opción marcada, como el tema en el menú de usuario).
   */
  initialFocus?: 'checked' | 'first';
  /**
   * El panel va flotando: en un portal sobre `document.body`, con `position: fixed` contra el disparador. Usalo en los
   * menús que viven dentro de una tabla o de cualquier contenedor con `overflow` (p. ej. las acciones de una fila): el
   * panel no estira ni lo recorta el contenedor, se abre hacia arriba si abajo no hay lugar y sigue al disparador con
   * el scroll. Si el scroll deja el disparador fuera de la pantalla o tapado (por la barra superior fija o el borde de
   * la tabla), el menú se cierra: no queda flotando suelto sobre otra cosa. Los menús de la barra superior no lo
   * necesitan.
   */
  floating?: boolean;
}

/**
 * `true` si ya no se ve el disparador de un panel flotante: su centro quedó fuera de la pantalla, debajo de otra cosa
 * (la barra superior, que es `sticky`) o recortado por un contenedor con scroll (en ese punto se ve lo que está
 * detrás). El panel mismo no cuenta: cuando no entra de ningún lado puede quedar sobre su botón.
 */
export function anchorHidden(anchor: HTMLElement, panel: HTMLElement | null): boolean {
  const rect = anchor.getBoundingClientRect();
  const root = document.documentElement;
  const x = (rect.left + rect.right) / 2;
  const y = (rect.top + rect.bottom) / 2;
  if (x < 0 || y < 0 || x > root.clientWidth || y > root.clientHeight) return true;
  // jsdom no lo implementa: ahí alcanza con la pantalla.
  if (typeof document.elementFromPoint !== 'function') return false;
  const hit = document.elementFromPoint(x, y);
  return hit !== null && !anchor.contains(hit) && !(panel?.contains(hit) ?? false);
}

/**
 * Estado y accesibilidad de un desplegable anclado a un botón: click afuera, ESC (devuelve el foco),
 * navegación con flechas entre ítems de menú y cierre al cambiar de ruta.
 */
export function useDropdown({ kind = 'menu', initialFocus = 'checked', floating = false }: UseDropdownOptions = {}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const location = useLocation();

  const close = useCallback((restoreFocus = false) => {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  }, []);

  const toggle = useCallback(() => setOpen((value) => !value), []);

  useOnClickOutside([triggerRef, panelRef], () => setOpen(false), open);

  useEffect(() => {
    setOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        close(true);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    if (kind === 'menu') {
      const items = panelRef.current?.querySelectorAll<HTMLElement>(ITEM_SELECTOR);
      const checked =
        initialFocus === 'checked' ? panelRef.current?.querySelector<HTMLElement>('[aria-checked="true"]') : null;
      (checked ?? items?.[0])?.focus({ preventScroll: true });
    }
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, kind, initialFocus, close]);

  useEffect(() => {
    if (!open || !floating) return;
    const onScroll = () => {
      const trigger = triggerRef.current;
      if (!trigger || !anchorHidden(trigger, panelRef.current)) return;
      // Si el foco estaba en el menú vuelve al botón (así Tab sigue desde ahí), pero sin scrollear hasta él.
      if (panelRef.current?.contains(document.activeElement)) trigger.focus({ preventScroll: true });
      setOpen(false);
    };
    // En captura, como el reubicado del panel: también el scroll de la tabla o del contenido.
    window.addEventListener('scroll', onScroll, true);
    return () => window.removeEventListener('scroll', onScroll, true);
  }, [open, floating]);

  const onPanelKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (kind !== 'menu') return;
    const items = Array.from(panelRef.current?.querySelectorAll<HTMLElement>(ITEM_SELECTOR) ?? []);
    if (items.length === 0) return;
    const index = items.indexOf(document.activeElement as HTMLElement);
    let next = -1;
    if (event.key === 'ArrowDown') next = (index + 1) % items.length;
    else if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    else if (event.key === 'Tab') {
      // El panel flotante está al final del body: se devuelve el foco al disparador antes de que el navegador mueva el
      // foco, así Tab sigue desde ahí (como si el menú estuviera al lado del botón) y no se va al final de la página.
      if (floating) triggerRef.current?.focus();
      setOpen(false);
    }
    if (next >= 0) {
      event.preventDefault();
      items[next].focus();
    }
  };

  return {
    open,
    setOpen,
    close,
    toggle,
    triggerProps: {
      ref: triggerRef,
      type: 'button' as const,
      onClick: toggle,
      'aria-haspopup': kind === 'menu' ? ('menu' as const) : ('dialog' as const),
      'aria-expanded': open,
      'aria-controls': open ? panelId : undefined,
    },
    panelProps: {
      ref: panelRef,
      id: panelId,
      role: kind,
      onKeyDown: onPanelKeyDown,
      anchorRef: floating ? triggerRef : undefined,
    },
  };
}

export interface DropdownPanelProps extends HTMLAttributes<HTMLDivElement> {
  align?: 'start' | 'end';
  /**
   * Disparador contra el que flota el panel. Lo pone `useDropdown({ floating: true })` en `panelProps`; sin él, el
   * panel va anclado debajo del disparador dentro de su contenedor.
   */
  anchorRef?: RefObject<HTMLElement>;
}

/** Margen mínimo entre un panel flotante y el borde de la pantalla (el gutter mobile de docs/design-system.md). */
export const VIEWPORT_GUTTER_PX = 16;

/**
 * Cuánto hay que correr horizontalmente un panel de `width` px que naturalmente arranca en `left` para que quede
 * dentro de `[gutter, viewportWidth - gutter]`. Si no entra ni así, se prioriza el borde izquierdo (donde empieza
 * el texto). Negativo = hacia la izquierda.
 */
export function viewportShift(left: number, width: number, viewportWidth: number, gutter = VIEWPORT_GUTTER_PX): number {
  const overflowRight = left + width - (viewportWidth - gutter);
  let shift = overflowRight > 0 ? -overflowRight : 0;
  if (left + shift < gutter) shift = gutter - left;
  return Math.round(shift);
}

/** Separación entre el disparador y el panel (el `mt-2` del panel anclado) y margen vertical contra la pantalla. */
const FLOATING_GAP_PX = 8;

export interface FloatingPosition {
  /** Coordenadas del panel en la pantalla (`position: fixed`). */
  top: number;
  left: number;
  /** De qué lado del disparador quedó: abajo si entra; si no, arriba. */
  side: 'bottom' | 'top';
}

/**
 * Dónde va un panel flotante de `size` px contra el rectángulo del disparador (`anchor`, de `getBoundingClientRect`).
 * Abre hacia abajo si entra y, si no, hacia arriba (la última fila de una tabla); si no entra de ningún lado, va del
 * lado con más lugar y pegado al borde de la pantalla. Horizontalmente se alinea al borde pedido y se corre lo justo
 * para quedar dentro del gutter de 16 px ({@link viewportShift}).
 */
export function floatingPosition(
  anchor: Pick<DOMRect, 'top' | 'bottom' | 'left' | 'right'>,
  size: { width: number; height: number },
  viewport: { width: number; height: number },
  align: 'start' | 'end' = 'end',
): FloatingPosition {
  const spaceBelow = viewport.height - anchor.bottom - FLOATING_GAP_PX * 2;
  const spaceAbove = anchor.top - FLOATING_GAP_PX * 2;
  const fitsBelow = size.height <= spaceBelow;
  const fitsAbove = size.height <= spaceAbove;
  const side = fitsBelow || (!fitsAbove && spaceBelow >= spaceAbove) ? 'bottom' : 'top';
  let top = side === 'bottom' ? anchor.bottom + FLOATING_GAP_PX : anchor.top - FLOATING_GAP_PX - size.height;
  if (!fitsBelow && !fitsAbove) {
    top = Math.max(FLOATING_GAP_PX, Math.min(top, viewport.height - FLOATING_GAP_PX - size.height));
  }
  const left = align === 'end' ? anchor.right - size.width : anchor.left;
  return {
    top: Math.round(top),
    left: Math.round(left + viewportShift(left, size.width, viewport.width)),
    side,
  };
}

/**
 * Panel flotante del disparador. Anclado (lo habitual, el contenedor debe ser `relative`) va debajo del disparador, se
 * alinea al borde pedido y, si así se saldría de la pantalla (p. ej. el selector de sucursal, centrado en la barra de
 * un celular), se corre lo justo para quedar dentro con el gutter de 16 px; el corrimiento usa la propiedad
 * `translate`, que se suma al `transform` de la animación de entrada sin pisarlo.
 *
 * Con `anchorRef` (`useDropdown({ floating: true })`) va en un portal con `position: fixed`: no agranda ni lo recorta
 * un contenedor con `overflow` (una tabla), abre hacia arriba cuando abajo no hay lugar y se reubica con el scroll y
 * al cambiar el tamaño de la ventana ({@link floatingPosition}).
 */
export const DropdownPanel = forwardRef<HTMLDivElement, DropdownPanelProps>(function DropdownPanel(
  { align = 'end', anchorRef, className, style, ...props },
  ref,
) {
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [shift, setShift] = useState(0);
  const [position, setPosition] = useState<FloatingPosition | null>(null);

  const setRefs = useCallback(
    (node: HTMLDivElement | null) => {
      panelRef.current = node;
      if (typeof ref === 'function') ref(node);
      else if (ref) ref.current = node;
    },
    [ref],
  );

  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    if (anchorRef) {
      const placeFloating = () => {
        const anchor = anchorRef.current;
        if (!anchor) return;
        const root = document.documentElement;
        const next = floatingPosition(
          anchor.getBoundingClientRect(),
          { width: panel.offsetWidth, height: panel.offsetHeight },
          { width: root.clientWidth, height: root.clientHeight },
          align,
        );
        // El scroll dispara muchas veces: solo se vuelve a dibujar si el panel se movió.
        setPosition((previous) =>
          previous && previous.top === next.top && previous.left === next.left && previous.side === next.side
            ? previous
            : next,
        );
      };
      placeFloating();
      window.addEventListener('resize', placeFloating);
      // En captura: también el scroll de la tabla o del contenido, que no burbujea hasta la ventana.
      window.addEventListener('scroll', placeFloating, true);
      return () => {
        window.removeEventListener('resize', placeFloating);
        window.removeEventListener('scroll', placeFloating, true);
      };
    }
    const place = () => {
      const anchor = panel.offsetParent;
      // Los paneles `fixed` (la campana en celulares) ya se ubican contra la pantalla.
      if (!anchor || getComputedStyle(panel).position === 'fixed') {
        setShift(0);
        return;
      }
      // Posición de layout (sin la escala de la animación ni el corrimiento actual).
      const left = anchor.getBoundingClientRect().left + panel.offsetLeft;
      setShift(viewportShift(left, panel.offsetWidth, document.documentElement.clientWidth));
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  }, [align, anchorRef]);

  const panelClasses = cn(
    'z-40 min-w-[14rem] animate-in fade-in-0 zoom-in-95 rounded-panel border border-border bg-popover p-1.5',
    'text-popover-foreground shadow-pop focus:outline-none',
  );

  if (anchorRef) {
    return createPortal(
      <div
        ref={setRefs}
        data-side={position?.side ?? 'bottom'}
        className={cn(
          'fixed',
          panelClasses,
          position?.side === 'top' ? 'origin-bottom' : 'origin-top',
          className,
        )}
        style={{ ...style, top: position?.top ?? 0, left: position?.left ?? 0 }}
        {...props}
      />,
      document.body,
    );
  }

  return (
    <div
      ref={setRefs}
      className={cn(
        'absolute top-full mt-2 origin-top',
        panelClasses,
        align === 'end' ? 'right-0' : 'left-0',
        className,
      )}
      style={shift ? { ...style, translate: `${shift}px 0` } : style}
      {...props}
    />
  );
});

export interface DropdownItemProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon?: ReactNode;
  /** Para opciones de selección única (`menuitemradio`). */
  checked?: boolean;
  tone?: 'default' | 'danger';
  description?: ReactNode;
  trailing?: ReactNode;
}

export const DropdownItem = forwardRef<HTMLButtonElement, DropdownItemProps>(function DropdownItem(
  { icon, checked, tone = 'default', description, trailing, className, children, disabled, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type="button"
      role={checked === undefined ? 'menuitem' : 'menuitemradio'}
      aria-checked={checked}
      aria-disabled={disabled || undefined}
      disabled={disabled}
      tabIndex={-1}
      className={cn(
        'flex w-full items-center gap-2.5 rounded-control px-2.5 py-2 text-left text-base transition-colors focus:outline-none disabled:opacity-50',
        tone === 'danger'
          ? 'text-crit-ink hover:bg-crit-soft focus-visible:bg-crit-soft'
          : 'text-foreground hover:bg-muted focus-visible:bg-muted',
        checked && 'bg-primary/10 text-primary hover:bg-primary/[0.14] focus-visible:bg-primary/[0.14]',
        className,
      )}
      {...props}
    >
      {icon && <span className="flex shrink-0 items-center [&_svg]:h-4 [&_svg]:w-4">{icon}</span>}
      <span className="min-w-0 flex-1">
        <Truncate className="block font-medium">{children}</Truncate>
        {description && <Truncate className="block text-xs text-muted-foreground">{description}</Truncate>}
      </span>
      {trailing}
    </button>
  );
});

export function DropdownSeparator() {
  return <div role="separator" className="-mx-1.5 my-1.5 h-px bg-border" />;
}

export function DropdownLabel({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('px-2.5 pb-1.5 pt-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground', className)}
      {...props}
    />
  );
}
