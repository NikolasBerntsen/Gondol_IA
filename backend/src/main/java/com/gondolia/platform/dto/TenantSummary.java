package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Ficha administrativa de un comercio para la consola de dueños (SPEC §6.6). Nunca incluye datos de negocio.
 *
 * @param monthlyFee    cuota mensual estimada: sucursales activas × (plan + adicionales de módulos). Los comercios
 *                      deshabilitados o dados de baja no facturan: 0.
 * @param lastActivityAt último inicio de sesión de cualquier usuario del comercio.
 */
public record TenantSummary(
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
        String statusReason) {
}
