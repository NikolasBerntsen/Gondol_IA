package com.gondolia.pos.dto;

import java.math.BigDecimal;

/**
 * Tramo de precio del producto en la sucursal: {@code quantity} unidades que se cobran a {@code unitPrice} (ya con el
 * {@code discountPct} del lote aplicado). Los tramos vienen en el orden en el que se venden (SPEC §4.2: primero los
 * lotes en liquidación y después FIFO/FEFO) y agrupan lotes consecutivos con el mismo precio. Lo que se venda por
 * encima de la suma de los tramos es faltante y se cobra a precio de lista.
 */
public record PosPriceTier(int quantity, BigDecimal discountPct, BigDecimal unitPrice) {
}
