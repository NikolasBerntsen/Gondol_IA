package com.gondolia.ai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Producto a analizar en una sucursal ({@code products[]} de §8.2). {@code stockoutDays} (opcional) son los días sin
 * stock vendible y sin ventas: demanda censurada que la IA no toma como una caída de la venta.
 */
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
        List<LotInput> lots,
        List<LocalDate> stockoutDays) {

    public ProductInput {
        dailySales = dailySales == null ? List.of() : List.copyOf(dailySales);
        lots = lots == null ? List.of() : List.copyOf(lots);
        stockoutDays = stockoutDays == null ? List.of() : List.copyOf(stockoutDays);
    }

    /** Sin días de faltante informados (compatibilidad con el contrato anterior). */
    public ProductInput(Long productId, String name, String category, BigDecimal salePrice, BigDecimal costPrice,
                        int minStock, int sellableStock, boolean perishable, Integer leadTimeDays, LocalDate createdAt,
                        List<DailySale> dailySales, List<LotInput> lots) {
        this(productId, name, category, salePrice, costPrice, minStock, sellableStock, perishable, leadTimeDays,
                createdAt, dailySales, lots, List.of());
    }
}
