// Llamadas de la administración del comercio (SPEC §6.9 · docs/api-f.md).
// Ninguno de estos recursos depende de la sucursal elegida: las keys no llevan el segmento `{branch}`.

import { apiGet, apiPost, apiPut } from '@/api/client';
import type {
  BranchLimits,
  BranchRequest,
  CreateUserRequest,
  ResetPasswordResponse,
  TenantAccount,
  TenantBranch,
  TenantSettings,
  TenantSettingsRequest,
  TenantUser,
  UpdateUserRequest,
} from './types';

export const tenantAdminKeys = {
  users: ['tenant-users'] as const,
  usersList: () => ['tenant-users', 'list'] as const,
  branches: ['tenant-branches'] as const,
  branchesList: (includeInactive: boolean) => ['tenant-branches', 'list', { includeInactive }] as const,
  branchLimits: () => ['tenant-branches', 'limits'] as const,
  settings: ['tenant-settings'] as const,
  settingsDetail: () => ['tenant-settings', 'detail'] as const,
  account: ['tenant-account'] as const,
  accountDetail: () => ['tenant-account', 'detail'] as const,
};

export const usersApi = {
  list: () => apiGet<TenantUser[]>('/tenant/users'),
  create: (body: CreateUserRequest) => apiPost<TenantUser>('/tenant/users', body),
  update: (id: number, body: UpdateUserRequest) => apiPut<TenantUser>(`/tenant/users/${id}`, body),
  /** Sin `newPassword` el backend genera una contraseña temporal y la devuelve una sola vez. */
  resetPassword: (id: number, newPassword?: string) =>
    apiPost<ResetPasswordResponse>(`/tenant/users/${id}/reset-password`, { newPassword: newPassword ?? null }),
};

export const branchesApi = {
  list: (includeInactive: boolean) => apiGet<TenantBranch[]>('/tenant/branches', { includeInactive }),
  limits: () => apiGet<BranchLimits>('/tenant/branches/limits'),
  create: (body: BranchRequest) => apiPost<TenantBranch>('/tenant/branches', body),
  update: (id: number, body: BranchRequest) => apiPut<TenantBranch>(`/tenant/branches/${id}`, body),
  deactivate: (id: number) => apiPost<TenantBranch>(`/tenant/branches/${id}/deactivate`),
  activate: (id: number) => apiPost<TenantBranch>(`/tenant/branches/${id}/activate`),
};

export const tenantSettingsApi = {
  get: () => apiGet<TenantSettings>('/tenant/settings'),
  update: (body: TenantSettingsRequest) => apiPut<TenantSettings>('/tenant/settings', body),
  account: () => apiGet<TenantAccount>('/tenant/account'),
};
