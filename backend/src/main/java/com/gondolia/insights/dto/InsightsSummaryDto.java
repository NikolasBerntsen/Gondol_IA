package com.gondolia.insights.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Resumen de la inteligencia del comercio en el alcance elegido (SPEC §6.5). */
public record InsightsSummaryDto(
        String scope,
        int branchCount,
        long productsAnalyzed,
        Instant lastRunAt,
        boolean running,
        boolean aiAvailable,
        Map<String, Long> patternCounts,
        Map<String, Long> abcCounts,
        long pendingRecommendations,
        Map<String, Long> recommendationsByType,
        BigDecimal atRiskValue,
        long predictedStockouts7d,
        long anomalies7d,
        List<LotRiskRow> topRisks,
        List<AiRunDto> runs) {

    /** Lote con riesgo de vencer sin venderse, según la simulación de la IA. */
    public record LotRiskRow(
            Long branchId,
            String branchName,
            Long productId,
            String productName,
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            Long daysToExpiry,
            int quantity,
            int unitsAtRisk,
            String riskLevel,
            Integer recommendedDiscountPct,
            BigDecimal valueAtRisk) {
    }
}
