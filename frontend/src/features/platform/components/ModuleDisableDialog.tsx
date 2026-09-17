import { Ban } from 'lucide-react';
import { TENANT_MODULE_LABELS } from '@/api/types';
import { Button, Modal } from '@/components/ui';
import { formatMoney, pluralize } from '@/lib/format';
import { estimatedMonthlyFee, MODULE_DISABLE_EFFECTS } from '../moduleMath';
import type { PendingDisable } from '../hooks/useModuleToggle';

export interface ModuleDisableDialogProps {
  pending: PendingDisable | null;
  onClose: () => void;
  onConfirm: () => void | Promise<unknown>;
  loading?: boolean;
}

/**
 * Confirmación al deshabilitar un módulo (SPEC §14.3): explica qué pierde el cliente y cómo queda su cuota.
 * Si es `MULTI_BRANCH` con más de una sucursal activa avisa que no se puede, sin llamar al backend.
 */
export function ModuleDisableDialog({ pending, onClose, onConfirm, loading }: ModuleDisableDialogProps) {
  if (!pending) {
    return null;
  }

  const { target, module, blocked } = pending;
  const label = TENANT_MODULE_LABELS[module];
  const extraBranches = Math.max(0, target.activeBranchCount - 1);

  if (blocked) {
    return (
      <Modal
        open
        onClose={onClose}
        size="sm"
        title="No se puede deshabilitar Multi-sucursal"
        description={`${target.tenantName} tiene ${pluralize(target.activeBranchCount, 'sucursal activa', 'sucursales activas')}.`}
        footer={
          <Button onClick={onClose} data-autofocus>
            Entendido
          </Button>
        }
      >
        <div
          className="flex items-start gap-3 rounded-control border border-crit/40 bg-crit-soft px-3 py-2.5 text-crit-ink"
          role="alert"
        >
          <Ban className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
          <p className="text-base">
            Sin este módulo el cliente puede tener una sola sucursal. Pedile que desactive{' '}
            {pluralize(extraBranches, 'sucursal', 'sucursales')} antes, o dejá el módulo habilitado.
          </p>
        </div>
      </Modal>
    );
  }

  const feeBefore = estimatedMonthlyFee(target);
  const feeAfter = estimatedMonthlyFee({ ...target, modules: { ...target.modules, [module]: false } });

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title={`¿Deshabilitar ${label} para ${target.tenantName}?`}
      description="Esto es lo que cambia para el cliente:"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading} data-autofocus>
            Cancelar
          </Button>
          <Button variant="destructive" onClick={() => void onConfirm()} loading={loading}>
            Deshabilitar módulo
          </Button>
        </>
      }
    >
      <ul className="space-y-2 text-base text-foreground">
        {[...MODULE_DISABLE_EFFECTS[module], 'No se borra ningún dato: podés volver a habilitarlo cuando quieras.'].map(
          (effect) => (
            <li key={effect} className="flex gap-2">
              <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-muted-foreground" />
              {effect}
            </li>
          ),
        )}
      </ul>
      {target.status === 'ACTIVE' && feeBefore !== feeAfter ? (
        <div className="mt-4 flex items-center justify-between gap-3 rounded-control bg-muted px-3 py-2 text-base">
          <span className="text-muted-foreground">Cuota estimada</span>
          <span className="tabular-nums">
            {formatMoney(feeBefore)} → <strong className="font-semibold">{formatMoney(feeAfter)}</strong>
          </span>
        </div>
      ) : null}
    </Modal>
  );
}
