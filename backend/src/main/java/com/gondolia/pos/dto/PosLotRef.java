package com.gondolia.pos.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Próximo lote que se vende de un producto en la sucursal (orden de rotación del comercio, SPEC §4.2).
 * {@code unitPrice} ya trae aplicado el {@code discountPct} del lote.
 */
public record PosLotRef(Long lotId, String lotNumber, LocalDate expiryDate, BigDecimal discountPct,
                        BigDecimal unitPrice) {
}
