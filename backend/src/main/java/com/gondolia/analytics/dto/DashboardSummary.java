package com.gondolia.analytics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Resumen del negocio para el Inicio (SPEC §6.5). Los contadores de stock son por producto y sucursal:
 * {@code lowStockCount} incluye a los que están en cero y {@code outOfStockCount} es ese subconjunto.
 */
public record DashboardSummary(
        String scope,
        int branchCount,
        long productsCount,
        long expiringSoonCount,
        long expiredCount,
        long lowStockCount,
        long outOfStockCount,
        BigDecimal inventoryCostValue,
        BigDecimal inventorySaleValue,
        long openAlertsCount,
        long pendingRecommendationsCount,
        long openRecallMatchesCount,
        long todaySalesUnits,
        BigDecimal todaySalesAmount,
        Instant lastAiRunAt,
        LocalDate today,
        Instant asOf) {
}
