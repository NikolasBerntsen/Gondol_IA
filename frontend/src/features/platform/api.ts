// Llamadas de la consola de dueños (SPEC §6.6 y §14.3). Ninguna de estas rutas expone datos de negocio.
import { apiDelete, apiGet, apiPost, apiPut } from '@/api/client';
import type { PageResponse, TenantModule, TenantModuleStatus } from '@/api/types';
import type {
  CreatePlatformUserRequest,
  CreateTenantRequest,
  ModuleCatalogItem,
  ModulesMatrixParams,
  PlatformMetrics,
  PlatformUserDto,
  TemporaryPasswordResponse,
  TenantDetail,
  TenantListParams,
  TenantModulesRow,
  TenantSummary,
  UpdateTenantRequest,
} from './types';

export const platformApi = {
  metrics: () => apiGet<PlatformMetrics>('/platform/metrics'),

  tenants: {
    list: (params: TenantListParams) => apiGet<PageResponse<TenantSummary>>('/platform/tenants', params),
    get: (id: number) => apiGet<TenantDetail>(`/platform/tenants/${id}`),
    create: (body: CreateTenantRequest) => apiPost<TenantDetail>('/platform/tenants', body),
    update: (id: number, body: UpdateTenantRequest) => apiPut<TenantDetail>(`/platform/tenants/${id}`, body),
    disable: (id: number, reason: string) => apiPost<TenantSummary>(`/platform/tenants/${id}/disable`, { reason }),
    enable: (id: number, reason?: string) => apiPost<TenantSummary>(`/platform/tenants/${id}/enable`, { reason }),
    cancel: (id: number, reason: string) => apiPost<TenantSummary>(`/platform/tenants/${id}/cancel`, { reason }),
    reactivate: (id: number, reason?: string) =>
      apiPost<TenantSummary>(`/platform/tenants/${id}/reactivate`, { reason }),
    remove: (id: number, confirmName: string) =>
      apiDelete<void>(`/platform/tenants/${id}`, { params: { confirmName } }),
    resetAdminPassword: (id: number) =>
      apiPost<TemporaryPasswordResponse>(`/platform/tenants/${id}/reset-admin-password`),
  },

  modules: {
    catalog: () => apiGet<ModuleCatalogItem[]>('/platform/modules'),
    matrix: (params: ModulesMatrixParams) => apiGet<PageResponse<TenantModulesRow>>('/platform/tenant-modules', params),
    ofTenant: (tenantId: number) => apiGet<TenantModuleStatus[]>(`/platform/tenants/${tenantId}/modules`),
    setEnabled: (tenantId: number, module: TenantModule, enabled: boolean) =>
      apiPut<TenantModuleStatus>(`/platform/tenants/${tenantId}/modules/${module}`, { enabled }),
  },

  team: {
    list: () => apiGet<PlatformUserDto[]>('/platform/users'),
    create: (body: CreatePlatformUserRequest) => apiPost<PlatformUserDto>('/platform/users', body),
    update: (id: number, body: { fullName: string; active: boolean }) =>
      apiPut<PlatformUserDto>(`/platform/users/${id}`, body),
    resetPassword: (id: number, newPassword: string) =>
      apiPost<PlatformUserDto>(`/platform/users/${id}/reset-password`, { newPassword }),
  },
};

/**
 * Keys de React Query de la consola. Los datos de plataforma no dependen de la sucursal
 * (los dueños no tienen sucursales): no llevan el segmento `{branch}`.
 */
export const platformKeys = {
  metrics: ['platform', 'metrics'] as const,
  tenants: ['platform', 'tenants'] as const,
  tenantList: (params: TenantListParams) => ['platform', 'tenants', 'list', params] as const,
  tenantDetail: (id: number) => ['platform', 'tenants', 'detail', id] as const,
  moduleCatalog: ['platform', 'modules', 'catalog'] as const,
  moduleMatrix: (params: ModulesMatrixParams) => ['platform', 'modules', 'matrix', params] as const,
  tenantModules: (id: number) => ['platform', 'modules', 'tenant', id] as const,
  team: ['platform', 'team'] as const,
};
