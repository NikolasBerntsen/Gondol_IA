import { AlertTriangle, HelpCircle } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { Modal } from './Modal';

export interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  /** Si devuelve una promesa, el botón queda "cargando" hasta que termine. No cierra solo: cerrá en `onSuccess`. */
  onConfirm: () => void | Promise<unknown>;
  title: ReactNode;
  description?: ReactNode;
  /** Verbo exacto de la acción: "Deshabilitar módulo", "Descartar lote"… (no "Aceptar"). */
  confirmLabel?: string;
  cancelLabel?: string;
  /** `danger` para acciones destructivas o que bloquean. */
  tone?: 'primary' | 'danger';
  loading?: boolean;
  confirmDisabled?: boolean;
  /** Contenido extra (p. ej. un campo "motivo"). */
  children?: ReactNode;
}

/** Confirmación de una acción: explica el efecto y ofrece la salida. */
export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel = 'Confirmar',
  cancelLabel = 'Cancelar',
  tone = 'primary',
  loading = false,
  confirmDisabled = false,
  children,
}: ConfirmDialogProps) {
  const titleId = `confirm-${useId().replace(/:/g, '')}`;
  const [pending, setPending] = useState(false);
  const busy = loading || pending;
  const Icon = tone === 'danger' ? AlertTriangle : HelpCircle;

  const handleConfirm = async () => {
    const result = onConfirm();
    if (result instanceof Promise) {
      setPending(true);
      try {
        await result;
      } catch {
        // El error lo informa quien llama (toast o mensaje dentro del diálogo).
      } finally {
        setPending(false);
      }
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="sm"
      preventClose={busy}
      hideCloseButton
      ariaLabelledBy={titleId}
      footer={
        <>
          {/* En una acción destructiva el foco arranca en "Cancelar": Enter no borra nada sin querer. */}
          <Button variant="outline" onClick={onClose} disabled={busy} data-autofocus={tone === 'danger' || undefined}>
            {cancelLabel}
          </Button>
          <Button
            variant={tone === 'danger' ? 'destructive' : 'default'}
            onClick={handleConfirm}
            loading={busy}
            disabled={confirmDisabled}
            data-autofocus={tone === 'danger' ? undefined : true}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex gap-3.5 pt-1">
        <span
          className={cn(
            'grid h-10 w-10 shrink-0 place-items-center rounded-control',
            tone === 'danger' ? 'bg-crit-soft text-crit-ink' : 'bg-primary/10 text-primary',
          )}
        >
          <Icon className="h-5 w-5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1 space-y-2">
          <h2 id={titleId} className="font-display text-md font-semibold text-foreground">
            {title}
          </h2>
          {description && <div className="text-base text-muted-foreground">{description}</div>}
          {children}
        </div>
      </div>
    </Modal>
  );
}
