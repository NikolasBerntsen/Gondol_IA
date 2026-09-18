package com.gondolia.catalog.dto;

/**
 * Stock vendible de un producto en una sucursal del alcance actual (SPEC §4.2): {@code stockStatus} es
 * {@code OUT} (0), {@code LOW} (≤ stock mínimo) u {@code OK}.
 */
public record BranchStockDto(Long branchId, String branchName, int sellableStock, String stockStatus) {
}
