package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.tenant.StockRotation;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Configuración del comercio (SPEC §6.9). {@code stockRotation} define el orden de salida del stock (SPEC §4.2).
 */
public record TenantSettingsDto(String currency, StockRotation stockRotation, int expiryWarningDays,
                                int expiryCriticalDays, int defaultLeadTimeDays, int targetCoverageDays,
                                BigDecimal serviceLevel, int maxDiscountPct, Instant updatedAt) {
}
