package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Datos administrativos del comercio (solo lectura para el cliente: los cambia GondolIA desde su consola).
 * {@code estimatedMonthlyFee} es el abono estimado: (precio del plan + adicionales de los módulos) × sucursales
 * activas (SPEC §14.1).
 */
public record TenantAccountDto(Long id, String name, String legalName, String taxId, BusinessType businessType,
                               TenantPlan plan, TenantStatus status, String contactName, String contactEmail,
                               String contactPhone, String address, String city, String province, Instant createdAt,
                               List<TenantModule> modules, long activeBranches, int maxBranches,
                               BigDecimal estimatedMonthlyFee) {
}
