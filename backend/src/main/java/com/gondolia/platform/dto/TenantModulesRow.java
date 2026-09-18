package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Fila de la matriz clientes × módulos (SPEC §14.3). {@code modules} trae los tres módulos con {@code true} o
 * {@code false} y {@code estimatedMonthlyFee} la cuota con los adicionales (0 si el comercio no factura).
 */
public record TenantModulesRow(Long tenantId, String tenantName, BusinessType businessType, String city,
                               TenantPlan plan, TenantStatus status, long activeBranchCount,
                               Map<TenantModule, Boolean> modules, BigDecimal estimatedMonthlyFee,
                               Instant lastActivityAt) {
}
