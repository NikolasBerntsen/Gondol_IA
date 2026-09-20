import { describe, expect, it } from 'vitest';
import { ROLES } from '@/api/types';
import { canAccessPath } from '@/config/access';
import { roleHome } from './roleHome';

describe('roleHome', () => {
  it('cada rol tiene su pantalla inicial', () => {
    expect(roleHome('PLATFORM_OWNER')).toBe('/owner');
    expect(roleHome('SUPPORT_AGENT')).toBe('/support');
    expect(roleHome('TENANT_BOSS')).toBe('/app/dashboard');
    expect(roleHome('TENANT_ADMIN')).toBe('/app/dashboard');
    expect(roleHome('TENANT_EMPLOYEE')).toBe('/app/intake');
    expect(roleHome('TENANT_CASHIER')).toBe('/app/pos');
  });

  it('todos los roles llegan a su propia pantalla inicial', () => {
    for (const role of ROLES) {
      expect(canAccessPath(role, roleHome(role))).toBe(true);
    }
  });
});
