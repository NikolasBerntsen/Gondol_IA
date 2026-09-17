package com.gondolia.pos.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Línea de una venta. {@code unitPrice} es el promedio efectivamente cobrado por unidad (puede combinar lotes con y
 * sin descuento); {@code listUnitPrice} es el precio de lista del producto.
 */
public record PosSaleItemDto(Long id, Long productId, String barcode, String productName, int quantity,
                             BigDecimal listUnitPrice, BigDecimal unitPrice, BigDecimal discountAmount,
                             BigDecimal lineTotal, int shortageQuantity, List<PosSaleLotDto> lots) {
}
