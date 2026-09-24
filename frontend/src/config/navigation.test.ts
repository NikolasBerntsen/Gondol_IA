import { describe, expect, it } from 'vitest';
import { ROLES, TENANT_MODULES, type Role } from '@/api/types';
import { canAccessPath, requiredModuleForPath } from './access';
import { NAVIGATION, getNavigation, isNavItemActive, type NavItem } from './navigation';

function itemsOf(role: Role): NavItem[] {
  return NAVIGATION[role].flatMap((section) => section.items);
}

describe('NAVIGATION', () => {
  it('todos los roles tienen menú', () => {
    for (const role of ROLES) {
      expect(NAVIGATION[role].length).toBeGreaterThan(0);
    }
  });

  it('cada ítem del menú lo puede abrir el rol que lo ve (SPEC §3.3)', () => {
    for (const role of ROLES) {
      for (const navItem of itemsOf(role)) {
        expect(canAccessPath(role, navItem.to), `${role} → ${navItem.to}`).toBe(true);
      }
    }
  });

  it('los ítems que dependen de un módulo lo declaran', () => {
    for (const role of ROLES) {
      for (const navItem of itemsOf(role)) {
        expect(navItem.module ?? null, `${role} → ${navItem.to}`).toBe(requiredModuleForPath(navItem.to));
      }
    }
  });

  it('ningún rol repite un destino', () => {
    for (const role of ROLES) {
      const targets = itemsOf(role).map((navItem) => navItem.to);
      expect(new Set(targets).size, role).toBe(targets.length);
    }
  });

  it('soporte tiene su bandeja y, para resolver tickets, Clientes y Módulos por cliente', () => {
    expect(itemsOf('SUPPORT_AGENT').map((navItem) => navItem.to)).toEqual([
      '/support',
      '/owner/tenants',
      '/owner/modules',
    ]);
    // Métricas, avisos y el equipo siguen siendo solo del dueño.
    expect(itemsOf('PLATFORM_OWNER').map((navItem) => navItem.to)).toEqual([
      '/owner',
      '/owner/tenants',
      '/owner/modules',
      '/owner/announcements',
      '/owner/team',
    ]);
  });

  it('cada ítem tiene etiqueta e ícono', () => {
    for (const role of ROLES) {
      for (const navItem of itemsOf(role)) {
        expect(navItem.label).toBeTruthy();
        expect(navItem.icon).toBeTruthy();
      }
    }
  });
});

describe('getNavigation', () => {
  it('sin módulos (roles de plataforma) devuelve el menú completo', () => {
    expect(getNavigation('PLATFORM_OWNER')).toEqual(NAVIGATION.PLATFORM_OWNER);
  });

  it('oculta los ítems de los módulos apagados', () => {
    const targets = getNavigation('TENANT_ADMIN', [])
      .flatMap((section) => section.items)
      .map((navItem) => navItem.to);
    expect(targets).not.toContain('/app/pos');
    expect(targets).not.toContain('/app/transfers');
    expect(targets).not.toContain('/app/integrations');
    expect(targets).toContain('/app/dashboard');
  });

  it('con todos los módulos no oculta nada', () => {
    const full = getNavigation('TENANT_ADMIN', TENANT_MODULES);
    expect(full.flatMap((s) => s.items)).toHaveLength(itemsOf('TENANT_ADMIN').length);
  });

  it('un grupo que queda sin ítems no se muestra', () => {
    // El cajero sin POS GondolIA pierde el grupo "Caja" entero.
    const sections = getNavigation('TENANT_CASHIER', []);
    expect(sections.every((section) => section.items.length > 0)).toBe(true);
    expect(sections.some((section) => section.title === 'Caja')).toBe(false);
  });
});

describe('isNavItemActive', () => {
  const inventory: NavItem = NAVIGATION.TENANT_ADMIN.flatMap((s) => s.items).find((i) => i.to === '/app/inventory')!;
  const pos: NavItem = NAVIGATION.TENANT_ADMIN.flatMap((s) => s.items).find((i) => i.to === '/app/pos')!;

  it('un ítem normal se activa con su ruta y con las de abajo', () => {
    expect(isNavItemActive(inventory, '/app/inventory')).toBe(true);
    expect(isNavItemActive(inventory, '/app/inventory/')).toBe(true);
    expect(isNavItemActive(inventory, '/app/inventory/filtros')).toBe(true);
    expect(isNavItemActive(inventory, '/app/sales')).toBe(false);
  });

  it('matchPrefixes activa el ítem desde otra rama (detalle de producto → Inventario)', () => {
    expect(isNavItemActive(inventory, '/app/products/12')).toBe(true);
  });

  it('con end solo se activa la ruta exacta', () => {
    expect(isNavItemActive(pos, '/app/pos')).toBe(true);
    expect(isNavItemActive(pos, '/app/pos/')).toBe(true);
    expect(isNavItemActive(pos, '/app/pos/registers')).toBe(false);
  });

  it('no se activa con una ruta que apenas empieza igual', () => {
    expect(isNavItemActive(inventory, '/app/inventoryX')).toBe(false);
  });
});
