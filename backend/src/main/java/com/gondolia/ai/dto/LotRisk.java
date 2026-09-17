package com.gondolia.ai.dto;

/** Riesgo de vencimiento de un lote, simulando el consumo en el orden de rotación. */
public record LotRisk(
        Long lotId,
        Integer daysToExpiry,
        Integer quantity,
        Double expectedSalesBeforeExpiry,
        Integer unitsAtRisk,
        String riskLevel,
        Integer recommendedDiscountPct,
        Double expectedUnitsSoldWithDiscount) {
}
