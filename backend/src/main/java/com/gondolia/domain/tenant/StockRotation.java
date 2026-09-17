package com.gondolia.domain.tenant;

/**
 * Orden en que se consumen los lotes vendibles en ventas y bajas sin lote indicado (SPEC §4.2).
 */
public enum StockRotation {
    /** Primero sale lo que entró antes: {@code received_at ASC, id ASC}. */
    FIFO,
    /** Primero sale lo que vence antes: {@code expiry_date ASC NULLS LAST, received_at ASC, id ASC}. */
    FEFO
}
