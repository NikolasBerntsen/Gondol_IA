package com.gondolia.ai.dto;

import java.util.List;
import java.util.Map;

/** Resumen del análisis de la sucursal ({@code summary} de §8.2). */
public record AnalyzeSummary(
        int productsAnalyzed,
        double atRiskValue,
        int predictedStockouts7d,
        int anomalies30d,
        Map<String, Integer> patternCounts,
        Map<String, Double> elasticityByCategory,
        List<String> modelNotes) {

    public AnalyzeSummary {
        patternCounts = patternCounts == null ? Map.of() : patternCounts;
        elasticityByCategory = elasticityByCategory == null ? Map.of() : elasticityByCategory;
        modelNotes = modelNotes == null ? List.of() : List.copyOf(modelNotes);
    }
}
