import {
  forwardRef,
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react';
import { useLocation } from 'react-router-dom';
import { cn } from '@/lib/cn';
import { useOnClickOutside } from '@/lib/useOnClickOutside';

const ITEM_SELECTOR = '[role="menuitem"]:not([aria-disabled="true"]),[role="menuitemradio"]:not([aria-disabled="true"])';

export interface UseDropdownOptions {
  /** Rol ARIA del panel: `menu` (acciones) o `dialog` (contenido libre, p. ej. notificaciones). */
  kind?: 'menu' | 'dialog';
}

/**
 * Estado y accesibilidad de un desplegable anclado a un botón: click afuera, ESC (devuelve el foco),
 * navegación con flechas entre ítems de menú y cierre al cambiar de ruta.
 */
export function useDropdown({ kind = 'menu' }: UseDropdownOptions = {}) {
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
      const checked = panelRef.current?.querySelector<HTMLElement>('[aria-checked="true"]');
      (checked ?? items?.[0])?.focus({ preventScroll: true });
    }
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, kind, close]);

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
    else if (event.key === 'Tab') setOpen(false);
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
    },
  };
}

export interface DropdownPanelProps extends HTMLAttributes<HTMLDivElement> {
  align?: 'start' | 'end';
}

/** Panel flotante debajo del disparador (el contenedor debe ser `relative`). */
export const DropdownPanel = forwardRef<HTMLDivElement, DropdownPanelProps>(function DropdownPanel(
  { align = 'end', className, ...props },
  ref,
) {
  return (
    <div
      ref={ref}
      className={cn(
        'absolute top-full z-40 mt-2 min-w-[14rem] origin-top animate-in fade-in-0 zoom-in-95 rounded-panel border border-border bg-popover p-1.5 text-popover-foreground shadow-pop focus:outline-none',
        align === 'end' ? 'right-0' : 'left-0',
        className,
      )}
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
        <span className="block truncate font-medium">{children}</span>
        {description && <span className="block truncate text-xs text-muted-foreground">{description}</span>}
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
