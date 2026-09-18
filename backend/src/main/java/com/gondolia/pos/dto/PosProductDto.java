package com.gondolia.pos.dto;

import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;

/**
 * Producto listo para vender en el POS, con el stock y el próximo lote de **esa** sucursal (SPEC §15.2).
 * {@code hasRecalledStock} bloquea la venta (cuarentena por recall).
 */
public record PosProductDto(Long productId, String barcode, String name, String brand, Long categoryId,
                            String categoryName, ProductUnit unit, BigDecimal listPrice, int sellableStock,
                            PosLotRef nextLot, boolean hasRecalledStock, boolean hasExpiredStock, boolean outOfStock,
                            Long branchId, String branchName) {
}
