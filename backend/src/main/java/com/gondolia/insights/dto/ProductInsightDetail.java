package com.gondolia.insights.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Ficha de inteligencia de un producto en una sucursal: el patrón, la historia diaria de 90 días, el pronóstico,
 * las anomalías, el riesgo por lote y las recomendaciones abiertas (SPEC §6.5).
 */
public record ProductInsightDetail(
        ProductInsightRow insight,
        String barcode,
        BigDecimal salePrice,
        BigDecimal costPrice,
        boolean perishable,
        List<HistoryPoint> history,
        JsonNode weekdayProfile,
        JsonNode forecast,
        String forecastMethod,
        JsonNode anomalies,
        JsonNode lotRisks,
        List<LotRow> lots,
        List<RecommendationDto> recommendations) {

    /**
     * Un día de ventas netas del producto en la sucursal (los días sin ventas van en 0). {@code stockout}: el día no
     * hubo stock vendible ni ventas, así que ese 0 no muestra la demanda (la IA lo toma como demanda censurada).
     */
    public record HistoryPoint(LocalDate date, long units, BigDecimal amount, boolean stockout) {
    }

    /** Lote vivo del producto en la sucursal, en orden de rotación. */
    public record LotRow(
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            Long daysToExpiry,
            int quantity,
            BigDecimal discountPct,
            String status,
            int rotationRank,
            String bucket) {
    }
}
