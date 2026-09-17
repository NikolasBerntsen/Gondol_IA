package com.gondolia.insights.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Recomendación de la IA (SPEC §6.5). */
public record RecommendationDto(
        Long id,
        Long branchId,
        String branchName,
        RecommendationType type,
        RecommendationStatus status,
        Long productId,
        String productName,
        String brand,
        Long lotId,
        String lotNumber,
        LocalDate lotExpiryDate,
        String title,
        String explanation,
        Integer suggestedQuantity,
        BigDecimal suggestedDiscountPct,
        LocalDate suggestedDate,
        int priority,
        BigDecimal confidence,
        BigDecimal expectedImpact,
        Instant createdAt,
        Instant decidedAt,
        String decidedByName,
        String decisionNote,
        JsonNode outcome) {
}
