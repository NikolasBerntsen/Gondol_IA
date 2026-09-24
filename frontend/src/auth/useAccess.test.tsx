import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MeDto, Role, TenantModule } from '@/api/types';
import { useAccess } from './useAccess';

const useAuth = vi.hoisted(() => vi.fn());
vi.mock('./AuthContext', () => ({ useAuth }));

/** Usuario mínimo con el rol y los módulos que pide cada caso. */
function signedInAs(role: Role | null, modules: TenantModule[] | null = []) {
  const me =
    role === null
      ? null
      : ({
          id: 1,
          email: 'x@gondolia.app',
          fullName: 'Usuario',
          role,
          mustChangePassword: false,
          tenant: modules === null ? null : ({ id: 1, name: 'Comercio', modules } as MeDto['tenant']),
          branches: [],
        } as unknown as MeDto);
  useAuth.mockReturnValue({ me });
  return renderHook(() => useAccess()).result;
}

describe('can', () => {
  it('sigue la matriz de permisos del rol', () => {
    const admin = signedInAs('TENANT_ADMIN');
    expect(admin.current.can('products.delete')).toBe(true);
    expect(admin.current.can('dashboard.view')).toBe(true);

    const employee = signedInAs('TENANT_EMPLOYEE');
    expect(employee.current.can('products.write')).toBe(true);
    expect(employee.current.can('products.delete')).toBe(false);
    expect(employee.current.can('dashboard.view')).toBe(false);
  });

  it('sin sesión no puede nada', () => {
    const access = signedInAs(null);
    expect(access.current.can('products.view')).toBe(false);
    expect(access.current.canOpen('/app/products')).toBe(false);
  });
});

describe('canOpen', () => {
  it('necesita el rol y el módulo habilitado', () => {
    const access = signedInAs('TENANT_CASHIER', ['POS_GONDOLIA']);
    expect(access.current.canOpen('/app/pos')).toBe(true);
  });

  it('con el módulo apagado la ruta no se abre aunque el rol la tenga', () => {
    const access = signedInAs('TENANT_CASHIER', []);
    expect(access.current.canOpen('/app/pos')).toBe(false);
  });

  it('las rutas sin módulo solo miran el rol', () => {
    const access = signedInAs('TENANT_BOSS', []);
    expect(access.current.canOpen('/app/dashboard')).toBe(true);
    expect(access.current.canOpen('/app/pos')).toBe(false);
  });

  it('un usuario de plataforma no abre rutas del comercio', () => {
    const access = signedInAs('PLATFORM_OWNER', null);
    expect(access.current.canOpen('/app/dashboard')).toBe(false);
    expect(access.current.canOpen('/owner/tenants')).toBe(true);
  });

  it('soporte abre Clientes y Módulos por cliente, pero no las métricas ni el alta', () => {
    const access = signedInAs('SUPPORT_AGENT', null);
    expect(access.current.canOpen('/owner/tenants/7')).toBe(true);
    expect(access.current.canOpen('/owner/modules')).toBe(true);
    expect(access.current.canOpen('/owner')).toBe(false);
    expect(access.current.canOpen('/owner/tenants/new')).toBe(false);
    expect(access.current.can('platform.tenants.edit')).toBe(true);
    expect(access.current.can('platform.tenants.changeStatus')).toBe(false);
  });

  it('transferencias exige MULTI_BRANCH', () => {
    expect(signedInAs('TENANT_BOSS', ['MULTI_BRANCH']).current.canOpen('/app/transfers')).toBe(true);
    expect(signedInAs('TENANT_BOSS', ['POS_GONDOLIA']).current.canOpen('/app/transfers')).toBe(false);
  });
});
