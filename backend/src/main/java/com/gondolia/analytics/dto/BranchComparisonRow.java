package com.gondolia.analytics.dto;

import java.math.BigDecimal;

/** Una sucursal en la comparación del Inicio consolidado (SPEC §6.5). */
public record BranchComparisonRow(
        Long branchId,
        String branchName,
        long salesUnits,
        BigDecimal salesAmount,
        BigDecimal inventoryCostValue,
        long expiringSoonCount,
        long lowStockCount,
        BigDecimal wasteValue,
        long openAlertsCount) {
}
