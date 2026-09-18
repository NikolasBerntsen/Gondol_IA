package com.gondolia.catalog.dto;

import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Fila del inventario (SPEC §6.3). Los totales de stock están sumados sobre el alcance de sucursales del request
 * ({@code X-Branch-Id}) y {@code stockStatus} es el <b>peor</b> estado entre las sucursales del alcance.
 */
public record ProductListItem(
        Long id,
        String barcode,
        String name,
        String brand,
        Long categoryId,
        String categoryName,
        ProductUnit unit,
        BigDecimal costPrice,
        BigDecimal salePrice,
        int minStock,
        boolean perishable,
        boolean active,
        int sellableStock,
        int expiredStock,
        int quarantinedStock,
        LocalDate nextExpiryDate,
        int lotsCount,
        String stockStatus,
        List<BranchStockDto> stockByBranch) {

    public ProductListItem {
        stockByBranch = stockByBranch == null ? List.of() : List.copyOf(stockByBranch);
    }
}
