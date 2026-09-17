import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { type ReactNode, type RefObject } from 'react';
import { cn } from '@/lib/cn';

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
  /** Id del elemento que da nombre al diálogo cuando no se usa `title`. */
  ariaLabelledBy?: string;
  /** Elemento que recibe el foco al abrir. Por defecto: `[data-autofocus]` o el primer control. */
  initialFocusRef?: RefObject<HTMLElement | null>;
  className?: string;
  bodyClassName?: string;
}

/**
 * Diálogo modal (Radix Dialog): foco atrapado y restaurado, ESC, bloqueo de scroll y velo `scrim/60`.
 * Radio de diálogo (16 px) y `shadow-pop`. En mobile se ancla abajo como hoja.
 *
 * El control que debe recibir el foco al abrir se marca con `data-autofocus`
 * (o se pasa `initialFocusRef`).
 */
export function Modal({
  open,
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
  const blockOutside = (event: Event) => {
    if (preventClose || !closeOnOverlayClick) event.preventDefault();
  };

  return (
    <DialogPrimitive.Root
      open={open}
      onOpenChange={(next) => {
        if (!next && !preventClose) onClose();
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-scrim/60 data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          // Con `ariaLabelledBy` el nombre lo da el contenido; sin descripción se quita el `aria-describedby`
          // que Radix agrega por defecto (si no, avisa por consola).
          {...(ariaLabelledBy ? { 'aria-labelledby': ariaLabelledBy } : null)}
          {...(description ? null : { 'aria-describedby': undefined })}
          onEscapeKeyDown={(event) => {
            if (preventClose) event.preventDefault();
          }}
          onPointerDownOutside={blockOutside}
          onInteractOutside={blockOutside}
          onOpenAutoFocus={(event) => {
            const target =
              initialFocusRef?.current ??
              (event.currentTarget as HTMLElement).querySelector<HTMLElement>('[data-autofocus]');
            if (!target) return;
            event.preventDefault();
            target.focus({ preventScroll: true });
          }}
          className={cn(
            'fixed inset-x-0 bottom-0 z-50 flex max-h-[92dvh] flex-col border bg-card text-foreground shadow-pop outline-none',
            'rounded-t-dialog data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:slide-out-to-bottom data-[state=open]:slide-in-from-bottom',
            'sm:inset-x-auto sm:bottom-auto sm:left-1/2 sm:top-1/2 sm:w-[calc(100%-24px)] sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-dialog',
            'sm:data-[state=closed]:slide-out-to-bottom-0 sm:data-[state=open]:slide-in-from-bottom-0 sm:data-[state=closed]:zoom-out-95 sm:data-[state=open]:zoom-in-95',
            SIZE_CLASSES[size],
            className,
          )}
        >
          <DialogPrimitive.Title
            className={cn(
              title
                ? 'border-b border-border px-5 pb-3 pt-4 font-display text-lg font-semibold leading-tight tracking-[-0.01em] text-foreground'
                : 'sr-only',
              title && description && 'pb-1',
            )}
          >
            {title ?? 'Diálogo'}
          </DialogPrimitive.Title>
          {description && (
            <DialogPrimitive.Description className="border-b border-border px-5 pb-3 text-base text-muted-foreground">
              {description}
            </DialogPrimitive.Description>
          )}

          <div className={cn('gd-scroll min-h-0 flex-1 overflow-y-auto px-5 py-4', bodyClassName)}>{children}</div>

          {footer && (
            <div className="flex flex-col-reverse gap-2 border-t border-border px-5 py-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:flex-row sm:justify-end sm:pb-4">
              {footer}
            </div>
          )}

          {!hideCloseButton && (
            <DialogPrimitive.Close
              disabled={preventClose}
              className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-control text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-40"
            >
              <X className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only">Cerrar</span>
            </DialogPrimitive.Close>
          )}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
