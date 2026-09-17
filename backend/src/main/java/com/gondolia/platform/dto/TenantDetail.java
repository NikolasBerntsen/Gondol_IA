package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Detalle administrativo de un comercio (SPEC §6.6): {@link TenantSummary} más datos fiscales, sucursales
 * (solo nombre, ciudad y estado: nunca stock ni ventas), usuarios, historial de eventos y módulos.
 *
 * @param maxBranches máximo <b>efectivo</b> de sucursales (límite del plan si tiene {@code MULTI_BRANCH}, si no 1)
 */
public record TenantDetail(
        Long id,
        String name,
        BusinessType businessType,
        TenantPlan plan,
        TenantStatus status,
        String city,
        String province,
        String contactName,
        String contactEmail,
        String contactPhone,
        long userCount,
        long branchCount,
        long activeBranchCount,
        List<TenantModule> modules,
        BigDecimal monthlyFee,
        Instant lastActivityAt,
        Instant createdAt,
        Instant statusChangedAt,
        String statusReason,
        String legalName,
        String taxId,
        String address,
        String notes,
        int maxBranches,
        StockRotation stockRotation,
        Map<Role, Long> usersByRole,
        List<TenantBranchDto> branches,
        List<TenantUserDto> users,
        List<TenantEventDto> events) {

    /** Arma el detalle a partir del resumen ya calculado. */
    public static TenantDetail of(TenantSummary summary, String legalName, String taxId, String address, String notes,
                                  int maxBranches, StockRotation stockRotation, Map<Role, Long> usersByRole,
                                  List<TenantBranchDto> branches, List<TenantUserDto> users,
                                  List<TenantEventDto> events) {
        return new TenantDetail(summary.id(), summary.name(), summary.businessType(), summary.plan(),
                summary.status(), summary.city(), summary.province(), summary.contactName(), summary.contactEmail(),
                summary.contactPhone(), summary.userCount(), summary.branchCount(), summary.activeBranchCount(),
                summary.modules(), summary.monthlyFee(), summary.lastActivityAt(), summary.createdAt(),
                summary.statusChangedAt(), summary.statusReason(), legalName, taxId, address, notes, maxBranches,
                stockRotation, usersByRole, branches, users, events);
    }
}
