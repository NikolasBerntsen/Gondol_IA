// Cuota y MRR estimados en el navegador (SPEC §14.1). El backend es la fuente de verdad: esto sirve para
// mostrar el "antes → después" de un cambio sin esperar la respuesta y el total en vivo de la matriz.
import {
  PLAN_MONTHLY_PRICE_PER_BRANCH,
  TENANT_MODULE_MONTHLY_PRICE,
  type TenantModule,
  type TenantPlan,
  type TenantStatus,
} from '@/api/types';

/**
 * Cuota mensual = sucursales activas × (precio del plan + adicionales de los módulos habilitados).
 * Los clientes deshabilitados o dados de baja no facturan.
 */
export function estimatedMonthlyFee(input: {
  plan: TenantPlan;
  status: TenantStatus;
  activeBranchCount: number;
  modules: readonly TenantModule[] | Record<TenantModule, boolean>;
}): number {
  if (input.status !== 'ACTIVE') return 0;
  const enabled: TenantModule[] = Array.isArray(input.modules)
    ? [...input.modules]
    : (Object.entries(input.modules) as Array<[TenantModule, boolean]>)
        .filter(([, on]) => on)
        .map(([module]) => module);
  const extras = enabled.reduce((total, module) => total + TENANT_MODULE_MONTHLY_PRICE[module], 0);
  return Math.max(0, input.activeBranchCount) * (PLAN_MONTHLY_PRICE_PER_BRANCH[input.plan] + extras);
}

/**
 * Módulos que se habilitan por defecto según el plan al dar de alta un cliente (SPEC §14.1).
 * Espejo de `ModuleCatalog.PRESETS`: el backend aplica el mismo preset si el alta no manda `modules`.
 */
export const PLAN_MODULE_PRESET: Record<TenantPlan, TenantModule[]> = {
  FREEMIUM: ['POS_GONDOLIA'],
  BASICO: ['POS_GONDOLIA', 'POS_INTEGRATION', 'MULTI_BRANCH'],
  PROFESIONAL: ['POS_GONDOLIA', 'POS_INTEGRATION', 'MULTI_BRANCH'],
};

/** Qué pierde el cliente cuando se le deshabilita un módulo (se explica antes de confirmar). */
export const MODULE_DISABLE_EFFECTS: Record<TenantModule, string[]> = {
  POS_GONDOLIA: [
    'Sus cajeros dejan de ver el Punto de venta y no pueden abrir turnos.',
    'Los tickets y cierres de caja anteriores siguen disponibles en el historial.',
  ],
  POS_INTEGRATION: [
    'Las ventas que envíe su sistema de caja se rechazan hasta que lo vuelvas a habilitar.',
    'Deja de ver el menú de integración con POS y la importación de ventas por CSV.',
  ],
  MULTI_BRANCH: [
    'Pierde las transferencias entre sucursales y la vista consolidada.',
    'Queda limitado a una sola sucursal activa.',
  ],
};
