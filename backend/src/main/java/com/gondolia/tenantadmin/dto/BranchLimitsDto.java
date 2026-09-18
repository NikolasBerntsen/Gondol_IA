package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.tenant.TenantPlan;

/**
 * Límite de sucursales del comercio (SPEC §3.5, §14.1).
 *
 * @param maxBranches      máximo <b>efectivo</b>: el del plan si {@code MULTI_BRANCH} está habilitado, si no 1
 * @param planMaxBranches  máximo del plan, para explicar cuánto se ganaría habilitando multi-sucursal
 */
public record BranchLimitsDto(TenantPlan plan, int maxBranches, int planMaxBranches, boolean multiBranchEnabled,
                              long activeBranches, long totalBranches) {

    public boolean canCreate() {
        return activeBranches < maxBranches;
    }
}
