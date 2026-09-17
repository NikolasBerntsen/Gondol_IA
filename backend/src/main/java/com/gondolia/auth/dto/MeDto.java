package com.gondolia.auth.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import com.gondolia.security.BranchAccessService.BranchRef;
import java.util.List;

/**
 * Usuario autenticado. Para los roles de plataforma {@code tenant} es {@code null} y {@code branches} está vacío;
 * para usuarios de comercio {@code branches} son las sucursales activas a las que tiene acceso.
 */
public record MeDto(
        Long id,
        String email,
        String fullName,
        Role role,
        boolean mustChangePassword,
        TenantInfo tenant,
        List<BranchRef> branches) {

    public MeDto {
        branches = branches == null ? List.of() : List.copyOf(branches);
    }

    /**
     * Comercio del usuario. {@code modules} son los módulos habilitados (SPEC §14) y {@code maxBranches} el
     * <b>máximo efectivo</b> de sucursales: el límite del plan si {@code MULTI_BRANCH} está habilitado; si no, 1.
     */
    public record TenantInfo(
            Long id,
            String name,
            TenantPlan plan,
            BusinessType businessType,
            String currency,
            StockRotation stockRotation,
            List<TenantModule> modules,
            int maxBranches) {

        public TenantInfo {
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }
}
