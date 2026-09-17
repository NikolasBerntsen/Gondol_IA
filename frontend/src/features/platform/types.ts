// DTOs de la consola de dueños (SPEC §6.6 y §14.3). Espejo de `com.gondolia.platform.dto`.
// Todo lo que llega acá es administrativo: nunca productos, stock, ventas, alertas ni chats de un cliente.

import type {
  BusinessType,
  PageParams,
  Role,
  StockRotation,
  TenantModule,
  TenantPlan,
  TenantStatus,
} from '@/api/types';

// ---------------------------------------------------------------------------
// Métricas
// ---------------------------------------------------------------------------

export interface PlatformMetrics {
  tenants: {
    total: number;
    active: number;
    disabled: number;
    cancelled: number;
    newLast30d: number;
    cancelledLast30d: number;
  };
  branches: {
    total: number;
    active: number;
    avgPerActiveTenant: number;
    multiBranchTenants: number;
  };
  tenantsByPlan: Partial<Record<TenantPlan, number>>;
  tenantsByBusinessType: Partial<Record<BusinessType, number>>;
  engagement: { activeTenants7d: number; activeTenants30d: number; activeUsers7d: number };
  users: { total: number; byRole: Partial<Record<Role, number>> };
  revenue: { estimatedMrr: number; currency: string; freemiumToPaidConversionPct: number };
  growth: GrowthPoint[];
  support: {
    openTickets: number;
    unassignedTickets: number;
    avgFirstResponseMinutes: number | null;
    resolvedLast30d: number;
    avgRating: number | null;
  };
  recalls: { activeRecalls: number; affectedTenantsTotal: number };
  modules: Partial<Record<TenantModule, ModuleAdoption>>;
}

/** Un mes del crecimiento; `month` viene como `"2026-09"`. */
export interface GrowthPoint {
  month: string;
  newTenants: number;
  cancelled: number;
  activeAtEndOfMonth: number;
}

export interface ModuleAdoption {
  tenants: number;
  pct: number;
}

// ---------------------------------------------------------------------------
// Clientes
// ---------------------------------------------------------------------------

export interface TenantSummary {
  id: number;
  name: string;
  businessType: BusinessType;
  plan: TenantPlan;
  status: TenantStatus;
  city: string | null;
  province: string | null;
  contactName: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  userCount: number;
  branchCount: number;
  activeBranchCount: number;
  modules: TenantModule[];
  monthlyFee: number;
  lastActivityAt: string | null;
  createdAt: string;
  statusChangedAt: string | null;
  statusReason: string | null;
}

export interface TenantBranchDto {
  id: number;
  name: string;
  code: string | null;
  city: string | null;
  active: boolean;
  createdAt: string;
}

export interface TenantUserDto {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  active: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export type TenantEventType =
  | 'CREATED'
  | 'PLAN_CHANGED'
  | 'DISABLED'
  | 'ENABLED'
  | 'CANCELLED'
  | 'REACTIVATED'
  | 'DELETED'
  | 'MODULE_ENABLED'
  | 'MODULE_DISABLED';

export const TENANT_EVENT_LABELS: Record<TenantEventType, string> = {
  CREATED: 'Alta del cliente',
  PLAN_CHANGED: 'Cambio de plan',
  DISABLED: 'Acceso deshabilitado',
  ENABLED: 'Acceso habilitado',
  CANCELLED: 'Baja del cliente',
  REACTIVATED: 'Reactivación',
  DELETED: 'Eliminación definitiva',
  MODULE_ENABLED: 'Módulo habilitado',
  MODULE_DISABLED: 'Módulo deshabilitado',
};

export interface TenantEventDto {
  id: number;
  type: TenantEventType;
  fromValue: string | null;
  toValue: string | null;
  reason: string | null;
  actorName: string | null;
  createdAt: string;
}

export interface TenantDetail extends TenantSummary {
  legalName: string | null;
  taxId: string | null;
  address: string | null;
  notes: string | null;
  /** Máximo **efectivo** de sucursales (sin `MULTI_BRANCH` es 1). */
  maxBranches: number;
  stockRotation: StockRotation;
  usersByRole: Partial<Record<Role, number>>;
  branches: TenantBranchDto[];
  users: TenantUserDto[];
  events: TenantEventDto[];
}

export interface TenantListParams extends PageParams {
  q?: string;
  status?: TenantStatus;
  plan?: TenantPlan;
  businessType?: BusinessType;
  module?: TenantModule;
  sort?: string;
}

export interface NewUserRequest {
  fullName: string;
  email: string;
  password: string;
}

export interface CreateTenantRequest {
  name: string;
  legalName?: string;
  taxId?: string;
  businessType: BusinessType;
  plan: TenantPlan;
  contactName?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
  city?: string;
  province?: string;
  notes?: string;
  firstBranch?: { name?: string; code?: string; address?: string; city?: string; province?: string };
  boss: NewUserRequest;
  admin: NewUserRequest;
  employee: NewUserRequest;
  /** Si es `undefined` el backend aplica el preset del plan (SPEC §14.1). */
  modules?: TenantModule[];
  stockRotation?: StockRotation;
}

export interface UpdateTenantRequest {
  name: string;
  legalName?: string;
  taxId?: string;
  businessType: BusinessType;
  plan: TenantPlan;
  contactName?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
  city?: string;
  province?: string;
  notes?: string;
  planChangeReason?: string;
  stockRotation?: StockRotation;
}

export interface TemporaryPasswordResponse {
  email: string;
  fullName: string;
  temporaryPassword: string;
}

// ---------------------------------------------------------------------------
// Módulos (SPEC §14.3)
// ---------------------------------------------------------------------------

export interface ModuleCatalogItem {
  module: TenantModule;
  name: string;
  description: string;
  monthlyPricePerBranch: number;
  enabledTenants: number;
  adoptionPct: number;
  activeTenants: number;
}

export interface TenantModulesRow {
  tenantId: number;
  tenantName: string;
  businessType: BusinessType;
  city: string | null;
  plan: TenantPlan;
  status: TenantStatus;
  activeBranchCount: number;
  modules: Record<TenantModule, boolean>;
  estimatedMonthlyFee: number;
  lastActivityAt: string | null;
}

export interface ModulesMatrixParams extends PageParams {
  q?: string;
  status?: TenantStatus;
  plan?: TenantPlan;
  module?: TenantModule;
  sort?: string;
}

// ---------------------------------------------------------------------------
// Equipo de GondolIA
// ---------------------------------------------------------------------------

export interface PlatformUserDto {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  active: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface CreatePlatformUserRequest {
  fullName: string;
  email: string;
  password: string;
  role: Role;
}
