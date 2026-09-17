package com.gondolia.ai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Producto a analizar en una sucursal ({@code products[]} de §8.2). */
public record ProductInput(
        Long productId,
        String name,
        String category,
        BigDecimal salePrice,
        BigDecimal costPrice,
        int minStock,
        int sellableStock,
        boolean perishable,
        Integer leadTimeDays,
        LocalDate createdAt,
        List<DailySale> dailySales,
        List<LotInput> lots) {

    public ProductInput {
        dailySales = dailySales == null ? List.of() : List.copyOf(dailySales);
        lots = lots == null ? List.of() : List.copyOf(lots);
    }
}
