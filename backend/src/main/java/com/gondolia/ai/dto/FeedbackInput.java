package com.gondolia.ai.dto;

import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import java.math.BigDecimal;

/** Resultado de una recomendación decidida ({@code feedback[]} de §8.2). */
public record FeedbackInput(
        Long recommendationId,
        RecommendationType type,
        Long productId,
        String category,
        RecommendationStatus status,
        BigDecimal discountPct,
        FeedbackOutcome outcome) {
}
