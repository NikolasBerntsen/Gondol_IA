import { AlertTriangle, HelpCircle } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Button } from './Button';
import { Modal } from './Modal';

export interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  /** Si devuelve una promesa, el botón muestra "cargando" hasta que termine. No cierra solo: cerrá en `onSuccess`. */
  onConfirm: () => void | Promise<unknown>;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  /** `danger` para acciones destructivas. */
  tone?: 'primary' | 'danger';
  loading?: boolean;
  confirmDisabled?: boolean;
  /** Contenido extra (p. ej. un campo "motivo"). */
  children?: ReactNode;
}

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
  const titleId = `confirm-${useId()}`;
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
        // El error lo informa quien llama (toast / mensaje en el diálogo).
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
          <Button variant="outline" onClick={onClose} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button
            variant={tone === 'danger' ? 'danger' : 'primary'}
            onClick={handleConfirm}
            loading={busy}
            disabled={confirmDisabled}
            data-autofocus
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex gap-4 pt-1">
        <span
          className={cn(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-full',
            tone === 'danger' ? 'bg-red-100 text-red-600' : 'bg-brand-100 text-brand-700',
          )}
        >
          <Icon className="h-5 w-5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1 space-y-2">
          <h2 id={titleId} className="text-base font-semibold text-slate-900">
            {title}
          </h2>
          {description && <div className="text-sm text-slate-600">{description}</div>}
          {children}
        </div>
      </div>
    </Modal>
  );
}
