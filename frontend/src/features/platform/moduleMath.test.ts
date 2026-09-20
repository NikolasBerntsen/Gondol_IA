import { describe, expect, it } from 'vitest';
import {
  PLAN_MONTHLY_PRICE_PER_BRANCH,
  TENANT_MODULES,
  TENANT_MODULE_MONTHLY_PRICE,
  type TenantModule,
} from '@/api/types';
import {
  MODULE_DISABLE_EFFECTS,
  PLAN_MODULE_PRESET,
  TENANT_MODULE_SHORT,
  estimatedMonthlyFee,
} from './moduleMath';

describe('estimatedMonthlyFee', () => {
  it('cobra el plan por cada sucursal activa', () => {
    expect(
      estimatedMonthlyFee({ plan: 'BASICO', status: 'ACTIVE', activeBranchCount: 3, modules: [] }),
    ).toBe(3 * PLAN_MONTHLY_PRICE_PER_BRANCH.BASICO);
  });

  it('suma el adicional de cada módulo habilitado', () => {
    const fee = estimatedMonthlyFee({
      plan: 'BASICO',
      status: 'ACTIVE',
      activeBranchCount: 2,
      modules: ['POS_GONDOLIA', 'MULTI_BRANCH'],
    });
    const extras = TENANT_MODULE_MONTHLY_PRICE.POS_GONDOLIA + TENANT_MODULE_MONTHLY_PRICE.MULTI_BRANCH;
    expect(fee).toBe(2 * (PLAN_MONTHLY_PRICE_PER_BRANCH.BASICO + extras));
  });

  it('acepta la matriz de la pantalla ({ módulo: encendido })', () => {
    const asRecord = estimatedMonthlyFee({
      plan: 'PROFESIONAL',
      status: 'ACTIVE',
      activeBranchCount: 1,
      modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: true },
    });
    const asList = estimatedMonthlyFee({
      plan: 'PROFESIONAL',
      status: 'ACTIVE',
      activeBranchCount: 1,
      modules: ['POS_GONDOLIA', 'MULTI_BRANCH'],
    });
    expect(asRecord).toBe(asList);
  });

  it('un cliente que no está activo no factura', () => {
    for (const status of ['DISABLED', 'CANCELLED'] as const) {
      expect(
        estimatedMonthlyFee({ plan: 'PROFESIONAL', status, activeBranchCount: 5, modules: TENANT_MODULES }),
      ).toBe(0);
    }
  });

  it('sin sucursales activas no factura', () => {
    expect(
      estimatedMonthlyFee({ plan: 'BASICO', status: 'ACTIVE', activeBranchCount: 0, modules: ['POS_GONDOLIA'] }),
    ).toBe(0);
    expect(
      estimatedMonthlyFee({ plan: 'BASICO', status: 'ACTIVE', activeBranchCount: -3, modules: [] }),
    ).toBe(0);
  });
});

describe('catálogo de módulos', () => {
  it('cada módulo tiene etiqueta corta, preset y efectos al deshabilitarlo', () => {
    for (const module of TENANT_MODULES as readonly TenantModule[]) {
      expect(TENANT_MODULE_SHORT[module]).toBeTruthy();
      expect(MODULE_DISABLE_EFFECTS[module].length).toBeGreaterThan(0);
    }
  });

  it('el preset de FREEMIUM es el más chico y los pagos traen todo', () => {
    expect(PLAN_MODULE_PRESET.FREEMIUM).toEqual(['POS_GONDOLIA']);
    expect([...PLAN_MODULE_PRESET.BASICO].sort()).toEqual([...TENANT_MODULES].sort());
    expect([...PLAN_MODULE_PRESET.PROFESIONAL].sort()).toEqual([...TENANT_MODULES].sort());
  });
});
