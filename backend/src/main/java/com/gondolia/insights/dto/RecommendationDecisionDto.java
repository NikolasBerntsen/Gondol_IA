package com.gondolia.insights.dto;

import java.math.BigDecimal;

/**
 * Resultado de aceptar o descartar una recomendación (SPEC §6.5): la recomendación actualizada y lo que se hizo.
 * {@code whatsappText} solo viene en las de reposición.
 */
public record RecommendationDecisionDto(
        RecommendationDto recommendation,
        String message,
        BigDecimal appliedDiscountPct,
        Integer discardedQuantity,
        Integer orderedQuantity,
        String whatsappText,
        String whatsappUrl) {
}
