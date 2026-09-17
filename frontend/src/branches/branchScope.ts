import type { BranchScope } from '@/api/types';

/** Header con el que el backend resuelve el alcance de sucursales (SPEC §3.5). */
export const BRANCH_HEADER = 'X-Branch-Id';

const STORAGE_PREFIX = 'gondolia.branch.';

/**
 * Sucursal activa para las requests HTTP. La mantiene `BranchProvider`; el interceptor de axios
 * la lee en cada request. `null` = no se envía el header (roles de plataforma o sin sesión).
 */
let activeScope: BranchScope | null = null;

export const branchScope = {
  get(): BranchScope | null {
    return activeScope;
  },
  set(scope: BranchScope | null): void {
    activeScope = scope;
  },
};

/** Clave de `localStorage` de la sucursal elegida por cada usuario (`gondolia.branch.<userId>`). */
export function branchStorageKey(userId: number): string {
  return `${STORAGE_PREFIX}${userId}`;
}

export function readStoredBranch(userId: number): BranchScope | null {
  try {
    const raw = window.localStorage.getItem(branchStorageKey(userId));
    if (raw === null) return null;
    if (raw === 'all') return 'all';
    const id = Number(raw);
    return Number.isInteger(id) && id > 0 ? id : null;
  } catch {
    return null;
  }
}

export function writeStoredBranch(userId: number, scope: BranchScope): void {
  try {
    window.localStorage.setItem(branchStorageKey(userId), String(scope));
  } catch {
    // Sin almacenamiento persistente: la elección dura lo que la pestaña.
  }
}
