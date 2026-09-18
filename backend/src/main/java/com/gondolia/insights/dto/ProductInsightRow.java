package com.gondolia.insights.dto;

import com.gondolia.domain.ai.SalesPattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Patrón detectado para un producto en una sucursal (SPEC §6.5). */
public record ProductInsightRow(
        Long productId,
        String productName,
        String brand,
        String categoryName,
        Long branchId,
        String branchName,
        SalesPattern pattern,
        String patternDescription,
        String abcClass,
        String xyzClass,
        BigDecimal avgDailySales,
        BigDecimal trendPct,
        BigDecimal daysOfCover,
        LocalDate predictedStockoutDate,
        Integer reorderPoint,
        Integer safetyStock,
        Integer suggestedOrderQty,
        int sellableStock,
        int minStock,
        int anomaliesCount,
        int lotsAtRisk,
        int unitsAtRisk,
        Instant updatedAt) {
}
