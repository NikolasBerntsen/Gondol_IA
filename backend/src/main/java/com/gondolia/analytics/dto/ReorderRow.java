package com.gondolia.analytics.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Fila de "Artículos a reponer" del Inicio: una por producto y sucursal (SPEC §4.2, §6.5).
 * <p>
 * {@code orderedQuantity}/{@code orderedAt}: el último pedido anotado (recomendación {@code REORDER} aceptada) que
 * todavía no llegó, es decir, sin ingresos de ese producto en esa sucursal desde que se anotó. {@code null} si no hay.
 */
public record ReorderRow(
        Long productId,
        String productName,
        String brand,
        Long branchId,
        String branchName,
        int sellableStock,
        int minStock,
        int suggestedQuantity,
        String status,
        LocalDate predictedStockoutDate,
        Integer orderedQuantity,
        Instant orderedAt) {
}
