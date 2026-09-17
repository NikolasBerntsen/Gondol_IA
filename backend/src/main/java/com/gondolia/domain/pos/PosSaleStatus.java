package com.gondolia.domain.pos;

/**
 * Estado de una venta del POS GondolIA: {@code VOIDED} cuando se anuló (SPEC §15.1).
 */
public enum PosSaleStatus {
    COMPLETED,
    VOIDED
}
