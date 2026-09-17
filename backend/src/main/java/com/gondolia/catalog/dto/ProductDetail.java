package com.gondolia.catalog.dto;

import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Ficha de producto (SPEC §6.3): los mismos campos que {@link ProductListItem} más la descripción, el proveedor, las
 * fechas y los lotes del alcance ordenados por sucursal y, dentro de cada una, en orden de rotación.
 */
public record ProductDetail(
        Long id,
        String barcode,
        String name,
        String brand,
        String description,
        Long categoryId,
        String categoryName,
        Long supplierId,
        String supplierName,
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
        List<BranchStockDto> stockByBranch,
        List<LotDto> lots,
        Instant createdAt,
        Instant updatedAt) {

    public ProductDetail {
        stockByBranch = stockByBranch == null ? List.of() : List.copyOf(stockByBranch);
        lots = lots == null ? List.of() : List.copyOf(lots);
    }
}
