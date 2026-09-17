package com.gondolia.ai.dto;

import java.time.Instant;
import java.util.List;

/** Respuesta de {@code POST /v1/analyze} (SPEC §8.2). */
public record AnalyzeResponse(
        String modelVersion,
        Instant generatedAt,
        List<ProductAnalysis> products,
        List<RecommendationResult> recommendations,
        AnalyzeSummary summary) {

    public AnalyzeResponse {
        products = products == null ? List.of() : List.copyOf(products);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
