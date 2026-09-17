package com.gondolia.auth.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
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

    public record TenantInfo(
            Long id,
            String name,
            TenantPlan plan,
            BusinessType businessType,
            String currency,
            StockRotation stockRotation,
            int maxBranches) {
    }
}
