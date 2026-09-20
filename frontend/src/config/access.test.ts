import { describe, expect, it } from 'vitest';
import { ROLES, type Role } from '@/api/types';
import {
  PERMISSIONS,
  can,
  canAccessPath,
  hasRole,
  linkTargetFor,
  requiredModuleForPath,
  rolesForPath,
  rolesWith,
  type Permission,
} from './access';

describe('can', () => {
  it('respeta la matriz de permisos', () => {
    expect(can('TENANT_BOSS', 'dashboard.view')).toBe(true);
    expect(can('TENANT_EMPLOYEE', 'dashboard.view')).toBe(false);
    expect(can('TENANT_ADMIN', 'products.delete')).toBe(true);
    expect(can('TENANT_EMPLOYEE', 'products.delete')).toBe(false);
    expect(can('TENANT_CASHIER', 'pos.use')).toBe(true);
    expect(can('TENANT_BOSS', 'pos.use')).toBe(false);
  });

  it('sin rol no hay permisos', () => {
    expect(can(null, 'dashboard.view')).toBe(false);
    expect(can(undefined, 'pos.use')).toBe(false);
  });

  it('los roles de plataforma no usan los permisos del comercio', () => {
    for (const permission of Object.keys(PERMISSIONS) as Permission[]) {
      expect(can('PLATFORM_OWNER', permission)).toBe(false);
      expect(can('SUPPORT_AGENT', permission)).toBe(false);
    }
  });

  it('rolesWith devuelve la lista de la matriz', () => {
    expect(rolesWith('products.delete')).toEqual(['TENANT_ADMIN']);
    expect(rolesWith('communication.view')).toContain('TENANT_CASHIER');
  });
});

describe('rutas', () => {
  it('perfil y notificaciones los abre cualquier rol', () => {
    for (const role of ROLES) {
      expect(canAccessPath(role, '/profile')).toBe(true);
      expect(canAccessPath(role, '/notifications')).toBe(true);
    }
  });

  it('cada consola es de su rol', () => {
    expect(canAccessPath('PLATFORM_OWNER', '/owner/tenants')).toBe(true);
    expect(canAccessPath('SUPPORT_AGENT', '/owner/tenants')).toBe(false);
    expect(canAccessPath('SUPPORT_AGENT', '/support/tickets/3')).toBe(true);
    expect(canAccessPath('TENANT_ADMIN', '/support/tickets/3')).toBe(false);
  });

  it('gana el patrón más específico', () => {
    // /app/pos/registers es solo del administrador, aunque /app/pos lo use el cajero.
    expect(canAccessPath('TENANT_CASHIER', '/app/pos')).toBe(true);
    expect(canAccessPath('TENANT_CASHIER', '/app/pos/registers')).toBe(false);
    expect(canAccessPath('TENANT_ADMIN', '/app/pos/registers')).toBe(true);
    // Crear y editar productos exige permiso de escritura; verlos, no.
    expect(canAccessPath('TENANT_BOSS', '/app/products')).toBe(true);
    expect(canAccessPath('TENANT_BOSS', '/app/products/new')).toBe(false);
    expect(canAccessPath('TENANT_EMPLOYEE', '/app/products/12/edit')).toBe(true);
  });

  it('ignora la query y el hash', () => {
    expect(canAccessPath('TENANT_BOSS', '/app/dashboard?branch=all')).toBe(true);
    expect(canAccessPath('TENANT_BOSS', '/app/dashboard#kpi')).toBe(true);
  });

  it('una ruta que no existe no la abre nadie', () => {
    expect(rolesForPath('/app/inventado')).toEqual([]);
    expect(canAccessPath('TENANT_ADMIN', '/app/inventado')).toBe(false);
    expect(canAccessPath(null, '/app/dashboard')).toBe(false);
  });
});

describe('requiredModuleForPath', () => {
  it('marca las rutas que dependen de un módulo', () => {
    expect(requiredModuleForPath('/app/pos')).toBe('POS_GONDOLIA');
    expect(requiredModuleForPath('/app/pos/registers')).toBe('POS_GONDOLIA');
    expect(requiredModuleForPath('/app/integrations')).toBe('POS_INTEGRATION');
    expect(requiredModuleForPath('/app/transfers')).toBe('MULTI_BRANCH');
  });

  it('el resto está siempre incluido', () => {
    expect(requiredModuleForPath('/app/dashboard')).toBeNull();
    expect(requiredModuleForPath('/app/products?query=leche')).toBeNull();
  });
});

describe('linkTargetFor', () => {
  it('deja pasar el enlace cuando el rol puede abrirlo', () => {
    expect(linkTargetFor('TENANT_BOSS', '/app/alerts/5')).toBe('/app/alerts/5');
  });

  it('manda al empleado a Vencimientos en vez de a la bandeja de alertas', () => {
    expect(linkTargetFor('TENANT_EMPLOYEE', '/app/alerts/5')).toBe('/app/expirations');
  });

  it('sin equivalente no navega a ningún lado', () => {
    expect(linkTargetFor('TENANT_CASHIER', '/app/alerts/5')).toBeNull();
    expect(linkTargetFor('TENANT_CASHIER', '/app/dashboard')).toBeNull();
    expect(linkTargetFor(null, '/app/dashboard')).toBeNull();
  });
});

describe('hasRole', () => {
  it('compara contra una lista', () => {
    const roles: readonly Role[] = ['TENANT_ADMIN', 'TENANT_BOSS'];
    expect(hasRole('TENANT_ADMIN', roles)).toBe(true);
    expect(hasRole('TENANT_CASHIER', roles)).toBe(false);
    expect(hasRole(null, roles)).toBe(false);
  });
});
