package com.gondolia.ai.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code POST /v1/analyze} (SPEC §8.2): análisis de una sucursal. {@code products[].dailySales}, {@code lots} y
 * {@code sellableStock} son de esa sucursal.
 */
public record AnalyzeRequest(
        Long tenantId,
        Long branchId,
        String branchName,
        LocalDate asOfDate,
        AnalyzeSettings settings,
        List<ProductInput> products,
        List<FeedbackInput> feedback) {

    public AnalyzeRequest {
        products = products == null ? List.of() : List.copyOf(products);
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }
}
