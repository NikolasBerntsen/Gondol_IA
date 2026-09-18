// DTOs de la administración del comercio (SPEC §6.9 · docs/api-f.md).

import type { BusinessType, Role, StockRotation, TenantModule, TenantPlan, TenantStatus } from '@/api/types';

// ---------------------------------------------------------------------------
// Usuarios
// ---------------------------------------------------------------------------

/** Sucursal asignada a un empleado o cajero. `active: false` = la sucursal fue desactivada después. */
export interface AssignedBranch {
  id: number;
  name: string;
  code: string | null;
  active: boolean;
}

export interface TenantUser {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  active: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  /** Vacío para jefe y administrador: acceden a todas las sucursales. */
  branches: AssignedBranch[];
}

export interface CreateUserRequest {
  fullName: string;
  email: string;
  password: string;
  role: Role;
  branchIds: number[];
}

export interface UpdateUserRequest {
  fullName: string;
  role: Role;
  active: boolean;
  branchIds: number[];
}

export interface ResetPasswordResponse {
  userId: number;
  email: string;
  fullName: string;
  /** Se muestra una sola vez. */
  temporaryPassword: string;
  /** `true` si la generó el sistema. */
  generated: boolean;
}

/** Roles que puede tener un usuario del comercio, en el orden en que se muestran. */
export const TENANT_USER_ROLES: readonly Role[] = [
  'TENANT_ADMIN',
  'TENANT_BOSS',
  'TENANT_EMPLOYEE',
  'TENANT_CASHIER',
];

/** Qué hace cada rol, para el selector del alta (SPEC §3.1). */
export const ROLE_DESCRIPTIONS: Partial<Record<Role, string>> = {
  TENANT_ADMIN: 'Acceso total al comercio y a todas las sucursales.',
  TENANT_BOSS: 'Solo lectura de tableros, estadísticas, IA y alertas de todas las sucursales.',
  TENANT_EMPLOYEE: 'Carga de mercadería, vencimientos y cobro en el punto de venta, en sus sucursales.',
  TENANT_CASHIER: 'Solo el punto de venta de sus sucursales: abre y cierra caja, cobra y anula su turno.',
};

/** `true` si el rol trabaja en las sucursales asignadas (necesita al menos una). */
export function worksInAssignedBranches(role: Role): boolean {
  return role === 'TENANT_EMPLOYEE' || role === 'TENANT_CASHIER';
}

// ---------------------------------------------------------------------------
// Sucursales
// ---------------------------------------------------------------------------

export interface TenantBranch {
  id: number;
  name: string;
  code: string | null;
  address: string | null;
  city: string | null;
  province: string | null;
  phone: string | null;
  active: boolean;
  /** Empleados y cajeros activos asignados. */
  employeeCount: number;
  /** Tiene lotes con remanente: no se puede desactivar. */
  hasStock: boolean;
  createdAt: string;
}

export interface BranchRequest {
  name: string;
  code?: string | null;
  address?: string | null;
  city?: string | null;
  province?: string | null;
  phone?: string | null;
}

export interface BranchLimits {
  plan: TenantPlan;
  /** Máximo **efectivo**: sin `MULTI_BRANCH` es 1. */
  maxBranches: number;
  /** Máximo del plan, para explicar cuánto se gana habilitando Multi-sucursal. */
  planMaxBranches: number;
  multiBranchEnabled: boolean;
  activeBranches: number;
  totalBranches: number;
}

// ---------------------------------------------------------------------------
// Configuración y datos del comercio
// ---------------------------------------------------------------------------

export interface TenantSettings {
  currency: string;
  stockRotation: StockRotation;
  expiryWarningDays: number;
  expiryCriticalDays: number;
  defaultLeadTimeDays: number;
  targetCoverageDays: number;
  /** 0,5 a 0,999. */
  serviceLevel: number;
  maxDiscountPct: number;
  updatedAt: string | null;
}

export type TenantSettingsRequest = Omit<TenantSettings, 'updatedAt'>;

export interface TenantAccount {
  id: number;
  name: string;
  legalName: string | null;
  taxId: string | null;
  businessType: BusinessType;
  plan: TenantPlan;
  status: TenantStatus;
  contactName: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  address: string | null;
  city: string | null;
  province: string | null;
  createdAt: string;
  modules: TenantModule[];
  activeBranches: number;
  maxBranches: number;
  /** (Plan + adicionales de módulos) x sucursales activas. */
  estimatedMonthlyFee: number;
}
