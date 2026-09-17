package com.gondolia.ai.dto;

import com.gondolia.domain.ai.RecommendationType;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Recomendación generada ({@code recommendations[]} de §8.2); {@code dedupeKey} es única por sucursal. */
public record RecommendationResult(
        RecommendationType type,
        Long productId,
        Long lotId,
        int priority,
        Double confidence,
        String title,
        String explanation,
        Integer suggestedQuantity,
        BigDecimal suggestedDiscountPct,
        LocalDate suggestedDate,
        BigDecimal expectedImpact,
        String dedupeKey) {
}
