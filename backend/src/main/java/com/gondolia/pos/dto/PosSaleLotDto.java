package com.gondolia.pos.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Lote del que salieron unidades de una línea de la venta. */
public record PosSaleLotDto(Long lotId, String lotNumber, LocalDate expiryDate, int quantity, BigDecimal unitPrice,
                            BigDecimal discountPct) {
}
