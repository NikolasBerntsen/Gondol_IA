package com.gondolia.pos.dto;

import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;
import java.util.List;

/**
 * Producto listo para vender en el POS, con el stock y el próximo lote de **esa** sucursal (SPEC §15.2).
 * {@code priceTiers} trae el precio de cada tramo de unidades en el orden en el que se venden, para que el carrito
 * cobre lo mismo que el núcleo cuando una cantidad cruza de un lote en liquidación a otro.
 * {@code hasRecalledStock} bloquea la venta (cuarentena por recall). {@code activeRecall} avisa que hay un recall
 * vigente del producto: se pueden vender los lotes cargados, pero no unidades sin stock registrado.
 */
public record PosProductDto(Long productId, String barcode, String name, String brand, Long categoryId,
                            String categoryName, ProductUnit unit, BigDecimal listPrice, int sellableStock,
                            PosLotRef nextLot, List<PosPriceTier> priceTiers, boolean hasRecalledStock,
                            PosRecallRef activeRecall, boolean hasExpiredStock, boolean outOfStock, Long branchId,
                            String branchName) {
}
