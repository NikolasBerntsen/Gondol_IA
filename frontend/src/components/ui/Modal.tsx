import { X } from 'lucide-react';
import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '@/lib/cn';
import { getFocusableElements, trapTabKey } from '@/lib/focus';
import { lockBodyScroll } from '@/lib/scrollLock';

export type ModalSize = 'sm' | 'md' | 'lg' | 'xl' | 'full';

const SIZE_CLASSES: Record<ModalSize, string> = {
  sm: 'sm:max-w-sm',
  md: 'sm:max-w-lg',
  lg: 'sm:max-w-2xl',
  xl: 'sm:max-w-4xl',
  full: 'sm:max-w-6xl',
};

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  /** Botones al pie (en mobile se apilan). */
  footer?: ReactNode;
  size?: ModalSize;
  /** Cerrar al tocar el fondo (por defecto `true`). */
  closeOnOverlayClick?: boolean;
  /** Impide cerrar (ESC, fondo, botón) mientras hay una operación en curso. */
  preventClose?: boolean;
  hideCloseButton?: boolean;
  /** Id del elemento que da nombre al diálogo cuando no se usa `title` (p. ej. el título propio del contenido). */
  ariaLabelledBy?: string;
  /** Elemento que recibe el foco al abrir. Por defecto: `[data-autofocus]` o el primer control del contenido. */
  initialFocusRef?: RefObject<HTMLElement | null>;
  className?: string;
  bodyClassName?: string;
}

/** Pila de modales abiertos: ESC y Tab solo afectan al de arriba. */
const modalStack: string[] = [];

/**
 * Diálogo modal accesible: portal a `body`, ESC, foco atrapado y restaurado al cerrar, bloqueo de scroll.
 * En mobile se muestra como hoja inferior.
 */
export function Modal(props: ModalProps) {
  if (!props.open) return null;
  return createPortal(<ModalPanel {...props} />, document.body);
}

function ModalPanel({
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  closeOnOverlayClick = true,
  preventClose = false,
  hideCloseButton = false,
  ariaLabelledBy,
  initialFocusRef,
  className,
  bodyClassName,
}: ModalProps) {
  const modalId = useId();
  const titleId = `${modalId}-title`;
  const descriptionId = `${modalId}-description`;
  const panelRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  const closeRef = useRef({ onClose, preventClose });
  closeRef.current = { onClose, preventClose };

  const requestClose = () => {
    if (!closeRef.current.preventClose) closeRef.current.onClose();
  };

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    modalStack.push(modalId);
    const unlock = lockBodyScroll();

    const panel = panelRef.current;
    const target =
      initialFocusRef?.current ??
      panel?.querySelector<HTMLElement>('[data-autofocus]') ??
      getFocusableElements(bodyRef.current)[0] ??
      panel;
    target?.focus({ preventScroll: true });

    const onKeyDown = (event: KeyboardEvent) => {
      if (modalStack[modalStack.length - 1] !== modalId) return;
      if (event.key === 'Escape') {
        event.stopPropagation();
        if (!closeRef.current.preventClose) closeRef.current.onClose();
      } else if (event.key === 'Tab') {
        trapTabKey(event, panelRef.current);
      }
    };
    document.addEventListener('keydown', onKeyDown);

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      const index = modalStack.lastIndexOf(modalId);
      if (index >= 0) modalStack.splice(index, 1);
      unlock();
      if (previouslyFocused?.isConnected) previouslyFocused.focus({ preventScroll: true });
    };
    // Solo al montar/desmontar: el foco inicial no debe moverse en cada render.
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center sm:items-center sm:p-4" role="presentation">
      <div
        className="fixed inset-0 animate-fade-in bg-slate-900/50 backdrop-blur-[2px]"
        aria-hidden="true"
        onClick={closeOnOverlayClick ? requestClose : undefined}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : ariaLabelledBy}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={cn(
          'relative flex max-h-[92dvh] w-full flex-col rounded-t-2xl bg-white shadow-popover outline-none',
          'animate-slide-up sm:animate-scale-in sm:rounded-2xl',
          SIZE_CLASSES[size],
          className,
        )}
      >
        {(title || !hideCloseButton) && (
          <div className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
            <div className="min-w-0 flex-1">
              {title && (
                <h2 id={titleId} className="text-lg font-semibold leading-snug text-slate-900">
                  {title}
                </h2>
              )}
              {description && (
                <p id={descriptionId} className="mt-1 text-sm text-slate-500">
                  {description}
                </p>
              )}
            </div>
            {!hideCloseButton && (
              <button
                type="button"
                onClick={requestClose}
                disabled={preventClose}
                className="-mr-2 -mt-1 rounded-lg p-2 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 disabled:opacity-40"
                aria-label="Cerrar"
              >
                <X className="h-5 w-5" aria-hidden="true" />
              </button>
            )}
          </div>
        )}
        <div ref={bodyRef} className={cn('flex-1 overflow-y-auto px-5 py-4', bodyClassName)}>
          {children}
        </div>
        {footer && (
          <div className="flex flex-col-reverse gap-2 border-t border-slate-100 px-5 py-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:flex-row sm:justify-end sm:pb-4">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
