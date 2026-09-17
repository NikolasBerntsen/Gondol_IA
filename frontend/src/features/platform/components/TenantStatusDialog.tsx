import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { Alert, ConfirmDialog, Field, Input, Textarea } from '@/components/ui';
import { platformApi, platformKeys } from '../api';

/** Acciones de estado de un cliente (SPEC §6.6). */
export type TenantAction = 'disable' | 'enable' | 'cancel' | 'reactivate' | 'delete';

export interface TenantActionTarget {
  id: number;
  name: string;
}

interface Copy {
  title: string;
  description: string;
  confirmLabel: string;
  tone: 'primary' | 'danger';
  /** El motivo es obligatorio en los bloqueos y las bajas. */
  reason: 'required' | 'optional' | 'none';
  success: string;
}

const COPY: Record<TenantAction, Copy> = {
  disable: {
    title: 'Deshabilitar el acceso del cliente',
    description:
      'Sus usuarios no van a poder entrar y se cierran las sesiones abiertas. El cliente sigue facturando hasta que lo des de baja.',
    confirmLabel: 'Deshabilitar acceso',
    tone: 'danger',
    reason: 'required',
    success: 'Deshabilitaste el acceso del cliente.',
  },
  enable: {
    title: 'Volver a habilitar el acceso',
    description: 'Sus usuarios vuelven a entrar con las mismas credenciales.',
    confirmLabel: 'Habilitar acceso',
    tone: 'primary',
    reason: 'optional',
    success: 'Habilitaste el acceso del cliente.',
  },
  cancel: {
    title: 'Dar de baja al cliente',
    description:
      'Cuenta como baja en las métricas, deja de facturar y sus usuarios quedan bloqueados. Los datos se conservan.',
    confirmLabel: 'Dar de baja',
    tone: 'danger',
    reason: 'required',
    success: 'Diste de baja al cliente.',
  },
  reactivate: {
    title: 'Reactivar al cliente',
    description: 'Vuelve a estar activo, con su plan y sus módulos, y factura de nuevo.',
    confirmLabel: 'Reactivar cliente',
    tone: 'primary',
    reason: 'optional',
    success: 'Reactivaste al cliente.',
  },
  delete: {
    title: 'Eliminar definitivamente al cliente',
    description:
      'Se borran sus usuarios, sucursales y datos. No se puede deshacer. Solo se puede eliminar un cliente dado de baja.',
    confirmLabel: 'Eliminar para siempre',
    tone: 'danger',
    reason: 'none',
    success: 'Eliminaste al cliente definitivamente.',
  },
};

export interface TenantStatusDialogProps {
  action: TenantAction | null;
  tenant: TenantActionTarget | null;
  onClose: () => void;
  /** A dónde ir después de eliminar (por defecto se queda donde está). */
  redirectAfterDelete?: string;
}

/**
 * Confirmación con motivo de los cambios de estado de un cliente y de su eliminación definitiva
 * (que además pide escribir el nombre exacto).
 */
export function TenantStatusDialog({ action, tenant, onClose, redirectAfterDelete }: TenantStatusDialogProps) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [reason, setReason] = useState('');
  const [confirmName, setConfirmName] = useState('');
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (action) {
      setReason('');
      setConfirmName('');
      setError(undefined);
    }
  }, [action, tenant?.id]);

  const mutation = useMutation({
    mutationFn: async () => {
      if (!action || !tenant) return;
      const trimmed = reason.trim();
      switch (action) {
        case 'disable':
          await platformApi.tenants.disable(tenant.id, trimmed);
          return;
        case 'enable':
          await platformApi.tenants.enable(tenant.id, trimmed || undefined);
          return;
        case 'cancel':
          await platformApi.tenants.cancel(tenant.id, trimmed);
          return;
        case 'reactivate':
          await platformApi.tenants.reactivate(tenant.id, trimmed || undefined);
          return;
        case 'delete':
          await platformApi.tenants.remove(tenant.id, confirmName.trim());
          return;
      }
    },
    onSuccess: () => {
      if (!action) return;
      toast.success(COPY[action].success);
      void queryClient.invalidateQueries({ queryKey: platformKeys.tenants });
      void queryClient.invalidateQueries({ queryKey: platformKeys.metrics });
      void queryClient.invalidateQueries({ queryKey: ['platform', 'modules'] });
      onClose();
      if (action === 'delete' && redirectAfterDelete) navigate(redirectAfterDelete, { replace: true });
    },
    onError: (err) => setError(getErrorMessage(err, 'No pudimos aplicar el cambio.')),
    meta: { errorToast: false },
  });

  if (!action || !tenant) return null;

  const copy = COPY[action];
  const nameMatches = confirmName.trim() === tenant.name;
  const reasonMissing = copy.reason === 'required' && reason.trim().length === 0;

  return (
    <ConfirmDialog
      open
      onClose={() => {
        if (!mutation.isPending) onClose();
      }}
      onConfirm={() => {
        setError(undefined);
        if (reasonMissing) {
          setError('Contá el motivo: queda en el historial del cliente.');
          return;
        }
        if (action === 'delete' && !nameMatches) {
          setError('Escribí el nombre exacto del cliente para confirmar.');
          return;
        }
        return mutation.mutateAsync();
      }}
      title={copy.title}
      description={
        <>
          <strong className="font-semibold text-foreground">{tenant.name}</strong>. {copy.description}
        </>
      }
      confirmLabel={copy.confirmLabel}
      tone={copy.tone}
      loading={mutation.isPending}
    >
      <div className="space-y-3">
        {copy.reason !== 'none' ? (
          <Field
            label="Motivo"
            optional={copy.reason === 'optional'}
            hint={copy.reason === 'required' ? 'Queda en el historial del cliente.' : undefined}
          >
            <Textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={3}
              maxLength={300}
              placeholder={
                action === 'disable'
                  ? 'Por ejemplo: falta de pago de la cuota de septiembre.'
                  : action === 'cancel'
                    ? 'Por ejemplo: cerró el local.'
                    : 'Por ejemplo: regularizó el pago.'
              }
              invalid={Boolean(error) && reasonMissing}
            />
          </Field>
        ) : null}

        {action === 'delete' ? (
          <Field
            label="Escribí el nombre del cliente para confirmar"
            hint={`Tiene que coincidir exactamente con "${tenant.name}".`}
          >
            <Input
              value={confirmName}
              onChange={(event) => setConfirmName(event.target.value)}
              placeholder={tenant.name}
              autoComplete="off"
              invalid={confirmName.length > 0 && !nameMatches}
            />
          </Field>
        ) : null}

        {error ? <Alert tone="crit">{error}</Alert> : null}
      </div>
    </ConfirmDialog>
  );
}
