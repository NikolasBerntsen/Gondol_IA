package com.gondolia.analytics.dto;

import java.time.LocalDate;

/** Fila de "Artículos a reponer" del Inicio: una por producto y sucursal (SPEC §4.2, §6.5). */
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
        LocalDate predictedStockoutDate) {
}
