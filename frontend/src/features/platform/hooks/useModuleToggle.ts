import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { getErrorMessage, isApiError } from '@/api/client';
import { TENANT_MODULE_LABELS, type TenantModule, type TenantStatus } from '@/api/types';
import { platformApi, platformKeys } from '../api';

/** Cliente sobre el que se está tocando un módulo (lo que necesita el diálogo de confirmación). */
export interface ModuleToggleTarget {
  tenantId: number;
  tenantName: string;
  status: TenantStatus;
  plan: import('@/api/types').TenantPlan;
  activeBranchCount: number;
  modules: Record<TenantModule, boolean>;
}

export interface PendingDisable {
  target: ModuleToggleTarget;
  module: TenantModule;
  /** `MULTI_BRANCH` con más de una sucursal activa: el backend responde 409 MODULE_IN_USE. */
  blocked: boolean;
}

/**
 * Activar y desactivar módulos de un cliente (SPEC §14.3). Habilitar es directo; deshabilitar siempre
 * pide confirmación explicando qué pierde el cliente, y avisa antes si `MULTI_BRANCH` está en uso.
 */
export function useModuleToggle() {
  const queryClient = useQueryClient();
  const [pending, setPending] = useState<PendingDisable | null>(null);

  const mutation = useMutation({
    mutationFn: ({ tenantId, module, enabled }: { tenantId: number; module: TenantModule; enabled: boolean }) =>
      platformApi.modules.setEnabled(tenantId, module, enabled),
    onSuccess: (_status, { tenantId, module, enabled }) => {
      toast.success(`${TENANT_MODULE_LABELS[module]}: ${enabled ? 'habilitado' : 'deshabilitado'}.`, {
        description: 'Sus usuarios ven el cambio al instante: el menú se actualiza solo.',
      });
      void queryClient.invalidateQueries({ queryKey: platformKeys.tenantModules(tenantId) });
      void queryClient.invalidateQueries({ queryKey: platformKeys.tenants });
      void queryClient.invalidateQueries({ queryKey: ['platform', 'modules'] });
      void queryClient.invalidateQueries({ queryKey: platformKeys.metrics });
    },
    onError: (error) => {
      if (isApiError(error, 'MODULE_IN_USE')) {
        toast.error(getErrorMessage(error), { description: 'Desactivá primero las sucursales extra del cliente.' });
        return;
      }
      toast.error(getErrorMessage(error, 'No pudimos cambiar el módulo.'));
    },
  });

  /** Llamalo desde el switch. Devuelve `true` si el cambio se mandó al instante. */
  const toggle = (target: ModuleToggleTarget, module: TenantModule, next: boolean): boolean => {
    if (next) {
      mutation.mutate({ tenantId: target.tenantId, module, enabled: true });
      return true;
    }
    setPending({
      target,
      module,
      blocked: module === 'MULTI_BRANCH' && target.activeBranchCount > 1,
    });
    return false;
  };

  const confirmDisable = async () => {
    if (!pending || pending.blocked) return;
    await mutation.mutateAsync({ tenantId: pending.target.tenantId, module: pending.module, enabled: false });
    setPending(null);
  };

  return {
    pending,
    closePending: () => setPending(null),
    toggle,
    confirmDisable,
    isPending: mutation.isPending,
    /** Módulo que se está guardando (para deshabilitar solo ese switch). */
    savingModule: mutation.isPending ? mutation.variables?.module : undefined,
    savingTenantId: mutation.isPending ? mutation.variables?.tenantId : undefined,
  };
}
