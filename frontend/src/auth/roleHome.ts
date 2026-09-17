import type { Role } from '@/api/types';

/** Pantalla inicial de cada rol (SPEC §9.3). */
export function roleHome(role: Role): string {
  switch (role) {
    case 'PLATFORM_OWNER':
      return '/owner';
    case 'SUPPORT_AGENT':
      return '/support';
    case 'TENANT_BOSS':
    case 'TENANT_ADMIN':
      return '/app/dashboard';
    case 'TENANT_EMPLOYEE':
      return '/app/intake';
  }
}
